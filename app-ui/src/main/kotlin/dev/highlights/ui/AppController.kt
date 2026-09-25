package dev.highlights.ui

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
import dev.highlights.pipeline.ExportOptions
import dev.highlights.pipeline.FolderWatcher
import dev.highlights.pipeline.HighlightPipeline
import dev.highlights.pipeline.MontageOptions
import dev.highlights.pipeline.Pipelines
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Pipeline prêt à l'emploi et fichier de config dont il provient. */
class Backend(val pipeline: HighlightPipeline, val configFile: Path)

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
    fun cancelJob()

    fun toggleSegment(id: String)
    fun setAllSegments(enabled: Boolean)
    fun selectSegment(id: String?)
    fun previewClip(id: String)
    fun previewVertical(id: String)
    fun regenerateVerticalPreview()

    fun openMontage()
    fun closeMontage()
    fun chooseMusic()
    fun updateMontage(change: (MontageUiState) -> MontageUiState)
    fun createMontage()

    /** Ferme l'analyse ouverte (elle reste enregistrée) et revient à la liste des analyses. */
    fun showLibrary()
    fun openLibraryItem(sessionFile: Path)
    fun toggleLibraryItem(sessionFile: Path)
    fun openLibrarySelection()
    fun chooseWatchFolder()
    fun stopWatching()

    fun open(path: Path)
    fun reveal(path: Path)
    fun dismissError()
    fun dismissImagePreview()
}

/**
 * Toute la logique de l'interface : les composables se contentent d'afficher [state] et d'appeler les actions.
 * Les traitements lourds tournent hors du thread UI ; un seul traitement long (analyse ou export) à la fois.
 */
class AppController(
    private val scope: CoroutineScope,
    private val platform: Platform,
    private val watchPrefs: WatchPrefsStore = WatchPrefsStore(WatchPrefsStore.DEFAULT_FILE),
    /** Intervalle entre deux passages sur le dossier surveillé, et temps sans écriture avant qu'une capture soit prise. */
    private val watchInterval: Duration = 5.seconds,
    private val watchSettle: Duration = 15.seconds,
    private val backendFactory: () -> Backend = {
        val file = ConfigLocator.locate()
        val config = ConfigYaml.load(file)
        Backend(Pipelines.create(config), config.source ?: file)
    },
) : UiActions {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile
    private var backend: Backend? = null
    private var mainJob: Job? = null
    private var thumbnailJob: Job? = null
    private var saveJob: Job? = null
    private var watchJob: Job? = null

    init {
        reloadConfig()
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
                        config = ConfigStatus.Ready(b.configFile, p.profilesDir, p.profiles.all().map { ProfileInfo(it.id, it.displayName) }),
                        settings = s.settings.copy(outputDir = s.settings.outputDir ?: p.defaultOutputDir),
                        sources = s.sources.map { src -> src.copy(detectedProfileId = p.resolveProfile(src.path).id) },
                    )
                }
                refreshLibrary()
                if (watchJob == null) watchPrefs.load()?.let { startWatching(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Chargement de la configuration impossible" }
                _state.update { it.copy(config = ConfigStatus.Failed(e.message ?: e.toString())) }
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
                "Fichier non supporté",
                "${unsupported.joinToString { it.name }} : pas une vidéo (${HighlightPipeline.SUPPORTED_EXTENSIONS.sorted().joinToString()}).",
            )
            return
        }
        val p = backend?.pipeline ?: return showError("Configuration non chargée", "Attends la fin du chargement ou corrige la configuration.")
        val files = paths.map { it.toAbsolutePath().normalize() }.distinct()
        runTask(if (files.size == 1) "Lecture du fichier" else "Lecture de ${files.size} fichiers", cancellable = false) {
            val sources = files.map { path -> SourceInfo(path, p.probe(path), p.resolveProfile(path).id, p.library.contains(path)) }
                .sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { it.media })
            val profile = p.resolveProfile(sources.first().path)
            thumbnailJob?.cancel()
            _state.update { s ->
                s.copy(
                    sources = sources,
                    session = null,
                    lastExport = null,
                    settings = s.settings.withProfileDefaults(profile).copy(profileId = null),
                )
            }
        }
    }

    /** Rouvre une ou plusieurs analyses enregistrées : plusieurs = un seul montage. */
    private fun openSessions(files: List<Path>) {
        if (state.value.job != null || files.isEmpty()) return
        val p = backend?.pipeline ?: return showError("Configuration non chargée", "Attends la fin du chargement ou corrige la configuration.")
        runTask(if (files.size == 1) "Ouverture de la session" else "Ouverture de ${files.size} sessions", cancellable = false) {
            val entries = withContext(Dispatchers.IO) { files.distinct().map { SessionEntry(SessionStore.load(it), it) } }
                .sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { it.session.media })
            val profile = p.profiles.byId(entries.first().session.profileId)
            val opened = SessionState(entries)
            _state.update { s ->
                s.copy(
                    sources = entries.map { SourceInfo(it.session.media.path, it.session.media, p.resolveProfile(it.session.media.path).id) },
                    session = opened.copy(selectedId = opened.segments.firstOrNull()?.key),
                    lastExport = null,
                    settings = s.settings.withProfileDefaults(profile).copy(profileId = profile.id),
                )
            }
            val openedFiles = files.map { it.toAbsolutePath().normalize() }.toSet()
            _state.update { s -> s.copy(watch = s.watch.copy(fresh = s.watch.fresh - openedFiles), librarySelection = emptySet()) }
            val missing = entries.map { it.session.media.path }.filterNot { it.exists() }
            if (missing.isNotEmpty()) {
                showError("Vidéo source introuvable", "${missing.joinToString()} n'existe plus : l'export et les aperçus seront impossibles.")
            }
            loadThumbnails()
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
                settings = s.settings.withProfileDefaults(profile).copy(profileId = id),
                session = s.session?.let { it.copy(profileChanged = it.sessions.any { session -> session.profileId != profile.id }) },
            )
        }
    }

    override fun setStyle(style: EditStyle) = _state.update { s -> s.copy(settings = s.settings.copy(style = style)) }

    override fun toggleFormat(format: OutputFormat) = _state.update { s ->
        val formats = if (format in s.settings.formats) s.settings.formats - format else s.settings.formats + format
        s.copy(settings = s.settings.copy(formats = formats))
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
        _state.update { it.copy(settings = change(it.settings)) }
        val s = state.value
        val current = s.session ?: return
        val target = s.settings.target ?: return
        val p = backend?.pipeline ?: return
        val reselected = try {
            p.reselectAll(current.sessions, s.settings.threshold, target, s.settings.momentMode.requiredEvent)
        } catch (e: HighlightsException) {
            return showError("Recalcul impossible", e.message ?: "")
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
        val target = s.settings.target ?: return showError("Réglage invalide", "La durée cible ou le nombre de moments n'est pas valide.")
        val title = if (sources.size == 1) "Analyse de ${sources.single().path.name}" else "Analyse de ${sources.size} vidéos"
        runTask(title) { progress ->
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
                val hint = if (s.settings.momentMode == MomentMode.KILLS) {
                    "Aucun kill détecté : ce profil n'a peut-être pas de détection de kills. Essaie « Les meilleurs moments »."
                } else {
                    "Baisse le seuil pour retenir plus de moments."
                }
                val warnings = outcomes.flatMap { o -> o.session.warnings.map { w -> if (outcomes.size > 1) "${o.session.media.path.name} : $w" else w } }
                showError("Aucun moment retenu", (warnings + hint).joinToString("\n"))
            }
            loadThumbnails()
        }
    }

    override fun export() {
        val s = state.value
        val current = s.session ?: return
        val p = backend?.pipeline ?: return
        runTask("Export du montage") { progress ->
            saveNow(current)
            val result = p.export(current.sessions, ExportOptions(s.settings.orderedFormats, s.settings.outputDir, s.settings.style), progress)
            _state.update { it.copy(lastExport = result) }
        }
    }

    override fun cancelJob() {
        mainJob?.cancel(CancellationException("Annulé par l'utilisateur"))
    }

    /** Annule les traitements en cours avant de quitter (tue les process FFmpeg). */
    fun shutdown() {
        val jobs = listOfNotNull(mainJob, thumbnailJob, saveJob, watchJob)
        jobs.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(3.seconds) { jobs.forEach { it.join() } } }
        state.value.session?.entries?.forEach { runCatching { SessionStore.save(it.session, it.file) } }
    }

    // ---------------------------------------------------------------- segments

    override fun toggleSegment(id: String) = updateSegments { s -> if (s.key == id) s.highlight.copy(enabled = !s.highlight.enabled) else s.highlight }

    override fun setAllSegments(enabled: Boolean) = updateSegments { it.highlight.copy(enabled = enabled) }

    override fun selectSegment(id: String?) = _state.update { s -> s.copy(session = s.session?.copy(selectedId = id)) }

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
                platform.open(clip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("Aperçu impossible", e)
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
                val where = if (current.multiple) " de ${session.media.path.name}" else ""
                _state.update { it.copy(imagePreview = ImagePreview(image, "Aperçu 9:16 — ${highlight.id}$where au pic", id)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure("Aperçu 9:16 impossible", e)
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
        _state.update {
            it.copy(
                montage = MontageUiState(
                    music = it.lastMusic,
                    maxDurationText = montage?.maxDuration?.let(dev.highlights.core.serialization.Durations::format) ?: "60s",
                    fitKills = montage?.length?.fitKills ?: true,
                    buildUp = montage?.order != dev.highlights.core.model.MontageOrder.CHRONOLOGICAL,
                    formats = montage?.formats?.toSet() ?: setOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
                    zoom = montage?.zoom?.enabled ?: true,
                    flash = montage?.flash?.enabled ?: true,
                    whip = montage?.whip?.enabled ?: true,
                    matchCut = montage?.matchCut?.enabled ?: true,
                    rounds = montage?.killStyle?.deathEvent?.isNotEmpty() ?: true,
                    slowMotion = montage?.slowMotion?.enabled ?: true,
                    text = montage?.text?.enabled ?: true,
                    balance = montage?.audio?.balance ?: 0.0,
                    gameAudio = montage?.audio?.game ?: GameAudio.FULL,
                    reactions = montage?.reactions ?: false,
                ),
            )
        }
    }

    override fun closeMontage() = _state.update { it.copy(montage = null) }

    override fun chooseMusic() {
        platform.chooseAudio(state.value.montage?.music?.parent ?: state.value.lastMusic?.parent)?.let { music ->
            _state.update { it.copy(montage = it.montage?.copy(music = music), lastMusic = music) }
        }
    }

    override fun updateMontage(change: (MontageUiState) -> MontageUiState) =
        _state.update { s -> s.copy(montage = s.montage?.let(change)) }

    override fun createMontage() {
        val s = state.value
        val montage = s.montage ?: return
        val music = montage.music ?: return
        val session = s.session ?: return
        val p = backend?.pipeline ?: return
        _state.update { it.copy(montage = null) }
        runTask("Montage kills sur ${music.name}") { progress ->
            saveNow(session)
            val result = p.killMontage(
                session.sessions,
                music,
                MontageOptions(
                    formats = OutputFormat.entries.filter { it in montage.formats },
                    outputDir = s.settings.outputDir,
                    maxDuration = montage.maxDuration,
                    fitKills = montage.fitKills,
                    order = if (montage.buildUp) dev.highlights.core.model.MontageOrder.BUILD_UP else dev.highlights.core.model.MontageOrder.CHRONOLOGICAL,
                    effectDensity = montage.density,
                    zoom = montage.zoom,
                    flash = montage.flash,
                    whip = montage.whip,
                    matchCut = montage.matchCut,
                    rounds = montage.rounds,
                    slowMotion = montage.slowMotion,
                    text = montage.text,
                    audioBalance = montage.balance,
                    gameAudio = montage.gameAudio,
                    reactions = montage.reactions,
                ),
                progress,
            )
            _state.update { it.copy(lastExport = result) }
        }
    }

    // ---------------------------------------------------------------- analyses enregistrées

    override fun showLibrary() {
        if (state.value.job != null) return
        state.value.session?.let { current -> scope.launch { saveNow(current) } }
        thumbnailJob?.cancel()
        _state.update { it.copy(sources = emptyList(), session = null, lastExport = null) }
        refreshLibrary()
    }

    override fun openLibraryItem(sessionFile: Path) = openSessions(listOf(sessionFile))

    override fun toggleLibraryItem(sessionFile: Path) = _state.update { s ->
        s.copy(librarySelection = if (sessionFile in s.librarySelection) s.librarySelection - setOf(sessionFile) else s.librarySelection + setOf(sessionFile))
    }

    override fun openLibrarySelection() {
        val s = state.value
        openSessions(s.library.map { it.sessionFile }.filter { it in s.librarySelection })
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

    // ---------------------------------------------------------------- dossier surveillé

    override fun chooseWatchFolder() {
        val initial = state.value.watch.folder ?: state.value.source?.path?.parent
        val folder = platform.chooseDirectory(initial, "Dossier où arrivent les captures") ?: return
        // Les captures déjà présentes ne sont pas analysées d'office : seulement celles qui arrivent à partir de maintenant.
        val prefs = WatchPrefs(folder.toAbsolutePath().normalize(), Instant.now())
        watchPrefs.save(prefs)
        startWatching(prefs)
    }

    override fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        watchPrefs.save(null)
        _state.update { it.copy(watch = WatchState(fresh = it.watch.fresh)) }
    }

    /**
     * Passe régulièrement sur le dossier et analyse en fond chaque nouvelle capture terminée, une à la fois et jamais
     * pendant un traitement lancé à la main. Seulement l'analyse : l'export se fait ensuite depuis la liste.
     */
    private fun startWatching(prefs: WatchPrefs) {
        watchJob?.cancel()
        val watcher = FolderWatcher(prefs.folder, prefs.since, watchSettle)
        _state.update { it.copy(watch = WatchState(folder = prefs.folder, fresh = it.watch.fresh)) }
        log.info { "Surveillance de ${prefs.folder} (captures depuis ${prefs.since})" }
        watchJob = scope.launch(Dispatchers.IO) {
            while (true) {
                backend?.pipeline?.let { p ->
                    val found = watcher.poll { file -> p.library.contains(file) || file in state.value.watch.pending }
                    if (found.isNotEmpty()) {
                        log.info { "Nouvelle(s) capture(s) : ${found.joinToString { it.name }}" }
                        _state.update { it.copy(watch = it.watch.copy(pending = it.watch.pending + found)) }
                    }
                    while (state.value.watch.pending.isNotEmpty()) {
                        while (mainJob?.isActive == true) delay(1.seconds)
                        analyzeInBackground(p, state.value.watch.pending.first())
                    }
                }
                delay(watchInterval)
            }
        }
    }

    private suspend fun analyzeInBackground(p: HighlightPipeline, file: Path) {
        _state.update { it.copy(watch = it.watch.copy(current = file, fraction = 0.0, pending = it.watch.pending - setOf(file))) }
        val lastUpdate = AtomicLong(0)
        val tracker = ProgressTracker { snap ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate.getAndUpdate { prev -> if (now - prev >= 250) now else prev } >= 250) {
                _state.update { s -> s.copy(watch = s.watch.copy(fraction = snap.fraction)) }
            }
        }
        try {
            val outcome = p.analyze(file, AnalyzeOptions(outputDir = state.value.settings.outputDir), tracker.root)
            val sessionFile = outcome.sessionFile.toAbsolutePath().normalize()
            _state.update { s -> s.copy(watch = s.watch.copy(fresh = s.watch.fresh + setOf(sessionFile), lastError = null)) }
            refreshLibrary()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // En fond, pas de boîte de dialogue : l'échec est signalé au-dessus de la liste, et la capture n'est pas
            // retentée tant qu'elle ne change pas.
            log.error(e) { "Analyse en fond de $file impossible" }
            _state.update { s -> s.copy(watch = s.watch.copy(lastError = "${file.name} : ${e.message ?: e}")) }
        } finally {
            _state.update { s -> s.copy(watch = s.watch.copy(current = null, fraction = 0.0)) }
        }
    }

    override fun open(path: Path) = platform.open(path)
    override fun reveal(path: Path) = platform.reveal(path)
    override fun dismissError() = _state.update { it.copy(error = null) }
    override fun dismissImagePreview() = _state.update { it.copy(imagePreview = null) }

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
                reportFailure("$title : échec", e)
            } finally {
                _state.update { it.copy(job = null) }
            }
        }
    }

    private fun reportFailure(title: String, e: Exception) {
        when (e) {
            is FfmpegException -> {
                log.error { "$title : ${e.message}" }
                showError(title, e.message ?: "", e.stderrTail.takeLast(20) + listOf("", "Commande : ${e.commandLine}", "Journal : $LOG_FILE"))
            }
            is HighlightsException -> showError(title, e.message ?: "")
            else -> {
                log.error(e) { title }
                showError(title, e.toString(), listOf("Journal : $LOG_FILE"))
            }
        }
    }

    private fun showError(title: String, message: String, details: List<String> = emptyList()) {
        _state.update { it.copy(error = ErrorInfo(title, message, details)) }
    }

    companion object {
        val LOG_FILE: String = Path.of(System.getProperty("user.home"), ".highlights", "logs", "highlights.log").toString()
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
        durationText = target.totalDuration?.let { dev.highlights.core.serialization.Durations.format(it) } ?: durationText,
        topNText = target.topN?.toString() ?: topNText,
    )
}
