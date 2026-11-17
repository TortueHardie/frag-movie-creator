package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.editing.ZoomCrop
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Zoom animé rendu par FFmpeg : un carré rouge au centre d'une image de 200 px, zoom de 1 à 1,9 en 2 s. Il doit rester
 * au centre (le réticule ne glisse pas). Avec l'ancien recadrage `(iw-w)/2`, son centre passait de 100 à 150 dès ×1,5.
 */
class ZoomCenterIT : FunSpec({
    // Point visé au centre, puis plus haut (réticule d'un 9:16 décalé sous le bandeau) : il ne bouge pas.
    listOf(0.5, 0.3).forEach { focus ->
    test("le zoom animé garde fixe le point visé (y = $focus)").config(enabledIf = { TestMedia.available }, timeout = 60.seconds) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val size = 200
        val target = (focus * size).toInt()
        val vf = ZoomCrop.filters(size, size, "(1+0.45*t)", "bilinear", focusY = focus).joinToString(",")
        val bytes = java.io.ByteArrayOutputStream()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-f", "lavfi", "-i", "color=c=black:s=${size}x$size:r=10:d=2,drawbox=x=95:y=${target - 5}:w=10:h=10:color=red:t=fill",
                    "-vf", vf, "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
                ),
                "zoom centré de test",
            ),
            stdout = StdoutHandler.Binary { it.copyTo(bytes) },
        )
        val frame = size * size * 3
        val data = bytes.toByteArray()
        val frames = data.size / frame
        (frames >= 18) shouldBe true
        for (f in listOf(0, frames / 2, frames - 1)) {
            var sx = 0.0
            var sy = 0.0
            var n = 0
            for (y in 0 until size) for (x in 0 until size) {
                val r = data[f * frame + (y * size + x) * 3].toInt() and 0xFF
                if (r > 128) { sx += x; sy += y; n++ }
            }
            (sx / n) shouldBe (99.5 plusOrMinus 1.5)
            (sy / n) shouldBe ((target - 0.5) plusOrMinus 1.5)
        }
    }
    }
})
