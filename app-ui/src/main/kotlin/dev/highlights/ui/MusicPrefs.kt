package dev.highlights.ui

import dev.highlights.core.serialization.SerialPath
import dev.highlights.core.session.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/**
 * Choix retenus pour chaque musique, d'un montage à l'autre : [fromStart], les musiques prises depuis leur début (celles
 * qu'on reconnaît à leur intro) plutôt qu'autour de leur drop.
 */
@Serializable
data class MusicPrefs(val fromStart: Set<SerialPath> = emptySet())

class MusicPrefsStore(private val file: Path) {
    private var prefs: MusicPrefs? = null

    /** La musique [music] se prend-elle depuis son début ? Non, tant qu'on ne l'a pas demandé pour elle. */
    fun fromStart(music: Path): Boolean = music.normalize() in load().fromStart

    /** Retient le choix fait pour [music] ; n'écrit que s'il change. */
    fun remember(music: Path, fromStart: Boolean) {
        val current = load()
        val path = music.normalize()
        if ((path in current.fromStart) == fromStart) return
        val next = current.copy(fromStart = if (fromStart) current.fromStart + path else current.fromStart - path)
        prefs = next
        runCatching {
            file.parent?.createDirectories()
            file.writeText(SessionStore.json.encodeToString(MusicPrefs.serializer(), next))
        }.onFailure { log.warn { "Choix des musiques non enregistrés ($file) : ${it.message}" } }
    }

    private fun load(): MusicPrefs = prefs ?: runCatching {
        if (!file.exists()) MusicPrefs() else SessionStore.json.decodeFromString(MusicPrefs.serializer(), file.readText())
    }.onFailure { log.warn { "Choix des musiques illisibles ($file) : ${it.message}" } }.getOrDefault(MusicPrefs()).also { prefs = it }

    companion object {
        val DEFAULT_FILE: Path = Path.of(System.getProperty("user.home"), ".highlights", "music.json")
    }
}
