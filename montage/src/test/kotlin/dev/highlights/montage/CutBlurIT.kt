package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * Flou radial des coupes rendu par FFmpeg ([MontageRenderBuilder.cutBlurStages]) : seules les k premières et k
 * dernières images du plan changent, le milieu reste identique image pour image, et le plan garde son nombre d'images.
 */
class CutBlurIT : FunSpec({
    test("le flou de coupe ne touche que k images de chaque côté").config(enabledIf = { TestMedia.available }, timeout = 60.seconds) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val (w, h, fps, frames, k) = listOf(160, 96, 60, 40, 3)
        val dir = Files.createTempDirectory("flou-coupe")

        suspend fun render(blur: Boolean): List<ByteArray> {
            val stages = if (blur) MontageRenderBuilder.cutBlurStages("q", "v", "k", frames, k, true, true, w, h, fps, 0.08) else emptyList()
            val head = "testsrc2=s=${w}x$h:r=$fps:d=1,trim=end_frame=$frames,format=yuv420p,settb=AVTB" + if (blur) "[q]" else "[v]"
            val script = dir.resolve(if (blur) "avec.txt" else "sans.txt").also { it.writeText((listOf(head) + stages).joinToString(";\n")) }
            val out = java.io.ByteArrayOutputStream()
            ffmpeg.run(
                FfmpegCommand(listOf("-/filter_complex", script.toString(), "-map", "[v]", "-f", "rawvideo", "-pix_fmt", "gray", "-"), "flou de coupe de test"),
                stdout = StdoutHandler.Binary { it.copyTo(out) },
            )
            val bytes = out.toByteArray()
            return (0 until bytes.size / (w * h)).map { bytes.copyOfRange(it * w * h, (it + 1) * w * h) }
        }

        val sharp = render(false)
        val blurred = render(true)
        blurred.size shouldBe frames
        val changed = sharp.zip(blurred).mapIndexedNotNull { i, (a, b) ->
            val diff = a.indices.sumOf { abs((a[it].toInt() and 0xFF) - (b[it].toInt() and 0xFF)) }.toDouble() / a.size
            i.takeIf { diff > 0.5 }
        }
        changed shouldBe (0 until k).toList() + (frames - k until frames).toList()
    }
})
