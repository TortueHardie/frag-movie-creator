package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import kotlinx.serialization.Serializable
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
