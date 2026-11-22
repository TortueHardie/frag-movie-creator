package dev.highlights.ui

import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.session.SessionStore
import dev.highlights.pipeline.HighlightPipeline
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/** Écran de premier lancement vu (passé, terminé ou montage lancé) : il ne revient plus de lui-même. */
@Serializable
data class FirstRunPrefs(val done: Boolean = true, val at: SerialInstant? = null)

/** `~/.highlights/first-run.json`. */
class FirstRunPrefsStore(private val file: Path) {
    fun done(): Boolean = runCatching {
        file.exists() && SessionStore.json.decodeFromString(FirstRunPrefs.serializer(), file.readText()).done
    }.onFailure { log.warn { "Premier lancement illisible ($file) : ${it.message}" } }.getOrDefault(true)

    fun markDone(at: Instant) {
        runCatching {
            file.parent?.createDirectories()
            file.writeText(SessionStore.json.encodeToString(FirstRunPrefs.serializer(), FirstRunPrefs(true, at)))
        }.onFailure { log.warn { "Premier lancement non enregistré ($file) : ${it.message}" } }
    }

    companion object {
        val DEFAULT_FILE: Path = Path.of(System.getProperty("user.home"), ".highlights", "first-run.json")
    }
}

/** Un jeu reconnu parmi les vidéos d'un dossier : profil, nom affiché, nombre de vidéos. */
data class SetupGame(val profileId: String, val name: String, val count: Int)

/**
 * Un enregistreur trouvé, tel que l'écran le montre. [videos] : vidéos de son dossier (les plus récentes d'abord, 300 au
 * plus), [games] : jeux reconnus par leur chemin (profils), le profil par défaut compris (« autre jeu »).
 */
data class SetupRecorder(
    /** null : dossier choisi à la main. */
    val kind: RecorderKind?,
    val folder: Path,
    val videos: List<FoundVideo>,
    val games: List<SetupGame>,
    val separateMic: Boolean?,
    val fromSettings: Boolean,
) {
    val latest: Instant? get() = videos.firstOrNull()?.modified
}

enum class SetupPage { WELCOME, TIPS }

/**
 * Écran de premier lancement ([FirstRunView]). [folder] : dossier choisi (celui d'un enregistreur trouvé ou un autre),
 * [watch] : le surveiller ensuite, [firstRun] : montré de lui-même au lancement (false : rouvert depuis le menu « ? »).
 */
data class SetupUiState(
    val loading: Boolean = true,
    val recorders: List<SetupRecorder> = emptyList(),
    val folder: Path? = null,
    /** Dossier choisi à la main (pas celui d'un enregistreur trouvé), et ses vidéos. */
    val other: SetupRecorder? = null,
    val watch: Boolean = true,
    val page: SetupPage = SetupPage.WELCOME,
    val firstRun: Boolean = true,
    /** Vidéos que « Faire mon premier montage » analysera (soirée la plus récente du dossier choisi). */
    val firstPick: List<Path> = emptyList(),
) {
    val chosen: SetupRecorder? get() = recorders.firstOrNull { it.folder == folder } ?: other?.takeIf { it.folder == folder }
    val canStart: Boolean get() = !loading && firstPick.isNotEmpty()
    /** Rien trouvé du tout : ni enregistreur, ni dossier choisi à la main. */
    val empty: Boolean get() = !loading && recorders.isEmpty() && other == null
}

/**
 * Choix des parties du premier montage : la soirée la plus récente (vidéos à moins de [GAP] l'une de l'autre), d'un
 * jeu reconnu de préférence (un profil sait où sont les kills), [MAX] parties au plus, dans l'ordre où elles ont été
 * jouées. Une capture encore en cours d'écriture (modifiée il y a moins de [SETTLE]) est laissée de côté.
 */
object FirstMontage {
    val GAP: Duration = Duration.ofHours(3)
    val SETTLE: Duration = Duration.ofSeconds(30)
    const val MAX = 5

    fun pick(videos: List<FoundVideo>, profileOf: (Path) -> String, now: Instant): List<Path> {
        val ready = videos.filter { it.modified.plus(SETTLE).isBefore(now) }.sortedByDescending { it.modified }
        val known = ready.filter { profileOf(it.path) != ProfileRepository.DEFAULT_ID }
        val pool = known.ifEmpty { ready }
        val newest = pool.firstOrNull() ?: return emptyList()
        val game = profileOf(newest.path)
        val evening = mutableListOf(newest)
        for (v in pool.drop(1)) {
            if (evening.size >= MAX) break
            if (profileOf(v.path) != game) continue
            if (Duration.between(v.modified, evening.last().modified) > GAP) break
            evening.add(v)
        }
        return evening.reversed().map { it.path }
    }
}

/**
 * Logique de l'écran de premier lancement : détection des enregistreurs (lecture seule de leurs réglages), dossier à
 * surveiller, premier montage. L'état vit dans [UiState.setup] ; surveiller et analyser passent par l'application
 * ([watchFolder], [firstMontage]).
 */
internal class FirstRunController(
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
    private val platform: Platform,
    private val prefs: FirstRunPrefsStore,
    private val detector: () -> RecorderDetector,
    private val pipeline: () -> HighlightPipeline?,
    private val clock: () -> Instant,
    private val watchFolder: (Path) -> Unit,
    private val firstMontage: (List<Path>) -> Unit,
) {
    private var job: Job? = null

    private fun edit(block: (SetupUiState) -> SetupUiState) = state.update { s -> s.copy(setup = s.setup?.let(block)) }

    /**
     * Au lancement : montré une seule fois, et seulement à un nouveau joueur (ni dossier surveillé, ni analyse déjà
     * faite) ; une mise à jour ne le montre pas à ceux qui se servaient déjà de l'application.
     */
    fun showIfNew(hasWatchFolder: Boolean) {
        val p = pipeline() ?: return
        if (state.value.setup != null) return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { !prefs.done() && !hasWatchFolder && runCatching { p.library.entries().isEmpty() }.getOrDefault(false) }
            if (fresh) show(firstRun = true)
        }
    }

    fun show(firstRun: Boolean = false) {
        state.update { it.copy(setup = SetupUiState(firstRun = firstRun, watch = it.watch.folder == null)) }
        job?.cancel()
        job = scope.launch {
            val found = withContext(Dispatchers.IO) { detector().detect().map { describe(it.kind, it.folder ?: return@map null, it.separateMic, it.fromSettings) }.filterNotNull() }
            // Dossier proposé : celui dont la dernière vidéo est la plus récente (l'enregistreur dont on se sert).
            val best = found.filter { it.videos.isNotEmpty() }.maxByOrNull { it.latest!! } ?: found.firstOrNull()
            edit { s -> s.copy(loading = false, recorders = found).choose(best?.folder) }
        }
    }

    /** Vidéos d'un dossier et jeux reconnus par leur chemin. */
    private fun describe(kind: RecorderKind?, folder: Path, separateMic: Boolean?, fromSettings: Boolean): SetupRecorder {
        val videos = RecordingScan.videos(folder)
        val p = pipeline()
        val games = videos.groupingBy { p?.resolveProfile(it.path)?.id ?: ProfileRepository.DEFAULT_ID }.eachCount()
            .map { (id, count) -> SetupGame(id, runCatching { p!!.profiles.byId(id).name }.getOrDefault(id), count) }
            // Jeux reconnus d'abord, le plus joué en tête ; « autre jeu » en dernier.
            .sortedWith(compareBy<SetupGame> { it.profileId == ProfileRepository.DEFAULT_ID }.thenByDescending { it.count })
        return SetupRecorder(kind, folder, videos, games, separateMic, fromSettings)
    }

    private fun SetupUiState.choose(folder: Path?): SetupUiState {
        val next = copy(folder = folder)
        val videos = next.chosen?.videos.orEmpty()
        val p = pipeline()
        return next.copy(firstPick = FirstMontage.pick(videos, { p?.resolveProfile(it)?.id ?: ProfileRepository.DEFAULT_ID }, clock()))
    }

    fun chooseFolder(folder: Path) = edit { it.choose(folder) }

    fun chooseOtherFolder() {
        val dir = platform.chooseDirectory(state.value.setup?.folder, dev.highlights.core.i18n.tr("ui.dialog.watchFolder")) ?: return
        val folder = dir.toAbsolutePath().normalize()
        edit { it.copy(loading = true) }
        scope.launch {
            val other = withContext(Dispatchers.IO) { describe(null, folder, null, false) }
            edit { s ->
                // Le dossier d'un enregistreur trouvé, choisi par l'autre chemin : c'est le même.
                if (s.recorders.any { it.folder == folder }) s.copy(loading = false).choose(folder)
                else s.copy(loading = false, other = other).choose(folder)
            }
        }
    }

    fun setWatch(watch: Boolean) = edit { it.copy(watch = watch) }

    fun setPage(page: SetupPage) = edit { it.copy(page = page) }

    /** Ferme l'écran ; le dossier choisi est surveillé si la case est cochée. */
    fun finish() {
        val s = state.value.setup ?: return
        job?.cancel()
        prefs.markDone(clock())
        if (s.watch) s.folder?.let(watchFolder)
        state.update { it.copy(setup = null) }
    }

    fun startFirstMontage() {
        val s = state.value.setup ?: return
        if (!s.canStart) return
        finish()
        firstMontage(s.firstPick)
    }

    fun openSite(kind: RecorderKind) = platform.browse(java.net.URI(kind.site))

    /** « Choisir une vidéo » de l'écran vide : l'écran se ferme, la sélection habituelle prend le relais. */
    fun chooseVideo(chooseSource: () -> Unit) {
        finish()
        chooseSource()
    }
}
