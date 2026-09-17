package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.EncoderSelector
import dev.highlights.core.ffmpeg.FfmpegProgressParser
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.roundTo
import dev.highlights.core.serialization.toTimecode
import dev.highlights.export.ExportResult
import dev.highlights.export.OutputNamer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

data class MontageExportRequest(
    val formats: List<OutputFormat>,
    val edit: EditSettings,
    val outputDir: Path,
    val workDir: Path,
    val gameName: String,
    val date: LocalDate,
    val audioBitrate: String = "192k",
    val hwaccel: String? = null,
)

@Serializable
data class MontageReport(
    val generatedAt: String,
    val music: String,
    val bpm: Double,
    val musicStart: String,
    val durationSeconds: Double,
    /** Instant de la drop dans le montage (secondes), si elle en fait partie. */
    val dropAtSeconds: Double? = null,
    val sections: List<MontageReportSection> = emptyList(),
    val outputs: List<String>,
    val clips: List<MontageReportClip>,
)

/** Section de la musique couverte par le montage (instants dans le montage). */
@Serializable
data class MontageReportSection(
    val startSeconds: Double,
    val endSeconds: Double,
    val intensity: Double,
    val level: String,
)

@Serializable
data class MontageReportClip(
    val source: String,
    val start: String,
    val end: String,
    /** Kills visibles dans le clip (source). */
    val kills: List<String>,
    /** Instants des kills dans le montage (secondes). */
    val killsInMontage: List<Double>,
    val beats: Int,
    /** Temps du slot où tombe le dernier kill (0 = coupe). */
    val anchorBeat: Int = 0,
    val onDrop: Boolean = false,
    val slowMotion: Boolean,
    /** Vitesse ajustée entre les kills pour les mettre tous sur un temps. */
    val speedRamped: Boolean = false,
    /** Image gelée (secondes) faute de source avant / après. */
    val frozenSeconds: Double = 0.0,
)

class KillMontageExporter(private val ffmpeg: FfmpegService, private val encoders: EncoderSelector) {
    private val json = Json { prettyPrint = true }

    suspend fun export(plan: MontagePlan, request: MontageExportRequest, progress: ProgressReporter): ExportResult {
        if (request.formats.isEmpty()) throw HighlightsException("Aucun format de sortie demandé")
        val encoder = encoders.select()
        request.outputDir.createDirectories()
        request.workDir.createDirectories()
        val paths = OutputNamer.reserve(request.outputDir, request.gameName, request.date, request.formats, kind = "killmontage")
        val sources = plan.clips.map { it.group.media.path.toAbsolutePath().normalize().toString().lowercase() }.toSet() +
            plan.music.file.toAbsolutePath().normalize().toString().lowercase()
        paths.all.forEach {
            if (it.toAbsolutePath().normalize().toString().lowercase() in sources) throw HighlightsException("Refus d'écrire sur un fichier source : $it")
        }

        for (format in request.formats) {
            val target = paths.videos.getValue(format)
            val temp = OutputNamer.tempFor(target)
            val step = progress.child(format.label, 1.0 / request.formats.size)
            val script = request.workDir.resolve("montage_${format.name.lowercase()}.txt")
            val render = MontageRenderBuilder.build(
                MontageRenderRequest(plan, format, request.edit, encoder, temp, script, request.audioBitrate, request.hwaccel),
            )
            script.writeText(render.filterGraph)
            log.info { "Montage ${format.label} → $target (${plan.clips.size} clips, ${plan.duration})" }
            try {
                ffmpeg.run(render.command, StdoutHandler.Lines { line ->
                    FfmpegProgressParser.parseOutTime(line)?.let { step.update(it / render.expectedDuration, format.label) }
                })
                temp.moveTo(target)
            } catch (e: Throwable) {
                temp.deleteIfExists()
                throw e
            }
            step.complete()
        }

        val report = MontageReport(
            generatedAt = Instant.now().toString(),
            music = plan.music.file.toString(),
            bpm = plan.music.bpm.roundTo(2),
            musicStart = plan.musicStart.toTimecode(),
            durationSeconds = (plan.duration.inWholeMilliseconds / 1000.0).roundTo(3),
            dropAtSeconds = plan.dropAt?.let { (it.inWholeMilliseconds / 1000.0).roundTo(3) },
            sections = plan.music.sections.filter { it.endBeat > plan.startBeat && it.startBeat < plan.endBeat }.map { s ->
                val from = plan.music.beatTime(s.startBeat.coerceAtLeast(plan.startBeat)) - plan.musicStart
                val to = plan.music.beatTime(s.endBeat.coerceAtMost(plan.endBeat)) - plan.musicStart
                MontageReportSection((from.inWholeMilliseconds / 1000.0).roundTo(3), (to.inWholeMilliseconds / 1000.0).roundTo(3), s.intensity.roundTo(2), s.level.name)
            },
            outputs = paths.videos.values.map { it.toString() },
            clips = plan.clips.zip(plan.clipOffsets()).map { (c, offset) ->
                MontageReportClip(
                    c.group.media.path.toString(), c.start.toTimecode(), c.end.toTimecode(), c.kills.map { it.toTimecode() },
                    c.outputKills().map { ((offset + it).inWholeMilliseconds / 1000.0).roundTo(3) }, c.beats,
                    anchorBeat = c.beatsPre, onDrop = c.slot.dropBeat != null, slowMotion = c.slow != null, speedRamped = c.ramps.isNotEmpty(),
                    frozenSeconds = ((c.padBefore + c.padAfter).inWholeMilliseconds / 1000.0).roundTo(3),
                )
            },
        )
        paths.report.writeText(json.encodeToString(MontageReport.serializer(), report))
        return ExportResult(paths.videos, paths.report, plan.duration, encoder.name)
    }

    companion object {
        fun recordingDate(instant: Instant?): LocalDate = (instant ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
