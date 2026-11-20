package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Webcam en incrustation : la vidéo du joueur, enregistrée à côté de la partie (fichier séparé d'OBS, d'une autre
 * application, ou second flux vidéo de la même capture), posée sur le montage.
 *
 * - 9:16 : bandeau en haut, sur toute la largeur ; le jeu, recadré, occupe le reste de la hauteur dessous.
 * - 16:9 et format source : incrustation dans un coin, l'image du jeu reste entière.
 *
 * Les effets qui bougent l'image (zoom, secousse, flous, whip pan) et ceux qui la teintent (flash, couleurs boostées)
 * ne touchent que le jeu : la webcam est posée par-dessus, une fois le plan fini (voir `WebcamLayout`).
 *
 * Désactivée par défaut : elle s'active quand l'utilisateur choisit une webcam (ou la recherche automatique) et
 * qu'une vidéo est trouvée.
 */
@Serializable
data class WebcamSettings(
    val enabled: Boolean = false,
    /** Hauteur du bandeau du 9:16, en part de la hauteur de l'image (0,30 : 576 px sur 1920). */
    val band: Double = 0.30,
    /** Coin de l'incrustation en 16:9 et au format source. */
    val corner: WebcamCorner = WebcamCorner.BOTTOM_LEFT,
    /** Largeur de l'incrustation du coin, en part de la largeur de l'image. */
    val size: Double = 0.24,
    /** Écart entre l'incrustation et les bords de l'image, en part de la largeur. */
    val margin: Double = 0.02,
    /** Rapport largeur / hauteur de l'incrustation du coin : l'image de la webcam y est recadrée au centre. */
    val aspect: Double = 16.0 / 9.0,
    /** Partie de l'image de la webcam à garder (coordonnées 0..1), ex. le visage ; null = l'image entière, recadrée au centre. */
    val crop: CropRegion? = null,
    /**
     * Liseré autour de l'incrustation du coin, sous le bandeau du 9:16 (pixels de sortie ; 0 = aucun). Actif par défaut :
     * choix du joueur (20/11), il détache le visage du jeu.
     */
    val border: Int = 4,
    /**
     * Rayon des coins arrondis de l'incrustation du coin (16:9, format source), en part de sa hauteur ; 0 = coins droits.
     * Le bandeau du 9:16 reste droit : il touche les bords de l'image.
     */
    val radius: Double = 0.12,
    /** Couleur du liseré (nom ou #RRGGBB, comme FFmpeg). */
    val borderColor: String = "white",
    /** Décalage ajouté à la synchronisation : positif si la webcam est en retard sur le jeu (le clap s'y entend plus tard). */
    val offset: SerialDuration = Duration.ZERO,
    /** Affiner la synchronisation en comparant le son de la webcam à celui de la capture (micro de préférence). */
    val audioSync: Boolean = true,
) {
    init {
        require(band in 0.1..0.45) { "webcam.band doit être entre 0,1 et 0,45" }
        require(size in 0.1..0.5) { "webcam.size doit être entre 0,1 et 0,5" }
        require(margin in 0.0..0.1) { "webcam.margin doit être entre 0 et 0,1" }
        require(aspect in 0.5..3.0) { "webcam.aspect doit être entre 0,5 et 3" }
        require(border in 0..20) { "webcam.border doit être entre 0 et 20 px" }
        require(radius in 0.0..0.5) { "webcam.radius doit être entre 0 et 0,5" }
    }

    /** Hauteur du bandeau en pixels pour une image haute de [height] (paire), 0 hors 9:16 ou webcam éteinte. */
    fun bandHeight(format: OutputFormat, height: Int): Int =
        if (enabled && format == OutputFormat.VERTICAL) ((band * height).toInt() / 2) * 2 else 0
}

/** Coin de l'incrustation de la webcam. */
@Serializable
enum class WebcamCorner {
    @SerialName("top-left") TOP_LEFT,
    @SerialName("top-right") TOP_RIGHT,
    @SerialName("bottom-left") BOTTOM_LEFT,
    @SerialName("bottom-right") BOTTOM_RIGHT,
}

/**
 * Vidéo de webcam trouvée pour une capture, et son calage : l'instant `t` de la capture correspond à `t + offset` dans
 * la webcam. Un offset négatif : la webcam a commencé après la partie (le début de la capture n'a pas de webcam).
 */
data class WebcamFeed(
    val file: Path,
    /** Rang du flux parmi les flux vidéo du fichier (1 : second flux vidéo de la capture elle-même). */
    val stream: Int = 0,
    val offset: Duration,
    val duration: Duration,
    val width: Int,
    val height: Int,
    /** D'où vient le calage, pour le journal et le rapport. */
    val sync: WebcamSync = WebcamSync.TIME,
)

/** Origine du calage d'une webcam. */
enum class WebcamSync {
    /** Même fichier que la capture : même horloge. */
    SAME_FILE,

    /** Heures de début d'enregistrement des deux fichiers. */
    TIME,

    /** Son de la webcam comparé à celui de la capture. */
    AUDIO,

    /** Aucune heure fiable : début contre début, plus le décalage réglé. */
    NONE,
}

/** Choix de la webcam par l'utilisateur. */
sealed interface WebcamChoice {
    /** Chercher : second flux vidéo de la capture, sinon une vidéo de webcam rangée à côté. */
    data object Auto : WebcamChoice

    data class File(val path: Path) : WebcamChoice

    /** Webcam déjà trouvée et calée (aperçu de l'application : pas de seconde recherche). */
    data class Resolved(val feed: WebcamFeed) : WebcamChoice

    companion object {
        /** « auto » ou un chemin de fichier (CLI, préférences). */
        fun parse(text: String): WebcamChoice = if (text.trim().equals("auto", ignoreCase = true)) Auto else File(Path.of(text.trim()))
    }
}
