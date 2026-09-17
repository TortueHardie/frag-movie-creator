package dev.highlights.pipeline

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.profile.DetectorConfig
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.core.serialization.toTimecode
import dev.highlights.export.ExportRequest
import dev.highlights.export.ExportResult
import dev.highlights.export.Exporter
import dev.highlights.montage.KillMontageExporter
import dev.highlights.montage.MontageExportRequest
import dev.highlights.montage.MontagePlanner
import dev.highlights.montage.MusicAnalyzer
import dev.highlights.scoring.HighlightMerge
import dev.highlights.scoring.ScoringEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

data class AnalyzeOptions(
    val profileId: String? = null,
    val threshold: Double? = null,
    val target: SelectionTarget? = null,
    /** Construire les moments autour d'un type d'événement (ex. "kill") plutôt que du seuil. */
    val requiredEvent: String? = null,
    /** Dossier où écrire la session (sous-dossier sessions). null = outputDir de la config. */
    val outputDir: Path? = null,
)

data class ExportOptions(
    val formats: List<OutputFormat>? = null,
    val outputDir: Path? = null,
)

/** Surcharges ponctuelles des réglages de montage du profil (null = valeur du profil). */
data class MontageOptions(
    val formats: List<OutputFormat>? = null,
    val outputDir: Path? = null,
    val maxDuration: Duration? = null,
    val order: MontageOrder? = null,
    val zoom: Boolean? = null,
    val flash: Boolean? = null,
    val slowMotion: Boolean? = null,
    val speedRamp: Boolean? = null,
    val text: Boolean? = null,
)

data class AnalysisOutcome(val session: Session, val sessionFile: Path, val profile: GameProfile)

data class ProcessOutcome(val analysis: AnalysisOutcome, val export: ExportResult?)

/** Point d'entrée du cœur, indépendant de toute interface (CLI, Compose, watch folder). */
class HighlightPipeline(
    private val config: LoadedConfig,
    private val ffmpeg: FfmpegService,
    profiles: ProfileRepository,
    private val detectors: DetectorRegistry,
    private val scoring: ScoringEngine,
    private val exporter: Exporter,
    private val montageExporter: KillMontageExporter,
) {
    @Volatile
    var profiles: ProfileRepository = profiles
        private set

    /** Dossier de sortie par défaut (app.yaml). */
    val defaultOutputDir: Path get() = config.outputDir

    /** Dossier des aperçus temporaires (hors dossier de sortie). */
    val previewDir: Path get() = config.workDir.resolve("cache").resolve("previews")

    val profilesDir: Path get() = config.profilesDir

    /** Relit les profils sur disque (après modification d'un YAML). */
    fun reloadProfiles() {
        profiles = ProfileRepository.loadDirectory(config.profilesDir)
    }

    /** Profil qui serait utilisé pour ce fichier. */
    fun resolveProfile(file: Path, forcedId: String? = null): GameProfile = profiles.resolve(file, forcedId)

    suspend fun probe(file: Path): MediaInfo {
        validateInput(file)
        return ffmpeg.probe(file)
    }

    suspend fun analyze(file: Path, options: AnalyzeOptions, progress: ProgressReporter): AnalysisOutcome {
        validateInput(file)
        val probeStep = progress.child("Lecture", 0.02)
        val media = ffmpeg.probe(file)
        probeStep.complete()
        if (media.video == null) throw InputException("$file ne contient pas de flux vidéo")
        if (!media.duration.isPositive()) throw InputException("$file a une durée nulle")

        val profile = profiles.resolve(file, options.profileId)
        val selection = profile.selection.let { s ->
            s.copy(threshold = options.threshold ?: s.threshold, target = options.target ?: s.target, requiredEvent = options.requiredEvent)
        }
        log.info { "Analyse de ${media.path} (${media.duration}, ${media.audio.size} piste(s) audio) avec le profil ${profile.id}" }

        val grid = WindowGrid(profile.window.size, profile.window.hop, media.duration)
        val warnings = Collections.synchronizedList(mutableListOf<String>())

        val tracks = withJobDir { workDir ->
            runDetectors(profile, media, grid, workDir, progress.child("Analyse", 0.93), warnings)
        }
        if (tracks.all { it.second.isMissing }) {
            throw HighlightsException("Aucun signal exploitable pour $file : ${warnings.joinToString("; ").ifEmpty { "tous les détecteurs sont vides" }}")
        }

        val scoreStep = progress.child("Scoring", 0.05)
        val timeline = scoring.score(tracks, grid)
        val highlights = scoring.select(timeline, selection, media.path)
        scoreStep.complete()

        if (highlights.isEmpty()) {
            warnings += "Aucun moment au-dessus du seuil ${selection.threshold} (score max ${timeline.total.maxOrNull() ?: 0.0})"
        }
        val session = Session(
            createdAt = Instant.now(),
            media = media,
            profileId = profile.id,
            timeline = timeline,
            highlights = highlights,
            warnings = warnings.toList(),
        )
        val sessionFile = (options.outputDir ?: config.outputDir).resolve("sessions").resolve("${media.path.nameWithoutExtension}.session.json")
        SessionStore.save(session, sessionFile)
        log.info { "${highlights.size} moment(s) retenu(s), session : $sessionFile" }
        return AnalysisOutcome(session, sessionFile, profile)
    }

    suspend fun export(session: Session, options: ExportOptions, progress: ProgressReporter): ExportResult {
        val profile = profiles.byId(session.profileId)
        val settings: EditSettings = options.formats?.let { profile.edit.copy(formats = it) } ?: profile.edit
        return withJobDir { workDir ->
            exporter.export(
                session,
                ExportRequest(
                    settings = settings,
                    outputDir = options.outputDir ?: config.outputDir,
                    workDir = workDir,
                    gameName = profile.id,
                    audioBitrate = config.app.encoder.audioBitrate,
                    hwaccel = config.app.ffmpeg.hwaccelDecode,
                ),
                progress,
            )
        }
    }

    /** Aperçu PNG des formats de sortie à un instant donné, sans analyse. */
    suspend fun preview(file: Path, at: Duration, profileId: String?, formats: List<OutputFormat>?, outputDir: Path?): List<Path> {
        validateInput(file)
        val media = ffmpeg.probe(file)
        if (media.video == null) throw InputException("$file ne contient pas de flux vidéo")
        if (at < Duration.ZERO || at >= media.duration) {
            throw InputException("Instant ${at.toTimecode()} hors de la vidéo (durée ${media.duration.toTimecode()})")
        }
        val profile = profiles.resolve(file, profileId)
        val settings = formats?.let { profile.edit.copy(formats = it) } ?: profile.edit
        return withJobDir { workDir ->
            exporter.preview(media, at, settings, (outputDir ?: config.outputDir).resolve("previews"), workDir)
        }
    }

    /**
     * Recalcule la sélection depuis la timeline de la session, sans réanalyser (instantané).
     * Les segments recouvrant un segment décoché restent décochés.
     */
    fun reselect(session: Session, threshold: Double?, target: SelectionTarget?, requiredEvent: String? = null): Session {
        val policy = profiles.byId(session.profileId).selection.let { s ->
            s.copy(threshold = threshold ?: s.threshold, target = target ?: s.target, requiredEvent = requiredEvent)
        }
        val fresh = scoring.select(session.timeline, policy, session.media.path)
        return session.copy(highlights = HighlightMerge.preserveDisabled(session.highlights, fresh))
    }

    /** Vignette JPEG d'un instant, mise en cache dans le dossier de travail. */
    suspend fun thumbnail(media: MediaInfo, at: Duration, width: Int = 320): Path =
        exporter.thumbnail(media, at, width, cacheDir(media).resolve("thumb_${at.inWholeMilliseconds}_$width.jpg"))

    /** Extrait basse résolution d'un segment (son du montage), mis en cache. */
    suspend fun clipPreview(session: Session, highlight: Highlight): Path {
        val audio = profiles.byId(session.profileId).edit.audioStreams?.firstOrNull()
        val name = "clip_${highlight.range.start.inWholeMilliseconds}_${highlight.range.end.inWholeMilliseconds}.mp4"
        return exporter.clipPreview(session.media, highlight.range, audio, cacheDir(session.media).resolve(name))
    }

    private fun cacheDir(media: MediaInfo): Path =
        config.workDir.resolve("cache").resolve("${media.path.nameWithoutExtension}_${media.sizeBytes}")

    /**
     * Montage « tous les kills » calé sur [music] à partir d'une ou plusieurs sessions analysées.
     * Les réglages viennent du profil de la première session, surchargés par [options].
     */
    suspend fun killMontage(sessions: List<Session>, music: Path, options: MontageOptions, progress: ProgressReporter): ExportResult {
        if (sessions.isEmpty()) throw InputException("Aucune session pour le montage")
        val profile = profiles.byId(sessions.first().profileId)
        val base = profile.montage
        val settings = base.copy(
            formats = options.formats ?: base.formats,
            maxDuration = options.maxDuration ?: base.maxDuration,
            order = options.order ?: base.order,
            zoom = base.zoom.copy(enabled = options.zoom ?: base.zoom.enabled),
            flash = base.flash.copy(enabled = options.flash ?: base.flash.enabled),
            slowMotion = base.slowMotion.copy(enabled = options.slowMotion ?: base.slowMotion.enabled),
            speedRamp = base.speedRamp.copy(enabled = options.speedRamp ?: base.speedRamp.enabled),
            text = base.text.copy(enabled = options.text ?: base.text.enabled),
        )
        val analysisStep = progress.child("Musique", 0.08)
        val analysis = MusicAnalyzer.analyze(ffmpeg, music)
        analysisStep.complete()
        val plan = MontagePlanner.plan(MontagePlanner.groups(sessions, settings), analysis, settings)
        log.info { "Montage : ${plan.clips.size} clips, ${plan.totalBeats} temps à ${"%.1f".format(analysis.bpm)} BPM (${plan.duration}), départ musique ${plan.musicStart}" }
        return withJobDir { workDir ->
            montageExporter.export(
                plan,
                MontageExportRequest(
                    formats = settings.formats,
                    edit = profile.edit,
                    outputDir = options.outputDir ?: config.outputDir,
                    workDir = workDir,
                    gameName = profile.id,
                    date = KillMontageExporter.recordingDate(sessions.first().media.creationTime),
                    audioBitrate = config.app.encoder.audioBitrate,
                    hwaccel = config.app.ffmpeg.hwaccelDecode,
                ),
                progress.child("Rendu", 0.92),
            )
        }
    }

    suspend fun process(file: Path, analyze: AnalyzeOptions, export: ExportOptions, progress: ProgressReporter): ProcessOutcome {
        val analysis = analyze(file, analyze.copy(outputDir = analyze.outputDir ?: export.outputDir), progress.child("", 0.6))
        if (analysis.session.highlights.isEmpty()) return ProcessOutcome(analysis, null)
        val result = export(analysis.session, export, progress.child("Export", 0.4))
        return ProcessOutcome(analysis, result)
    }

    private suspend fun runDetectors(
        profile: GameProfile,
        media: MediaInfo,
        grid: WindowGrid,
        workDir: Path,
        progress: ProgressReporter,
        warnings: MutableList<String>,
    ): List<Pair<DetectorConfig, SignalTrack>> {
        val enabled = profile.detectors.filter { it.enabled }
        if (enabled.isEmpty()) throw HighlightsException("Le profil ${profile.id} n'a aucun détecteur actif")
        val instances = enabled.map { it to detectors.create(it.type, it.id, it.detectorParams()) }
        val semaphore = Semaphore(config.app.analysis.parallelism)

        return coroutineScope {
            instances.map { (cfg, detector) ->
                val step = progress.child(cfg.id, 1.0 / instances.size)
                async {
                    semaphore.withPermit {
                        val track = try {
                            detector.analyze(AnalysisContext(media, grid, ffmpeg, workDir, step, config.baseDir))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!config.app.analysis.continueOnDetectorError) throw e
                            log.error(e) { "Détecteur ${cfg.id} en échec, poursuite sans ce signal" }
                            SignalTrack.missing(cfg.id, grid.count, "échec : ${e.message}")
                        }
                        step.complete()
                        track.note?.let { warnings += "${cfg.id} : $it" }
                        cfg to track
                    }
                }
            }.awaitAll()
        }
    }

    private fun validateInput(file: Path) {
        if (!file.isRegularFile()) throw InputException("Fichier introuvable : $file")
        if (!file.isReadable()) throw InputException("Fichier illisible : $file")
        if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) {
            throw InputException("Extension non supportée : ${file.fileName} (attendu : ${SUPPORTED_EXTENSIONS.joinToString()})")
        }
    }

    /** Dossier temporaire propre à un job : supprimé en cas de succès, conservé en cas d'échec pour le diagnostic. */
    private suspend fun <T> withJobDir(block: suspend (Path) -> T): T {
        val dir = config.workDir.resolve("job-${UUID.randomUUID()}").createDirectories()
        val result = try {
            block(dir)
        } catch (e: Throwable) {
            if (e !is CancellationException) log.info { "Fichiers temporaires conservés pour diagnostic : $dir" }
            throw e
        }
        @OptIn(ExperimentalPathApi::class)
        runCatching { dir.deleteRecursively() }.onFailure { log.warn { "Suppression impossible de $dir : ${it.message}" } }
        return result
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("mp4", "mkv", "mov")
    }
}
