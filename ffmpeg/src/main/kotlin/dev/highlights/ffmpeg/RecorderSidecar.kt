package dev.highlights.ffmpeg

import dev.highlights.core.model.AudioSourceKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * Description des pistes audio qu'un enregistreur écrit à côté de la vidéo. Seul FTHRClips le fait aujourd'hui :
 * `<clip>.mp4.fthr-audio.json` (version 1, code source `FTHR_UI/core/audio_manifest.py`), une entrée par piste avec
 * `stream_index` (index dans le conteneur), `source_type` (`system`, `microphone`, `application`) et `display_name`.
 *
 * Lu sans vérifier l'empreinte SHA-256 du fichier vidéo qu'il porte : la lire ferait relire toute la capture à chaque
 * sonde. Le nom du fichier décrit doit correspondre ; un fichier illisible ou d'une autre version est ignoré (les titres
 * des pistes prennent alors le relais, comme FTHRClips le fait lui-même).
 */
internal object RecorderSidecar {
    const val FTHR_SUFFIX = ".fthr-audio.json"
    private const val FTHR_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    /** Ce que l'enregistreur dit d'une piste. */
    data class Declared(val kind: AudioSourceKind?, val name: String?)

    /** Pistes décrites, par index dans le conteneur ; vide sans description lisible. */
    fun read(media: Path): Map<Int, Declared> {
        val sidecar = media.resolveSibling(media.name + FTHR_SUFFIX)
        if (!sidecar.isRegularFile()) return emptyMap()
        return runCatching { parse(sidecar.readText(), media.name) }
            .onFailure { log.warn { "Description des pistes illisible, ignorée : $sidecar (${it.message})" } }
            .getOrDefault(emptyMap())
    }

    internal fun parse(text: String, mediaName: String): Map<Int, Declared> {
        val root = json.parseToJsonElement(text).jsonObject
        if (root.int("manifest_version") != FTHR_VERSION) return emptyMap()
        if (root.str("media_file") != mediaName) return emptyMap()
        return root["sources"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val index = o.int("stream_index") ?: return@mapNotNull null
            val kind = when (o.str("source_type")?.lowercase()) {
                "system" -> AudioSourceKind.SYSTEM
                "microphone" -> AudioSourceKind.MICROPHONE
                "application" -> AudioSourceKind.APPLICATION
                else -> null
            }
            index to Declared(kind, o.str("display_name")?.takeIf { it.isNotBlank() })
        }.toMap()
    }

    /**
     * Piste décrite dans son handler_name par FTHRClips (mesuré le 22/11 sur de vrais enregistrements) :
     * `FTHR3|<uuid>|<type>|<volume>|<muet>|<…>|<nom complété d'espaces>`. Le type y vaut « system » pour toutes les
     * pistes d'appareil, le micro compris : c'est le nom (« SteelSeries Sonar - Microphone (SteelSer… », « Chat »,
     * « Gaming ») qui dit le rôle, le type ne sert qu'à départager. Null pour tout autre handler_name.
     */
    fun fromHandler(handler: String): Declared? {
        val parts = handler.split('|')
        if (parts.size < 3 || !parts[0].trim().startsWith("FTHR", ignoreCase = true)) return null
        val kind = when (parts[2].trim().lowercase()) {
            "microphone" -> AudioSourceKind.MICROPHONE
            "application" -> AudioSourceKind.APPLICATION
            // « system » : une piste d'appareil, ou la « Default Mix » ; le nom décide (AudioTracks).
            else -> null
        }
        return Declared(kind, parts.last().trim().takeIf { it.isNotEmpty() })
    }

    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    private fun JsonObject.int(key: String): Int? = this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
}
