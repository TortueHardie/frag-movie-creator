package dev.highlights.ui

import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import dev.highlights.core.session.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

private val log = KotlinLogging.logger {}

/**
 * Dossier surveillé, retenu d'un lancement à l'autre. [since] : moment où il a été choisi ; seules les captures
 * arrivées depuis sont analysées d'office, pas tout l'historique qu'il contenait déjà.
 */
@Serializable
data class WatchPrefs(val folder: SerialPath, val since: SerialInstant, val evenings: EveningAuto = EveningAuto())

/**
 * Montage automatique de chaque soirée finie, depuis le dossier surveillé (désactivé par défaut). [delay] : temps sans
 * nouvelle partie après lequel la soirée est finie ; [minKills] : en dessous, rien n'est monté ni notifié ; [arrived] :
 * captures arrivées dans le dossier surveillé pendant que l'option est active, les seules dont la soirée se monte
 * (l'activer ne remonte pas l'historique).
 */
@Serializable
data class EveningAuto(
    val enabled: Boolean = false,
    val delay: SerialDuration = DEFAULT_DELAY,
    val minKills: Int = DEFAULT_MIN_KILLS,
    val arrived: Set<SerialPath> = emptySet(),
) {
    companion object {
        /** Une heure sans nouvelle partie : une pause repas ne coupe pas la soirée en deux. */
        val DEFAULT_DELAY = 60.minutes
        /** Moins de 5 kills : pas de quoi faire un montage, ni réveiller l'utilisateur par une notification. */
        const val DEFAULT_MIN_KILLS = 5
        /** Captures gardées dans [arrived] : des mois de soirées. */
        const val KEEP_ARRIVED = 500
    }
}

class WatchPrefsStore(private val file: Path) {
    fun load(): WatchPrefs? = runCatching {
        if (!file.exists()) null else SessionStore.json.decodeFromString(WatchPrefs.serializer(), file.readText())
    }.onFailure { log.warn { "Dossier surveillé illisible ($file) : ${it.message}" } }.getOrNull()

    fun save(prefs: WatchPrefs?) {
        runCatching {
            if (prefs == null) {
                file.deleteIfExists()
            } else {
                file.parent?.createDirectories()
                file.writeText(SessionStore.json.encodeToString(WatchPrefs.serializer(), prefs))
            }
        }.onFailure { log.warn { "Dossier surveillé non enregistré ($file) : ${it.message}" } }
    }

    companion object {
        val DEFAULT_FILE: Path = Path.of(System.getProperty("user.home"), ".highlights", "watch.json")
    }
}
