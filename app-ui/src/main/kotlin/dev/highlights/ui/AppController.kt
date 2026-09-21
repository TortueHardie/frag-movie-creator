package dev.highlights.ui

import dev.highlights.core.HighlightsException
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.pipeline.AnalyzeOptions
import dev.highlights.pipeline.ConfigLocator
import dev.highlights.pipeline.ExportOptions
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Pipeline prêt à l'emploi et fichier de config dont il provient. */
class Backend(val pipeline: HighlightPipeline, val configFile: Path)

interface UiActions {
    fun chooseSource()
    fun chooseSession()
    fun dropFiles(paths: List<Path>)
    fun reloadConfig()
    fun openProfilesFolder()
    fun editProfile()

    fun setProfile(id: String?)
    fun toggleFormat(format: OutputFormat)
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
                        source = s.source?.let { src -> src.copy(detectedProfileId = p.resolveProfile(src.path).id) },
                    )
                }
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
        platform.chooseVideo(state.value.source?.path?.parent)?.let(::selectSource)
    }

    override fun chooseSession() {
        val dir = state.value.settings.outputDir?.resolve("sessions")
        platform.chooseSession(dir)?.let(::openSession)
    }

    override fun dropFiles(paths: List<Path>) {
        val file = paths.firstOrNull() ?: return
        if (file.name.lowercase().endsWith(".json")) openSession(file) else selectSource(file)
    }

    private fun selectSource(path: Path) {
        if (state.value.job != null) return
        if (path.extension.lowercase() !in HighlightPipeline.SUPPORTED_EXTENSIONS) {
            showError(
                "Fichier non supporté",
                "${path.name} n'est pas une vidéo (${HighlightPipeline.SUPPORTED_EXTENSIONS.sorted().joinToString()}).",
            )
            return
        }
        val p = backend?.pipeline ?: return showError("Configuration non chargée", "Attends la fin du chargement ou corrige la configuration.")
        runTask("Lecture du fichier", cancellable = false) {
            val media = p.probe(path)
            val profile = p.resolveProfile(path)
            thumbnailJob?.cancel()
            _state.update { s ->
                s.copy(
                    source = SourceInfo(path, media, profile.id),
                    session = null,
                    lastExport = null,
                    settings = s.settings.withProfileDefaults(profile).copy(profileId = null),
                )
            }
        }
    }

    private fun openSession(file: Path) {
        if (state.value.job != null) return
        val p = backend?.pipeline ?: return showError("Configuration non chargée", "Attends la fin du chargement ou corrige la configuration.")
        runTask("Ouverture de la session", cancellable = false) {
            val session = withContext(Dispatchers.IO) { SessionStore.load(file) }
            val profile = p.profiles.byId(session.profileId)
            _state.update { s ->
                s.copy(
                    source = SourceInfo(session.media.path, session.media, p.resolveProfile(session.media.path).id),
                    session = SessionState(session, file, session.highlights.firstOrNull()?.id),
                    lastExport = null,
                    settings = s.settings.withProfileDefaults(profile).copy(profileId = profile.id),
                )
            }
            if (!session.media.path.exists()) {
                showError("Vidéo source introuvable", "${session.media.path} n'existe plus : l'export et les aperçus seront impossibles.")
            }
            loadThumbnails(session)
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
                session = s.session?.let { it.copy(profileChanged = it.session.profileId != profile.id) },
            )
        }
    }

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
            p.reselect(current.session, s.settings.threshold, target, s.settings.momentMode.requiredEvent)
        } catch (e: HighlightsException) {
            return showError("Recalcul impossible", e.message ?: "")
        }
        _state.update { st ->
            val sel = st.session ?: return@update st
            st.copy(session = sel.copy(session = reselected, selectedId = sel.selectedId?.takeIf { id -> reselected.highlights.any { it.id == id } }))
        }
        scheduleSave()
        loadThumbnails(reselected)
    }

    // ---------------------------------------------------------------- traitements

    override fun analyze() {
        val s = state.value
        val source = s.source ?: return
        val p = backend?.pipeline ?: return
        val target = s.settings.target ?: return showError("Réglage invalide", "La durée cible ou le nombre de moments n'est pas valide.")
        runTask("Analyse de ${source.path.name}") { progress ->
            val outcome = p.analyze(
                source.path,
                AnalyzeOptions(
                    s.settings.profileId,
                    s.settings.threshold,
                    target,
                    requiredEvent = s.settings.momentMode.requiredEvent,
                    outputDir = s.settings.outputDir,
                ),
                progress,
            )
            _state.update { st ->
                st.copy(
                    session = SessionState(outcome.session, outcome.sessionFile, outcome.session.highlights.firstOrNull()?.id),
                    lastExport = null,
                )
            }
            if (outcome.session.highlights.isEmpty()) {
                val hint = if (s.settings.momentMode == MomentMode.KILLS) {
                    "Aucun kill détecté : ce profil n'a peut-être pas de détection de kills. Essaie « Les meilleurs moments »."
                } else {
                    "Baisse le seuil pour retenir plus de moments."
                }
                showError("Aucun moment retenu", (outcome.session.warnings + hint).joinToString("\n"))
            }
            loadThumbnails(outcome.session)
        }
    }

    override fun export() {
        val s = state.value
        val current = s.session ?: return
        val p = backend?.pipeline ?: return
        runTask("Export du montage") { progress ->
            saveNow(current)
            val result = p.export(current.session, ExportOptions(s.settings.orderedFormats, s.settings.outputDir), progress)
            _state.update { it.copy(lastExport = result) }
        }
    }

    override fun cancelJob() {
        mainJob?.cancel(CancellationException("Annulé par l'utilisateur"))
    }

    /** Annule les traitements en cours avant de quitter (tue les process FFmpeg). */
    fun shutdown() {
        val jobs = listOfNotNull(mainJob, thumbnailJob, saveJob)
        jobs.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(3.seconds) { jobs.forEach { it.join() } } }
        state.value.session?.let { runCatching { SessionStore.save(it.session, it.file) } }
    }

    // ---------------------------------------------------------------- segments

    override fun toggleSegment(id: String) = updateHighlights { h -> if (h.id == id) h.copy(enabled = !h.enabled) else h }

    override fun setAllSegments(enabled: Boolean) = updateHighlights { it.copy(enabled = enabled) }

    override fun selectSegment(id: String?) = _state.update { s -> s.copy(session = s.session?.copy(selectedId = id)) }

    override fun previewClip(id: String) {
        val s = state.value
        val current = s.session ?: return
        val highlight = current.highlights.firstOrNull { it.id == id } ?: return
        val p = backend?.pipeline ?: return
        if (id in s.busyClips) return
        _state.update { it.copy(busyClips = it.busyClips + id) }
        scope.launch {
            try {
                val clip = withContext(Dispatchers.IO) { p.clipPreview(current.session, highlight) }
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
        val highlight = current.highlights.firstOrNull { it.id == id } ?: return
        val p = backend?.pipeline ?: return
        if (s.busyVerticalPreview != null) return
        _state.update { it.copy(busyVerticalPreview = id) }
        scope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    // Relit les profils : les zones du HUD modifiées dans le YAML s'appliquent immédiatement.
                    p.reloadProfiles()
                    p.preview(current.session.media.path, highlight.peak, current.session.profileId, listOf(OutputFormat.VERTICAL), p.previewDir).single()
                }
                _state.update { it.copy(imagePreview = ImagePreview(image, "Aperçu 9:16 — ${highlight.id} au pic", highlight.id)) }
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
        val profileId = s.session?.session?.profileId ?: return
        val montage = runCatching { p.profiles.byId(profileId).montage }.getOrNull()
        _state.update {
            it.copy(
                montage = MontageUiState(
                    music = it.lastMusic,
                    maxDurationText = montage?.maxDuration?.let(dev.highlights.core.serialization.Durations::format) ?: "60s",
                    buildUp = montage?.order != dev.highlights.core.model.MontageOrder.CHRONOLOGICAL,
                    formats = montage?.formats?.toSet() ?: setOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
                    zoom = montage?.zoom?.enabled ?: true,
                    flash = montage?.flash?.enabled ?: true,
                    slowMotion = montage?.slowMotion?.enabled ?: true,
                    text = montage?.text?.enabled ?: true,
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
                listOf(session.session),
                music,
                MontageOptions(
                    formats = OutputFormat.entries.filter { it in montage.formats },
                    outputDir = s.settings.outputDir,
                    maxDuration = montage.maxDuration,
                    order = if (montage.buildUp) dev.highlights.core.model.MontageOrder.BUILD_UP else dev.highlights.core.model.MontageOrder.CHRONOLOGICAL,
                    effectDensity = montage.density,
                    zoom = montage.zoom,
                    flash = montage.flash,
                    slowMotion = montage.slowMotion,
                    text = montage.text,
                ),
                progress,
            )
            _state.update { it.copy(lastExport = result) }
        }
    }

    override fun open(path: Path) = platform.open(path)
    override fun reveal(path: Path) = platform.reveal(path)
    override fun dismissError() = _state.update { it.copy(error = null) }
    override fun dismissImagePreview() = _state.update { it.copy(imagePreview = null) }

    // ---------------------------------------------------------------- interne

    private fun currentProfileId(): String? {
        val s = state.value
        return s.session?.session?.profileId ?: s.settings.profileId ?: s.source?.detectedProfileId
    }

    private fun updateHighlights(change: (dev.highlights.core.model.Highlight) -> dev.highlights.core.model.Highlight) {
        _state.update { s ->
            val current = s.session ?: return@update s
            s.copy(session = current.copy(session = current.session.copy(highlights = current.highlights.map(change))))
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
        runCatching { SessionStore.save(session.session, session.file) }
            .onFailure { log.warn(it) { "Sauvegarde de la session impossible" } }
    }

    private fun loadThumbnails(session: Session) {
        val p = backend?.pipeline ?: return
        if (!session.media.path.exists()) return
        thumbnailJob?.cancel()
        thumbnailJob = scope.launch {
            for (h in session.highlights) {
                val key = h.peak.inWholeMilliseconds
                if (state.value.session?.thumbnails?.containsKey(key) == true) continue
                val thumb = try {
                    withContext(Dispatchers.IO) { p.thumbnail(session.media, h.peak) }
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
