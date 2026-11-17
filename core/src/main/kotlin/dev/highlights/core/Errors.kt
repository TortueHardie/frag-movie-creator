package dev.highlights.core

import dev.highlights.core.i18n.Message

/**
 * Erreur métier attendue : son message est destiné à l'utilisateur. Il est porté par [text] (clé des bundles et
 * arguments) et traduit à l'affichage : [getLocalizedMessage] dans la langue de l'interface, [message] en anglais pour
 * les journaux. Le constructeur à texte brut reste pour les messages venus d'ailleurs (Google, tests) : rendu tel quel.
 */
open class HighlightsException(val text: Message, cause: Throwable? = null) : RuntimeException(text.english(), cause) {
    constructor(message: String, cause: Throwable? = null) : this(Message.raw(message), cause)

    override fun getLocalizedMessage(): String = text.text()
}

/** Configuration ou profil invalide. */
class ConfigException(text: Message, cause: Throwable? = null) : HighlightsException(text, cause) {
    constructor(message: String, cause: Throwable? = null) : this(Message.raw(message), cause)
}

/** Fichier d'entrée absent, illisible ou non supporté. */
class InputException(text: Message, cause: Throwable? = null) : HighlightsException(text, cause) {
    constructor(message: String, cause: Throwable? = null) : this(Message.raw(message), cause)
}

/** Message traduisible d'une exception quelconque : le sien pour une [HighlightsException], sinon son texte brut. */
fun Throwable.userMessage(): Message = (this as? HighlightsException)?.text ?: Message.raw(message ?: toString())
