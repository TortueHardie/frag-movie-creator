package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class SelectionPolicy(
    /** Score fusionné minimal pour qu'une fenêtre soit candidate. */
    val threshold: Double = 0.55,
    /** Deux groupes de fenêtres séparés de moins que ça sont fusionnés. */
    val mergeGap: SerialDuration = 4.seconds,
    val preRoll: SerialDuration = 3.seconds,
    val postRoll: SerialDuration = 2.seconds,
    val minClip: SerialDuration = 4.seconds,
    val maxClip: SerialDuration = 20.seconds,
    val target: SelectionTarget = SelectionTarget(totalDuration = 60.seconds),
    /** Si renseigné (ex. "kill"), les moments sont construits autour de ces événements et le seuil est ignoré. */
    val requiredEvent: String? = null,
    /** Types de segments à ne jamais couper (ex. speech, laughter) : le moment est étendu jusqu'à leur fin. */
    val keepWhole: List<String> = emptyList(),
    /** Extension maximale ajoutée de chaque côté pour ne pas couper un segment. */
    val maxExtension: SerialDuration = 8.seconds,
) {
    init {
        require(threshold in 0.0..1.0) { "threshold doit être dans [0, 1]" }
        require(minClip <= maxClip) { "minClip ($minClip) > maxClip ($maxClip)" }
    }
}

/** Exactement un des trois : topN, totalDuration ou all. */
@Serializable
data class SelectionTarget(val topN: Int? = null, val totalDuration: SerialDuration? = null, val all: Boolean = false) {
    init {
        require(listOf(topN != null, totalDuration != null, all).count { it } == 1) { "target : renseigner exactement un de topN, totalDuration ou all" }
        require(topN == null || topN > 0) { "target.topN doit être > 0" }
    }
}

@Serializable
data class EditSettings(
    val order: ClipOrder = ClipOrder.CHRONOLOGICAL,
    val transition: TransitionSettings = TransitionSettings(),
    /** Cible de normalisation loudnorm en LUFS. null = pas de normalisation. */
    val loudnessLufs: Double? = -14.0,
    /** Pistes audio (0:a:N) mixées dans le montage. null = toutes. */
    val audioStreams: List<Int>? = null,
    val formats: List<OutputFormat> = listOf(OutputFormat.SOURCE),
    /** Hauteur de sortie du format "source" : la largeur suit le ratio de la capture (3440x1440 → 2580x1080). */
    val sourceHeight: Int = 1080,
    val landscape: FrameSize = FrameSize(1920, 1080),
    val vertical: VerticalSettings = VerticalSettings(),
    val grade: GradeSettings = GradeSettings(),
    val fps: Int = 60,
) {
    init {
        require(sourceHeight > 0 && sourceHeight % 2 == 0) { "sourceHeight doit être pair et > 0" }
    }
}

/**
 * Étalonnage appliqué à l'image, avant les effets du montage : il unifie des captures venues de sessions, de réglages
 * ou de jeux différents. Rien n'est appliqué tant que tout est à sa valeur neutre.
 */
@Serializable
data class GradeSettings(
    /** Table de correspondance .cube (ou .3dl) appliquée à l'image. Chemin relatif au fichier de configuration. */
    val lut: String? = null,
    /** Saturation (1 = inchangée). */
    val saturation: Double = 1.0,
    /** Contraste (1 = inchangé). */
    val contrast: Double = 1.0,
    /** Assombrissement des bords, 0 (aucun) à 1 (marqué) : ramène le regard au centre de l'image. */
    val vignette: Double = 0.0,
) {
    init {
        require(saturation in 0.0..3.0) { "grade.saturation doit être entre 0 et 3" }
        require(contrast in 0.0..3.0) { "grade.contrast doit être entre 0 et 3" }
        require(vignette in 0.0..1.0) { "grade.vignette doit être entre 0 et 1" }
    }

    /** Vrai si l'étalonnage laisse l'image telle quelle. */
    val isNeutral: Boolean
        get() = lut == null && saturation == 1.0 && contrast == 1.0 && vignette == 0.0
}

@Serializable
enum class ClipOrder {
    @SerialName("chronological") CHRONOLOGICAL,
    @SerialName("score") SCORE,
}

@Serializable
data class TransitionSettings(
    val type: TransitionType = TransitionType.FADE,
    val duration: SerialDuration = 300.milliseconds,
)

@Serializable
enum class TransitionType {
    @SerialName("cut") CUT,
    @SerialName("fade") FADE,
}

@Serializable
enum class OutputFormat(val fileSuffix: String, val label: String) {
    /** Ratio de la capture conservé (21:9 pour un écran ultrawide) : fichier principal, sans suffixe. */
    @SerialName("source") SOURCE("", "source"),
    /** 16:9 avec bandes noires si la source n'est pas en 16:9. */
    @SerialName("16:9") LANDSCAPE("_16x9", "16:9"),
    @SerialName("9:16") VERTICAL("_9x16", "9:16"),
    ;

    companion object {
        fun parse(text: String): OutputFormat? = when (text.trim().lowercase()) {
            "source", "natif", "native" -> SOURCE
            else -> entries.firstOrNull { it.label == text.trim() }
        }
    }
}

@Serializable
data class FrameSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "dimensions paires > 0 attendues : ${width}x$height" }
    }
}

@Serializable
data class VerticalSettings(
    val size: FrameSize = FrameSize(1080, 1920),
    /** Zone source (coordonnées normalisées 0..1) autour de laquelle recadrer. null = centre. */
    val cropRegion: CropRegion? = null,
    /** Éléments d'interface découpés hors du recadrage et replacés par-dessus (« crop and replace »). */
    val hud: List<HudOverlay> = emptyList(),
)

/**
 * Un élément du HUD : [source] est la zone dans la capture (normalisée 0..1),
 * [target] sa position dans l'image verticale (coin haut-gauche et largeur normalisés ; la hauteur suit le ratio de la zone).
 */
@Serializable
data class HudOverlay(
    val name: String,
    val source: CropRegion,
    val target: OverlayTarget,
    val enabled: Boolean = true,
)

@Serializable
data class OverlayTarget(val x: Double, val y: Double, val width: Double) {
    init {
        require(x in 0.0..1.0 && y in 0.0..1.0 && width > 0 && x + width <= 1.0001) { "target hors de [0, 1] : $this" }
    }
}

@Serializable
data class CropRegion(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1.0001 && y + height <= 1.0001) {
            "zone hors de [0, 1] : $this"
        }
    }
}
