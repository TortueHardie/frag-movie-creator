package dev.highlights.core

/** Erreur métier attendue : son message est destiné à l'utilisateur. */
open class HighlightsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Configuration ou profil invalide. */
class ConfigException(message: String, cause: Throwable? = null) : HighlightsException(message, cause)

/** Fichier d'entrée absent, illisible ou non supporté. */
class InputException(message: String, cause: Throwable? = null) : HighlightsException(message, cause)
