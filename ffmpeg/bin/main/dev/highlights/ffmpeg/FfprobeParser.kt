package dev.highlights.ffmpeg

import dev.highlights.core.InputException
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object FfprobeParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(file: Path, output: String): MediaInfo {
        val root = try {
            json.parseToJsonElement(output).jsonObject
        } catch (e: Exception) {
            throw InputException("Sortie ffprobe illisible pour $file : ${e.message}", e)
        }
        val format = root["format"]?.jsonObject ?: throw InputException("ffprobe n'a pas reconnu le format de $file")
        val streams = root["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()

        val video = streams
            .filter { it.str("codec_type") == "video" && it.obj("disposition")?.int("attached_pic") != 1 }
            .firstOrNull()
            ?.let { s ->
                VideoStream(
                    index = s.int("index") ?: 0,
                    codec = s.str("codec_name") ?: "unknown",
                    width = s.int("width") ?: 0,
                    height = s.int("height") ?: 0,
                    fps = parseRate(s.str("avg_frame_rate")) ?: parseRate(s.str("r_frame_rate")) ?: 0.0,
                    pixelFormat = s.str("pix_fmt"),
                )
            }

        val audio = streams.filter { it.str("codec_type") == "audio" }.mapIndexed { i, s ->
            AudioStream(
                index = s.int("index") ?: i,
                audioIndex = i,
                codec = s.str("codec_name") ?: "unknown",
                channels = s.int("channels") ?: 0,
                sampleRate = s.str("sample_rate")?.toIntOrNull() ?: 0,
                // MKV : "title" ; MP4 : "name" / "handler_name" (sauf les valeurs génériques du muxer).
                title = s.obj("tags")?.let { it.str("title") ?: it.str("name") ?: it.str("handler_name")?.takeUnless(::isGenericHandler) },
            )
        }

        val duration = format.str("duration")?.toDoubleOrNull()?.seconds
            ?: streams.mapNotNull { it.str("duration")?.toDoubleOrNull() }.maxOrNull()?.seconds
            ?: throw InputException("Durée inconnue pour $file (fichier en cours d'écriture ou corrompu ?)")

        return MediaInfo(
            path = file.toAbsolutePath().normalize(),
            sizeBytes = format.str("size")?.toLongOrNull() ?: runCatching { file.fileSize() }.getOrDefault(0),
            duration = duration.coerceAtLeast(Duration.ZERO),
            creationTime = format.obj("tags")?.str("creation_time")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            container = format.str("format_name"),
            video = video,
            audio = audio,
        )
    }

    internal fun parseRate(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val parts = text.split('/')
        val value = if (parts.size == 2) {
            val den = parts[1].toDoubleOrNull() ?: return null
            if (den == 0.0) return null
            (parts[0].toDoubleOrNull() ?: return null) / den
        } else {
            text.toDoubleOrNull()
        }
        return value?.takeIf { it > 0 }
    }

    private fun isGenericHandler(name: String) = name.contains("SoundHandler", ignoreCase = true) || name.contains("VideoHandler", ignoreCase = true)

    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    private fun JsonObject.int(key: String): Int? = this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    private fun JsonObject.obj(key: String): JsonObject? = this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
}
