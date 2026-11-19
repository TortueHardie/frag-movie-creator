package dev.highlights.assistant

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.CropRegion
import dev.highlights.core.serialization.Durations
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.time.Duration

/** Image RVB de la capture, en pixels de la vidéo (3 octets par pixel). */
class RgbFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    fun r(x: Int, y: Int) = pixels[(y * width + x) * 3].toInt() and 0xFF
    fun g(x: Int, y: Int) = pixels[(y * width + x) * 3 + 1].toInt() and 0xFF
    fun b(x: Int, y: Int) = pixels[(y * width + x) * 3 + 2].toInt() and 0xFF
    fun gray(x: Int, y: Int) = (r(x, y) * 299 + g(x, y) * 587 + b(x, y) * 114) / 1000

    /** Zone normalisée → rectangle en pixels, dans l'image. */
    fun pixelsOf(region: CropRegion): PixelBox {
        val x0 = (region.x * width).roundToInt().coerceIn(0, width - 1)
        val y0 = (region.y * height).roundToInt().coerceIn(0, height - 1)
        val x1 = ((region.x + region.width) * width).roundToInt().coerceIn(x0 + 1, width)
        val y1 = ((region.y + region.height) * height).roundToInt().coerceIn(y0 + 1, height)
        return PixelBox(x0, y0, x1 - x0, y1 - y0)
    }

    fun toImage(box: PixelBox = PixelBox(0, 0, width, height), grayscale: Boolean = false): BufferedImage {
        val out = BufferedImage(box.width, box.height, if (grayscale) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB)
        for (y in 0 until box.height) for (x in 0 until box.width) {
            val px = box.x + x
            val py = box.y + y
            if (grayscale) {
                out.raster.setSample(x, y, 0, gray(px, py))
            } else {
                out.setRGB(x, y, (r(px, py) shl 16) or (g(px, py) shl 8) or b(px, py))
            }
        }
        return out
    }
}

/** Rectangle en pixels de la vidéo. */
data class PixelBox(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height

    /** Agrandi de [dx], [dy] pixels de chaque côté, sans sortir d'une image [w]x[h]. */
    fun grow(dx: Int, dy: Int, w: Int, h: Int): PixelBox {
        val x0 = (x - dx).coerceAtLeast(0)
        val y0 = (y - dy).coerceAtLeast(0)
        return PixelBox(x0, y0, (right + dx).coerceAtMost(w) - x0, (bottom + dy).coerceAtMost(h) - y0)
    }

    fun toRegion(w: Int, h: Int) = CropRegion(x.toDouble() / w, y.toDouble() / h, width.toDouble() / w, height.toDouble() / h)
}

/** Images de la capture à un instant, lues par FFmpeg (recherche rapide puis image exacte). */
class FrameGrabber(private val ffmpeg: FfmpegService, private val video: Path, private val width: Int, private val height: Int) {
    suspend fun at(time: Duration): RgbFrame {
        val size = width * height * 3
        var bytes = ByteArray(0)
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSeconds(if (time.isNegative()) Duration.ZERO else time), "-i", video.toString(),
                    "-map", "0:v:0", "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
                ),
                "image ${Durations.format(time)}",
            ),
            StdoutHandler.Binary { input -> bytes = input.readAllBytes() },
        )
        return if (bytes.size >= size) RgbFrame(width, height, bytes.copyOf(size)) else RgbFrame(width, height, ByteArray(size))
    }
}
