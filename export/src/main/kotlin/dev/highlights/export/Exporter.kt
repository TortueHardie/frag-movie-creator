package dev.highlights.export

import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.EncoderSelector
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.ffmpeg.FfmpegProgressParser
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.toTimecode
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.Session
import dev.highlights.editing.EditPlanner
import dev.highlights.editing.RenderCommand
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.RenderRequest
import dev.highlights.editing.SourceCutter
import dev.highlights.editing.story.StoryPlan
import dev.highlights.editing.story.StoryPlanner
import dev.highlights.editing.story.StoryRenderBuilder
import dev.highlights.editing.story.StoryRenderRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

data class ExportRequest(
    val settings: EditSettings,
    val outputDir: Path,
    /** Dossier temporaire du job (scripts de filtres). */
    val workDir: Path,
    val gameName: String,
    val audioBitrate: String = "192k",
    val hwaccel: String? = null,
    /** Indices de pistes imposés par le profil ; vide = rôles déduits de la capture. */
    val audioLayout: AudioLayout = AudioLayout(),
)

data class ExportResult(val videos: Map<OutputFormat, Path>, val report: Path, val duration: Duration, val encoder: String)

class Exporter(
    private val ffmpeg: FfmpegService,
    private val encoders: EncoderSelector,
    private val planner: EditPlanner,
) {
    suspend fun export(session: Session, request: ExportRequest, progress: ProgressReporter): ExportResult =
        export(listOf(session), request, progress)

    /** Un seul montage pour toutes les [sessions] (une par capture). */
    suspend fun export(sessions: List<Session>, request: ExportRequest, progress: ProgressReporter): ExportResult {
        val settings = request.settings
        if (settings.formats.isEmpty()) throw HighlightsException("Aucun format de sortie demandé")
        if (sessions.isEmpty()) throw HighlightsException("Aucune session à exporter")
        val plan = planner.plan(sessions, settings)
        // Montage « story » : les moments retenus sont redécoupés en plans (jump cuts, accroche, effets).
        val story: StoryPlan? = if (settings.style == EditStyle.STORY) StoryPlanner.plan(plan, sessions) else null
        story?.let { log.info { "Montage story : ${plan.clips.size} moments, ${it.shots.size} plans, ${it.removed.inWholeMilliseconds / 1000.0} s de temps morts retirés" } }
        val duration = story?.outputDuration ?: plan.outputDuration
        val encoder = encoders.select()

        request.outputDir.createDirectories()
        request.workDir.createDirectories()
        val paths = OutputNamer.reserve(request.outputDir, request.gameName, recordingDate(sessions), settings.formats)
        val sources = plan.clips.map { it.media.path }.toSet()
        paths.all.forEach { ensureNotSource(it, sources) }

        // Découpe des extraits avant le rendu : une seule fois pour tous les formats. Voir SourceCuts.
        val cuts = SourceCutter.prepare(
            ffmpeg,
            story?.let(StoryRenderBuilder::sourceCuts) ?: RenderCommandBuilder.sourceCuts(plan),
            request.workDir.resolve("cuts"),
            progress.child("Préparation des extraits", CUT_WEIGHT),
        )

        val filterScriptOption = ffmpeg.filterScriptOption()
        val done = mutableListOf<Path>()
        for (format in settings.formats) {
            val target = paths.videos.getValue(format)
            val temp = OutputNamer.tempFor(target)
            ensureNotSource(temp, sources)
            val step = progress.child(format.label, (1.0 - CUT_WEIGHT) / settings.formats.size)

            val filterScript = request.workDir.resolve("filters_${format.name.lowercase()}.txt")
            val render = if (story != null) {
                StoryRenderBuilder.build(
                    StoryRenderRequest(
                        plan = story,
                        format = format,
                        encoder = encoder,
                        output = temp,
                        filterScript = filterScript,
                        audioBitrate = request.audioBitrate,
                        hwaccel = request.hwaccel,
                        cuts = cuts,
                        audioLayout = request.audioLayout,
                        filterScriptOption = filterScriptOption,
                    ),
                )
            } else {
                RenderCommandBuilder.build(
                    RenderRequest(
                        plan = plan,
                        format = format,
                        encoder = encoder,
                        output = temp,
                        filterScript = filterScript,
                        audioBitrate = request.audioBitrate,
                        hwaccel = request.hwaccel,
                        cuts = cuts,
                        audioLayout = request.audioLayout,
                        filterScriptOption = filterScriptOption,
                    ),
                )
            }
            filterScript.writeText(render.filterGraph)
            log.info { "Rendu ${format.label} → $target (${plan.clips.size} clips, ${render.expectedDuration})" }

            try {
                runWithSoftwareFallback(render, request) { line ->
                    FfmpegProgressParser.parseOutTime(line)?.let { t -> step.update(t / render.expectedDuration, format.label) }
                }
                temp.moveTo(target)
                done.add(target) // pas de += : Path est lui-même un Iterable<Path>
            } catch (e: Throwable) {
                temp.deleteIfExists()
                throw e
            }
            step.complete()
        }

        val multiple = sessions.size > 1
        val report = ExportReport(
            generatedAt = Instant.now().toString(),
            source = sessions.first().media.path.toString(),
            sources = if (multiple) sessions.map { it.media.path.toString() } else emptyList(),
            profile = sessions.first().profileId,
            encoder = encoder.name,
            outputs = paths.videos.map { (f, p) -> ReportOutput(f.label, p.toString()) },
            totalDurationSeconds = duration.inWholeMilliseconds / 1000.0,
            highlights = plan.clips.mapIndexed { i, c -> ReportHighlight.of(c.highlight, i + 1, multiple) },
            skippedHighlights = sessions.flatMap { s -> s.highlights.filterNot { it.enabled } }.map { ReportHighlight.of(it, null, multiple) },
        )
        paths.report.writeText(reportJson.encodeToString(ExportReport.serializer(), report))
        log.info { "Export terminé : ${done.joinToString()} + ${paths.report}" }
        return ExportResult(paths.videos, paths.report, duration, encoder.name)
    }

    /**
     * Lance le rendu, et le rejoue en décodage logiciel s'il a échoué avec le décodage matériel : sur une machine
     * où celui-ci n'aboutit pas (pilote, codec exotique), l'export marche quand même.
     */
    private suspend fun runWithSoftwareFallback(render: RenderCommand, request: ExportRequest, onLine: (String) -> Unit) {
        try {
            ffmpeg.run(render.command, StdoutHandler.Lines(onLine))
        } catch (e: FfmpegException) {
            val args = render.command.args
            if (request.hwaccel == null || "-hwaccel" !in args) throw e
            log.warn { "Rendu en échec avec -hwaccel ${request.hwaccel} (${e.message}), nouvel essai en décodage logiciel" }
            ffmpeg.run(render.command.copy(args = withoutHwaccel(args)), StdoutHandler.Lines(onLine))
        }
    }

    /** Retire les options « -hwaccel <valeur> » d'une commande. */
    private fun withoutHwaccel(args: List<String>): List<String> =
        args.filterIndexed { i, arg -> arg != "-hwaccel" && (i == 0 || args[i - 1] != "-hwaccel") }

    /** Images PNG de chaque format à l'instant [at], pour régler recadrage et HUD. */
    suspend fun preview(
        media: MediaInfo,
        at: Duration,
        settings: EditSettings,
        outputDir: Path,
        workDir: Path,
    ): List<Path> {
        outputDir.createDirectories()
        workDir.createDirectories()
        val stamp = at.toTimecode().replace(":", "-").replace(".", "-")
        return settings.formats.map { format ->
            val output = outputDir.resolve("${media.path.nameWithoutExtension}_${stamp}${format.fileSuffix.ifEmpty { "_source" }}.png")
            ensureNotSource(output, setOf(media.path))
            val script = workDir.resolve("preview_${format.name.lowercase()}.txt")
            val render = RenderCommandBuilder.buildPreview(media, at, format, settings, script, output, ffmpeg.filterScriptOption())
            script.writeText(render.filterGraph)
            ffmpeg.run(render.command)
            output
        }
    }

    /** Petite image JPEG à l'instant [at] (vignette de segment). Réutilisée si déjà générée. */
    suspend fun thumbnail(media: MediaInfo, at: Duration, width: Int, output: Path): Path {
        if (output.exists()) return output
        ensureNotSource(output, setOf(media.path))
        output.parent?.createDirectories()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSeconds(at), "-i", media.path.toString(),
                    "-frames:v", "1", "-vf", "scale=$width:-2", "-q:v", "4", "-update", "1", "-y", output.toString(),
                ),
                "vignette ${at.toTimecode()}",
            ),
        )
        return output
    }

    /**
     * Extrait basse résolution d'un segment, avec le son du montage, pour le revoir dans le lecteur du système.
     * Réutilisé si déjà généré.
     */
    suspend fun clipPreview(media: MediaInfo, range: TimeRange, audioStream: Int?, output: Path): Path {
        if (output.exists()) return output
        ensureNotSource(output, setOf(media.path))
        output.parent?.createDirectories()
        val encoder = encoders.select()
        val temp = OutputNamer.tempFor(output)
        val audio = audioStream?.takeIf { s -> media.audio.any { it.audioIndex == s } }
            ?: AudioTracks.of(media.audio).mixIndices().firstOrNull()
        try {
            ffmpeg.run(
                FfmpegCommand(
                    listOf(
                        "-ss", Durations.ffmpegSeconds(range.start), "-t", Durations.ffmpegSeconds(range.length),
                        "-i", media.path.toString(),
                        "-map", "0:v:0",
                    ) + (audio?.let { listOf("-map", "0:a:$it", "-c:a", "aac", "-b:a", "128k") } ?: emptyList()) +
                        listOf("-vf", "scale=-2:720") + encoder.videoArgs + listOf("-movflags", "+faststart", "-y", temp.toString()),
                    "aperçu du segment ${range}",
                ),
            )
            temp.moveTo(output, overwrite = true)
        } catch (e: Throwable) {
            temp.deleteIfExists()
            throw e
        }
        return output
    }

    /** Date de la première partie enregistrée : c'est elle qui nomme le montage. */
    private fun recordingDate(sessions: List<Session>): LocalDate {
        val instant = sessions.mapNotNull { it.media.recordedAt }.minOrNull() ?: sessions.minOf { it.createdAt }
        return instant.atZone(ZoneId.systemDefault()).toLocalDate()
    }

    /** Garde-fou : on n'écrit jamais par-dessus une source. */
    private fun ensureNotSource(path: Path, sources: Set<Path>) {
        val normalized = path.toAbsolutePath().normalize().toString()
        if (sources.any { it.toAbsolutePath().normalize().toString().equals(normalized, ignoreCase = true) }) {
            throw HighlightsException("Refus d'écrire sur un fichier source : $path")
        }
    }

    private companion object {
        /** Part de la progression rendue par la pré-découpe : rapide (copie de flux) face au rendu lui-même. */
        const val CUT_WEIGHT = 0.08
    }
}
