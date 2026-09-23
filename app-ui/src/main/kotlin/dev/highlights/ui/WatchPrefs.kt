package dev.highlights.ui

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

private val log = KotlinLogging.logger {}

/**
 * Dossier surveillé, retenu d'un lancement à l'autre. [since] : moment où il a été choisi ; seules les captures
 * arrivées depuis sont analysées d'office, pas tout l'historique qu'il contenait déjà.
 */
@Serializable
data class WatchPrefs(val folder: SerialPath, val since: SerialInstant)

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
