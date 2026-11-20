package dev.highlights.ui

import dev.highlights.montage.ThumbnailStyle
import dev.highlights.export.OutputNamer
import dev.highlights.publish.YouTubeText
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.HighlightsException
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.pipeline.AnalyzeOptions
import dev.highlights.pipeline.ConfigLocator
import dev.highlights.pipeline.Evening
import dev.highlights.pipeline.EveningPlanner
import dev.highlights.pipeline.ExportOptions
import dev.highlights.pipeline.FolderWatcher
import dev.highlights.pipeline.HighlightPipeline
import dev.highlights.pipeline.MontageOptions
import dev.highlights.pipeline.Pipelines
import dev.highlights.pipeline.VariantPick
import dev.highlights.montage.MontageVariants
import dev.highlights.pipeline.ProblemReport
import dev.highlights.pipeline.SystemInfo
import dev.highlights.pipeline.YouTubePublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Pipeline prêt à l'emploi et fichier de config dont il provient. */
/** [ffmpeg] : celui du pipeline, pour le lecteur intégré ; null dans les tests sans FFmpeg (lecteur du système). */
class Backend(val pipeline: HighlightPipeline, val configFile: Path, val ffmpeg: dev.highlights.core.ffmpeg.FfmpegService? = null)

interface UiActions {
    fun chooseSource()
    /** Ajoute des captures à celles déjà choisies : un seul montage pour toutes. */
    fun addSources()
    fun removeSource(path: Path)
    fun chooseSession()
    fun dropFiles(paths: List<Path>)
    fun reloadConfig()
    fun openProfilesFolder()
    fun editProfile()

    fun setProfile(id: String?)
    fun toggleFormat(format: OutputFormat)
    /** Plateforme visée à l'export (null : les formats cochés). */
    fun setPlatform(id: String?)
    fun setStyle(style: EditStyle)
    fun setMomentMode(mode: MomentMode)
    fun setTargetMode(mode: TargetMode)
    fun setDurationText(text: String)
    fun setTopNText(text: String)
    fun setThreshold(value: Double)
    fun chooseOutputDir()
    fun openOutputDir()

    fun analyze()
    fun export()
    /** Clip « meilleur moment » du moment [key] : une vidéo 9:16 de 15 à 30 s (voir [dev.highlights.pipeline.MomentClips]). */
    fun clipMoment(key: String)
    fun cancelJob()

    fun toggleSegment(id: String)
    fun setAllSegments(enabled: Boolean)
    fun selectSegment(id: String?)
    /** Retouche : déplace le début (ou la fin si [end]) du moment [id] de [delta], dans les bornes du profil. */
    fun nudgeSegment(id: String, end: Boolean, delta: Duration)
    /** Ajoute à la main un moment centré sur [at] dans la capture [entry] (index dans la session ouverte). */
    fun addSegmentAt(entry: Int, at: Duration)
    /** Fait monter ([delta] < 0) ou descendre le moment [id] dans l'ordre du montage. */
    fun moveSegment(id: String, delta: Int)
    fun previewClip(id: String)
    fun previewVertical(id: String)
    fun regenerateVerticalPreview()

    fun openMontage()
    fun closeMontage()
    fun chooseMusic()
    /** Dossier de musiques où le montage choisit la sienne. */
    fun chooseMusicLibrary()
    fun updateMontage(change: (MontageUiState) -> MontageUiState)

    // Cartes d'ouverture et de fin (logo, pseudo, fond).
    fun setIntroEnabled(enabled: Boolean)
    fun openIntro()
    fun closeIntro()
    fun updateIntro(change: (dev.highlights.core.model.IntroOutroSettings) -> dev.highlights.core.model.IntroOutroSettings)
    fun chooseIntroLogo()
    fun chooseIntroImage()
    /** Carte montrée en aperçu : l'ouverture ou la fin ([outro]), dans [format]. */
    fun setIntroPreview(format: OutputFormat, outro: Boolean)
    /** Réglages d'export des highlights (préréglage, miniature…) ; la sélection est recalculée si la durée visée change. */
    fun updateExportSettings(change: (SettingsState) -> SettingsState) {}
    fun createMontage()

    /** File des traitements (voir [JobQueue]) : annuler, avancer/reculer un traitement en attente, ouvrir un résultat. */
    fun cancelQueuedJob(id: Long) {}
    fun moveQueuedJob(id: Long, delta: Int) {}
    fun openJobResult(id: Long) {}
    fun showJobError(id: Long) {}
    fun clearFinishedJobs() {}
    /** Fermeture demandée avec des traitements en file : quitter quand même, ou rester. */
    fun confirmQuit() {}
    fun cancelQuit() {}
    /** Aperçus de quelques variantes du montage au lieu du rendu final (voir [VariantsUiState]). */
    fun previewMontage() {}
    /** Rend en pleine qualité la variante [index] des aperçus, et enregistre ce choix. */
    fun renderVariant(index: Int) {}
    fun closeVariants() {}

    /** Frise du plan de montage ([MontagePlanState]) : l'ouvrir (plan calculé aussitôt), la fermer. */
    /** Recalcule le plan avec les corrections posées, sans réanalyser (inspection des kills en cache). */
    fun replanMontage() {}
    /** Corrige un groupe : [kills], ses désignations « capture@instant » ([dev.highlights.montage.KillRef]). */
    fun editMontageKill(kills: List<String>, edit: KillEdit) {}
    /**
     * Épingle le groupe [kills] sur le temps du morceau le plus proche de [musicSeconds] (instant dans le morceau, en
     * secondes : le pont le passe tel quel) et replanifie aussitôt.
     */
    fun pinMontageKill(kills: List<String>, musicSeconds: Double) {}
    /** Retire toutes les corrections. */
    fun resetMontagePlan() {}
    /**
     * Calage à l'image d'un kill de la frise ([KillTimingUi]) : l'ouvrir ([kill] : sa désignation « capture@instant »),
     * avancer de [delta] images, choisir l'image [index], décaler le kill de [frames] images par rapport à son temps,
     * appliquer (et replanifier), revenir à la détection, écarter le kill, fermer.
     */
    fun openKillTiming(kill: String) {}
    fun stepKillFrame(delta: Int) {}
    fun pickKillFrame(index: Int) {}
    fun shiftKillBeat(frames: Int) {}
    fun applyKillTiming() {}
    fun clearKillTiming() {}
    fun excludeTimedKill() {}
    fun closeKillTiming() {}
    /**
     * « Kill ici » à l'écoute ([KillTimingUi.cursor]) : déplacer le curseur de [millis] ms, le placer à [seconds] de la
     * capture, saisir l'instant, écouter image et son (depuis 1 s avant si [fromBefore] ; rejouer arrête), poser le
     * curseur sur l'image montrée. Le lecteur du panneau : [killTimingPlayer].
     */
    fun stepKillMs(millis: Int) {}
    fun seekKillTiming(seconds: Double) {}
    fun setKillTimingText(text: String) {}
    fun playKillTiming(fromBefore: Boolean) {}
    fun markKillHere() {}
    /**
     * Repères du panneau de calage, au curseur : début ([start]) ou fin du plan du kill, début d'une section, fin d'une
     * section sans kill ou à garder entière ([keep]). Sur la frise : retirer une borne de plan ([kill] : sa désignation)
     * ou une section ([index] dans la liste). Chacun replanifie.
     */
    fun markKillCut(start: Boolean) {}
    fun markSectionStart() {}
    fun markSectionEnd(keep: Boolean) {}
    fun clearKillCut(kill: String, start: Boolean) {}
    fun removeMontageRange(index: Int) {}
    /** Oublie les longueurs de plan apprises des coupes (`kill-cuts.json` mis de côté). */
    fun resetLengths() {}
    /**
     * « Kill sur ce moment de la musique » : l'outil d'écoute du morceau ([MusicDropUi.target]) pour le kill [kill] ;
     * « Poser le kill ici » ([confirmMusicDrop]) l'épingle à l'instant du curseur, exactement.
     */
    fun openKillOnMusic(kill: String) {}
    /** Oublie ce qui a été appris des kills posés sur la musique (`kill-pins.json` mis de côté). */
    fun resetPins() {}
    /**
     * « Montage sur la musique » ([MusicEditorUi]) : ouvrir (aussi tout seul après le choix d'une musique), fermer, vue
     * (zoom de [factor] autour de [center] s, défiler de [seconds], centrer), lecture (bascule, tête de lecture, écouter
     * autour), aimant, choisir un kill ([kill] : désignation de son groupe, null : aucun), glisser ([kind] : KILL, NEW ou
     * DROP ; [free] : Alt, sans aimant ; [over] : au-dessus de la musique) puis lâcher, retirer, tout remettre en auto,
     * aperçu 360p du montage tel quel.
     */
    fun openMusicEditor() {}
    fun closeMusicEditor() {}
    fun zoomEditor(factor: Double, center: Double) {}
    fun scrollEditor(seconds: Double) {}
    fun centerEditor(seconds: Double) {}
    fun toggleEditorPlayback() {}
    fun seekEditor(seconds: Double) {}
    fun listenEditorAround(seconds: Double) {}
    fun toggleEditorMagnet() {}
    fun selectEditorKill(kill: String?) {}
    fun dragEditor(kind: String, key: String?, seconds: Double, free: Boolean, over: Boolean) {}
    fun dropEditor() {}
    fun removeEditorKill(kill: String) {}
    fun resetEditorAuto() {}
    fun previewEditedMontage() {}
    /** Oublie ce qui a été appris des placements de l'étape 1 (`kill-layout.json` mis de côté). */
    fun resetLayoutTaste() {}
    /** Étape 1 → 2 : les plans se construisent autour des kills posés ; étape 2 → 1 : revenir aux kills. */
    fun placeEditorClips() {}
    /** Parcours du montage ([MontageStep]) : aller à une étape, suivante, précédente, tout en auto, musique changée. */
    fun goMontageStep(step: String) {}
    fun nextMontageStep() {}
    fun previousMontageStep() {}
    fun montageAllAuto() {}
    fun montageMusicChanged() {}
    /** Étape 3b : retirer un plan (son repère avec), ajouter un passage sans kill à [seconds]. */
    fun removeEditorClip(kill: String) {}
    fun addEditorShowcase(seconds: Double) {}
    /** Étape 1 : l'outil d'un double-clic ([EditorTool]), poser un repère, repères « kill » en trop ([dev.highlights.montage.ExtraMarks]). */
    fun setEditorTool(tool: String) {}
    fun addEditorMark(seconds: Double, free: Boolean) {}
    fun setEditorExtras(mode: String) {}
    fun backEditorToKills() {}
    val killTimingPlayer: dev.highlights.ui.player.VideoPlayer? get() = null
    /** Oublie les calages appris (`kill-timing.json` mis de côté, comme les goûts). */
    fun resetTiming() {}
    /**
     * Outil de la drop du morceau du plan ([MusicDropUi]) : ouvrir (clic sur « DROP »), placer le curseur à [seconds]
     * du morceau, le déplacer de [millis] ms ou de [beats] temps, saisir l'instant, écouter (depuis 2 s avant si
     * [fromBefore] ; rejouer arrête), poser le curseur à l'instant entendu, « La drop est ici » (retenue pour ce morceau
     * et plan recalculé), revenir à la détection, fermer.
     */
    fun openMusicDrop() {}
    fun seekMusicDrop(seconds: Double) {}
    fun stepMusicDrop(millis: Int) {}
    fun stepMusicDropBeat(beats: Int) {}
    fun setMusicDropText(text: String) {}
    fun playMusicDrop(fromBefore: Boolean) {}
    fun markMusicDropHere() {}
    fun confirmMusicDrop() {}
    fun clearMusicDrop() {}
    fun closeMusicDrop() {}
    /** Oublie la drop posée d'un morceau ([key] : sa clé, vue des goûts). */
    fun forgetMusicDrop(key: String) {}
    /** Oublie ce qui a été appris des drops posées (`drop-learning.json` mis de côté ; les drops posées restent). */
    fun resetDropLearning() {}

    /** Fenêtre de publication d'une vidéo exportée sur YouTube, champs proposés d'après la vidéo. */
    fun openPublish(video: Path)
    fun closePublish()
    fun updatePublish(change: (PublishUiState) -> PublishUiState)
    /** Revient au titre, à la description et aux tags proposés. */
    fun resetPublishText()
    fun publishToYouTube()
    fun youtubeLogout()
    /** Envoi manuel : page d'envoi de YouTube dans le navigateur, vidéo montrée dans l'Explorateur pour l'y glisser. */
    fun openYouTubeUpload()
    fun copyToClipboard(text: String)
    /** Miniature YouTube de la vidéo à publier (habillage par défaut, le vif), écrite à côté d'elle. */
    fun makeThumbnail()
    /** Réponse à la question du rythme rapide sous le résultat d'un montage kills : ressenti de sa musique retenu. */
    fun rateMusicFeel(vote: dev.highlights.montage.FeelVote) {}
    /** Ouvre app.yaml dans l'éditeur (identifiant YouTube, réglages de publication). */
    fun editConfig()
    fun browse(uri: java.net.URI)

    /** Recherche de moments dans les parties analysées, et montage kills des moments cochés. */
    fun showSearch()
    fun closeSearch()
    fun updateSearch(change: (SearchState) -> SearchState)
    fun toggleMoment(key: String)
    fun montageFromSearch()

    /** Statistiques des parties analysées, par soirée. */
    fun showStats()

    // Assistant « nouveau jeu » (NewGameView) : défauts vides pour les faux contrôleurs des tests.
    fun showNewGame() {}
    fun closeNewGame() {}
    fun chooseNewGameVideo() {}
    fun updateNewGame(game: String? = null, time: String? = null) {}
    fun showNewGameFrame() {}
    fun setNewGameBox(x: Double, y: Double, width: Double, height: Double) {}
    fun addNewGameMark() {}
    fun removeNewGameMark(index: Int) {}
    fun scanNewGame() {}
    fun toggleNewGameVerdict(method: String, index: Int) {}
    fun createNewGameProfile() {}
    fun closeStats()
    /** Écran des goûts appris (TasteView) : ouvrir, fermer, oublier (après confirmation). */
    fun showTaste() {}
    fun closeTaste() {}
    fun askResetTaste() {}
    fun cancelResetTaste() {}
    fun confirmResetTaste() {}
    fun setStatsGame(game: String)
    /** Réanalyse les parties analysées avec d'anciens réglages de détection, puis rouvre les statistiques. */
    fun refreshOutdated()
    /** Récap d'une soirée ([date] AAAA-MM-JJ, jeu [profileId]) : intercalaire par partie, moments, bilan ; dans la file. */
    fun recapEvening(date: String, profileId: String) {}

    /** Ferme l'analyse ouverte (elle reste enregistrée) et revient à la liste des analyses. */
    fun showLibrary()
    fun openLibraryItem(sessionFile: Path)
    fun toggleLibraryItem(sessionFile: Path)
    fun openLibrarySelection()
    /** Oublier une analyse : demande d'abord confirmation (la vidéo n'est jamais touchée). */
    fun askForget(sessionFile: Path)
    fun confirmForget()
    fun cancelForget()
    fun chooseWatchFolder()
    fun stopWatching()
    /** Montage automatique de chaque soirée finie (voir [EveningAuto]). */
    fun setEveningMontage(enabled: Boolean) {}
    /** Délai sans nouvelle partie après lequel la soirée est finie (« 1 h », « 45 min »). */
    fun setEveningDelay(text: String) {}
    fun setEveningMinKills(count: Int) {}
    /** Style du montage de soirée : un identifiant, `none`, ou null (celui par défaut du fichier de styles). */
    fun setEveningStyle(id: String?) {}
    /** Ouvre la vidéo du dernier montage de soirée. */
    fun openLastEvening() {}

    fun open(path: Path)
    fun reveal(path: Path)
    fun dismissError()
    fun dismissImagePreview()

    /** Webcam en incrustation ([WebcamSection]). */
    fun setWebcamMode(mode: WebcamMode) {}
    fun chooseWebcamFile() {}
    fun setWebcamOffset(text: String) {}
    fun previewWebcam() {}
    fun dismissWebcamPreview() {}

    /** Télécharge la nouvelle version, ferme l'application et l'installe par-dessus. */
    fun installUpdate()
    fun dismissUpdate()

    /** Langue de l'interface ([tag] d'un fichier de traduction, null = celle de Windows), retenue d'un lancement à l'autre. */
    fun setLanguage(tag: String?)

    // Signaler un problème : ZIP de diagnostic enregistré par l'utilisateur, puis ticket GitHub prérempli.
    fun openReport()
    /** Depuis la boîte d'erreur : l'erreur affichée entre dans le rapport et le titre du ticket. */
    fun reportError()
    /** Depuis la proposition qui suit un plantage. */
    fun reportCrash()
    fun dismissCrash()
    fun closeReport()
    fun saveReport()
    fun openIssuePage()
    fun revealReport()
    /** Dossier des journaux dans l'Explorateur (menu d'aide). */
    fun openLogsFolder()

    // Mémoire insuffisante pour le traitement demandé.
    fun retryMemoryCheck()
    fun startDespiteMemory()
    fun dismissMemoryWarning()

    // Lecteur intégré (dev.highlights.ui.player) : lit une vidéo dans l'application ; sans FFmpeg, le lecteur du système.
    /** Moteur du lecteur, dont la fenêtre lit les images ; null tant qu'aucune vidéo n'a été ouverte. */
    val videoPlayer: dev.highlights.ui.player.VideoPlayer? get() = null
    fun playVideo(path: Path) {}
    /** Glissement sur la frise de la capture [entry] : son image à [at], lecteur en pause. */
    fun scrubSource(entry: Int, at: Duration) {}
    /** Fin d'un glissement sur la frise : [play] lance la lecture à l'endroit relâché (faux : glissement annulé). */
    fun scrubSourceEnd(play: Boolean = true) {}
    fun playerToggle() {}
    fun playerSeek(at: Duration) {}
    fun playerScrub(at: Duration) {}
    fun playerScrubEnd() {}
    fun closePlayer() {}
}

/**
 * Ce dont le rapport de problème a besoin, remplaçable en test : [crashLog] null = pas de proposition après plantage
 * (tests : le vrai dossier des journaux n'est pas lu), [memory] = mémoire disponible (null : pas de contrôle).
 */
class ReportEnvironment(
    val logsDir: Path = CrashLog.DEFAULT_DIR,
    val crashDirs: () -> List<Path> = { emptyList() },
    val redactor: dev.highlights.pipeline.Redactor = dev.highlights.pipeline.Redactor.forThisMachine(),
    val version: String = System.getProperty("jpackage.app-version") ?: "dev",
    val system: () -> List<String> = { emptyList() },
    val windows: () -> String = { System.getProperty("os.name") },
    val totalMemory: () -> Long = { 0 },
    val memory: () -> Long? = { null },
    val crashLog: CrashLog? = null,
    /** Dossier proposé pour enregistrer le ZIP. */
    val saveDir: () -> Path? = { null },
) {
    companion object {
        /** L'application installée (ou lancée en développement) : vrais journaux, vraie machine. */
        fun forThisApp(): ReportEnvironment {
            // Dossier de l'exécutable installé : la JVM y écrit ses hs_err quand elle est lancée depuis le raccourci.
            val appDir = System.getProperty("jpackage.app-path")?.let { Path.of(it).parent }
            val crashDirs = { dev.highlights.pipeline.ProblemReport.defaultCrashDirs(appDir) + CrashLog.DEFAULT_DIR }
            val version = System.getProperty("jpackage.app-version") ?: "dev"
            return ReportEnvironment(
                crashDirs = crashDirs,
                version = version,
                system = { dev.highlights.pipeline.SystemInfo.describe(version, I18n.locale.toLanguageTag()) },
                windows = dev.highlights.pipeline.SystemInfo::windowsVersion,
                totalMemory = dev.highlights.pipeline.SystemInfo::totalMemory,
                // -Dhighlights.availableMemory=<Mo> simule une machine à court de mémoire (vérifier le message).
                memory = {
                    System.getProperty("highlights.availableMemory")?.toLongOrNull()?.let { it shl 20 }
                        ?: dev.highlights.pipeline.SystemInfo.availableMemory()
                },
                crashLog = CrashLog(CrashLog.DEFAULT_DIR, crashDirs),
                // Le Bureau (FileSystemView suit sa redirection vers OneDrive), sinon le dossier personnel.
                saveDir = { runCatching { javax.swing.filechooser.FileSystemView.getFileSystemView().homeDirectory.toPath() }.getOrNull() },
            )
        }
    }
}

/**
 * Toute la logique de l'interface : les composables se contentent d'afficher [state] et d'appeler les actions.
 * Les traitements lourds tournent hors du thread UI ; un seul traitement long (analyse ou export) à la fois.
 */
class AppController(
    private val scope: CoroutineScope,
    private val platform: Platform,
    private val watchPrefs: WatchPrefsStore = WatchPrefsStore(WatchPrefsStore.DEFAULT_FILE),
    private val musicPrefs: MusicPrefsStore = MusicPrefsStore(MusicPrefsStore.DEFAULT_FILE),
    private val languagePrefs: LanguagePrefsStore = LanguagePrefsStore(LanguagePrefsStore.DEFAULT_FILE),
    private val introPrefs: IntroPrefsStore = IntroPrefsStore(IntroPrefsStore.DEFAULT_FILE),
    /** Intervalle entre deux passages sur le dossier surveillé, et temps sans écriture avant qu'une capture soit prise. */
    private val watchInterval: Duration = 5.seconds,
    private val watchSettle: Duration = 15.seconds,
    private val clock: () -> Instant = Instant::now,
    private val updater: Updater = Updater.NONE,
    private val reportEnv: ReportEnvironment = ReportEnvironment(),
    private val backendFactory: () -> Backend = {
        val file = ConfigLocator.locate()
        val config = ConfigYaml.load(file)
        val ffmpeg = Pipelines.ffmpeg(config)
        Backend(Pipelines.create(config, ffmpeg), config.source ?: file, ffmpeg)
    },
) : UiActions {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val player = dev.highlights.ui.player.PlayerController(scope, _state, { platform.open(it) })

    @Volatile
    private var backend: Backend? = null
    private val intro = IntroController(scope, platform, introPrefs, _state) { backend?.pipeline }
    private var mainJob: Job? = null

    // ---------------------------------------------------------------- file des traitements lourds

    /**
     * Traitements lourds, un à la fois ([JobQueue]). Ceux qui changent l'écran (analyse, réanalyse : [QueuedJob.blocking])
     * tiennent aussi [UiState.job], qui verrouille l'interface comme avant ; les autres (export, montage, aperçus, dossier
     * surveillé, soirée) la laissent libre.
     */
    private val queue: JobQueue = JobQueue(scope, onChange = { list ->
        _state.update { s -> s.copy(jobs = list, job = blockingJob(list) ?: s.job?.takeIf { it.queueId == null }) }
    }, describe = ::describeFailure)

    /** Analyse du dossier surveillé en attente dans la file, par traitement : annulée, sa capture sort de l'attente. */
    private val watchJobs = java.util.concurrent.ConcurrentHashMap<Long, Path>()

    /** Soirées déjà mises en file : la vérification suivante ne les remet pas. */
    private val queuedEvenings: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun blockingJob(list: List<QueuedJob>): JobState? = list.firstOrNull { it.blocking && it.active }?.let { j ->
        JobState(
            j.title, j.fraction,
            if (j.status == JobStatus.WAITING) tr("ui.jobs.waitingTurn") else j.stage,
            j.elapsed, j.eta, cancellable = true, queueId = j.id,
        )
    }

    /**
     * Met [block] dans la file. Un traitement bloquant qui échoue ouvre aussi la boîte d'erreur (l'utilisateur
     * l'attendait) ; les autres l'affichent dans la carte des traitements, sans arrêter les suivants.
     */
    private fun enqueue(
        title: String,
        blocking: Boolean = false,
        auto: Boolean = false,
        onProgress: ((dev.highlights.core.progress.ProgressSnapshot) -> Unit)? = null,
        block: suspend (ProgressReporter) -> Path?,
    ): Long = queue.submit(title, blocking, auto, onProgress) { progress ->
        try {
            block(progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (blocking) reportFailure(tr("ui.error.taskFailed", "task" to title), e)
            throw e
        }
    }

    /** Message lisible et détails d'un échec, comme la boîte d'erreur les montre. */
    private fun describeFailure(e: Throwable): Pair<String, List<String>> = when (e) {
        is FfmpegException -> e.localizedMessage to (e.stderrTail.takeLast(20) + listOf("", tr("ui.error.step", "step" to e.description), tr("ui.error.command", "command" to e.commandLine), tr("ui.error.log", "file" to LOG_FILE)))
        is HighlightsException -> e.localizedMessage to emptyList()
        is OutOfMemoryError -> tr("ui.memory.outOfMemory") to listOf(tr("ui.error.log", "file" to LOG_FILE))
        else -> e.toString() to listOf(tr("ui.error.log", "file" to LOG_FILE))
    }

    override fun cancelQueuedJob(id: Long) {
        queue.cancel(id)
        // Analyse du dossier surveillé annulée avant d'avoir commencé : sa capture ne bloque plus la soirée.
        watchJobs.remove(id)?.let { file -> _state.update { s -> s.copy(watch = s.watch.copy(pending = s.watch.pending - setOf(file))) } }
    }

    override fun moveQueuedJob(id: Long, delta: Int) = queue.move(id, delta)

    override fun openJobResult(id: Long) {
        queue.snapshot.find { it.id == id }?.result?.let(platform::open)
    }

    override fun showJobError(id: Long) {
        val job = queue.snapshot.find { it.id == id } ?: return
        showError(tr("ui.error.taskFailed", "task" to job.title), job.error ?: "", job.details)
    }

    override fun clearFinishedJobs() = queue.clearFinished()

    /** Fermeture de la fenêtre : vrai si l'on peut quitter tout de suite ; sinon une confirmation est demandée. */
    fun requestQuit(): Boolean {
        val active = queue.activeCount
        if (active == 0) return true
        _state.update { it.copy(quitConfirm = active) }
        return false
    }

    override fun confirmQuit() = _state.update { it.copy(quitConfirm = null, exitRequested = true) }

    override fun cancelQuit() = _state.update { it.copy(quitConfirm = null) }
    private var thumbnailJob: Job? = null
    private var saveJob: Job? = null
    private var watchJob: Job? = null
    private var watchSettings: WatchPrefs? = null
    private var watcher: FolderWatcher? = null
    /** Prochaine relecture des soirées (null : à la prochaine analyse en fond) ; voir [checkEvenings]. */
    @Volatile
    private var eveningCheckAt: Instant? = null
    private var updateJob: Job? = null
    private var planJob: Job? = null

    init {
        reloadConfig()
        checkForUpdate()
        reportEnv.crashLog?.let { log ->
            scope.launch {
                val crash = withContext(Dispatchers.IO) { log.pending() }
                if (crash != null) _state.update { it.copy(crash = crash) }
            }
        }
    }

    // ---------------------------------------------------------------- configuration

    override fun reloadConfig() {
        _state.update { it.copy(config = ConfigStatus.Loading) }
        scope.launch {
            try {
                val b = withContext(Dispatchers.IO) { backendFactory() }
                backend = b
                val p = b.pipeline
                _state.update { s ->
                    s.copy(
                        config = ConfigStatus.Ready(
                            b.configFile, p.profilesDir, p.profiles.all().map { ProfileInfo(it.id, it.displayName) },
                            p.platforms.map { (id, platform) -> PlatformInfo.of(id, platform) },
                            // Fichier illisible : pas de préréglages, l'erreur est dans le journal.
                            runCatching { p.exportPresets.presets }.onFailure { log.warn(it) { "Préréglages d'export illisibles" } }.getOrDefault(emptyList()),
                        ),
                        settings = s.settings.copy(outputDir = s.settings.outputDir ?: p.defaultOutputDir),
                        sources = s.sources.map { src -> src.copy(detectedProfileId = p.resolveProfile(src.path).id) },
                    )
                }
                refreshLibrary()
                intro.load()
                if (watchJob == null) watchPrefs.load()?.let { startWatching(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Chargement de la configuration impossible" }
                _state.update { it.copy(config = ConfigStatus.Failed(e.localizedMessage ?: e.toString())) }
            }
        }
    }

    override fun openProfilesFolder() {
        (state.value.config as? ConfigStatus.Ready)?.let { platform.reveal(it.profilesDir) }
    }

    override fun editProfile() {
        val p = backend?.pipeline ?: return
        val id = currentProfileId() ?: return
        p.profiles.fileOf(id)?.let(platform::edit)
    }

    // ---------------------------------------------------------------- source

    override fun chooseSource() {
        platform.chooseVideos(state.value.source?.path?.parent).takeIf { it.isNotEmpty() }?.let(::selectSources)
    }

    override fun addSources() {
        val added = platform.chooseVideos(state.value.sources.lastOrNull()?.path?.parent)
        if (added.isNotEmpty()) selectSources(state.value.sources.map { it.path } + added)
    }

    override fun removeSource(path: Path) {
        if (state.value.job != null) return
        val remaining = state.value.sources.filterNot { it.path == path }
        thumbnailJob?.cancel()
        // L'analyse portait sur l'ensemble (cible partagée) : elle est à refaire.
        _state.update { s -> s.copy(sources = remaining, session = null, lastExport = null) }
    }

    override fun chooseSession() {
        val dir = state.value.settings.outputDir?.resolve("sessions")
        platform.chooseSessions(dir).takeIf { it.isNotEmpty() }?.let(::openSessions)
    }

    override fun dropFiles(paths: List<Path>) {
        if (paths.isEmpty()) return
        val (sessions, videos) = paths.partition { it.name.lowercase().endsWith(".json") }
        if (videos.isNotEmpty()) selectSources(videos) else openSessions(sessions)
    }

    /** Remplace les captures choisies ; elles sont rangées dans l'ordre où les parties ont été jouées. */
    private fun selectSources(paths: List<Path>) {
        if (state.value.job != null) return
        val unsupported = paths.filter { it.extension.lowercase() !in HighlightPipeline.SUPPORTED_EXTENSIONS }
        if (unsupported.isNotEmpty()) {
            showError(
                tr("ui.error.unsupportedTitle"),
                tr("ui.error.unsupported", "files" to unsupported.joinToString { it.name }, "expected" to HighlightPipeline.SUPPORTED_EXTENSIONS.sorted().joinToString()),
            )
            return
        }
        val p = backend?.pipeline ?: return showError(tr("ui.error.noConfigTitle"), tr("ui.error.noConfig"))
        val files = paths.map { it.toAbsolutePath().normalize() }.distinct()
        runTask(if (files.size == 1) tr("ui.job.readFile") else tr("ui.job.readFiles", "count" to files.size), cancellable = false) {
            val sources = files.map { path -> SourceInfo(path, p.probe(path), p.resolveProfile(path).id, p.library.contains(path)) }
                .sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { it.media })
            val profile = p.resolveProfile(sources.first().path)
            thumbnailJob?.cancel()
            _state.update { s ->
                s.copy(
                    sources = sources,
                    session = null,
                    lastExport = null,
                    settings = s.settings.withProfileDefaults(profile).copy(profileId = null).choosePreset(s.settings.preset, s.presets),
                )
            }
        }
    }

    /** Rouvre une ou plusieurs analyses enregistrées : plusieurs = un seul montage. */
    /** [then] : après l'ouverture réussie (montage d'une recherche). */
    private fun openSessions(files: List<Path>, then: (() -> Unit)? = null) {
        if (state.value.job != null || files.isEmpty()) return
        val p = backend?.pipeline ?: return showError(tr("ui.error.noConfigTitle"), tr("ui.error.noConfig"))
        runTask(if (files.size == 1) tr("ui.job.openSession") else tr("ui.job.openSessions", "count" to files.size), cancellable = false) {
            val entries = withContext(Dispatchers.IO) { files.distinct().map { SessionEntry(SessionStore.load(it), it) } }
                .sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { it.session.media })
            val profile = p.profiles.byId(entries.first().session.profileId)
            val opened = SessionState(entries)
            _state.update { s ->
                s.copy(
                    sources = entries.map { SourceInfo(it.session.media.path, it.session.media, p.resolveProfile(it.session.media.path).id) },
                    session = opened.copy(selectedId = opened.segments.firstOrNull()?.key),
                    lastExport = null,
                    settings = s.settings.withProfileDefaults(profile).copy(profileId = profile.id).choosePreset(s.settings.preset, s.presets),
                )
            }
            val openedFiles = files.map { it.toAbsolutePath().normalize() }.toSet()
            _state.update { s -> s.copy(watch = s.watch.copy(fresh = s.watch.fresh - openedFiles), librarySelection = emptySet()) }
            val missing = entries.map { it.session.media.path }.filterNot { it.exists() }
            if (missing.isNotEmpty()) {
                showError(tr("ui.error.sourceMissingTitle"), tr("ui.error.sourceMissing", "files" to missing.joinToString()))
            }
            loadThumbnails()
            then?.invoke()
        }
    }

    // ---------------------------------------------------------------- réglages

    override fun setProfile(id: String?) {
        val p = backend?.pipeline ?: return
        val source = state.value.source
        val profile = when {
            id != null -> p.profiles.byId(id)
            source != null -> p.resolveProfile(source.path)
            else -> return _state.update { it.copy(settings = it.settings.copy(profileId = null)) }
        }
        _state.update { s ->
            s.copy(
                settings = s.settings.withProfileDefaults(profile).copy(profileId = id).choosePreset(s.settings.preset, s.presets),
                session = s.session?.let { it.copy(profileChanged = it.sessions.any { session -> session.profileId != profile.id }) },
            )
        }
    }

    override fun setStyle(style: EditStyle) = _state.update { s -> s.copy(settings = s.settings.copy(style = style).presetTrackedFrom(s.settings, s.presets)) }

    override fun setPlatform(id: String?) = _state.update { s -> s.copy(settings = s.settings.copy(platform = id).presetTrackedFrom(s.settings, s.presets)) }

    override fun toggleFormat(format: OutputFormat) = _state.update { s ->
        val formats = if (format in s.settings.formats) s.settings.formats - format else s.settings.formats + format
        s.copy(settings = s.settings.copy(formats = formats).presetTrackedFrom(s.settings, s.presets))
    }

    override fun updateExportSettings(change: (SettingsState) -> SettingsState) {
        val before = state.value.settings
        val next = change(before).presetTrackedFrom(before, state.value.presets)
        // Durée visée changée (préréglage) : les moments sont resélectionnés comme quand on tape la durée.
        if (next.target != before.target) updateSelection { next } else _state.update { it.copy(settings = next) }
    }

    override fun setMomentMode(mode: MomentMode) = updateSelection { it.copy(momentMode = mode) }
    override fun setTargetMode(mode: TargetMode) = updateSelection { it.copy(targetMode = mode) }
    override fun setDurationText(text: String) = updateSelection { it.copy(durationText = text) }
    override fun setTopNText(text: String) = updateSelection { it.copy(topNText = text.filter(Char::isDigit).take(3)) }
    override fun setThreshold(value: Double) = updateSelection { it.copy(threshold = (value * 100).toInt() / 100.0) }

    override fun chooseOutputDir() {
        platform.chooseDirectory(state.value.settings.outputDir)?.let { dir ->
            _state.update { it.copy(settings = it.settings.copy(outputDir = dir)) }
        }
    }

    override fun openOutputDir() {
        state.value.settings.outputDir?.let { dir -> if (dir.exists()) platform.reveal(dir) }
    }

    /** Seuil et cible s'appliquent instantanément à une analyse existante (recalcul sans réanalyser). */
    private fun updateSelection(change: (SettingsState) -> SettingsState) {
        _state.update { it.copy(settings = change(it.settings).presetTrackedFrom(it.settings, it.presets)) }
        val s = state.value
        val current = s.session ?: return
        val target = s.settings.target ?: return
        val p = backend?.pipeline ?: return
        val reselected = try {
            p.reselectAll(current.sessions, s.settings.threshold, target, s.settings.momentMode.requiredEvent)
        } catch (e: HighlightsException) {
            return showError(tr("ui.error.reselect"), e.localizedMessage ?: "")
        }
        _state.update { st ->
            val sel = st.session ?: return@update st
            val updated = sel.copy(entries = sel.entries.zip(reselected) { e, session -> e.copy(session = session) })
            st.copy(session = updated.copy(selectedId = sel.selectedId?.takeIf { key -> updated.segments.any { it.key == key } }))
        }
        scheduleSave()
        loadThumbnails()
    }

    // ---------------------------------------------------------------- traitements

    override fun analyze() {
        val s = state.value
        val sources = s.sources.takeIf { it.isNotEmpty() } ?: return
        val p = backend?.pipeline ?: return
        val target = s.settings.target ?: return showError(tr("ui.error.invalidSettingTitle"), tr("ui.error.invalidSetting"))
        val title = if (sources.size == 1) tr("ui.job.analyze", "name" to sources.single().path.name) else tr("ui.job.analyzeMany", "count" to sources.size)
        withMemory(title, MEMORY_ANALYSIS) {
            enqueue(title, blocking = true) { progress ->
                val outcomes = p.analyzeAll(
                    sources.map { it.path },
                    AnalyzeOptions(
                        s.settings.profileId,
                        s.settings.threshold,
                        target,
                        requiredEvent = s.settings.momentMode.requiredEvent,
                        outputDir = s.settings.outputDir,
                        // « Réanalyser » une analyse ouverte recalcule tout ; sinon une capture déjà analysée est reprise.
                        reuse = s.session == null,
                    ),
                    progress,
                )
                refreshLibrary()
                val analyzed = SessionState(outcomes.map { SessionEntry(it.session, it.sessionFile) })
                _state.update { st ->
                    st.copy(
                        session = analyzed.copy(selectedId = analyzed.segments.firstOrNull()?.key),
                        lastExport = null,
                    )
                }
                if (analyzed.highlights.isEmpty()) {
                    val hint = tr(if (s.settings.momentMode == MomentMode.KILLS) "ui.error.noKillsHint" else "ui.error.lowerThresholdHint")
                    val warnings = outcomes.flatMap { o ->
                        o.session.shownWarnings.map { w -> if (outcomes.size > 1) tr("ui.error.inFile", "file" to o.session.media.path.name, "detail" to w) else w.text() }
                    }
                    showError(tr("ui.error.noMomentTitle"), (warnings + hint).joinToString("\n"))
                }
                loadThumbnails()
                null
            }
        }
    }

    override fun export() {
        val s = state.value
        val current = s.session ?: return
        val p = backend?.pipeline ?: return
        // Le titre nomme la partie : plusieurs exports peuvent attendre l'un derrière l'autre.
        val title = tr("ui.job.exportOf", "name" to (current.entries.firstOrNull()?.session?.media?.path?.name ?: ""))
        withMemory(title, MEMORY_EXPORT) {
            enqueue(title) { progress ->
                saveNow(current)
                val (introOn, introCards) = intro.exportChoice()
                val result = p.export(
                    current.sessions,
                    ExportOptions(
                        s.settings.orderedFormats, s.settings.outputDir, s.settings.style, platform = s.settings.platform,
                        thumbnail = ThumbnailStyle.DEFAULT.takeIf { s.settings.thumbnail }, intro = introOn, introSettings = introCards,
                        webcam = s.settings.webcam.choice, webcamOffset = s.settings.webcam.offset,
                    ),
                    progress,
                )
                _state.update { it.copy(lastExport = result, lastUpload = null) }
                result.videos.values.firstOrNull()
            }
        }
    }

    override fun clipMoment(key: String) {
        val s = state.value
        val current = s.session ?: return
        val segment = current.segments.firstOrNull { it.key == key } ?: return
        val p = backend?.pipeline ?: return
        // Un rendu FFmpeg comme un export : dans la file (un seul rendu lourd à la fois, l'export reste disponible).
        // Le titre nomme le moment et la partie : plusieurs clips peuvent attendre l'un derrière l'autre.
        val title = tr("ui.job.clipOf", "moment" to segment.highlight.id, "name" to current.sessions[segment.entry].media.path.name)
        withMemory(title, MEMORY_EXPORT) {
            enqueue(title) { progress ->
                saveNow(current)
                val options = dev.highlights.pipeline.ClipOptions(segment.highlight.id, s.settings.platform, s.settings.outputDir)
                val result = p.clip(current.sessions[segment.entry], options, progress)
                _state.update { it.copy(lastExport = result, lastUpload = null) }
                result.videos.values.firstOrNull()
            }
        }
    }

    override fun cancelJob() {
        val queued = state.value.job?.queueId
        if (queued != null) queue.cancel(queued) else mainJob?.cancel(CancellationException("Annulé par l'utilisateur"))
    }

    /** Annule les traitements en cours avant de quitter (tue les process FFmpeg). */
    fun shutdown() {
        player.dispose()
        queue.cancelAll()
        val jobs = listOfNotNull(mainJob, thumbnailJob, saveJob, watchJob)
        jobs.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(3.seconds) { jobs.forEach { it.join() } } }
        state.value.session?.entries?.forEach { runCatching { SessionStore.save(it.session, it.file) } }
    }

    // ---------------------------------------------------------------- mises à jour

    /** Au démarrage, en fond : une release plus récente que la version installée est proposée. Silencieux hors ligne. */
    private fun checkForUpdate() {
        val current = updater.currentVersion ?: return
        scope.launch {
            val release = runCatching { withContext(Dispatchers.IO) { updater.latest() } }
                .onFailure { log.warn { "Recherche de mise à jour impossible : ${it.message}" } }
                .getOrNull() ?: return@launch
            if (compareVersions(release.version, current) <= 0) return@launch
            log.info { "Version ${release.version} disponible (installée : $current)" }
            _state.update { it.copy(update = UpdateState.Available(release)) }
        }
    }

    override fun installUpdate() {
        val offer = state.value.update ?: return
        // Pas pendant une analyse ou un export : fermer l'application les interromprait.
        if (offer is UpdateState.Downloading || offer is UpdateState.Installing || state.value.job != null) return
        val release = offer.release
        _state.update { it.copy(update = UpdateState.Downloading(release)) }
        val lastUpdate = AtomicLong(0)
        updateJob = scope.launch {
            try {
                // Interruptible : « Annuler » arrête le téléchargement en cours.
                val msi = runInterruptible(Dispatchers.IO) {
                    updater.download(release) { fraction ->
                        val now = System.currentTimeMillis()
                        if (fraction >= 1.0 || now - lastUpdate.getAndUpdate { prev -> if (now - prev >= 100) now else prev } >= 100) {
                            _state.update { s -> (s.update as? UpdateState.Downloading)?.let { s.copy(update = it.copy(fraction = fraction)) } ?: s }
                        }
                    }
                }
                withContext(Dispatchers.IO) { updater.installAfterExit(msi) }
                _state.update { it.copy(update = UpdateState.Installing(release), exitRequested = true) }
            } catch (e: CancellationException) {
                log.info { "Téléchargement de la version ${release.version} annulé" }
            } catch (e: Exception) {
                log.error(e) { "Mise à jour vers la version ${release.version} impossible" }
                _state.update { it.copy(update = UpdateState.Failed(release, e.localizedMessage ?: e.toString())) }
            }
        }
    }

    override fun dismissUpdate() {
        if (state.value.update is UpdateState.Installing) return
        updateJob?.cancel()
        _state.update { it.copy(update = null) }
    }

    // ---------------------------------------------------------------- segments

    override fun toggleSegment(id: String) = updateSegments { s -> if (s.key == id) s.highlight.copy(enabled = !s.highlight.enabled) else s.highlight }

    override fun setAllSegments(enabled: Boolean) = updateSegments { it.highlight.copy(enabled = enabled) }

    override fun selectSegment(id: String?) = _state.update { s -> s.copy(session = s.session?.copy(selectedId = id)) }

    override fun nudgeSegment(id: String, end: Boolean, delta: Duration) = retouch { current, policy ->
        MomentRetouch.nudge(current, id, end, delta, policy).let { (updated, key) -> updated.copy(selectedId = key) }
    }

    override fun addSegmentAt(entry: Int, at: Duration) = retouch { current, policy ->
        MomentRetouch.add(current, entry, at, policy)?.let { (updated, key) -> updated.copy(selectedId = key) } ?: current
    }

    override fun moveSegment(id: String, delta: Int) = retouch { current, _ -> MomentRetouch.move(current, id, delta) }

    /** Retouche à la main de la session ouverte, avec la sélection du profil (bornes de longueur), puis sauvegarde différée. */
    private fun retouch(change: (SessionState, dev.highlights.core.model.SelectionPolicy) -> SessionState) {
        val current = state.value.session ?: return
        val policy = runCatching { backend?.pipeline?.profiles?.byId(current.sessions.first().profileId)?.selection }.getOrNull()
            ?: dev.highlights.core.model.SelectionPolicy()
        val updated = change(current, policy)
        if (updated == current) return
        _state.update { it.copy(session = updated) }
        scheduleSave()
        loadThumbnails()
    }

    override fun previewClip(id: String) {
        val s = state.value
        val current = s.session ?: return
        val segment = current.segments.firstOrNull { it.key == id } ?: return
        val p = backend?.pipeline ?: return
        if (id in s.busyClips) return
        _state.update { it.copy(busyClips = it.busyClips + id) }
        scope.launch {
            try {
                val clip = withContext(Dispatchers.IO) { p.clipPreview(current.entries[segment.entry].session, segment.highlight) }
                playVideo(clip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(tr("ui.error.preview"), e)
            } finally {
                _state.update { it.copy(busyClips = it.busyClips - id) }
            }
        }
    }

    override fun previewVertical(id: String) {
        val s = state.value
        val current = s.session ?: return
        val segment = current.segments.firstOrNull { it.key == id } ?: return
        val highlight = segment.highlight
        val session = current.entries[segment.entry].session
        val p = backend?.pipeline ?: return
        if (s.busyVerticalPreview != null) return
        _state.update { it.copy(busyVerticalPreview = id) }
        scope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    // Relit les profils : les zones du HUD modifiées dans le YAML s'appliquent immédiatement.
                    p.reloadProfiles()
                    p.preview(session.media.path, highlight.peak, session.profileId, listOf(OutputFormat.VERTICAL), p.previewDir).single()
                }
                val title = if (current.multiple) {
                    tr("ui.preview.titleIn", "id" to highlight.id, "file" to session.media.path.name)
                } else {
                    tr("ui.preview.title", "id" to highlight.id)
                }
                _state.update { it.copy(imagePreview = ImagePreview(image, title, id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(tr("ui.error.verticalPreview"), e)
            } finally {
                _state.update { it.copy(busyVerticalPreview = null) }
            }
        }
    }

    override fun regenerateVerticalPreview() {
        state.value.imagePreview?.let { previewVertical(it.highlightId) }
    }

    override fun openMontage() {
        val s = state.value
        val p = backend?.pipeline ?: return
        val profileId = s.session?.sessions?.firstOrNull()?.profileId ?: return
        val montage = runCatching { p.profiles.byId(profileId).montage }.getOrNull()
        // Fichier de styles illisible : la fenêtre s'ouvre quand même, sans styles (l'erreur est dans le journal).
        val styles = runCatching { p.montageStyles }.onFailure { log.warn(it) { "Styles de montage illisibles" } }.getOrDefault(dev.highlights.core.model.MontageStyles.NONE)
        _state.update {
            it.copy(
                montage = MontageUiState(
                    music = it.lastMusic,
                    musicFromStart = it.lastMusic?.let(musicPrefs::fromStart) ?: false,
                    useLibrary = musicPrefs.useLibrary(),
                    musicLibrary = musicPrefs.library(),
                    maxDurationText = I18n.duration(montage?.maxDuration ?: 60.seconds),
                    fitKills = montage?.length?.fitKills ?: true,
                    formats = montage?.formats?.toSet() ?: setOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
                    rounds = montage?.killStyle?.deathEvent?.isNotEmpty() ?: true,
                    gameName = runCatching { p.profiles.byId(profileId).name }.getOrDefault(profileId),
                    skipAbilityKills = montage?.skipAbilityKills ?: false,
                    abilityKillsKnown = runCatching { p.profiles.byId(profileId).detectors.any { d -> d.enabled && d.type == "ammo-counter" } }.getOrDefault(false),
                    balance = montage?.audio?.balance ?: 0.0,
                    killCounter = montage?.text?.killCounter ?: dev.highlights.core.model.KillCounterMode.OFF,
                    roundScore = montage?.text?.roundScore ?: false,
                    roundScoreKnown = runCatching { p.profiles.byId(profileId).detectors.any { d -> d.enabled && d.type == "round-score" } }.getOrDefault(false),
                    platform = it.settings.platform,
                    styles = styles.styles,
                    profileKnobs = StyleKnobs.of(montage),
                ).chooseStyle(styles.default).choosePreset(it.settings.preset, it.presets),
            )
        }
        flow.loadKills()
        flow.loadSong()
    }

    override fun closeMontage() {
        planJob?.cancel()
        musicEditor.close()
        _state.update { it.copy(montage = null) }
    }

    override fun chooseMusic() {
        platform.chooseAudio(state.value.montage?.music?.parent ?: state.value.lastMusic?.parent)?.let { music ->
            _state.update { it.copy(montage = it.montage?.copy(music = music, useLibrary = false, musicFromStart = musicPrefs.fromStart(music)), lastMusic = music) }
            // Parcours : l'éditeur s'ouvrira à l'étape 3 ; une autre musique fait repartir ses repères de zéro.
            flow.musicChanged()
        }
    }

    override fun chooseMusicLibrary() {
        val current = state.value.montage?.musicLibrary ?: musicPrefs.library()
        platform.chooseDirectory(current ?: state.value.lastMusic?.parent, tr("ui.dialog.musicFolder"))?.let { dir ->
            _state.update { it.copy(montage = it.montage?.copy(musicLibrary = dir, useLibrary = true)) }
            flow.musicChanged()
        }
    }

    override fun updateMontage(change: (MontageUiState) -> MontageUiState) =
        _state.update { s -> s.copy(montage = s.montage?.let { before -> change(before).trackedFrom(before).presetTrackedFrom(before, s.presets) }) }

    override fun setIntroEnabled(enabled: Boolean) = intro.setEnabled(enabled)
    override fun openIntro() = intro.open()
    override fun closeIntro() = intro.close()
    override fun updateIntro(change: (dev.highlights.core.model.IntroOutroSettings) -> dev.highlights.core.model.IntroOutroSettings) = intro.update(change)
    override fun chooseIntroLogo() = intro.chooseLogo()
    override fun chooseIntroImage() = intro.chooseImage()
    override fun setIntroPreview(format: OutputFormat, outro: Boolean) = intro.setPreview(format, outro)

    override fun createMontage() {
        val s = state.value
        val montage = s.montage ?: return
        val music = montage.musicSource ?: return
        if (!montage.useLibrary) musicPrefs.remember(music, montage.musicFromStart)
        musicPrefs.rememberLibrary(montage.musicLibrary, montage.useLibrary)
        val session = s.session ?: return
        val p = backend?.pipeline ?: return
        planJob?.cancel()
        _state.update { it.copy(montage = null) }
        val title = if (montage.useLibrary) tr("ui.job.montageFolder", "folder" to music.name) else tr("ui.job.montage", "track" to music.name)
        withMemory(title, MEMORY_MONTAGE) {
            enqueue(title) { progress ->
                saveNow(session)
                val result = p.killMontage(session.sessions, music, montageOptions(montage, s), progress)
                _state.update { it.copy(lastExport = result, lastUpload = null) }
                result.videos.values.firstOrNull()
            }
        }
    }

    /** Parties, musique et réglages des derniers aperçus : la variante choisie se rend avec exactement les mêmes. */
    private data class PendingVariants(val sessions: List<Session>, val music: Path, val options: MontageOptions, val file: Path)

    @Volatile
    private var pendingVariants: PendingVariants? = null

    override fun previewMontage() {
        val s = state.value
        val montage = s.montage ?: return
        val music = montage.musicSource ?: return
        if (!montage.useLibrary) musicPrefs.remember(music, montage.musicFromStart)
        musicPrefs.rememberLibrary(montage.musicLibrary, montage.useLibrary)
        val session = s.session ?: return
        val p = backend?.pipeline ?: return
        _state.update { it.copy(montage = null, variants = null) }
        val options = montageOptions(montage, s)
        val title = tr("ui.job.previews", "track" to music.name)
        withMemory(title, MEMORY_MONTAGE) {
            enqueue(title) { progress ->
                saveNow(session)
                // Chaque aperçu se montre dès qu'il est prêt ; fenêtre fermée entre-temps : elle ne rouvre qu'à la fin.
                val ready = mutableListOf<dev.highlights.pipeline.VariantPreview>()
                val previews = p.montagePreviews(session.sessions, music, options, MontageVariants.DEFAULT_COUNT, progress) { preview, total ->
                    ready += preview
                    _state.update { if (it.variants == null && ready.size > 1) it else it.copy(variants = VariantsUiState.partial(ready.toList(), total, MontageVariants.DEFAULT_COUNT)) }
                }
                pendingVariants = PendingVariants(session.sessions, music, options, previews.variantsFile)
                _state.update { it.copy(variants = VariantsUiState.of(previews, MontageVariants.DEFAULT_COUNT)) }
                null
            }
        }
    }

    override fun renderVariant(index: Int) {
        val pending = pendingVariants ?: return
        val p = backend?.pipeline ?: return
        _state.update { it.copy(variants = null) }
        val title = tr("ui.job.montage", "track" to pending.music.name)
        withMemory(title, MEMORY_MONTAGE) {
            enqueue(title) { progress ->
                val result = p.killMontage(pending.sessions, pending.music, pending.options.copy(pick = VariantPick(index, pending.file)), progress)
                _state.update { it.copy(lastExport = result, lastUpload = null) }
                result.videos.values.firstOrNull()
            }
        }
    }

    override fun closeVariants() = _state.update { it.copy(variants = null) }

    /** Réglages du dialogue pour le pipeline : les mêmes pour la frise et pour le rendu, sinon le plan montré mentirait. */
    private fun montageOptions(montage: MontageUiState, s: UiState) = MontageOptions(
        // Miniature (habillage par défaut) de chaque vidéo : prête dans la fenêtre de publication ; le préréglage peut l'ôter.
        thumbnail = montage.thumbnailStyle.takeIf { montage.thumbnail },
        webcam = s.settings.webcam.choice,
        webcamOffset = s.settings.webcam.offset,
        formats = OutputFormat.entries.filter { it in montage.formats },
        outputDir = s.settings.outputDir,
        maxDuration = montage.maxDuration,
        fitKills = montage.fitKills,
        // Montée en puissance, whip pan, vitrine, ralenti, rythme rapide : plus dans l'application (les repères les
        // remplacent) ; le style les pose pour la proposition automatique.
        effectDensity = montage.density,
        zoom = montage.zoom,
        flash = montage.flash,
        whoosh = montage.whoosh,
        boostColors = montage.boostColors,
        motionBlur = montage.motionBlur,
        matchCut = montage.matchCut,
        rounds = montage.rounds,
        text = montage.text,
        killCounter = montage.killCounter,
        roundScore = montage.roundScore,
        audioBalance = montage.balance,
        gameAudio = montage.gameAudio,
        reactions = montage.reactions,
        // Bibliothèque : chaque musique garde son réglage « depuis le début ».
        musicFromStart = if (montage.useLibrary) null else montage.musicFromStart,
        fromStartMusics = if (montage.useLibrary) musicPrefs.fromStartMusics() else emptySet(),
        platform = montage.platform,
        onlyKills = montage.pick,
        oneTaps = montage.oneTaps,
        reactionMontage = montage.reactionMontage,
        reactionSpacing = if (montage.reactionSpacing) null else false,
        skipAbilityKills = montage.skipAbilityKills,
        // Les cases de la fenêtre sont déjà passées ; le style garde ce qu'elle ne montre pas (accroche, rampes…).
        style = montage.style,
        constraints = montage.constraints,
        intro = intro.exportChoice().first,
        introSettings = intro.exportChoice().second,
    )

    // ---------------------------------------------------------------- frise du plan de montage

    /**
     * Toute correction replanifie aussitôt, comme l'épingle : l'utilisateur voit l'effet de son clic sans passer par
     * « Replanifier » (demandé le 20/11 ; le bouton reste pour relancer après une erreur).
     */
    override fun editMontageKill(kills: List<String>, edit: KillEdit) {
        updateMontage { it.copy(constraints = it.constraints.edit(kills.map(dev.highlights.montage.KillRef::parse), edit)) }
        replanIfOpen()
    }

    /** Replanifie si la frise est ouverte (une correction posée ailleurs n'a pas de plan à rafraîchir). */
    private fun replanIfOpen() {
        if (state.value.montage?.plan != null) replanMontage()
    }

    override fun pinMontageKill(kills: List<String>, musicSeconds: Double) {
        val at = (musicSeconds * 1_000_000).roundToLong().microseconds
        updateMontage { it.copy(constraints = it.constraints.pinAt(kills.map(dev.highlights.montage.KillRef::parse), at)) }
        replanMontage()
    }

    override fun resetMontagePlan() {
        updateMontage { it.copy(constraints = dev.highlights.montage.MontageConstraints.NONE) }
        replanIfOpen()
    }

    private val killTiming = KillTimingController(
        scope, _state, { backend?.pipeline }, ::updateMontage, ::replanMontage,
        players = { backend?.ffmpeg?.let { dev.highlights.ui.player.VideoPlayer(it, scope) } },
    )
    override fun stepKillMs(millis: Int) = killTiming.stepMs(millis)
    override fun seekKillTiming(seconds: Double) = killTiming.seek(seconds)
    override fun setKillTimingText(text: String) = killTiming.text(text)
    override fun playKillTiming(fromBefore: Boolean) = killTiming.play(fromBefore)
    override fun markKillHere() = killTiming.markHere()
    override fun markKillCut(start: Boolean) = killTiming.markCut(start)
    override fun markSectionStart() = killTiming.markSectionStart()
    override fun markSectionEnd(keep: Boolean) = killTiming.markSectionEnd(keep)
    override fun clearKillCut(kill: String, start: Boolean) {
        val ref = runCatching { dev.highlights.montage.KillRef.parse(kill) }.getOrNull() ?: return
        updateMontage { it.copy(constraints = it.constraints.clearCut(ref, start)) }
        replanIfOpen()
    }
    override fun removeMontageRange(index: Int) {
        updateMontage { it.copy(constraints = it.constraints.removeRange(index)) }
        replanIfOpen()
    }
    override fun resetLengths() {
        val p = backend?.pipeline ?: return
        scope.launch {
            val aside = withContext(Dispatchers.IO) { p.cutHistory.reset() }
            loadTaste(resetDone = aside?.fileName?.toString())
        }
    }
    override val killTimingPlayer get() = killTiming.player
    override fun openKillTiming(kill: String) = killTiming.open(kill)
    override fun stepKillFrame(delta: Int) = killTiming.step(delta)
    override fun pickKillFrame(index: Int) = killTiming.pick(index)
    override fun shiftKillBeat(frames: Int) = killTiming.shiftBeat(frames)
    override fun applyKillTiming() = killTiming.apply()
    override fun clearKillTiming() = killTiming.clear()
    override fun excludeTimedKill() = killTiming.exclude()
    override fun closeKillTiming() = killTiming.close()

    private val musicDrop = MusicDropController(scope, _state, { backend?.pipeline }, ::replanMontage, updateMontage = ::updateMontage)
    private val musicEditor = MusicEditorController(scope, _state, { backend?.pipeline }, ::updateMontage, ::replanMontage, recordLayout = ::recordLayout)

    /** Dernier plan calculé (celui de la proposition quand on valide l'étape 1). */
    @Volatile private var lastPlanned: dev.highlights.pipeline.PlannedMontage? = null

    private fun recordLayout(auto: dev.highlights.montage.MusicIntents, final: dev.highlights.montage.MusicIntents) {
        val planned = lastPlanned ?: return
        val p = backend?.pipeline ?: return
        scope.launch(Dispatchers.IO) { runCatching { p.recordIntents(planned, auto, final) } }
    }
    override fun resetLayoutTaste() {
        val p = backend?.pipeline ?: return
        scope.launch {
            val aside = withContext(Dispatchers.IO) { p.layoutHistory.reset() }
            loadTaste(resetDone = aside?.fileName?.toString())
        }
    }
    override fun openMusicEditor() = musicEditor.open()
    override fun closeMusicEditor() {
        planJob?.cancel()
        musicEditor.close()
    }
    override fun zoomEditor(factor: Double, center: Double) = musicEditor.zoom(factor, center)
    override fun scrollEditor(seconds: Double) = musicEditor.scroll(seconds)
    override fun centerEditor(seconds: Double) = musicEditor.center(seconds)
    override fun toggleEditorPlayback() = musicEditor.toggle()
    override fun seekEditor(seconds: Double) = musicEditor.seek(seconds)
    override fun listenEditorAround(seconds: Double) = musicEditor.listenAround((seconds * 1000).toLong().milliseconds)
    override fun toggleEditorMagnet() = musicEditor.toggleMagnet()
    override fun selectEditorKill(kill: String?) = musicEditor.select(kill)
    override fun dragEditor(kind: String, key: String?, seconds: Double, free: Boolean, over: Boolean) {
        val k = runCatching { EditorDragKind.valueOf(kind) }.getOrNull() ?: return
        musicEditor.drag(k, key, seconds, free, over)
    }
    override fun dropEditor() = musicEditor.drop()
    override fun removeEditorKill(kill: String) = musicEditor.remove(kill)
    override fun resetEditorAuto() = musicEditor.resetAuto()
    override fun placeEditorClips() = musicEditor.placeClips()
    override fun setEditorTool(tool: String) {
        runCatching { EditorTool.valueOf(tool) }.getOrNull()?.let(musicEditor::setTool)
    }
    override fun addEditorMark(seconds: Double, free: Boolean) = musicEditor.addMark(seconds, free)
    override fun setEditorExtras(mode: String) {
        runCatching { dev.highlights.montage.ExtraMarks.valueOf(mode) }.getOrNull()?.let(musicEditor::setExtras)
    }
    override fun backEditorToKills() = musicEditor.backToKills()
    override fun removeEditorClip(kill: String) = musicEditor.removeClip(kill)
    override fun addEditorShowcase(seconds: Double) = musicEditor.addShowcase(seconds)

    private val flow = MontageFlowController(
        scope, _state, { backend?.pipeline }, ::replanMontage,
        openEditor = { musicEditor.open() }, closeEditor = { musicEditor.close() },
        placeClips = { musicEditor.placeClips() }, backToKills = { musicEditor.backToKills() },
    )
    override fun goMontageStep(step: String) {
        runCatching { MontageStep.valueOf(step) }.getOrNull()?.let(flow::go)
    }
    override fun nextMontageStep() = flow.next()
    override fun previousMontageStep() = flow.back()
    override fun montageAllAuto() = flow.allAuto()
    override fun montageMusicChanged() = flow.musicChanged()

    /** Aperçu 360p du montage tel que l'éditeur le montre (une variante, la mieux notée), lu dans le lecteur intégré. */
    override fun previewEditedMontage() {
        val s = state.value
        val montage = s.montage ?: return
        val music = montage.musicSource ?: return
        val session = s.session ?: return
        val p = backend?.pipeline ?: return
        val options = montageOptions(montage, s)
        val title = tr("ui.job.previews", "track" to music.name)
        withMemory(title, MEMORY_MONTAGE) {
            enqueue(title) { progress ->
                val previews = p.montagePreviews(session.sessions, music, options, 1, progress) { _, _ -> }
                previews.previews.firstOrNull()?.video?.also { video -> scope.launch { playVideo(video) } }
            }
        }
    }

    override fun openKillOnMusic(kill: String) {
        killTiming.close()
        musicDrop.openForKill(kill)
    }
    override fun resetPins() {
        val p = backend?.pipeline ?: return
        scope.launch {
            val aside = withContext(Dispatchers.IO) { p.pinHistory.reset() }
            loadTaste(resetDone = aside?.fileName?.toString())
        }
    }
    override fun openMusicDrop() = musicDrop.open()
    override fun seekMusicDrop(seconds: Double) = musicDrop.seek(seconds)
    override fun stepMusicDrop(millis: Int) { musicDrop.step(millis) }
    override fun stepMusicDropBeat(beats: Int) = musicDrop.stepBeat(beats)
    override fun setMusicDropText(text: String) = musicDrop.text(text)
    override fun playMusicDrop(fromBefore: Boolean) = musicDrop.play(fromBefore)
    override fun markMusicDropHere() = musicDrop.markHere()
    override fun confirmMusicDrop() = musicDrop.confirm()
    override fun clearMusicDrop() = musicDrop.clear()
    override fun closeMusicDrop() = musicDrop.close()
    override fun resetDropLearning() {
        val p = backend?.pipeline ?: return
        scope.launch {
            val aside = withContext(Dispatchers.IO) { p.dropExamples.reset() }
            loadTaste(resetDone = aside?.fileName?.toString())
        }
    }
    override fun forgetMusicDrop(key: String) {
        val p = backend?.pipeline ?: return
        scope.launch {
            withContext(Dispatchers.IO) { p.forgetMusicDrop(key) }
            loadTaste()
        }
    }

    override fun replanMontage() {
        val s = state.value
        val montage = s.montage ?: return
        val music = montage.musicSource ?: return
        val session = s.session ?: return
        val p = backend?.pipeline ?: return
        val constraints = montage.constraints
        planJob?.cancel()
        updatePlan { it.copy(loading = true, fraction = 0.0, error = null) }
        planJob = scope.launch {
            try {
                val tracker = ProgressTracker { snap -> updatePlan { it.copy(fraction = snap.fraction) } }
                // Pas de rendu : la musique, l'inspection des kills (en cache après le premier plan) et la planification.
                val planned = withContext(Dispatchers.Default) { p.planKillMontage(session.sessions, music, montageOptions(montage, s), tracker.root) }
                // Gardé tant que l'étape 1 n'est pas validée : c'est la proposition qu'on enregistre avec le placement.
                if (state.value.montage?.constraints?.let { it.layout == null && it.intents == null } == true) lastPlanned = planned
                val view = PlanView.of(planned)
                updatePlan { it.copy(loading = false, view = view, planned = constraints, error = null) }
                // La drop du plan (posée à l'instant dans l'outil) suit dans le morceau du parcours.
                _state.update { s ->
                    s.copy(montage = s.montage?.let { m -> m.song?.takeIf { it.file == view.musicPath }?.let { song -> m.copy(song = song.copy(drop = view.dropAt?.let { view.musicStart + it } ?: song.drop)) } ?: m })
                }
                // Dossier de musiques : le morceau retenu par le plan devient celui de l'éditeur (étape 3).
                state.value.montage?.takeIf { it.useLibrary && it.step == MontageStep.ON_MUSIC }?.let { m ->
                    view.musicPath?.let { file ->
                        _state.update { s -> s.copy(montage = s.montage?.copy(music = file, useLibrary = false)) }
                        flow.loadSong()
                        musicEditor.open()
                        return@launch
                    }
                }
                musicEditor.planned(view)
                val known = state.value.montage?.plan?.thumbnails.orEmpty()
                for (g in (view.clips.mapNotNull { it.group } + view.left.take(PLAN_THUMBNAILS)).filter { it.key !in known }) {
                    val image = runCatching { withContext(Dispatchers.IO) { p.thumbnail(g.media, g.kills.last()) } }.getOrNull() ?: continue
                    updatePlan { it.copy(thumbnails = it.thumbnails + (g.key to image)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HighlightsException) {
                // Correction impossible (ou kill introuvable) : dite dans la frise, le dernier plan reste affiché.
                updatePlan { it.copy(loading = false, planned = constraints, error = e.localizedMessage) }
            } catch (e: Exception) {
                updatePlan { it.copy(loading = false) }
                reportFailure(tr("ui.montagePlan.failed"), e)
            }
        }
    }

    private fun updatePlan(change: (MontagePlanState) -> MontagePlanState) =
        _state.update { s -> s.copy(montage = s.montage?.let { m -> m.plan?.let { m.copy(plan = change(it)) } ?: m }) }

    // ---------------------------------------------------------------- publication YouTube

    private fun youtube() = backend?.pipeline?.youtube(platform::browse)

    override fun openPublish(video: Path) {
        val yt = youtube() ?: return
        _state.update {
            it.copy(publish = PublishUiState(video, api = yt.apiEnabled, configured = !yt.apiEnabled || yt.configured, connected = yt.connected, thumbnail = OutputNamer.thumbnailOf(video)))
        }
        scope.launch {
            try {
                val suggested = withContext(Dispatchers.IO) { yt.suggest(video) }
                _state.update { s -> s.copy(publish = s.publish?.takeIf { it.video == video }?.withSuggestion(suggested)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(publish = null) }
                reportFailure(tr("ui.error.publish"), e)
            }
        }
    }

    override fun closePublish() = _state.update { it.copy(publish = null) }

    override fun updatePublish(change: (PublishUiState) -> PublishUiState) =
        _state.update { s -> s.copy(publish = s.publish?.let(change)) }

    override fun resetPublishText() = updatePublish { p -> p.suggested?.let(p::withSuggestion) ?: p }

    override fun publishToYouTube() {
        val publish = state.value.publish ?: return
        if (!publish.canPublish) return
        val yt = youtube() ?: return
        val metadata = publish.metadata()
        _state.update { it.copy(publish = null, lastUpload = null) }
        runTask(tr("ui.job.upload", "name" to publish.video.name)) { progress ->
            if (!yt.connected) progress.update(0.0, tr("ui.job.youtubeLogin"))
            val uploaded = yt.upload(publish.video, metadata, progress)
            _state.update { it.copy(lastUpload = uploaded) }
        }
    }

    override fun youtubeLogout() {
        val yt = youtube() ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { yt.logout() } }
                .onFailure { log.warn { "Déconnexion YouTube : ${it.message}" } }
            _state.update { s -> s.copy(publish = s.publish?.copy(connected = false)) }
        }
    }

    override fun openYouTubeUpload() {
        val video = state.value.publish?.video ?: return
        platform.browse(YouTubePublisher.UPLOAD_PAGE)
        platform.reveal(video)
    }

    override fun copyToClipboard(text: String) = platform.copy(text)

    override fun rateMusicFeel(vote: dev.highlights.montage.FeelVote) {
        val result = state.value.lastExport ?: return
        val music = result.music ?: return
        val p = backend?.pipeline ?: return
        val hadFast = (result.fastShots ?: 0) > 0
        // Réponse affichée tout de suite ; les mesures de la musique (pour l'apprentissage) se lisent ensuite.
        _state.update { s -> s.copy(musicFeels = s.musicFeels + (result.report to vote.feel(hadFast))) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { p.rateMusic(music, vote, hadFast) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.copy(musicFeels = s.musicFeels - result.report) }
                reportFailure(tr("ui.error.musicFeel"), e)
            }
        }
    }

    override fun makeThumbnail() {
        val video = state.value.publish?.video ?: return
        val p = backend?.pipeline ?: return
        updatePublish { it.copy(thumbnailBusy = true) }
        scope.launch {
            try {
                val made = withContext(Dispatchers.IO) { p.miniature(video, ThumbnailStyle.DEFAULT) }
                _state.update { s -> s.copy(publish = s.publish?.takeIf { it.video == video }?.copy(thumbnail = made, thumbnailBusy = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePublish { it.copy(thumbnailBusy = false) }
                reportFailure(tr("ui.error.thumbnail"), e)
            }
        }
    }

    override fun editConfig() {
        backend?.configFile?.let(platform::edit)
    }

    override fun browse(uri: java.net.URI) = platform.browse(uri)

    // ---------------------------------------------------------------- analyses enregistrées

    override fun showLibrary() {
        if (state.value.job != null) return
        state.value.session?.let { current -> scope.launch { saveNow(current) } }
        thumbnailJob?.cancel()
        _state.update { it.copy(sources = emptyList(), session = null, lastExport = null) }
        refreshLibrary()
    }

    override fun openLibraryItem(sessionFile: Path) {
        _state.update { it.copy(stats = null, search = null, taste = null) }
        openSessions(listOf(sessionFile))
    }

    override fun toggleLibraryItem(sessionFile: Path) = _state.update { s ->
        s.copy(librarySelection = if (sessionFile in s.librarySelection) s.librarySelection - setOf(sessionFile) else s.librarySelection + setOf(sessionFile))
    }

    override fun openLibrarySelection() {
        val s = state.value
        openSessions(s.library.map { it.sessionFile }.filter { it in s.librarySelection })
    }

    override fun askForget(sessionFile: Path) = _state.update { s -> s.copy(forget = s.library.firstOrNull { it.sessionFile == sessionFile }) }

    override fun cancelForget() = _state.update { it.copy(forget = null) }

    override fun confirmForget() {
        val item = state.value.forget ?: return
        val p = backend?.pipeline ?: return
        _state.update { it.copy(forget = null) }
        scope.launch(Dispatchers.IO) {
            runCatching { p.library.forget(item.sessionFile) }
                .onFailure { log.warn(it) { "Analyse ${item.sessionFile} non oubliée" } }
            refreshLibrary()
        }
    }

    private fun refreshLibrary() {
        val p = backend?.pipeline ?: return
        scope.launch(Dispatchers.IO) {
            val items = p.library.entries().map { e ->
                LibraryItem(e.source, e.sessionFile, e.profileId, e.analyzedAt, e.recordedAt, e.duration, e.events, e.source.exists())
            }
            _state.update { s ->
                s.copy(library = items, librarySelection = s.librarySelection.filterTo(mutableSetOf()) { f -> items.any { it.sessionFile == f } })
            }
        }
    }

    // ---------------------------------------------------------------- recherche

    override fun showSearch() {
        val p = backend?.pipeline ?: return
        _state.update { it.copy(search = SearchState(), stats = null, taste = null) }
        scope.launch {
            try {
                val moments = withContext(Dispatchers.IO) { p.search(dev.highlights.pipeline.MomentQuery()) }
                _state.update { s -> s.copy(search = s.search?.copy(loading = false, moments = moments)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(search = null) }
                reportFailure(tr("ui.library.search"), e)
            }
        }
    }

    override fun closeSearch() = _state.update { it.copy(search = null) }

    override fun updateSearch(change: (SearchState) -> SearchState) = _state.update { s -> s.copy(search = s.search?.let(change)) }

    override fun toggleMoment(key: String) = updateSearch { s ->
        s.copy(unpicked = if (key in s.unpicked) s.unpicked - key else s.unpicked + key)
    }

    /** Ouvre les parties des moments cochés, puis le montage kills limité à ces moments. */
    override fun montageFromSearch() {
        val search = state.value.search ?: return
        val picked = search.picked.takeIf { it.isNotEmpty() } ?: return
        val pick = dev.highlights.pipeline.MomentPick.of(picked)
        _state.update { it.copy(search = null) }
        openSessions(picked.map { it.sessionFile }.distinct()) {
            openMontage()
            // Des one taps cherchés : le montage qui va avec, que l'on peut encore décocher.
            _state.update { s -> s.copy(montage = s.montage?.let { m -> m.copy(pick = pick, oneTaps = search.oneTaps || m.oneTaps, touched = if (search.oneTaps) m.touched + "oneTaps" else m.touched) }) }
        }
    }

    // ---------------------------------------------------------------- statistiques

    override fun showStats() {
        val p = backend?.pipeline ?: return
        _state.update { it.copy(stats = StatsState(), search = null, taste = null) }
        scope.launch {
            try {
                val games = withContext(Dispatchers.IO) { p.statistics() }
                _state.update { s -> s.copy(stats = s.stats?.copy(loading = false, games = games)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(stats = null) }
                reportFailure(tr("ui.library.stats"), e)
            }
        }
    }

    override fun closeStats() = _state.update { it.copy(stats = null) }

    // ---------------------------------------------------------------- goûts appris

    override fun showTaste() {
        _state.update { it.copy(taste = TasteUiState(), stats = null, search = null) }
        loadTaste()
    }

    private fun loadTaste(resetDone: String? = null) {
        val p = backend?.pipeline ?: return
        scope.launch {
            try {
                val taste = withContext(Dispatchers.IO) { p.taste() }
                _state.update { s -> s.copy(taste = s.taste?.copy(state = taste, confirmReset = false, resetDone = resetDone)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(taste = null) }
                reportFailure(tr("ui.taste.title"), e)
            }
        }
    }

    override fun closeTaste() = _state.update { it.copy(taste = null) }

    override fun askResetTaste() = _state.update { s -> s.copy(taste = s.taste?.copy(confirmReset = true, resetDone = null)) }

    override fun cancelResetTaste() = _state.update { s -> s.copy(taste = s.taste?.copy(confirmReset = false)) }

    /** Comme `app gouts --reset` : l'historique est mis de côté, pas effacé. */
    override fun confirmResetTaste() {
        val p = backend?.pipeline ?: return
        scope.launch {
            val aside = withContext(Dispatchers.IO) { p.tasteHistory.reset() }
            loadTaste(resetDone = aside?.fileName?.toString())
        }
    }

    override fun resetTiming() {
        val p = backend?.pipeline ?: return
        scope.launch {
            val aside = withContext(Dispatchers.IO) { p.timingHistory.reset() }
            loadTaste(resetDone = aside?.fileName?.toString())
        }
    }

    // ---------------------------------------------------------------- nouveau jeu

    private val newGame by lazy {
        NewGameController(_state, scope, platform, { backend?.configFile }, { title, block -> runTask(title, block = block) }, ::reportFailure)
    }

    override fun showNewGame() = newGame.show()
    override fun closeNewGame() = newGame.close()
    override fun chooseNewGameVideo() = newGame.chooseVideo()
    override fun updateNewGame(game: String?, time: String?) = newGame.update(game, time)
    override fun showNewGameFrame() = newGame.showFrame()
    override fun setNewGameBox(x: Double, y: Double, width: Double, height: Double) = newGame.setBox(x, y, width, height)
    override fun addNewGameMark() = newGame.addMark()
    override fun removeNewGameMark(index: Int) = newGame.removeMark(index)
    override fun scanNewGame() = newGame.scan()
    override fun toggleNewGameVerdict(method: String, index: Int) = newGame.toggleVerdict(method, index)
    override fun createNewGameProfile() = newGame.create()

    override fun setStatsGame(game: String) = _state.update { s -> s.copy(stats = s.stats?.copy(game = game)) }

    override fun refreshOutdated() {
        val p = backend?.pipeline ?: return
        val game = state.value.stats?.game
        withMemory(tr("ui.job.refresh"), MEMORY_ANALYSIS) {
            enqueue(tr("ui.job.refresh"), blocking = true) { progress ->
                p.refreshOutdated(progress)
                refreshLibrary()
                showStats()
                _state.update { s -> s.copy(stats = s.stats?.copy(game = game)) }
                null
            }
        }
    }

    override fun recapEvening(date: String, profileId: String) {
        val p = backend?.pipeline ?: return
        val s = state.value
        val title = tr("ui.job.recap", "date" to I18n.date(java.time.LocalDate.parse(date), "format.date.dayMonth"))
        withMemory(title, MEMORY_EXPORT) {
            enqueue(title) { progress ->
                val evening = p.evenings().firstOrNull { it.date.toString() == date && it.profileId == profileId }
                    ?: throw dev.highlights.core.InputException(dev.highlights.core.i18n.Message("error.recap.noMoments"))
                val options = dev.highlights.pipeline.RecapOptions(OutputFormat.entries.filter { it in s.settings.formats }, s.settings.style, s.settings.platform, s.settings.outputDir)
                val result = p.eveningRecap(evening, options, progress)
                _state.update { it.copy(lastExport = result, lastUpload = null) }
                result.videos.values.firstOrNull()
            }
        }
    }

    // ---------------------------------------------------------------- dossier surveillé

    override fun chooseWatchFolder() {
        val initial = state.value.watch.folder ?: state.value.source?.path?.parent
        val folder = platform.chooseDirectory(initial, tr("ui.dialog.watchFolder")) ?: return
        // Les captures déjà présentes ne sont pas analysées d'office : seulement celles qui arrivent à partir de maintenant.
        val prefs = WatchPrefs(folder.toAbsolutePath().normalize(), Instant.now())
        watchPrefs.save(prefs)
        startWatching(prefs)
    }

    override fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        watchSettings = null
        watcher = null
        watchPrefs.save(null)
        _state.update { it.copy(watch = WatchState(fresh = it.watch.fresh)) }
    }

    // ---------------------------------------------------------------- montage automatique des soirées

    private fun updateEvenings(change: (EveningAuto) -> EveningAuto) {
        val prefs = watchSettings ?: return
        val next = prefs.copy(evenings = change(prefs.evenings))
        watchSettings = next
        watchPrefs.save(next)
        _state.update { it.copy(watch = it.watch.copy(evenings = next.evenings)) }
    }

    override fun setEveningMontage(enabled: Boolean) {
        updateEvenings { it.copy(enabled = enabled) }
        if (enabled) eveningCheckAt = clock()
        _state.update { it.copy(watch = it.watch.copy(eveningProblem = if (enabled && musicPrefs.library() == null) tr("ui.evening.noLibrary") else null)) }
    }

    override fun setEveningDelay(text: String) {
        _state.update { it.copy(watch = it.watch.copy(eveningDelayText = text)) }
        val delay = I18n.parseDuration(text)?.takeIf { it.isPositive() } ?: return
        updateEvenings { it.copy(delay = delay) }
        eveningCheckAt = clock()
    }

    override fun setEveningMinKills(count: Int) {
        updateEvenings { it.copy(minKills = count.coerceAtLeast(0)) }
        eveningCheckAt = clock()
    }

    override fun setEveningStyle(id: String?) = updateEvenings { it.copy(style = id) }

    override fun openLastEvening() {
        state.value.watch.lastEvening?.video?.let(platform::open)
    }

    /**
     * Monte les soirées finies, s'il y en a. La bibliothèque n'est relue qu'au moment prévu ([eveningCheckAt] : après
     * une analyse en fond, puis quand la prochaine soirée en attente sera finie), pas à chaque passage sur le dossier.
     * Rien tant qu'une capture s'écrit, qu'une analyse attend ou qu'un traitement lancé à la main tourne.
     */
    private suspend fun checkEvenings(p: HighlightPipeline) {
        val auto = watchSettings?.evenings ?: return
        if (!auto.enabled) return
        val at = eveningCheckAt ?: return
        val now = clock()
        if (now.isBefore(at)) return
        // Une capture s'écrit, attend son analyse ou est en cours d'analyse : la soirée n'est peut-être pas finie.
        if (watcher?.writing() == true || state.value.watch.pending.isNotEmpty() || state.value.watch.current != null) return
        val arrived = auto.arrived
        val eligible = { e: Evening -> e.sources.any { it.toAbsolutePath().normalize() in arrived } }
        val evenings = runCatching { p.evenings() }.onFailure { log.warn(it) { "Soirées illisibles" } }.getOrDefault(emptyList())
        val due = EveningPlanner.due(evenings, p.eveningHistory::done, now, auto.delay, auto.minKills, eligible, busy = false)
        eveningCheckAt = EveningPlanner.nextCheck(evenings, p.eveningHistory::done, now, auto.delay, auto.minKills, eligible)
        // Chaque soirée due passe par la file, comme un montage lancé à la main ; une seule fois.
        for (e in due) {
            val key = "${e.date}/${e.profileId}"
            if (!queuedEvenings.add(key)) continue
            val label = tr("ui.evening.label", "date" to I18n.date(e.date, "format.date.dayMonth"))
            val lastUpdate = AtomicLong(0)
            enqueue(tr("ui.jobs.evening", "evening" to label), auto = true, onProgress = { snap ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate.getAndUpdate { prev -> if (now - prev >= 250) now else prev } >= 250) {
                    _state.update { s -> s.copy(watch = s.watch.copy(montageFraction = snap.fraction)) }
                }
            }) { progress ->
                try {
                    montageEvening(p, e, progress)
                } finally {
                    queuedEvenings.remove(key)
                }
            }
        }
    }

    private suspend fun montageEvening(p: HighlightPipeline, evening: Evening, progress: ProgressReporter): Path? {
        val music = musicPrefs.library()
        if (music == null) {
            // Pas de dossier de musiques : rien à monter, l'utilisateur le voit dans la carte (pas de notification).
            _state.update { it.copy(watch = it.watch.copy(eveningProblem = tr("ui.evening.noLibrary"))) }
            eveningCheckAt = null
            return null
        }
        val label = tr("ui.evening.label", "date" to I18n.date(evening.date, "format.date.dayMonth"))
        _state.update { it.copy(watch = it.watch.copy(montaging = label, montageFraction = 0.0, eveningProblem = null)) }
        try {
            val s = state.value
            log.info { "Montage de la soirée du ${evening.date} (${evening.game}) : ${evening.sessionFiles.size} partie(s), ${evening.kills} kills" }
            val result = p.eveningMontage(
                evening,
                music,
                MontageOptions(
                    formats = OutputFormat.entries.filter { it in s.settings.formats },
                    platform = s.settings.platform,
                    outputDir = s.settings.outputDir,
                    fromStartMusics = musicPrefs.fromStartMusics(),
                    thumbnail = ThumbnailStyle.DEFAULT,
                    style = EveningPlanner.style(watchSettings?.evenings?.style, runCatching { p.montageStyles.default }.getOrNull()),
                ),
                progress,
            )
            val video = result.videos.values.firstOrNull()
            val text = tr("ui.evening.ready", "date" to I18n.date(evening.date, "format.date.dayMonth"), "count" to evening.kills)
            _state.update { it.copy(watch = it.watch.copy(lastEvening = EveningReady(text, video))) }
            platform.notify(tr("ui.evening.notifyTitle"), text) { video?.let(platform::open) }
            return video
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retenue comme faite (voir [HighlightPipeline.eveningMontage]) : pas de nouvel essai toutes les 5 s. Dite
            // dans la carte du dossier surveillé, et dans la file (échec lisible).
            log.error(e) { "Montage de la soirée du ${evening.date} impossible" }
            _state.update { s -> s.copy(watch = s.watch.copy(eveningProblem = tr("ui.evening.failed", "date" to label, "detail" to (e.localizedMessage ?: e.toString())))) }
            throw e
        } finally {
            _state.update { s -> s.copy(watch = s.watch.copy(montaging = null, montageFraction = 0.0)) }
        }
    }

    /**
     * Passe régulièrement sur le dossier et analyse en fond chaque nouvelle capture terminée, une à la fois et jamais
     * pendant un traitement lancé à la main. Seulement l'analyse : l'export se fait ensuite depuis la liste.
     */
    private fun startWatching(prefs: WatchPrefs) {
        watchJob?.cancel()
        val watcher = FolderWatcher(prefs.folder, prefs.since, watchSettle)
        this.watcher = watcher
        watchSettings = prefs
        // Au lancement, une soirée finie pendant que l'application était fermée se monte aussitôt.
        eveningCheckAt = clock()
        _state.update {
            it.copy(
                watch = WatchState(
                    folder = prefs.folder, fresh = it.watch.fresh, evenings = prefs.evenings, eveningDelayText = I18n.duration(prefs.evenings.delay),
                    eveningStyles = backend?.pipeline?.let { p -> runCatching { p.montageStyles }.getOrNull() } ?: dev.highlights.core.model.MontageStyles.NONE,
                ),
            )
        }
        log.info { "Surveillance de ${prefs.folder} (captures depuis ${prefs.since})" }
        watchJob = scope.launch(Dispatchers.IO) {
            while (true) {
                backend?.pipeline?.let { p ->
                    val found = watcher.poll { file -> p.library.contains(file) || file in state.value.watch.pending }
                    if (found.isNotEmpty()) {
                        log.info { "Nouvelle(s) capture(s) : ${found.joinToString { it.name }}" }
                        _state.update { it.copy(watch = it.watch.copy(pending = it.watch.pending + found)) }
                        // Chaque capture passe par la file : jamais en même temps qu'un export ou un montage.
                        for (file in found) {
                            val lastUpdate = AtomicLong(0)
                            val id = enqueue(tr("ui.jobs.watchAnalyze", "name" to file.name), auto = true, onProgress = { snap ->
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate.getAndUpdate { prev -> if (now - prev >= 250) now else prev } >= 250) {
                                    _state.update { s -> s.copy(watch = s.watch.copy(fraction = snap.fraction)) }
                                }
                            }) { progress -> analyzeInBackground(p, file, progress); null }
                            watchJobs[id] = file
                        }
                    }
                    checkEvenings(p)
                }
                delay(watchInterval)
            }
        }
    }

    private suspend fun analyzeInBackground(p: HighlightPipeline, file: Path, progress: ProgressReporter) {
        watchJobs.entries.removeIf { it.value == file }
        _state.update { it.copy(watch = it.watch.copy(current = file, fraction = 0.0, pending = it.watch.pending - setOf(file))) }
        try {
            val outcome = p.analyze(file, AnalyzeOptions(outputDir = state.value.settings.outputDir), progress)
            val sessionFile = outcome.sessionFile.toAbsolutePath().normalize()
            // Capture arrivée pendant que le montage des soirées est actif : sa soirée se montera une fois finie.
            if (watchSettings?.evenings?.enabled == true) {
                val key = file.toAbsolutePath().normalize()
                updateEvenings { it.copy(arrived = (it.arrived.plusElement(key)).toList().takeLast(EveningAuto.KEEP_ARRIVED).toSet()) }
                eveningCheckAt = clock()
            }
            _state.update { s -> s.copy(watch = s.watch.copy(fresh = s.watch.fresh + setOf(sessionFile), lastError = null)) }
            refreshLibrary()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // En fond, pas de boîte de dialogue : l'échec est signalé au-dessus de la liste, et la capture n'est pas
            // retentée tant qu'elle ne change pas.
            log.error(e) { "Analyse en fond de $file impossible" }
            _state.update { s -> s.copy(watch = s.watch.copy(lastError = tr("ui.error.inFile", "file" to file.name, "detail" to (e.localizedMessage ?: e.toString())))) }
            throw e
        } finally {
            _state.update { s -> s.copy(watch = s.watch.copy(current = null, fraction = 0.0)) }
        }
    }

    override fun setLanguage(tag: String?) {
        languagePrefs.save(tag)
        val locale = I18n.setLanguage(tag)
        _state.update { s ->
            // Ce qui a été traduit d'avance (titre du traitement, erreur affichée) garde sa langue jusqu'au suivant.
            s.copy(language = locale.toLanguageTag(), publish = s.publish?.copy(language = YouTubeText.language(dev.highlights.core.config.YouTubeSettings())))
        }
    }

    // ---------------------------------------------------------------- signaler un problème

    /** Fichiers du rapport en préparation, déjà expurgés ; écrits tels quels par [saveReport]. */
    @Volatile
    private var reportEntries: List<ProblemReport.Entry> = emptyList()
    private var reportJob: Job? = null
    @Volatile
    private var reportWindows: String = ""

    override fun openReport() = startReport(context = null, error = null)

    override fun reportError() {
        val error = state.value.error ?: return
        _state.update { it.copy(error = null) }
        startReport(context = error.title, error = error)
    }

    override fun reportCrash() {
        val crash = state.value.crash ?: return
        reportEnv.crashLog?.acknowledge()
        _state.update { it.copy(crash = null) }
        startReport(context = crash.summary.substringBefore('\n').take(120), error = null)
    }

    override fun dismissCrash() {
        reportEnv.crashLog?.acknowledge()
        _state.update { it.copy(crash = null) }
    }

    /**
     * Exception non gérée ailleurs que dans un traitement (Main) : déjà notée par [CrashLog.record], elle est proposée
     * au signalement tout de suite si l'interface tient encore.
     */
    fun onUncaughtException() {
        val crash = runCatching { reportEnv.crashLog?.pending() }.getOrNull() ?: return
        _state.update { if (it.report == null) it.copy(crash = crash) else it }
    }

    /** Rassemble le contenu en fond (diagnostic FFmpeg : quelques secondes), puis montre le résumé. */
    private fun startReport(context: String?, error: ErrorInfo?) {
        if (state.value.report != null) return
        _state.update { it.copy(report = ReportUiState(context = context?.let(reportEnv.redactor::redact))) }
        val s = state.value
        reportJob = scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { collectReport(s, error) }
                reportEntries = entries
                val items = entries.groupBy { it.part }.map { (part, list) -> ReportItem(part, list.map { it.name.substringAfterLast('/') }, list.sumOf { it.size }) }
                    .sortedBy { it.part.ordinal }
                _state.update { st -> st.copy(report = st.report?.copy(loading = false, items = items)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Préparation du rapport de problème impossible" }
                _state.update { st -> st.copy(report = st.report?.copy(loading = false, failed = e.localizedMessage ?: e.toString())) }
            }
        }
    }

    private suspend fun collectReport(s: UiState, error: ErrorInfo?): List<ProblemReport.Entry> {
        reportWindows = runCatching { reportEnv.windows() }.getOrDefault("")
        val p = backend?.pipeline
        val config = s.config
        val system = runCatching { reportEnv.system() }.getOrElse { listOf(it.toString()) } + SystemInfo.appState(
            configFile = (config as? ConfigStatus.Ready)?.configFile,
            configProblem = (config as? ConfigStatus.Failed)?.message,
            profiles = (config as? ConfigStatus.Ready)?.profiles.orEmpty().map { "${it.id} (${it.displayName})" },
            currentProfile = currentProfileId(),
            sources = s.sources.size,
            sessions = s.session?.entries?.size ?: 0,
            job = s.job?.let { "${it.title} (${I18n.percent(it.fraction)})" },
        )
        val diagnostics = SystemInfo.diagnostics(p)
        val texts = buildList {
            add(Triple("system.txt", ProblemReport.Part.SYSTEM, system.joinToString("\n")))
            add(Triple("diagnostics.txt", ProblemReport.Part.DIAGNOSTICS, diagnostics))
            error?.let { add(Triple("shown-error.txt", ProblemReport.Part.ERROR, (listOf(it.title, it.message) + it.details).joinToString("\n"))) }
        }
        return ProblemReport(reportEnv.redactor).collect(
            ProblemReport.Sources(
                texts = texts,
                logsDir = reportEnv.logsDir,
                crashDirs = reportEnv.crashDirs(),
                sessions = s.session?.entries?.map { it.file }.orEmpty(),
                reports = listOfNotNull(s.lastExport?.report),
            ),
        )
    }

    override fun closeReport() {
        reportJob?.cancel()
        reportEntries = emptyList()
        _state.update { it.copy(report = null) }
    }

    /**
     * Enregistre le ZIP où l'utilisateur le veut (Bureau proposé), puis ouvre le ticket prérempli dans le navigateur.
     * L'archive est déjà prête en mémoire : l'écrire est immédiat, d'où l'écriture directe (le navigateur s'ouvre
     * dans la même action, juste après).
     */
    override fun saveReport() {
        val report = state.value.report?.takeIf { !it.loading && it.failed == null } ?: return
        val name = "highlights-report-${REPORT_STAMP.format(java.time.LocalDateTime.now())}.zip"
        val chosen = platform.chooseSaveFile(report.saved?.parent ?: reportEnv.saveDir(), name, tr("ui.report.saveTitle")) ?: return
        val target = if (chosen.name.lowercase().endsWith(".zip")) chosen else chosen.resolveSibling("${chosen.name}.zip")
        try {
            ProblemReport(reportEnv.redactor).write(reportEntries, target)
        } catch (e: Exception) {
            log.error(e) { "Rapport de problème non enregistré" }
            return _state.update { it.copy(report = it.report?.copy(failed = e.localizedMessage ?: e.toString())) }
        }
        val url = issueUrl(report.context, target.name)
        _state.update { it.copy(report = it.report?.copy(saved = target, issueUrl = url)) }
        platform.browse(java.net.URI(url))
    }

    private fun issueUrl(context: String?, zipName: String): String {
        val total = reportEnv.totalMemory()
        val facts = listOfNotNull(
            "Highlights ${reportEnv.version}",
            reportWindows.ifEmpty { null },
            total.takeIf { it > 0 }?.let { tr("ui.report.issue.ram", "value" to I18n.decimal(it / (1L shl 30).toDouble(), 0)) },
            I18n.locale.toLanguageTag(),
        ).joinToString(" · ")
        val title = context?.let { tr("ui.report.issue.title", "summary" to it) } ?: tr("ui.report.issue.titleDefault")
        val body = listOf(
            facts, "",
            "### ${tr("ui.report.issue.happened")}", tr("ui.report.issue.happenedHint"), "", "",
            "### ${tr("ui.report.issue.expected")}", "", "",
            "### ${tr("ui.report.issue.steps")}", "1. ", "",
            "---", tr("ui.report.issue.attach", "file" to zipName),
        ).joinToString("\n")
        return ProblemReport.issueUrl(reportEnv.redactor.redact(title), reportEnv.redactor.redact(body))
    }

    override fun openIssuePage() {
        state.value.report?.issueUrl?.let { platform.browse(java.net.URI(it)) }
    }

    override fun revealReport() {
        state.value.report?.saved?.let(platform::reveal)
    }

    override fun openLogsFolder() {
        reportEnv.logsDir.takeIf { it.exists() }?.let(platform::reveal)
    }

    // ---------------------------------------------------------------- mémoire

    /** Traitement retenu par [withMemory] en attendant la décision de l'utilisateur. */
    private var memoryPending: (() -> Unit)? = null

    /**
     * Lance [start] si la machine a assez de mémoire disponible pour [task] ([needed] octets, mesuré sur de vraies
     * captures, voir [MEMORY_ANALYSIS]) ; sinon le retient et explique pourquoi, plutôt que de laisser FFmpeg ou la JVM
     * tomber au milieu du traitement.
     */
    private fun withMemory(task: String, needed: Long, start: () -> Unit) {
        val available = runCatching { reportEnv.memory() }.getOrNull()
        if (available == null || available >= needed) return start()
        log.warn { "$task retenu : ${available shr 20} Mo disponibles pour ${needed shr 20} Mo" }
        memoryPending = start
        _state.update { it.copy(memoryWarning = MemoryWarning(task, available, needed)) }
    }

    override fun retryMemoryCheck() {
        val warning = state.value.memoryWarning ?: return
        val start = memoryPending ?: return dismissMemoryWarning()
        dismissMemoryWarning()
        withMemory(warning.task, warning.needed, start)
    }

    override fun startDespiteMemory() {
        val start = memoryPending
        dismissMemoryWarning()
        start?.invoke()
    }

    override fun dismissMemoryWarning() {
        memoryPending = null
        _state.update { it.copy(memoryWarning = null) }
    }

    override fun open(path: Path) = platform.open(path)

    // ---------------------------------------------------------------- lecteur intégré

    override val videoPlayer get() = player.current
    override fun playVideo(path: Path) = player.open(backend?.ffmpeg, path)

    override fun scrubSource(entry: Int, at: Duration) {
        val file = state.value.session?.entries?.getOrNull(entry)?.session?.media?.path ?: return
        player.scrub(backend?.ffmpeg, file, entry, at)
    }

    override fun scrubSourceEnd(play: Boolean) {
        player.endScrub(play)
    }

    override fun playerToggle() {
        player.toggle()
    }

    override fun playerSeek(at: Duration) {
        player.seek(at)
    }

    override fun playerScrub(at: Duration) {
        player.scrub(at)
    }

    override fun playerScrubEnd() {
        player.endScrub()
    }

    override fun closePlayer() = player.close()
    override fun reveal(path: Path) = platform.reveal(path)
    override fun dismissError() = _state.update { it.copy(error = null) }
    override fun dismissImagePreview() = _state.update { it.copy(imagePreview = null) }

    override fun setWebcamMode(mode: WebcamMode) {
        // Fichier demandé sans fichier choisi : le sélecteur s'ouvre tout de suite.
        _state.update { s -> s.copy(settings = s.settings.copy(webcam = s.settings.webcam.copy(mode = mode))) }
        if (mode == WebcamMode.FILE && state.value.settings.webcam.file == null) chooseWebcamFile()
    }

    override fun chooseWebcamFile() {
        val current = state.value.settings.webcam.file
        val file = platform.chooseVideos(current?.parent ?: state.value.source?.path?.parent).firstOrNull() ?: return
        _state.update { s -> s.copy(settings = s.settings.copy(webcam = s.settings.webcam.copy(mode = WebcamMode.FILE, file = file))) }
    }

    override fun setWebcamOffset(text: String) =
        _state.update { s -> s.copy(settings = s.settings.copy(webcam = s.settings.webcam.copy(offsetText = text))) }

    override fun previewWebcam() {
        val s = state.value
        val source = s.source ?: return
        val choice = s.settings.webcam.choice ?: return
        val p = backend?.pipeline ?: return
        if (s.busyWebcamPreview) return
        // Au pic du premier moment retenu s'il y en a un, sinon au milieu de la capture.
        val at = s.session?.entries?.firstOrNull { it.session.media.path == source.path }?.session?.highlights
            ?.firstOrNull { it.enabled }?.peak ?: (source.media.duration / 2)
        _state.update { it.copy(busyWebcamPreview = true) }
        scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    p.reloadProfiles()
                    val feed = p.webcamFor(source.path, s.settings.profileId, choice, s.settings.webcam.offset)
                    val images = if (feed == null) emptyList() else p.preview(
                        source.path, at, s.settings.profileId, listOf(OutputFormat.VERTICAL, OutputFormat.LANDSCAPE), p.previewDir,
                        dev.highlights.core.model.WebcamChoice.Resolved(feed),
                    )
                    WebcamPreviewState(images, feed?.file, feed?.sync, feed?.offset)
                }
                _state.update { it.copy(webcamPreview = preview) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(tr("ui.error.webcamPreview"), e)
            } finally {
                _state.update { it.copy(busyWebcamPreview = false) }
            }
        }
    }

    override fun dismissWebcamPreview() = _state.update { it.copy(webcamPreview = null) }

    // ---------------------------------------------------------------- interne

    private fun currentProfileId(): String? {
        val s = state.value
        return s.session?.sessions?.firstOrNull()?.profileId ?: s.settings.profileId ?: s.source?.detectedProfileId
    }

    private fun updateSegments(change: (Segment) -> dev.highlights.core.model.Highlight) {
        _state.update { s ->
            val current = s.session ?: return@update s
            val entries = current.entries.mapIndexed { i, e ->
                e.copy(session = e.session.copy(highlights = e.session.highlights.map { h -> change(Segment(current.keyOf(i, h), i, h)) }))
            }
            s.copy(session = current.copy(entries = entries))
        }
        scheduleSave()
    }

    /** Sauvegarde différée : évite d'écrire la session à chaque cran du curseur. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600.milliseconds)
            state.value.session?.let { saveNow(it) }
        }
    }

    private suspend fun saveNow(session: SessionState) = withContext(Dispatchers.IO) {
        session.entries.forEach { e ->
            runCatching { SessionStore.save(e.session, e.file) }
                .onFailure { log.warn(it) { "Sauvegarde de la session ${e.file} impossible" } }
        }
    }

    /** Vignettes manquantes des segments affichés, toutes captures confondues. */
    private fun loadThumbnails() {
        val p = backend?.pipeline ?: return
        val current = state.value.session ?: return
        thumbnailJob?.cancel()
        thumbnailJob = scope.launch {
            for (segment in current.segments) {
                val h = segment.highlight
                val media = current.entries[segment.entry].session.media
                if (!media.path.exists()) continue
                val key = SessionState.thumbnailKey(segment.entry, h)
                if (state.value.session?.thumbnails?.containsKey(key) == true) continue
                val thumb = try {
                    withContext(Dispatchers.IO) { p.thumbnail(media, h.peak) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn { "Vignette impossible pour ${h.id} : ${e.message}" }
                    continue
                }
                _state.update { s -> s.copy(session = s.session?.let { it.copy(thumbnails = it.thumbnails + (key to thumb)) }) }
            }
        }
    }

    /** Lance un traitement long avec progression ; les erreurs deviennent une boîte de dialogue. */
    private fun runTask(title: String, cancellable: Boolean = true, block: suspend (ProgressReporter) -> Unit) {
        if (mainJob?.isActive == true) return
        _state.update { it.copy(job = JobState(title, cancellable = cancellable), error = null) }
        val lastUpdate = AtomicLong(0)
        val tracker = ProgressTracker { snap ->
            val now = System.currentTimeMillis()
            if (snap.fraction >= 1.0 || now - lastUpdate.getAndUpdate { prev -> if (now - prev >= 100) now else prev } >= 100) {
                _state.update { s ->
                    s.copy(job = s.job?.copy(fraction = snap.fraction, stage = snap.stage, elapsed = snap.elapsed, eta = snap.eta))
                }
            }
        }
        mainJob = scope.launch {
            try {
                withContext(Dispatchers.Default) { block(tracker.root) }
            } catch (e: CancellationException) {
                log.info { "$title annulé" }
            } catch (e: Exception) {
                reportFailure(tr("ui.error.taskFailed", "task" to title), e)
            } catch (e: OutOfMemoryError) {
                // Le tas Java est plein : le traitement s'arrête, l'application reste debout et le dit.
                log.error(e) { "$title : mémoire épuisée" }
                showError(
                    tr("ui.error.taskFailed", "task" to title), tr("ui.memory.outOfMemory"),
                    listOf(tr("ui.error.log", "file" to LOG_FILE)),
                )
            } finally {
                // Un traitement bloquant de la file garde la main sur l'écran.
                _state.update { it.copy(job = blockingJob(queue.snapshot)) }
            }
        }
    }

    private fun reportFailure(title: String, e: Exception) {
        when (e) {
            is FfmpegException -> {
                log.error { "$title : ${e.message}" }
                showError(
                    title, e.localizedMessage,
                    e.stderrTail.takeLast(20) + listOf("", tr("ui.error.step", "step" to e.description), tr("ui.error.command", "command" to e.commandLine), tr("ui.error.log", "file" to LOG_FILE)),
                )
            }
            is HighlightsException -> showError(title, e.localizedMessage)
            else -> {
                log.error(e) { title }
                showError(title, e.toString(), listOf(tr("ui.error.log", "file" to LOG_FILE)))
            }
        }
    }

    private fun showError(title: String, message: String, details: List<String> = emptyList()) {
        _state.update { it.copy(error = ErrorInfo(title, message, details)) }
    }

    companion object {
        val LOG_FILE: String = Path.of(System.getProperty("user.home"), ".highlights", "logs", "highlights.log").toString()

        private val REPORT_STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

        /**
         * Mémoire physique disponible demandée avant de lancer un traitement. FFmpeg tourne à côté de la JVM et compte
         * autant qu'elle : décodage de la capture pour l'analyse, une entrée par extrait (ou la pré-découpe) et
         * l'encodeur pour les rendus. Valeurs mesurées : voir README (configuration minimale).
         */
        const val MEMORY_ANALYSIS: Long = 1L shl 30
        const val MEMORY_EXPORT: Long = 3L shl 29
        const val MEMORY_MONTAGE: Long = 3L shl 29
    }
}

/** Formats, seuil et cible par défaut du profil (le dossier de sortie est conservé). */
internal fun SettingsState.withProfileDefaults(profile: GameProfile): SettingsState {
    val target = profile.selection.target
    return copy(
        formats = profile.edit.formats.toSet(),
        threshold = profile.selection.threshold,
        targetMode = when {
            target.all -> TargetMode.ALL
            target.topN != null -> TargetMode.TOP_N
            else -> TargetMode.DURATION
        },
        durationText = target.totalDuration?.let { I18n.duration(it) } ?: durationText,
        topNText = target.topN?.toString() ?: topNText,
    )
}
