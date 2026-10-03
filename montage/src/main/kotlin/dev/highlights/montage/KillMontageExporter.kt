package dev.highlights.montage

import dev.highlights.core.HighlightsException
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
import dev.highlights.editing.story.Caption
import dev.highlights.export.ExportResult
import dev.highlights.export.OutputNamer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
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
    /** Modèle whisper des paroles ([MontageSettings.lyrics]) ; null ou absent : montage sans paroles. */
    val lyricsModel: Path? = null,
    /** Transcriptions des musiques gardées d'un montage à l'autre ; null : pas de cache. */
    val lyricsCache: Path? = null,
    /** Paroles fournies (.lrc ou .txt) ; null : celles posées à côté de la musique ([LyricsText.sidecar]), sinon whisper seul. */
    val lyricsText: Path? = null,
    /** Paroles saisies dans l'interface, par musique (chemin absolu normalisé) : passent avant tout fichier. */
    val lyricsTexts: Map<Path, String> = emptyMap(),
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
)

class KillMontageExporter(private val ffmpeg: FfmpegService, private val encoders: EncoderSelector) {
    private val json = Json { prettyPrint = true }

    suspend fun export(plan: MontagePlan, request: MontageExportRequest, progress: ProgressReporter): ExportResult {
        if (request.formats.isEmpty()) throw HighlightsException("Aucun format de sortie demandé")
        val encoder = encoders.select().withMaxBitrate(request.edit.maxBitrate)
        request.outputDir.createDirectories()
        request.workDir.createDirectories()
        val kind = request.platform?.let { "${request.kind}_${OutputNamer.slug(it)}" } ?: request.kind
        val paths = OutputNamer.reserve(request.outputDir, request.gameName, request.date, request.formats, kind = kind)
        val sources = plan.clips.map { it.group.media.path.toAbsolutePath().normalize().toString().lowercase() }.toSet() +
            plan.music.file.toAbsolutePath().normalize().toString().lowercase()
        paths.all.forEach {
            if (it.toAbsolutePath().normalize().toString().lowercase() in sources) throw HighlightsException("Refus d'écrire sur un fichier source : $it")
        }

        // Découpe des extraits avant le rendu (une seule fois pour tous les formats, voir SourceCuts) et rotation de la
        // caméra de chaque plan (le flou par vecteurs) : l'une copie des flux, l'autre décode, elles vont ensemble.
        val cutStep = progress.child("Préparation des extraits", CUT_WEIGHT)
        val motionStep = progress.child("Mouvements de caméra", MOTION_WEIGHT)
        val (cuts, motion) = coroutineScope {
            val motion = async { if (plan.settings.motionBlur.usesVectors) CameraMotion(ffmpeg).measure(plan, motionStep) else emptyList() }
            SourceCutter.prepare(ffmpeg, MontageRenderBuilder.sourceCuts(plan, request.edit.fps), request.workDir.resolve("cuts"), cutStep) to motion.await()
        }

        val lyrics = lyrics(plan, request)
        val filterScriptOption = ffmpeg.filterScriptOption()
        for (format in request.formats) {
            val target = paths.videos.getValue(format)
            val temp = OutputNamer.tempFor(target)
            val step = progress.child(format.label, (1.0 - CUT_WEIGHT - MOTION_WEIGHT) / request.formats.size)
            val script = request.workDir.resolve("montage_${format.name.lowercase()}.txt")
            val render = MontageRenderBuilder.build(
                MontageRenderRequest(
                    plan, format, request.edit, encoder, temp, script,
                    request.audioBitrate, request.hwaccel, cuts, request.audioLayout, filterScriptOption, motion, lyrics,
                ),
            )
            script.writeText(render.filterGraph)
            log.info { "Montage ${format.label} → $target (${plan.clips.size} clips, ${plan.duration}, ${MontageRenderBuilder.whips(plan).count { it != null }} whip pan(s))" }
            val onLine = { line: String ->
                FfmpegProgressParser.parseOutTime(line)?.let { step.update(it / render.expectedDuration, format.label) }
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
            clips = plan.clips.zip(plan.clipOffsets()).map { (c, offset) ->
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
                )
            },
        )
        paths.report.writeText(json.encodeToString(MontageReport.serializer(), report))
        return ExportResult(paths.videos, paths.report, plan.duration, encoder.name, music = plan.music.file)
    }

    /** Mots des paroles à écrire, en temps du montage ; aucun si elles ne sont pas demandées ou sans modèle. */
    /**
     * Mots des paroles à écrire, en temps du montage. Texte fourni (fichier donné ou posé à côté de la musique) : calé sur
     * la transcription ([LyricsText.align]), ou, sans modèle, sur les lignes d'un .lrc. Sinon les mots entendus par
     * whisper. Aucun si elles ne sont pas demandées ou que rien ne permet de les placer.
     */
    private suspend fun lyrics(plan: MontagePlan, request: MontageExportRequest): List<Caption> {
        val settings = plan.settings.lyrics
        if (!settings.enabled) return emptyList()
        val typed = request.lyricsTexts[plan.music.file.toAbsolutePath().normalize()]?.takeIf { it.isNotBlank() }
        val textFile = if (typed != null) null else request.lyricsText ?: LyricsText.sidecar(plan.music.file)
        val given = typed?.let(LyricsText::parse) ?: textFile?.let { f ->
            runCatching { LyricsText.read(f) }.onFailure { log.warn { "Paroles illisibles ($f) : ${it.message}" } }.getOrNull()
        }
        val model = request.lyricsModel?.takeIf { it.exists() }
        val heard = model?.let { LyricsTranscriber(ffmpeg, request.lyricsCache).words(plan.music.file, it, settings, request.workDir) }.orEmpty()
        val words = when {
            given != null && heard.isNotEmpty() -> LyricsText.align(given, heard).also { log.info { "Paroles ${textFile ?: "saisies"} calées sur la transcription" } }
            given != null && given.all { it.start != null } -> LyricsText.spread(given).also { log.info { "Paroles ${textFile ?: "saisies"} placées sur leurs lignes (sans transcription)" } }
            given != null -> emptyList<Caption>().also { log.warn { "Paroles sans horodatage (${textFile ?: "saisies"}) et sans modèle de transcription pour les caler : montage sans paroles" } }
            model == null -> emptyList<Caption>().also { log.warn { "Modèle de transcription introuvable (${request.lyricsModel ?: "aucun"}) : montage sans paroles" } }
            else -> heard
        }
        return Lyrics.shown(words, plan, settings).also { log.info { "Paroles : ${it.size} mot(s) à l'écran" } }
    }

    companion object {
        /** Part de la progression rendue par la pré-découpe : rapide (copie de flux) face au rendu lui-même. */
        private const val CUT_WEIGHT = 0.08
        /** Mesure du mouvement de caméra : un décodage en tout petit de chaque plan, rapide face au rendu. */
        private const val MOTION_WEIGHT = 0.06

        fun recordingDate(instant: Instant?): LocalDate = (instant ?: Instant.now()).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
