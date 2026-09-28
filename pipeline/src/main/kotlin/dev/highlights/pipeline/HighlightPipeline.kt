package dev.highlights.pipeline

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.PlatformProfile
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.profile.DetectorConfig
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.core.serialization.toTimecode
import dev.highlights.core.video.FrameSampler
import dev.highlights.export.ExportRequest
import dev.highlights.export.ExportResult
import dev.highlights.export.Exporter
import dev.highlights.montage.KillInspector
import dev.highlights.montage.KillMontageExporter
import dev.highlights.montage.MatchCutter
import dev.highlights.montage.MontageExportRequest
import dev.highlights.montage.MontagePlanner
import dev.highlights.montage.MontageReportMusic
import dev.highlights.montage.MusicAnalysis
import dev.highlights.montage.MusicAnalyzer
import dev.highlights.montage.MusicChoice
import dev.highlights.montage.MusicHistory
import dev.highlights.montage.MusicLibrary
import dev.highlights.montage.ScopeCuts
import dev.highlights.scoring.HighlightMerge
import dev.highlights.scoring.ScoringEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import kotlin.io.path.isDirectory
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
    /** Reprendre l'analyse déjà faite d'une capture inchangée (voir [AnalysisLibrary]) ; false = tout recalculer. */
    val reuse: Boolean = true,
)

data class ExportOptions(
    val formats: List<OutputFormat>? = null,
    val outputDir: Path? = null,
    /** simple ou story (montage façon YouTube) ; null = réglage du profil. */
    val style: EditStyle? = null,
    /** Musique de fond du montage story ; null = réglage du profil. */
    val music: Path? = null,
    /** Plateforme visée (tiktok, shorts, reels, youtube…) : format, volume, débit, zone sûre et durée maximale. */
    val platform: String? = null,
)

/** Surcharges ponctuelles des réglages de montage du profil (null = valeur du profil). */
data class MontageOptions(
    val formats: List<OutputFormat>? = null,
    val outputDir: Path? = null,
    val maxDuration: Duration? = null,
    /** Durée tirée du nombre de kills, la durée maximale n'étant qu'un plafond ; false : remplir la durée maximale. */
    val fitKills: Boolean? = null,
    val order: MontageOrder? = null,
    val hook: Boolean? = null,
    val effectDensity: EffectDensity? = null,
    val zoom: Boolean? = null,
    val flash: Boolean? = null,
    val flashEveryCut: Boolean? = null,
    /** Whip pan dans le sens du flick aux coupes qui en suivent ou en précèdent un. */
    val whip: Boolean? = null,
    /** Raccords sur les animations du jeu (rechargement, sprint, sort…) qui reviennent d'un clip à l'autre. */
    val matchCut: Boolean? = null,
    /** Classement des kills selon leur round (mort juste après, ace, clutch) ; false : les morts sont ignorées. */
    val rounds: Boolean? = null,
    val slowMotion: Boolean? = null,
    val speedRamp: Boolean? = null,
    val text: Boolean? = null,
    /** Équilibre jeu / musique, de -1 (musique devant) à 1 (jeu devant). */
    val audioBalance: Double? = null,
    val gameAudio: GameAudio? = null,
    /** Mettre en avant voix et rires (plans prolongés, micro monté, musique baissée dessous). */
    val reactions: Boolean? = null,
    /** Musique prise depuis son début, au lieu du passage le plus intense autour de la drop. null : réglage de chaque musique. */
    val musicFromStart: Boolean? = null,
    /**
     * Musiques d'une bibliothèque à prendre depuis leur début (choix retenu pour chacune dans l'application), quand
     * [musicFromStart] ne tranche pas pour toutes.
     */
    val fromStartMusics: Set<Path> = emptySet(),
    /** Plateforme visée : format, volume, débit, zone sûre, et la durée maximale devient un plafond de plus. */
    val platform: String? = null,
)

/** [reused] : analyse reprise de la mémoire, sans recalcul. */
data class AnalysisOutcome(val session: Session, val sessionFile: Path, val profile: GameProfile, val reused: Boolean = false)

data class ProcessOutcome(val analysis: AnalysisOutcome, val export: ExportResult?)

/** Analyse de plusieurs captures pour un seul montage : une session par capture, dans l'ordre d'enregistrement. */
data class BatchOutcome(val analyses: List<AnalysisOutcome>, val export: ExportResult?) {
    val sessions: List<Session> get() = analyses.map { it.session }
}

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

    /** Musiques déjà analysées : une bibliothèque se réessaie à chaque montage sans tout redécoder. */
    private val musicLibrary = MusicLibrary(config.workDir.resolve("cache").resolve("music"))

    /** Musiques des derniers montages : une bibliothèque ne ressort pas toujours la même. */
    val musicHistory = MusicHistory(config.outputDir.resolve("sessions").resolve("music-history.json"))

    /** Analyses déjà faites : une capture inchangée n'est pas réanalysée. */
    val library = AnalysisLibrary(config.outputDir.resolve("sessions").resolve("library.json"))

    /** Publication sur YouTube ; [browse] ouvre la page de connexion de Google. */
    fun youtube(browse: (java.net.URI) -> Unit): YouTubePublisher = YouTubePublisher(config, ffmpeg, { profiles }, browse)

    /** Plateformes connues (app.yaml compris), par identifiant. */
    val platforms: Map<String, PlatformProfile> get() = config.platforms

    private fun platform(id: String?): PlatformProfile? = id?.let {
        config.platforms[it] ?: throw InputException("Plateforme inconnue : $it (connues : ${config.platforms.keys.joinToString()})")
    }

    /** Relit les profils sur disque (après modification d'un YAML). */
    fun reloadProfiles() {
        profiles = ProfileRepository.loadDirectory(config.profilesDir)
    }

    /** Profil qui serait utilisé pour ce fichier. */
    fun resolveProfile(file: Path, forcedId: String? = null): GameProfile = profiles.resolve(file, forcedId)

    /**
     * Réglages d'édition du profil, table d'étalonnage résolue depuis le dossier de configuration (comme les modèles
     * d'images du HUD) : le rendu ne connaît que des chemins absolus.
     */
    private fun GameProfile.gradedEdit(): EditSettings {
        val story = edit.story
        return edit.copy(
            grade = edit.grade.copy(lut = edit.grade.lut?.let { config.resolve(it).toString() }),
            story = story.copy(
                music = story.music.copy(file = story.music.file?.let { config.resolve(it).toString() }),
                captions = story.captions.copy(model = story.captions.model?.let { config.resolve(it).toString() }),
                sfx = story.sfx.copy(
                    whooshFile = story.sfx.whooshFile?.let { config.resolve(it).toString() },
                    impactFile = story.sfx.impactFile?.let { config.resolve(it).toString() },
                    whooshDir = story.sfx.whooshDir?.let { config.resolve(it).toString() },
                    impactDir = story.sfx.impactDir?.let { config.resolve(it).toString() },
                ),
            ),
        )
    }

    suspend fun probe(file: Path): MediaInfo {
        validateInput(file)
        return ffmpeg.probe(file)
    }

    suspend fun analyze(file: Path, options: AnalyzeOptions, progress: ProgressReporter): AnalysisOutcome =
        analyze(file, options, progress, file.nameWithoutExtension)

    /** [sessionName] : nom du fichier de session, sans extension. */
    private suspend fun analyze(file: Path, options: AnalyzeOptions, progress: ProgressReporter, sessionName: String): AnalysisOutcome {
        validateInput(file)
        val profileForFile = profiles.resolve(file, options.profileId)
        val fingerprint = AnalysisLibrary.fingerprint(profileForFile)
        val stamp = AnalysisLibrary.stamp(file)
        if (options.reuse) {
            library.find(file, profileForFile.id, fingerprint)?.let { entry -> reuse(entry, profileForFile, options, progress)?.let { return it } }
        }
        val probeStep = progress.child("Lecture", 0.02)
        val media = ffmpeg.probe(file)
        probeStep.complete()
        if (media.video == null) throw InputException("$file ne contient pas de flux vidéo")
        if (!media.duration.isPositive()) throw InputException("$file a une durée nulle")

        val profile = profileForFile
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
        val sessionFile = (options.outputDir ?: config.outputDir).resolve("sessions").resolve("$sessionName.session.json")
        SessionStore.save(session, sessionFile)
        log.info { "${highlights.size} moment(s) retenu(s), session : $sessionFile" }
        val outcome = AnalysisOutcome(session, sessionFile, profile)
        // Empreinte prise avant l'analyse : une capture encore en cours d'écriture ne passe pas pour analysée en entier.
        if (stamp != null && AnalysisLibrary.stamp(file) == stamp) library.record(libraryEntry(outcome, fingerprint, stamp))
        return outcome
    }

    /**
     * Reprend une analyse de la mémoire : la timeline est gardée, seule la sélection est recalculée avec les réglages
     * demandés (les moments décochés le restent). null si la session n'est plus lisible : on réanalyse alors.
     */
    private fun reuse(entry: LibraryEntry, profile: GameProfile, options: AnalyzeOptions, progress: ProgressReporter): AnalysisOutcome? {
        val stored = try {
            SessionStore.load(entry.sessionFile)
        } catch (e: HighlightsException) {
            log.warn { "Analyse enregistrée illisible, nouvelle analyse : ${e.message}" }
            return null
        }
        // Deux captures du même nom dans des dossiers différents écrivent la même session : celle-ci a pu être remplacée.
        if (stored.media.path.toAbsolutePath().normalize() != entry.source || stored.profileId != entry.profileId) return null
        val session = reselect(stored, options.threshold, options.target, options.requiredEvent)
        SessionStore.save(session, entry.sessionFile)
        progress.child("Analyse reprise", 1.0).complete()
        log.info { "${entry.source} déjà analysé le ${entry.analyzedAt} : analyse reprise (${entry.sessionFile})" }
        return AnalysisOutcome(session, entry.sessionFile, profile, reused = true)
    }

    /**
     * Analyse plusieurs captures d'une même soirée pour en faire un seul montage. Chaque capture garde sa session ;
     * la cible (top N, durée) vaut pour l'ensemble : les meilleurs moments de toutes les parties, pas de chacune.
     * Les sessions sont rendues dans l'ordre d'enregistrement des captures.
     */
    suspend fun analyzeAll(files: List<Path>, options: AnalyzeOptions, progress: ProgressReporter): List<AnalysisOutcome> {
        if (files.isEmpty()) throw InputException("Aucune vidéo à analyser")
        files.forEach(::validateInput)
        val distinct = files.map { it.toAbsolutePath().normalize() }.distinct()
        // Deux captures du même nom (dossiers différents) ne doivent pas écrire la même session.
        val names = distinct.map { it.nameWithoutExtension }
        val outcomes = distinct.mapIndexed { i, file ->
            val name = if (names.count { it == names[i] } > 1) "${file.parent?.fileName ?: "capture"}_${names[i]}_${i + 1}" else names[i]
            analyze(file, options, progress.child("${file.fileName} (${i + 1}/${distinct.size})", 1.0 / distinct.size), name)
        }.sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { it.session.media })
        if (outcomes.size == 1) return outcomes
        val reselected = reselectAll(outcomes.map { it.session }, options.threshold, options.target, options.requiredEvent)
        return outcomes.zip(reselected) { outcome, session ->
            SessionStore.save(session, outcome.sessionFile)
            outcome.copy(session = session)
        }.also { all -> log.info { "${all.sumOf { it.session.highlights.size }} moment(s) retenu(s) sur ${all.size} captures" } }
    }

    suspend fun export(session: Session, options: ExportOptions, progress: ProgressReporter): ExportResult =
        export(listOf(session), options, progress)

    /** Un seul montage des segments cochés de toutes les [sessions]. Réglages du profil de la première. */
    suspend fun export(sessions: List<Session>, options: ExportOptions, progress: ProgressReporter): ExportResult {
        if (sessions.isEmpty()) throw InputException("Aucune session à exporter")
        val profile = profiles.byId(sessions.first().profileId)
        val edit = profile.gradedEdit()
        val platform = platform(options.platform)
        val settings: EditSettings = edit.copy(
            formats = options.formats ?: edit.formats,
            style = options.style ?: edit.style,
            story = options.music?.let { edit.story.copy(music = edit.story.music.copy(file = it.toAbsolutePath().toString())) } ?: edit.story,
        ).let { platform?.applyTo(it) ?: it }
        return withJobDir { workDir ->
            exporter.export(
                sessions,
                ExportRequest(
                    settings = settings,
                    outputDir = options.outputDir ?: config.outputDir,
                    workDir = workDir,
                    gameName = profile.id,
                    audioBitrate = config.app.encoder.audioBitrate,
                    hwaccel = Hwaccel.resolve(config.app.ffmpeg.hwaccelDecode),
                    audioLayout = profile.audio,
                    captionCache = config.workDir.resolve("cache").resolve("captions"),
                    platform = options.platform,
                    maxDuration = platform?.maxDuration,
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
        val edit = profile.gradedEdit()
        val settings = formats?.let { edit.copy(formats = it) } ?: edit
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

    /**
     * [reselect] sur plusieurs captures à la fois : la cible est partagée entre elles. Seuil, marges et cible viennent
     * du profil de la première session, comme pour l'export.
     */
    fun reselectAll(sessions: List<Session>, threshold: Double?, target: SelectionTarget?, requiredEvent: String? = null): List<Session> {
        if (sessions.size <= 1) return sessions.map { reselect(it, threshold, target, requiredEvent) }
        val policy = profiles.byId(sessions.first().profileId).selection.let { s ->
            s.copy(threshold = threshold ?: s.threshold, target = target ?: s.target, requiredEvent = requiredEvent)
        }
        val fresh = scoring.selectAcross(sessions.map { it.timeline to it.media.path }, policy)
        return sessions.zip(fresh) { session, highlights ->
            session.copy(highlights = HighlightMerge.preserveDisabled(session.highlights, highlights))
        }
    }

    /** Vignette JPEG d'un instant, mise en cache dans le dossier de travail. */
    suspend fun thumbnail(media: MediaInfo, at: Duration, width: Int = 320): Path =
        exporter.thumbnail(media, at, width, cacheDir(media).resolve("thumb_${at.inWholeMilliseconds}_$width.jpg"))

    /** Extrait basse résolution d'un segment (son du montage), mis en cache. */
    suspend fun clipPreview(session: Session, highlight: Highlight): Path {
        val profile = profiles.byId(session.profileId)
        val audio = profile.edit.audioIndices(AudioTracks.of(session.media.audio, profile.audio)).firstOrNull()
        val name = "clip_${highlight.range.start.inWholeMilliseconds}_${highlight.range.end.inWholeMilliseconds}.mp4"
        return exporter.clipPreview(session.media, highlight.range, audio, cacheDir(session.media).resolve(name))
    }

    private fun cacheDir(media: MediaInfo): Path =
        config.workDir.resolve("cache").resolve("${media.path.nameWithoutExtension}_${media.sizeBytes}")

    /** Analyse chaque musique de [dir] (ou la reprend du cache) : le prochain montage sur ce dossier n'aura plus qu'à choisir. */
    suspend fun loadMusicLibrary(dir: Path, progress: ProgressReporter): List<MusicAnalysis> = musicLibrary.load(ffmpeg, dir, progress)

    /**
     * Montage « tous les kills » calé sur [music] à partir d'une ou plusieurs sessions analysées.
     * Les réglages viennent du profil de la première session, surchargés par [options].
     * [music] peut être un dossier : la musique y est choisie ([MusicChoice]), celle sur laquelle le montage est le mieux noté.
     */
    suspend fun killMontage(sessions: List<Session>, music: Path, options: MontageOptions, progress: ProgressReporter): ExportResult {
        if (sessions.isEmpty()) throw InputException("Aucune session pour le montage")
        val profile = profiles.byId(sessions.first().profileId)
        val base = profile.montage
        val platform = platform(options.platform)
        val maxDuration = (options.maxDuration ?: base.maxDuration).let { d -> platform?.maxDuration?.let { minOf(it, d) } ?: d }
        val settings = base.copy(
            formats = platform?.let { listOf(it.format) } ?: options.formats ?: base.formats,
            maxDuration = maxDuration,
            length = base.length.copy(fitKills = options.fitKills ?: base.length.fitKills),
            order = options.order ?: base.order,
            hook = options.hook ?: base.hook,
            cuts = base.cuts.copy(fromStart = options.musicFromStart ?: base.cuts.fromStart),
            effectDensity = options.effectDensity ?: base.effectDensity,
            zoom = base.zoom.copy(enabled = options.zoom ?: base.zoom.enabled),
            flash = base.flash.copy(
                enabled = options.flash ?: base.flash.enabled,
                onEveryCut = options.flashEveryCut ?: base.flash.onEveryCut,
            ),
            whip = base.whip.copy(enabled = options.whip ?: base.whip.enabled),
            matchCut = base.matchCut.copy(
                enabled = options.matchCut ?: base.matchCut.enabled,
                weapon = base.matchCut.weapon?.let { it.copy(template = config.resolve(it.template).toString()) },
            ),
            // Sans événement de mort, ni round, ni ace, ni clutch, ni pénalité : le classement redevient celui d'avant.
            killStyle = if (options.rounds == false) base.killStyle.copy(deathEvent = "") else base.killStyle,
            slowMotion = base.slowMotion.copy(enabled = options.slowMotion ?: base.slowMotion.enabled),
            speedRamp = base.speedRamp.copy(enabled = options.speedRamp ?: base.speedRamp.enabled),
            text = base.text.copy(enabled = options.text ?: base.text.enabled),
            reactions = options.reactions ?: base.reactions,
            audio = base.audio.copy(
                balance = options.audioBalance ?: base.audio.balance,
                game = options.gameAudio ?: base.audio.game,
                loudnessLufs = platform?.loudnessLufs ?: base.audio.loudnessLufs,
            ),
        )
        val musicStep = progress.child(if (music.isDirectory()) "Musiques" else "Musique", 0.06)
        val musics = if (music.isDirectory()) musicLibrary.load(ffmpeg, music, musicStep) else listOf(MusicAnalyzer.analyze(ffmpeg, music))
        musicStep.complete()
        val inspected = KillInspector(ffmpeg).inspect(MontagePlanner.groups(sessions, settings), settings, profile.audio, progress.child("Kills", 0.06))
        val groups = MatchCutter(ffmpeg).inspect(inspected, settings, progress.child("Visée", 0.02))
        val fromStart = options.fromStartMusics.map { it.toAbsolutePath().normalize() }.toSet()
        val (chosen, choice) = if (music.isDirectory()) {
            // Le réglage « depuis le début » de chaque musique, sauf si l'appel l'impose à toutes.
            val ranked = MusicChoice.rank(groups, musics, settings, musicHistory.recent()) { f ->
                options.musicFromStart ?: (f.toAbsolutePath().normalize() in fromStart || base.cuts.fromStart)
            }
            val best = ranked.first()
            val plan = best.plan ?: throw HighlightsException("Aucune musique de $music ne permet ce montage : ${best.error}")
            log.info { "Musique choisie : ${best.music.file.fileName} (note ${"%.3f".format(best.score)}, ${best.groups} groupe(s), parmi ${ranked.size})" }
            plan to ranked.map(MontageReportMusic::of)
        } else {
            MontagePlanner.best(groups, MusicChoice.prepare(musics.single(), settings), settings) to emptyList()
        }
        val plan = ScopeCuts.apply(chosen)
        log.info { "Montage : ${plan.clips.size} clips, ${plan.totalBeats} temps à ${"%.1f".format(plan.music.bpm)} BPM (${plan.duration}), départ musique ${plan.musicStart}" }
        return withJobDir { workDir ->
            montageExporter.export(
                plan,
                MontageExportRequest(
                    formats = settings.formats,
                    edit = profile.gradedEdit().let { platform?.applyTo(it) ?: it },
                    outputDir = options.outputDir ?: config.outputDir,
                    workDir = workDir,
                    gameName = profile.id,
                    date = KillMontageExporter.recordingDate(sessions.first().media.creationTime),
                    audioBitrate = config.app.encoder.audioBitrate,
                    hwaccel = Hwaccel.resolve(config.app.ffmpeg.hwaccelDecode),
                    audioLayout = profile.audio,
                    musicChoice = choice,
                    platform = options.platform,
                ),
                progress.child("Rendu", 0.86),
            )
        }.also { musicHistory.record(plan.music.file) } // Choisie ou imposée : elle a servi, elle se repose un peu.
    }

    /** [analyzeAll] puis un seul montage de toutes les captures. */
    suspend fun processAll(files: List<Path>, analyze: AnalyzeOptions, export: ExportOptions, progress: ProgressReporter): BatchOutcome {
        val analyses = analyzeAll(files, analyze.copy(outputDir = analyze.outputDir ?: export.outputDir), progress.child("", 0.6))
        val sessions = analyses.map { it.session }
        if (sessions.none { s -> s.highlights.any { it.enabled } }) return BatchOutcome(analyses, null)
        return BatchOutcome(analyses, export(sessions, export, progress.child("Export", 0.4)))
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
        val tracks = AudioTracks.of(media.audio, profile.audio)
        log.info { "Pistes audio : ${tracks.describe()}" }
        val steps = enabled.associate { it.id to progress.child(it.id, 1.0 / enabled.size) }

        // Les détecteurs de secours (fallbackFor) attendent le signal qu'ils remplacent : ils ne tournent que s'il est
        // absent, pour ne pas compter deux fois les mêmes événements ni décoder la vidéo pour rien. Seuls les signaux
        // remplaçables sont lus d'abord (base d'Outplayed : un instant) ; les secours nécessaires tournent ensuite en
        // même temps que tous les autres, décodage vidéo et analyse audio en parallèle, au lieu d'attendre leur fin.
        val (fallbacks, others) = enabled.partition { cfg -> cfg.fallbackFor != null && enabled.any { it.id == cfg.fallbackFor } }
        val replaceable = fallbacks.mapNotNull { it.fallbackFor }.toSet()
        val (targets, rest) = others.partition { it.id in replaceable }
        val first = if (targets.isEmpty()) emptyList() else runPhase(targets, media, grid, workDir, steps, tracks, warnings)
        val missing = first.filter { it.second.isMissing }.map { it.first.id }.toSet()
        val (needed, skipped) = fallbacks.partition { it.fallbackFor in missing }
        skipped.forEach { steps.getValue(it.id).complete() }
        needed.forEach { log.info { "${it.fallbackFor} sans signal : ${it.id} prend le relais" } }
        val second = runPhase(rest + needed, media, grid, workDir, steps, tracks, warnings)
        val results = (first + second).associateBy { it.first.id }
        return enabled.mapNotNull { results[it.id] }
    }

    /** Lance [configs] ensemble : une seule passe de décodage vidéo partagée, au plus `parallelism` à la fois. */
    private suspend fun runPhase(
        configs: List<DetectorConfig>,
        media: MediaInfo,
        grid: WindowGrid,
        workDir: Path,
        steps: Map<String, ProgressReporter>,
        tracks: AudioTracks,
        warnings: MutableList<String>,
    ): List<Pair<DetectorConfig, SignalTrack>> {
        val instances = configs.map { it to detectors.create(it.type, it.id, it.detectorParams()) }
        val semaphore = Semaphore(config.app.analysis.parallelism)

        // Chaque détecteur déclare d'abord ses besoins (zones vidéo, cadence) : la capture n'est ensuite décodée
        // qu'une fois pour tous ceux qui lisent des images. Une préparation en échec est relancée dans son détecteur,
        // pour être traitée comme n'importe quelle autre panne (continueOnDetectorError).
        val frames = FrameSampler(ffmpeg, media)
        val contexts = instances.map { (cfg, _) ->
            AnalysisContext(media, grid, ffmpeg, workDir, steps.getValue(cfg.id), config.baseDir, frames, tracks)
        }
        val preparations = instances.mapIndexed { i, (_, detector) -> runCatching { detector.prepare(contexts[i]) } }

        return coroutineScope {
            // Les passes vidéo partagées démarrent tout de suite, hors du sémaphore : le décodage recouvre
            // l'analyse audio au lieu d'attendre son tour.
            launch { frames.runAll() }
            instances.mapIndexed { i, (cfg, detector) ->
                val step = contexts[i].progress
                async {
                    semaphore.withPermit {
                        val track = try {
                            preparations[i].getOrThrow()
                            detector.analyze(contexts[i])
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
        /** Conteneurs acceptés : ceux des enregistreurs courants (Outplayed, OBS, ShadowPlay, Medal, Xbox Game Bar). */
        val SUPPORTED_EXTENSIONS = setOf("mp4", "mkv", "mov", "m4v", "avi", "flv", "ts", "mts", "m2ts", "webm", "wmv", "mpg", "mpeg")
    }
}
