package dev.highlights.analysis.outplayed

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Événement de jeu enregistré par Outplayed (« kill », « death », « assist », « headshot »…), instant dans la vidéo. */
data class OutplayedEvent(val type: String, val at: Duration, val data: String?)

/** Une vidéo connue d'Outplayed, avec ses événements et le résumé de la partie. */
data class OutplayedMedia(
    val path: Path,
    val gameId: Long?,
    /** « fullMatch », « fullSession », « replay » (clip). */
    val type: String?,
    val events: List<OutplayedEvent>,
    /** Résumé de la partie : agentKey, killCount, deathCount, assistCount, headshotCount, isVictory… */
    val info: Map<String, Any?>,
)

/**
 * Bibliothèque d'Outplayed (application Overwolf d'enregistrement) : pour chaque partie, les vidéos produites et les
 * événements que le jeu a transmis à Overwolf (API officielle, pas de reconnaissance d'image). Stockée dans l'IndexedDB
 * de l'application, lue sans rien modifier.
 *
 * Instants : en millisecondes depuis le début de chaque fichier vidéo, y compris pour les clips découpés.
 */
class OutplayedLibrary(private val medias: List<OutplayedMedia>) {

    val size: Int get() = medias.size

    /** Vidéo correspondant à [video] : même chemin (sans tenir compte de la casse), sinon même nom de fichier s'il est unique. */
    fun find(video: Path): OutplayedMedia? {
        val wanted = normalize(video)
        medias.firstOrNull { normalize(it.path) == wanted }?.let { return it }
        return medias.filter { it.path.name.equals(video.name, ignoreCase = true) }.singleOrNull()
    }

    companion object {
        /** Base IndexedDB d'Outplayed (identifiant d'extension Overwolf fixe) dans le profil Windows courant. */
        fun defaultDatabase(): Path? {
            val local = System.getenv("LOCALAPPDATA") ?: return null
            return Path(local, "Overwolf", "CefBrowserCache", "Default", "IndexedDB", "overwolf-extension_${OUTPLAYED_ID}_0.indexeddb.leveldb")
        }

        fun load(database: Path): OutplayedLibrary = parse(LevelDbSnapshot.read(database).values)

        /** Les parties sont les objets qui portent une liste « medias » ; les autres magasins sont ignorés. */
        internal fun parse(values: Collection<ByteArray>): OutplayedLibrary {
            val medias = mutableListOf<OutplayedMedia>()
            for (raw in values) {
                if (!raw.containsAscii(MEDIAS)) continue
                val match = V8Deserializer.decodeIndexedDbValue(raw) as? Map<*, *> ?: continue
                val list = match["medias"] as? List<*> ?: continue
                val gameId = (match["gameId"] as? Number)?.toLong()
                @Suppress("UNCHECKED_CAST")
                val info = (match["info"] as? Map<String, Any?>).orEmpty()
                for (m in list) {
                    val media = m as? Map<*, *> ?: continue
                    val path = (media["path"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                    val events = (media["events"] as? List<*>).orEmpty().mapNotNull { e ->
                        val event = e as? Map<*, *> ?: return@mapNotNull null
                        val type = event["type"] as? String ?: return@mapNotNull null
                        val time = (event["time"] as? Number)?.toDouble() ?: return@mapNotNull null
                        OutplayedEvent(type, time.milliseconds, event["data"]?.toString())
                    }.sortedBy { it.at }
                    medias += OutplayedMedia(runCatching { Path(path) }.getOrNull() ?: continue, gameId, media["type"] as? String, events, info)
                }
            }
            return OutplayedLibrary(medias)
        }

        private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString().replace('/', '\\').lowercase()

        private fun ByteArray.containsAscii(text: String): Boolean {
            val needle = text.toByteArray(Charsets.ISO_8859_1)
            outer@ for (i in 0..size - needle.size) {
                for (k in needle.indices) if (this[i + k] != needle[k]) continue@outer
                return true
            }
            return false
        }

        const val OUTPLAYED_ID = "cghphpbjeabdkomiphingnegihoigeggcfphdofo"
        private const val MEDIAS = "medias"
    }
}
