package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.io.path.getLastModifiedTime
import kotlin.time.Duration

@Serializable
data class MediaInfo(
    val path: SerialPath,
    val sizeBytes: Long,
    val duration: SerialDuration,
    val creationTime: SerialInstant? = null,
    val container: String? = null,
    val video: VideoStream? = null,
    val audio: List<AudioStream> = emptyList(),
) {
    val bounds: TimeRange get() = TimeRange(Duration.ZERO, duration)

    /** Moment de l'enregistrement : date de création inscrite dans la capture, sinon date de modification du fichier. */
    val recordedAt: Instant?
        get() = creationTime ?: runCatching { path.getLastModifiedTime().toInstant() }.getOrNull()

    companion object {
        /**
         * Ordre des parties jouées : par date d'enregistrement, puis par nom (captures numérotées). Un montage de
         * plusieurs captures les enchaîne dans cet ordre, quel que soit celui dans lequel elles ont été choisies.
         */
        val RECORDING_ORDER: Comparator<MediaInfo> =
            compareBy<MediaInfo, Instant?>(nullsLast()) { it.recordedAt }.thenBy { it.path.toString() }
    }
}

@Serializable
data class VideoStream(
    val index: Int,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val pixelFormat: String? = null,
)

@Serializable
data class AudioStream(
    /** Index absolu dans le conteneur (0:N). */
    val index: Int,
    /** Position parmi les pistes audio uniquement (0:a:N). */
    val audioIndex: Int,
    val codec: String,
    val channels: Int,
    val sampleRate: Int,
    val title: String? = null,
) {
    val label: String get() = "a:$audioIndex" + (title?.let { " \"$it\"" } ?: "")
}
