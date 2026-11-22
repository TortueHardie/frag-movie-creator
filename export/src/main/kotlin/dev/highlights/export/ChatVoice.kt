package dev.highlights.export

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Prises de parole sur la piste du chat vocal (Discord, chat de SteelSeries Sonar) : cette piste ne porte que des voix,
 * leur niveau suffit. Tranches de 20 ms au-dessus de [THRESHOLD], trous de moins de [BRIDGE] comblés, éclats de moins de
 * [MIN_SPEECH] écartés (clics, notifications de Discord).
 */
class ChatVoice(private val ffmpeg: FfmpegService) {
    suspend fun spans(media: MediaInfo, range: TimeRange, track: Int): List<TimeRange> {
        var samples = FloatArray(0)
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(range.start), "-t", Durations.ffmpegSeconds(range.length), "-i", media.path.toString(),
                    "-map", "0:a:$track", "-vn", "-ac", "1", "-ar", "$RATE", "-f", "f32le", "-acodec", "pcm_f32le", "pipe:1",
                ),
                "voix du chat ${media.path.fileName}",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                samples = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            },
        )
        return spans(samples, range.start)
    }

    companion object {
        const val RATE = 8000
        private const val FRAME = 160
        private val FRAME_LENGTH = 20.milliseconds
        /** Énergie d'une tranche au-dessus de laquelle quelqu'un parle (−45 dBFS). */
        const val THRESHOLD = 3.2e-5
        val BRIDGE = 400.milliseconds
        val MIN_SPEECH = 200.milliseconds

        /** Prises de parole dans [samples] (mono, [RATE] Hz), datées à partir de [offset]. */
        fun spans(samples: FloatArray, offset: Duration): List<TimeRange> {
            val frames = samples.size / FRAME
            val loud = BooleanArray(frames) { f ->
                var sum = 0.0
                for (j in 0 until FRAME) { val v = samples[f * FRAME + j].toDouble(); sum += v * v }
                sum / FRAME > THRESHOLD
            }
            val raw = mutableListOf<TimeRange>()
            var start = -1
            for (f in 0..frames) {
                val on = f < frames && loud[f]
                if (on && start < 0) start = f
                if (!on && start >= 0) { raw += TimeRange(offset + FRAME_LENGTH * start, offset + FRAME_LENGTH * f); start = -1 }
            }
            val merged = mutableListOf<TimeRange>()
            for (r in raw) {
                val last = merged.lastOrNull()
                if (last != null && r.start - last.end < BRIDGE) merged[merged.size - 1] = last.union(r) else merged += r
            }
            return merged.filter { it.length >= MIN_SPEECH }
        }
    }
}
