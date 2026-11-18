package dev.highlights.montage

import dev.highlights.core.i18n.tr
import dev.highlights.core.i18n.Message
import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.EncoderSelector
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.ffmpeg.FfmpegProgressParser
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.roundTo
import dev.highlights.core.serialization.toTimecode
import dev.highlights.editing.SourceCutter
import dev.highlights.editing.story.SfxBank
import dev.highlights.export.ExportResult
import dev.highlights.export.OutputNamer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import dev.highlights.core.session.Session
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
    /** Indices de pistes imposés par le profil ; vide = rôles déduits de la capture. */
    val audioLayout: AudioLayout = AudioLayout(),
    /** Musiques essayées quand elle a été choisie dans une bibliothèque, de la retenue à la moins bonne. */
    val musicChoice: List<MontageReportMusic> = emptyList(),
    /** Plateforme visée : dans le nom des fichiers. */
    val platform: String? = null,
    /** Genre de montage, dans le nom des fichiers : killmontage, onetaps. */
    val kind: String = "killmontage",
    /** Avertissements du montage, repris dans le rapport et le résultat (voir [ExportResult.notices]). */
    val notices: List<dev.highlights.core.i18n.Message> = emptyList(),
    /**
     * Aperçu ([MontagePreview]) : petite taille, encodage rapide, sans flou par vecteurs ; même calage, image pour image.
     * Un seul format, celui de [MontagePreview.format].
     */
    val preview: Boolean = false,
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
    /** Note du montage : ce que le moteur prétend faire, mesuré. Voir [MontageScorer]. */
    val score: MontageScore? = null,
    /** Variante de plan retenue (voir [MontagePlanner.best]). */
    val variant: String? = null,
    /** Musiques essayées, la retenue d'abord, quand elle a été choisie dans une bibliothèque (voir [MusicChoice]). */
    val musicCandidates: List<MontageReportMusic> = emptyList(),
    /** Profil (jeu) du montage ; null dans les rapports écrits avant. */
    val profile: String? = null,
    /** Avertissements du montage (peu de kills trouvés…) ; vide dans les rapports écrits avant. */
    val notices: List<dev.highlights.core.i18n.Message> = emptyList(),
    /** Empreinte du montage ([MontageVariants.signature]) : la même pour un aperçu et le rendu final de sa variante. */
    val signature: String? = null,
    /** Aperçu basse résolution ([MontagePreview]) plutôt que rendu final. */
    val preview: Boolean = false,
)

/** Musique d'une bibliothèque essayée pour le montage ; [error] : aucun plan possible sur elle. */
@Serializable
data class MontageReportMusic(
    val music: String,
    val value: Double,
    val score: Double,
    val groups: Int,
    val error: String? = null,
    /** Retenue pour avoir servi à un montage récent (voir [MusicChoice.recency]). */
    val recency: Double = 0.0,
) {
    companion object {
        fun of(c: MusicCandidate) = MontageReportMusic(c.music.file.toString(), c.value.roundTo(3), c.score.roundTo(3), c.groups, c.error, c.recency.roundTo(3))
    }
}

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
    /** Kills visibles tirés à la tête. */
    val headshots: Int = 0,
    /** Un kill visible est un flick franc (ralenti mérité). */
    val flick: Boolean = false,
    /** Correction de chaque kill visible par le recalage sur le son du tir (ms, négatif : le tir précède la notification). */
    val shotShiftMs: List<Long> = emptyList(),
    /** Le groupe finit un round de tous les kills (voir [RoundOutcome]). */
    val ace: Boolean = false,
    val clutch: Boolean = false,
    /** Le kill d'ancrage tombe sur un moment qui ressort de la musique : note marquante, instrument qui entre. */
    val onMusicMoment: Boolean = false,
    /** Le kill d'ancrage tombe sur un contretemps syncopé (un demi-temps après [anchorBeat]). */
    val offbeat: Boolean = false,
    /** L'attente avant le kill est coupée en deux plans (voir [MontagePlanner.jumpCuts]). */
    val jumpCut: Boolean = false,
    /** Plan de l'ouverture vitrine, sans kill (voir [MontagePlanner.showcase]). */
    val showcase: Boolean = false,
    /** La coupe qui ouvre le plan est un whip pan (voir [MontageRenderBuilder.whips]). */
    val whip: Boolean = false,
    /** Souffle entendu sur ce whip pan : fichier de `whip.sounds` ou variante générée ; null : aucun. */
    val whoosh: String? = null,
    /** Montage des morts : texte affiché sur la mort (OUPS, RIP…, voir [FailLabels]) ; null ailleurs. */
    val failText: String? = null,
    /** Plan d'une partie rapide de la musique, au rythme rapide (voir [FastSections]). */
    val fastPart: Boolean = false,
)

class KillMontageExporter(private val ffmpeg: FfmpegService, private val encoders: EncoderSelector) {
    private val json = Json { prettyPrint = true }

    suspend fun export(plan: MontagePlan, request: MontageExportRequest, progress: ProgressReporter): ExportResult {
        if (request.formats.isEmpty()) throw HighlightsException(Message("error.export.noFormat"))
        // Aperçu : même plan (même empreinte, mêmes images), réglages de rendu ramenés à l'aperçu.
        val signature = MontageVariants.signature(plan)
        if (request.preview) {
            return render(
                MontagePreview.plan(plan),
                request.copy(formats = listOf(MontagePreview.format(request.formats)), edit = MontagePreview.edit(request.edit), audioBitrate = MontagePreview.AUDIO_BITRATE, hwaccel = null),
                MontagePreview.ENCODER, signature, progress,
            )
        }
        return render(plan, request, encoders.select().withMaxBitrate(request.edit.maxBitrate), signature, progress)
    }

    private suspend fun render(plan: MontagePlan, request: MontageExportRequest, encoder: EncoderProfile, signature: String, progress: ProgressReporter): ExportResult {
        request.outputDir.createDirectories()
        request.workDir.createDirectories()
        val kind = request.platform?.let { "${request.kind}_${OutputNamer.slug(it)}" } ?: request.kind
        val paths = OutputNamer.reserve(request.outputDir, request.gameName, request.date, request.formats, kind = kind)
        val sources = plan.clips.map { it.group.media.path.toAbsolutePath().normalize().toString().lowercase() }.toSet() +
            plan.music.file.toAbsolutePath().normalize().toString().lowercase()
        paths.all.forEach {
            if (it.toAbsolutePath().normalize().toString().lowercase() in sources) throw HighlightsException(Message("error.export.wouldOverwriteSource", "file" to it))
        }

        // Découpe des extraits avant le rendu (une seule fois pour tous les formats, voir SourceCuts) et rotation de la
        // caméra de chaque plan (le flou par vecteurs) : l'une copie des flux, l'autre décode, elles vont ensemble.
        val cutStep = progress.child(tr("progress.cuts"), CUT_WEIGHT)
        val motionStep = progress.child(tr("progress.cameraMotion"), MOTION_WEIGHT)
        val (cuts, motion) = coroutineScope {
            val motion = async { if (plan.settings.motionBlur.usesVectors) CameraMotion(ffmpeg).measure(plan, motionStep) else emptyList() }
            SourceCutter.prepare(ffmpeg, MontageRenderBuilder.sourceCuts(plan, request.edit.fps), request.workDir.resolve("cuts"), cutStep) to motion.await()
        }

        val whooshFiles = whooshFiles(plan)
        val filterScriptOption = ffmpeg.filterScriptOption()
        for (format in request.formats) {
            val target = paths.videos.getValue(format)
            val temp = OutputNamer.tempFor(target)
            val step = progress.child(tr("format.${format.name.lowercase()}.short"), (1.0 - CUT_WEIGHT - MOTION_WEIGHT) / request.formats.size)
            val script = request.workDir.resolve("montage_${format.name.lowercase()}.txt")
            val render = MontageRenderBuilder.build(
                MontageRenderRequest(
                    plan, format, request.edit, encoder, temp, script,
                    request.audioBitrate, request.hwaccel, cuts, request.audioLayout, filterScriptOption, motion,
                    whooshFiles, if (request.preview) MontagePreview.DECODE else emptyList(),
                ),
            )
            script.writeText(render.filterGraph)
            log.info { "Montage ${format.label} → $target (${plan.clips.size} clips, ${plan.duration}, ${MontageRenderBuilder.whips(plan).count { it != null }} whip pan(s))" }
            val onLine = { line: String ->
                FfmpegProgressParser.parseOutTime(line)?.let { step.update(it / render.expectedDuration, tr("format.${format.name.lowercase()}.short")) }
                Unit
            }
            try {
                try {
                    ffmpeg.run(render.command, StdoutHandler.Lines(onLine))
                } catch (e: FfmpegException) {
                    // Machine où le décodage matériel n'aboutit pas : on rejoue en logiciel plutôt que d'échouer.
                    if (request.hwaccel == null) throw e
                    log.warn { "Montage en échec avec -hwaccel ${request.hwaccel} (${e.message}), nouvel essai en décodage logiciel" }
                    val args = render.command.args
                    val software = args.filterIndexed { i, arg -> arg != "-hwaccel" && (i == 0 || args[i - 1] != "-hwaccel") }
                    ffmpeg.run(render.command.copy(args = software), StdoutHandler.Lines(onLine))
                }
                temp.moveTo(target)
            } catch (e: Throwable) {
                temp.deleteIfExists()
                throw e
            }
            step.complete()
        }

        val whooshes = MontageRenderBuilder.whooshes(plan, whooshFiles)
        val whips = MontageRenderBuilder.whips(plan)
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
            score = MontageScorer.score(plan),
            variant = plan.variant.toString(),
            musicCandidates = request.musicChoice,
            profile = request.gameName,
            notices = request.notices,
            signature = signature,
            preview = request.preview,
            clips = plan.clips.zip(plan.clipOffsets()).mapIndexed { i, (c, offset) -> c to offset }.let { pairs ->
                val fails = if (plan.settings.fails.enabled && plan.settings.text.enabled) FailLabels.assign(plan.clips, FailLabels.available(plan.settings.fails)) else plan.clips.map { null }
                pairs.mapIndexed { i, (c, offset) ->
                MontageReportClip(
                    c.group.media.path.toString(), c.start.toTimecode(), c.end.toTimecode(), c.kills.map { it.toTimecode() },
                    c.outputKills().map { ((offset + it).inWholeMilliseconds / 1000.0).roundTo(3) }, c.beats,
                    anchorBeat = c.beatsPre, onDrop = c.slot.dropBeat != null, slowMotion = c.slow != null, speedRamped = c.ramps.isNotEmpty(),
                    frozenSeconds = ((c.padBefore + c.padAfter).inWholeMilliseconds / 1000.0).roundTo(3),
                    headshots = c.kills.count { c.group.traitsOf(it).headshot },
                    flick = c.flick,
                    shotShiftMs = c.kills.map { c.group.traitsOf(it).shift.inWholeMilliseconds },
                    ace = c.group.outcome.ace,
                    clutch = c.group.outcome.clutch,
                    onMusicMoment = plan.music.salience.getOrElse(c.anchorBeat) { 0.0 } >= MusicAnalyzer.SALIENT,
                    offbeat = c.anchorHalf,
                    jumpCut = c.jumps.isNotEmpty(),
                    showcase = c.showcase,
                    whip = whips[i] != null,
                    whoosh = whooshes[i],
                    fastPart = c.fastPart,
                    failText = fails[i],
                )
            } },
        )
        paths.report.writeText(json.encodeToString(MontageReport.serializer(), report))
        return ExportResult(paths.videos, paths.report, plan.duration, encoder.name, music = plan.music.file, notices = request.notices)
    }

    /** Souffles du dossier `whip.sounds` ; un dossier absent ou sans son est signalé, les souffles générés le remplacent. */
    private fun whooshFiles(plan: MontagePlan): List<String> {
        val dir = plan.settings.whip.sounds ?: return emptyList()
        return SfxBank.list(Path.of(dir)).also {
            if (it.isEmpty()) log.warn { "Aucun son dans $dir (whip.sounds) : souffles générés à la place" }
        }
    }

    companion object {
        /** Part de la progression rendue par la pré-découpe : rapide (copie de flux) face au rendu lui-même. */
        private const val CUT_WEIGHT = 0.08
        /** Mesure du mouvement de caméra : un décodage en tout petit de chaque plan, rapide face au rendu. */
        private const val MOTION_WEIGHT = 0.06

        /**
         * Date de la première partie jouée, qui nomme le montage : la date d'enregistrement de la capture
         * ([dev.highlights.core.model.MediaInfo.recordedAt] : inscrite dans le fichier, sinon sa date de modification),
         * sinon celle de l'analyse ; jamais celle du rendu. Même règle que l'export highlights. Une capture VALORANT
         * sans date inscrite donnait `valorant_2026-11-18_killmontage` pour une partie du 17 rendue le 18.
         */
        fun recordingDate(sessions: List<Session>, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
            val instant = sessions.mapNotNull { it.media.recordedAt }.minOrNull() ?: sessions.minOf { it.createdAt }
            return instant.atZone(zone).toLocalDate()
        }
    }
}
