package dev.highlights.ui

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.serialization.Durations
import dev.highlights.core.session.Session
import dev.highlights.export.ExportResult
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration

data class UiState(
    val config: ConfigStatus = ConfigStatus.Loading,
    val source: SourceInfo? = null,
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
    /** Dernière musique choisie, proposée à la prochaine ouverture. */
    val lastMusic: Path? = null,
) {
    val canAnalyze: Boolean get() = config is ConfigStatus.Ready && source != null && job == null
    val canExport: Boolean get() = session != null && job == null && session.enabledCount > 0 && settings.formats.isNotEmpty()
}

sealed interface ConfigStatus {
    data object Loading : ConfigStatus
    data class Ready(val configFile: Path, val profilesDir: Path, val profiles: List<ProfileInfo>) : ConfigStatus
    data class Failed(val message: String) : ConfigStatus
}

data class ProfileInfo(val id: String, val displayName: String)

data class SourceInfo(val path: Path, val media: MediaInfo, val detectedProfileId: String)

enum class TargetMode { DURATION, TOP_N, ALL }

/** Quels moments construire : les meilleurs (score) ou un moment par kill détecté. */
enum class MomentMode(val requiredEvent: String?) { BEST(null), KILLS("kill") }

data class SettingsState(
    /** null = profil détecté automatiquement d'après le chemin. */
    val profileId: String? = null,
    val formats: Set<OutputFormat> = setOf(OutputFormat.SOURCE),
    val momentMode: MomentMode = MomentMode.BEST,
    val targetMode: TargetMode = TargetMode.DURATION,
    val durationText: String = "60s",
    val topNText: String = "8",
    val threshold: Double = 0.6,
    val outputDir: Path? = null,
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

data class SessionState(
    val session: Session,
    val file: Path,
    val selectedId: String? = null,
    /** Vignettes par instant du pic (ms), pour survivre au recalcul des identifiants. */
    val thumbnails: Map<Long, Path> = emptyMap(),
    /** Le profil choisi a changé depuis l'analyse. */
    val profileChanged: Boolean = false,
) {
    val highlights: List<Highlight> get() = session.highlights
    val enabledCount: Int get() = highlights.count { it.enabled }
    val enabledDuration: Duration get() = highlights.filter { it.enabled }.fold(Duration.ZERO) { acc, h -> acc + h.range.length }
    val selected: Highlight? get() = highlights.firstOrNull { it.id == selectedId }

    fun thumbnailOf(h: Highlight): Path? = thumbnails[h.peak.inWholeMilliseconds]

    /** Nombre d'événements détectés dans la partie, par type. */
    val eventCounts: Map<String, Int> get() = session.timeline.events.groupingBy { it.kind }.eachCount()
}

data class ErrorInfo(val title: String, val message: String, val details: List<String> = emptyList())

data class ImagePreview(val path: Path, val title: String, val highlightId: String, val version: Long = System.nanoTime())

/** "21:9", "16:9", "9:16"… ou "3440x1440" si le ratio n'est pas courant. */
fun aspectLabel(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "?"
    val ratio = width.toDouble() / height
    val known = listOf("32:9" to 32.0 / 9, "21:9" to 21.0 / 9, "16:10" to 1.6, "16:9" to 16.0 / 9, "4:3" to 4.0 / 3, "9:16" to 9.0 / 16)
    return known.minBy { abs(it.second - ratio) }.takeIf { abs(it.second - ratio) < 0.06 }?.first ?: "${width}x$height"
}

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
    val maxDurationText: String = "60s",
    val buildUp: Boolean = true,
    val formats: Set<OutputFormat> = setOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
    val density: EffectDensity = EffectDensity.BALANCED,
    val zoom: Boolean = true,
    val flash: Boolean = true,
    val slowMotion: Boolean = true,
    val text: Boolean = true,
) {
    val maxDuration: Duration? get() = Durations.parseOrNull(maxDurationText)?.takeIf { it.isPositive() }
    val canCreate: Boolean get() = music != null && maxDuration != null && formats.isNotEmpty()
}
