package dev.highlights.ui

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.aspectLabel
import dev.highlights.core.serialization.Durations
import dev.highlights.core.session.Session
import dev.highlights.export.ExportResult
import dev.highlights.core.config.YouTubePrivacy
import dev.highlights.core.model.PlatformProfile
import dev.highlights.core.model.SafeArea
import dev.highlights.core.serialization.toShortText
import dev.highlights.publish.UploadedVideo
import dev.highlights.publish.YouTubeMetadata
import dev.highlights.publish.YouTubeText
import java.nio.file.Path
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.time.Duration

data class UiState(
    val config: ConfigStatus = ConfigStatus.Loading,
    /** Captures choisies, dans l'ordre d'enregistrement : plusieurs = un seul montage de toutes les parties. */
    val sources: List<SourceInfo> = emptyList(),
    val settings: SettingsState = SettingsState(),
    val job: JobState? = null,
    val session: SessionState? = null,
    val lastExport: ExportResult? = null,
    val error: ErrorInfo? = null,
    val imagePreview: ImagePreview? = null,
    /** Segments dont l'extrait d'aperçu est en cours de génération. */
    val busyClips: Set<String> = emptySet(),
    val busyVerticalPreview: String? = null,
    /** Fenêtre de réglage du montage kills (null = fermée). */
    val montage: MontageUiState? = null,
    /** Fenêtre de publication sur YouTube (null = fermée). */
    val publish: PublishUiState? = null,
    /** Dernière vidéo mise en ligne. */
    val lastUpload: UploadedVideo? = null,
    /** Dernière musique choisie, proposée à la prochaine ouverture. */
    val lastMusic: Path? = null,
    /** Vue des statistiques, à la place des analyses enregistrées (null = fermée). */
    val stats: StatsState? = null,
    /** Vue de recherche de moments, à la place des analyses enregistrées (null = fermée). */
    val search: SearchState? = null,
    /** Analyses déjà faites, la plus récente d'abord. */
    val library: List<LibraryItem> = emptyList(),
    /** Analyses cochées dans la liste, à ouvrir ensemble pour un seul montage (fichiers de session). */
    val librarySelection: Set<Path> = emptySet(),
    val watch: WatchState = WatchState(),
    /** Nouvelle version proposée (null : à jour, ou pas encore vérifié). */
    val update: UpdateState? = null,
    /** L'installeur de la mise à jour attend que l'application se ferme. */
    val exitRequested: Boolean = false,
) {
    /** Première capture : celle qui décide du profil détecté et du ratio d'origine. */
    val source: SourceInfo? get() = sources.firstOrNull()

    val canAnalyze: Boolean get() = config is ConfigStatus.Ready && sources.isNotEmpty() && job == null
    val canExport: Boolean get() = session != null && job == null && session.enabledCount > 0 && (settings.formats.isNotEmpty() || settings.platform != null)

    /** Plateformes proposées (config chargée). */
    val platforms: List<PlatformInfo> get() = (config as? ConfigStatus.Ready)?.platforms.orEmpty()
}

sealed interface ConfigStatus {
    data object Loading : ConfigStatus
    data class Ready(val configFile: Path, val profilesDir: Path, val profiles: List<ProfileInfo>, val platforms: List<PlatformInfo> = emptyList()) : ConfigStatus
    data class Failed(val message: String) : ConfigStatus
}

data class ProfileInfo(val id: String, val displayName: String)

/** Plateforme visée à l'export : [id] (app.yaml), nom affiché et ce qu'elle impose, en clair. */
data class PlatformInfo(val id: String, val name: String, val format: OutputFormat, val summary: String) {
    companion object {
        fun of(id: String, p: PlatformProfile) = PlatformInfo(
            id, p.name, p.format,
            listOfNotNull(
                "format ${p.format.label}",
                p.maxDuration?.let { d -> (if (d.inWholeSeconds % 60 == 0L) "${d.inWholeMinutes} min" else d.toShortText()) + " au plus" },
                "${String.format(java.util.Locale.ROOT, "%.0f", p.loudnessLufs)} LUFS",
                "textes hors de l'interface de l'appli".takeIf { p.safeArea != SafeArea.NONE },
            ).joinToString(" · "),
        )
    }
}

/** [alreadyAnalyzed] : l'analyse de cette capture est en mémoire et sera reprise sans recalcul. */
data class SourceInfo(val path: Path, val media: MediaInfo, val detectedProfileId: String, val alreadyAnalyzed: Boolean = false)

/** Une analyse enregistrée, telle que la liste l'affiche. */
data class LibraryItem(
    val source: Path,
    val sessionFile: Path,
    val profileId: String,
    val analyzedAt: Instant,
    val recordedAt: Instant?,
    val duration: Duration,
    val events: Map<String, Int>,
    /** La vidéo est encore là (sinon l'analyse se rouvre, mais sans export ni aperçu). */
    val sourceExists: Boolean,
)

/** Dossier où l'enregistreur dépose les parties : chaque nouvelle capture y est analysée en fond. */
data class WatchState(
    val folder: Path? = null,
    /** Capture en cours d'analyse en fond, et avancement. */
    val current: Path? = null,
    val fraction: Double = 0.0,
    val pending: List<Path> = emptyList(),
    /** Analyses faites en fond et pas encore ouvertes (fichiers de session). */
    val fresh: Set<Path> = emptySet(),
    val lastError: String? = null,
)

enum class TargetMode { DURATION, TOP_N, ALL }

/** Quels moments construire : les meilleurs (score) ou un moment par kill détecté. */
enum class MomentMode(val requiredEvent: String?) { BEST(null), KILLS("kill") }

data class SettingsState(
    /** null = profil détecté automatiquement d'après le chemin. */
    val profileId: String? = null,
    val formats: Set<OutputFormat> = setOf(OutputFormat.SOURCE),
    /** story : montage façon YouTube (jump cuts, accroche, effets) ; simple : moments bout à bout. */
    val style: EditStyle = EditStyle.STORY,
    val momentMode: MomentMode = MomentMode.BEST,
    val targetMode: TargetMode = TargetMode.DURATION,
    val durationText: String = "60s",
    val topNText: String = "8",
    val threshold: Double = 0.6,
    val outputDir: Path? = null,
    /** Plateforme visée (id) : impose le format et adapte volume, débit, textes ; null : les formats cochés. */
    val platform: String? = null,
) {
    val targetDuration: Duration? get() = Durations.parseOrNull(durationText)?.takeIf { it.isPositive() }
    val topN: Int? get() = topNText.trim().toIntOrNull()?.takeIf { it > 0 }

    /** Cible de sélection, ou null si le champ actif est invalide. */
    val target: SelectionTarget?
        get() = when (targetMode) {
            TargetMode.DURATION -> targetDuration?.let { SelectionTarget(totalDuration = it) }
            TargetMode.TOP_N -> topN?.let { SelectionTarget(topN = it) }
            TargetMode.ALL -> SelectionTarget(all = true)
        }

    /** Formats dans un ordre stable (source, 16:9, 9:16). */
    val orderedFormats: List<OutputFormat> get() = OutputFormat.entries.filter { it in formats }
}

data class JobState(
    val title: String,
    val fraction: Double = 0.0,
    val stage: String = "",
    val elapsed: Duration = Duration.ZERO,
    val eta: Duration? = null,
    val cancellable: Boolean = true,
)

/** Analyse d'une capture et fichier où elle est enregistrée. */
data class SessionEntry(val session: Session, val file: Path)

/**
 * Un moment de la liste : [key] l'identifie parmi toutes les captures (les identifiants h001… recommencent à chaque
 * capture), [entry] est l'index de sa capture dans [SessionState.entries].
 */
data class Segment(val key: String, val entry: Int, val highlight: Highlight)

data class SessionState(
    /** Une analyse par capture, dans l'ordre d'enregistrement. */
    val entries: List<SessionEntry>,
    /** Clé du segment sélectionné (voir [Segment.key]). */
    val selectedId: String? = null,
    /** Vignettes par capture et instant du pic, pour survivre au recalcul des identifiants. */
    val thumbnails: Map<String, Path> = emptyMap(),
    /** Le profil choisi a changé depuis l'analyse. */
    val profileChanged: Boolean = false,
) {
    constructor(session: Session, file: Path, selectedId: String? = null) : this(listOf(SessionEntry(session, file)), selectedId)

    val sessions: List<Session> get() = entries.map { it.session }
    val multiple: Boolean get() = entries.size > 1

    /** Tous les moments, capture par capture : l'ordre du montage chronologique. */
    val segments: List<Segment>
        get() = entries.flatMapIndexed { i, e -> e.session.highlights.map { Segment(keyOf(i, it), i, it) } }
    val highlights: List<Highlight> get() = sessions.flatMap { it.highlights }
    val enabledCount: Int get() = highlights.count { it.enabled }
    val enabledDuration: Duration get() = highlights.filter { it.enabled }.fold(Duration.ZERO) { acc, h -> acc + h.range.length }
    val selected: Segment? get() = segments.firstOrNull { it.key == selectedId }

    /** Capture dont la courbe est affichée : celle du segment sélectionné, sinon la première. */
    val shownEntry: Int get() = selected?.entry ?: 0

    fun thumbnailOf(segment: Segment): Path? = thumbnails[thumbnailKey(segment.entry, segment.highlight)]

    /** Clé d'un moment : son identifiant seul avec une capture, préfixé du numéro de la capture avec plusieurs. */
    fun keyOf(entry: Int, h: Highlight): String = if (multiple) "${entry + 1}-${h.id}" else h.id

    /** Nombre d'événements détectés dans les parties, par type. */
    val eventCounts: Map<String, Int> get() = sessions.flatMap { it.timeline.events }.groupingBy { it.kind }.eachCount()

    companion object {
        fun thumbnailKey(entry: Int, h: Highlight): String = "$entry@${h.peak.inWholeMilliseconds}"
    }
}

data class ErrorInfo(val title: String, val message: String, val details: List<String> = emptyList())

data class ImagePreview(val path: Path, val title: String, val highlightId: String, val version: Long = System.nanoTime())

fun formatLabel(format: OutputFormat, media: MediaInfo?): String = when (format) {
    OutputFormat.SOURCE -> media?.video?.let { "Ratio d'origine (${aspectLabel(it.width, it.height)})" } ?: "Ratio d'origine"
    OutputFormat.LANDSCAPE -> "16:9 (bandes noires si besoin)"
    OutputFormat.VERTICAL -> "9:16 vertical (Shorts, TikTok)"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${(bytes / (1L shl 20).toDouble()).roundToInt()} Mo"
    else -> "${bytes / 1024} Ko"
}

/** "2 kills · 1 assistance" (types connus traduits). */
fun eventsLabel(events: Map<String, Int>): String = events.entries
    .sortedBy { listOf("kill", "assist", "revive", "death", "laughter", "shout").indexOf(it.key).let { i -> if (i < 0) 99 else i } }
    .joinToString(" · ") { (kind, n) ->
        val (one, many) = when (kind) {
            "kill" -> "kill" to "kills"
            "assist" -> "assistance" to "assistances"
            "revive" -> "réanimation" to "réanimations"
            "death" -> "mort" to "morts"
            "laughter" -> "rire" to "rires"
            "shout" -> "exclamation" to "exclamations"
            else -> kind to kind
        }
        "$n ${if (n > 1) many else one}"
    }

data class MontageUiState(
    val music: Path? = null,
    /** Choisir la musique dans [musicLibrary] : celle sur laquelle le montage est le mieux noté. */
    val useLibrary: Boolean = false,
    /** Dossier de musiques ; retenu d'un montage à l'autre (voir [MusicPrefs]). */
    val musicLibrary: Path? = null,
    /** Musique prise depuis son début ; retenu pour chaque musique (voir [MusicPrefs]). */
    val musicFromStart: Boolean = false,
    val maxDurationText: String = "60s",
    /** Durée tirée du nombre de kills, la durée maximale restant un plafond. */
    val fitKills: Boolean = true,
    val buildUp: Boolean = true,
    val formats: Set<OutputFormat> = setOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
    /** Plateforme visée : impose le format, et sa durée maximale devient un plafond. */
    val platform: String? = null,
    /** Seulement ces moments (choisis dans la recherche) ; null : tous les kills des parties ouvertes. */
    val pick: dev.highlights.pipeline.MomentPick? = null,
    val density: EffectDensity = EffectDensity.BALANCED,
    val zoom: Boolean = true,
    val flash: Boolean = true,
    val whip: Boolean = true,
    /** Couleurs boostées (vibrance, saturation, contraste), comme les kill montages de TikTok. */
    val boostColors: Boolean = false,
    /** Flou de mouvement : traînées sur les mouvements rapides, flou radial sur les coupes. */
    val motionBlur: Boolean = true,
    /** Coupes déplacées de quelques images pour raccorder sur une animation qui revient (rechargement, sort…). */
    val matchCut: Boolean = true,
    /** Kills classés selon leur round : mort juste après, ace, clutch (captures avec les morts d'Outplayed). */
    val rounds: Boolean = true,
    val slowMotion: Boolean = true,
    val text: Boolean = true,
    /** Équilibre jeu / musique, de -1 (musique devant) à 1 (jeu devant). */
    val balance: Double = 0.0,
    val gameAudio: GameAudio = GameAudio.FULL,
    /** Voix et rires mis en avant ; sinon le micro reste au niveau du jeu et l'écran décide seul. */
    val reactions: Boolean = false,
    /** Montage « onetaps » : que les kills d'une balle à la tête, un plan très court chacun. */
    val oneTaps: Boolean = false,
) {
    val maxDuration: Duration? get() = Durations.parseOrNull(maxDurationText)?.takeIf { it.isPositive() }
    /** Musique ou dossier de musiques passé au montage, selon le mode. */
    val musicSource: Path? get() = if (useLibrary) musicLibrary else music
    val canCreate: Boolean get() = musicSource != null && maxDuration != null && (formats.isNotEmpty() || platform != null)
}

/**
 * Publication d'une vidéo sur YouTube : chaque champ part de ce que propose [YouTubeText] et se modifie. [api] : envoi
 * direct par l'API ; sinon envoi manuel assisté (textes à copier, page d'envoi de YouTube ouverte). [configured] :
 * identifiant OAuth trouvé ; [connected] : compte déjà connecté (sinon connexion au premier envoi).
 */
data class PublishUiState(
    val video: Path,
    val api: Boolean = false,
    val loading: Boolean = true,
    val configured: Boolean = true,
    val connected: Boolean = false,
    val title: String = "",
    val description: String = "",
    val tagsText: String = "",
    val privacy: YouTubePrivacy = YouTubePrivacy.PRIVATE,
    val categoryText: String = "20",
    val language: String = "fr",
    val madeForKids: Boolean = false,
    val notify: Boolean = true,
    /** Mise en ligne programmée, heure locale ; vide : tout de suite. */
    val publishAtText: String = "",
    /** Texte proposé, pour y revenir. */
    val suggested: YouTubeMetadata? = null,
) {
    val tags: List<String> get() = tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val publishAt: Instant? get() = publishAtText.takeIf { it.isNotBlank() }?.let(YouTubeText::parseSchedule)
    private val scheduleInvalid: Boolean get() = publishAtText.isNotBlank() && publishAt == null

    fun metadata() = YouTubeMetadata(
        title = title.trim(),
        description = description.trim(),
        tags = tags,
        privacy = privacy,
        categoryId = categoryText.trim(),
        madeForKids = madeForKids,
        language = language.trim().ifEmpty { "fr" },
        publishAt = publishAt,
        notifySubscribers = notify,
    )

    val problems: List<String>
        get() = if (scheduleInvalid) listOf("Heure de mise en ligne illisible (ex. 2026-10-01 18:00)") else metadata().problems()

    val canPublish: Boolean get() = api && !loading && configured && problems.isEmpty()

    /** Champs remplis avec le texte proposé (à l'ouverture, ou pour y revenir). */
    fun withSuggestion(m: YouTubeMetadata) = copy(
        loading = false,
        title = m.title,
        description = m.description,
        tagsText = m.tags.joinToString(", "),
        privacy = m.privacy,
        categoryText = m.categoryId,
        language = m.language,
        madeForKids = m.madeForKids,
        notify = m.notifySubscribers,
        suggested = m,
    )
}
