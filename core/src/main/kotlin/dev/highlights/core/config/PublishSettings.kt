package dev.highlights.core.config

import dev.highlights.core.i18n.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Publication des vidéos exportées : pour l'instant YouTube. */
@Serializable
data class PublishSettings(
    val youtube: YouTubeSettings = YouTubeSettings(),
)

/** Visibilité d'une vidéo envoyée sur YouTube. */
@Serializable
enum class YouTubePrivacy {
    @SerialName("private") PRIVATE,
    @SerialName("unlisted") UNLISTED,
    @SerialName("public") PUBLIC,
    ;

    /** Nom attendu par l'API YouTube (`status.privacyStatus`). */
    val apiName: String get() = name.lowercase()

    /** Libellé dans la langue de l'interface (« Private », « Privée »). */
    val label: String get() = tr("youtube.privacy.$apiName")
}

/**
 * Publication sur YouTube. Par défaut, envoi manuel assisté : titre, description et tags sont préparés, la page d'envoi
 * de YouTube s'ouvre dans le navigateur (où l'on est déjà connecté) et on y glisse la vidéo. Rien à configurer, et la
 * visibilité se choisit librement, alors que l'envoi par l'API ([api]) verrouille en privé les vidéos d'un projet
 * Google non audité par YouTube.
 *
 * Envoi par l'API ([api] : true) : il faut un identifiant OAuth « application de bureau » créé dans un projet Google Cloud où l'API
 * YouTube Data v3 est activée : soit le fichier `client_secret_….json` téléchargé depuis la console ([clientSecretFile],
 * relatif au dossier de la configuration), soit [clientId] et [clientSecret] recopiés. Pour une application de bureau, ce
 * « secret » n'en est pas un (Google le dit lui-même) : c'est le jeton gardé après la connexion qui donne accès à la chaîne.
 *
 * Tout ce qui suit sert de valeur de départ, modifiable avant chaque envoi.
 */
@Serializable
data class YouTubeSettings(
    /** Envoi direct par l'API YouTube, au lieu de l'envoi manuel assisté. */
    val api: Boolean = false,
    val clientSecretFile: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** Privée par défaut : on regarde la vidéo sur YouTube avant de la rendre visible. */
    val privacy: YouTubePrivacy = YouTubePrivacy.PRIVATE,
    /** Catégorie YouTube : 20 = Jeux vidéo. */
    val categoryId: String = "20",
    /** Vidéo « conçue pour les enfants » (obligation légale de le déclarer, COPPA). */
    val madeForKids: Boolean = false,
    /** Tags ajoutés à ceux tirés de la vidéo (nom du jeu). */
    val tags: List<String> = listOf("gaming", "highlights"),
    /** Langue du titre, de la description et de la voix (code YouTube : fr, en, pt-BR) ; null = langue de l'interface. */
    val language: String? = null,
    /**
     * Modèles de titre et de description ; null = texte écrit d'après la vidéo, dans la langue de l'interface. Champs :
     * {game}, {date}, {hook} (ACE, CLUTCH, TRIPLE KILL…), {kills}, {headshots}, {detail} (double kills, ace…),
     * {moments}, {duration}, {music}, {hashtags}, {title} (description seulement). Les anciens noms français restent
     * acceptés : {jeu}, {accroche}, {duree}, {musique}, {titre}.
     */
    val titleTemplate: String? = null,
    val descriptionTemplate: String? = null,
    /** #Shorts dans la description des vidéos verticales de 3 minutes au plus. */
    val shortsHashtag: Boolean = true,
    /** Prévenir les abonnés (vidéo publique seulement). */
    val notifySubscribers: Boolean = true,
) {
    init {
        require(categoryId.all { it.isDigit() } && categoryId.isNotEmpty()) { "publish.youtube.categoryId doit être un numéro de catégorie (20 = Jeux vidéo)" }
    }
}
