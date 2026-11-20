package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Cartes d'ouverture et de fin à l'image de la chaîne du joueur : son logo, son pseudo, sur un fond uni, une image ou
 * une image floutée de la vidéo. Défini dans le profil (`intro:`) ou dans app.yaml pour tous les jeux ; le profil
 * l'emporte. Valent pour les highlights (simple et story) et les montages kills : dans un montage, l'ouverture joue sur
 * l'amorce de la musique et s'arrête sur un temps, les kills restent sur les leurs (voir `IntroOutroCards`).
 *
 * Désactivé par défaut : une carte de marque n'a de sens qu'une fois le logo ou le pseudo renseigné.
 */
@Serializable
data class IntroOutroSettings(
    val enabled: Boolean = false,
    /** Carte d'ouverture. */
    val intro: Boolean = true,
    /** Carte de fin. */
    val outro: Boolean = true,
    /**
     * Durée de l'ouverture. Dans un montage kills, elle est ramenée à un nombre entier de temps de la musique (un au
     * moins) : la carte s'ouvre et se ferme sur un temps.
     */
    val introLength: SerialDuration = 1.seconds,
    /** Durée de la fin. La musique (montage, story) continue sous elle et s'éteint en fondu. */
    val outroLength: SerialDuration = 1500.milliseconds,
    /** Animation des cartes (choix du joueur, 20/11/2026 : celle d'origine par défaut). */
    val animation: CardAnimation = CardAnimation.RISE,
    /** Image du logo (PNG avec transparence de préférence). Chemin relatif au fichier de configuration. */
    val logo: String? = null,
    /** Pseudo écrit sous le logo (ou seul au centre, sans logo). */
    val name: String? = null,
    val background: CardBackground = CardBackground.BLUR,
    /** Couleur du fond uni, et du fond de secours quand l'image manque : « #RRGGBB ». */
    val color: String = "#101820",
    /** Image de fond (fond « image »), recadrée pour remplir l'écran. Chemin relatif au fichier de configuration. */
    val image: String? = null,
    /** Police du pseudo, gardée tant qu'elle a tous ses caractères (sinon repli selon l'écriture, voir VideoFonts). */
    val font: String = "C:/Windows/Fonts/impact.ttf",
    /** Couleur du pseudo, « #RRGGBB ». */
    val textColor: String = "#FFFFFF",
) {
    init {
        require(introLength in MIN_LENGTH..MAX_LENGTH && outroLength in MIN_LENGTH..MAX_LENGTH) {
            "intro : introLength et outroLength entre $MIN_LENGTH et $MAX_LENGTH"
        }
        require(COLOR.matches(color) && COLOR.matches(textColor)) { "intro : couleurs au format #RRGGBB" }
    }

    /** Il y a quelque chose à montrer : un logo ou un pseudo. */
    val hasContent: Boolean get() = !logo.isNullOrBlank() || !name.isNullOrBlank()

    /** Durée ajoutée en tête et en fin (zéro pour une carte désactivée). */
    val introDuration: Duration get() = if (enabled && intro) introLength else Duration.ZERO
    val outroDuration: Duration get() = if (enabled && outro) outroLength else Duration.ZERO

    companion object {
        val MIN_LENGTH = 500.milliseconds
        val MAX_LENGTH = 5.seconds
        private val COLOR = Regex("#[0-9A-Fa-f]{6}")
    }
}

/** Fond des cartes. */
@Serializable
enum class CardBackground {
    /** Couleur unie ([IntroOutroSettings.color]). */
    @SerialName("color") COLOR,

    /** Image choisie ([IntroOutroSettings.image]). */
    @SerialName("image") IMAGE,

    /** Une image de la vidéo, floutée et assombrie : le meilleur moment en ouverture, le dernier en fin. */
    @SerialName("blur") BLUR,
}

/** Animation des cartes. */
@Serializable
enum class CardAnimation {
    /** Le logo apparaît en montant, le pseudo « pop » juste après ; sortie du noir, coupe franche sur la vidéo. */
    @SerialName("rise") RISE,

    /** Logo et pseudo posés d'emblée ; un flash blanc à chaque coupe entre une carte et la vidéo. */
    @SerialName("flash") FLASH,

    /** Logo et pseudo posés ; le fond avance lentement (zoom) ; sortie du noir. */
    @SerialName("zoom") ZOOM,

    /** Rien d'animé : cartes fixes, coupes franches. */
    @SerialName("none") NONE,
}
