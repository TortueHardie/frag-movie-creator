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
 * qu'on reconnaît à leur intro) plutôt qu'autour de leur drop ; [library], le dossier de musiques où choisir, et
 * [useLibrary], s'il faut y choisir plutôt que prendre une musique précise ; [lyrics], les paroles saisies pour chaque
 * musique (chemin absolu → texte), reprises à chaque montage sur elle, choisie à la main ou dans la bibliothèque.
 */
@Serializable
data class MusicPrefs(
    val fromStart: Set<SerialPath> = emptySet(),
    val library: SerialPath? = null,
    val useLibrary: Boolean = false,
    val lyrics: Map<String, String> = emptyMap(),
)

class MusicPrefsStore(private val file: Path) {
    private var prefs: MusicPrefs? = null

    /** La musique [music] se prend-elle depuis son début ? Non, tant qu'on ne l'a pas demandé pour elle. */
    fun fromStart(music: Path): Boolean = music.normalize() in load().fromStart

    /** Musiques prises depuis leur début : le réglage de chacune, quand la musique est choisie dans une bibliothèque. */
    fun fromStartMusics(): Set<Path> = load().fromStart

    /** Dossier de musiques retenu (null : jamais choisi). */
    fun library(): Path? = load().library

    /** Choisir dans la bibliothèque plutôt que prendre une musique précise, comme au dernier montage. */
    fun useLibrary(): Boolean = load().let { it.useLibrary && it.library != null }

    /** Retient le choix fait pour [music] ; n'écrit que s'il change. */
    fun remember(music: Path, fromStart: Boolean) {
        val current = load()
        val path = music.normalize()
        if ((path in current.fromStart) == fromStart) return
        // plusElement, pas + : un Path est itérable, + ajouterait ses morceaux (« Musiques », « intro.mp3 »).
        save(current.copy(fromStart = if (fromStart) current.fromStart.plusElement(path) else current.fromStart.minusElement(path)))
    }

    /** Paroles saisies pour [music] (vide : aucune). */
    fun lyrics(music: Path): String = load().lyrics[key(music)].orEmpty()

    /** Paroles de chaque musique : celles de la musique retenue, quand elle est choisie dans une bibliothèque. */
    fun lyricsByMusic(): Map<Path, String> = load().lyrics.mapKeys { Path.of(it.key) }

    /** Retient les paroles saisies pour [music] (vides : oubliées) ; n'écrit que si elles changent. */
    fun rememberLyrics(music: Path, text: String) {
        val current = load()
        val k = key(music)
        val next = if (text.isBlank()) current.lyrics - k else current.lyrics + (k to text.trim())
        if (next != current.lyrics) save(current.copy(lyrics = next))
    }

    private fun key(music: Path) = music.toAbsolutePath().normalize().toString()

    /** Retient le dossier de musiques et s'il faut y choisir ; n'écrit que s'ils changent. */
    fun rememberLibrary(library: Path?, useLibrary: Boolean) {
        val current = load()
        val next = current.copy(library = library?.normalize() ?: current.library, useLibrary = useLibrary)
        if (next != current) save(next)
    }

    private fun save(next: MusicPrefs) {
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
