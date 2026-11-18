package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.editing.HudPin
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.ZoomCrop
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Rendu FFmpeg : trame empilée comme la rend `videoChain(pinHud = true)` : image de 200 px avec un carré vert au centre
 * (le jeu), puis dessous le bandeau rouge (200x30, le HUD). Zoom ×1 à ×1,9 : le bandeau est recollé en haut, à sa
 * taille, et nulle part ailleurs (aucune copie zoomée qui dépasse) ; le carré vert, lui, grossit.
 */
class HudPinIT : FunSpec({
    test("le bandeau recollé reste fixe pendant le zoom, le jeu zoome").config(enabledIf = { TestMedia.available }, timeout = 60.seconds) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val size = 200
        val (lines, out) = HudPin.statements(
            "0:v", emptyList(), ZoomCrop.filters(size, size, "(1+0.45*t)", "bilinear"),
            listOf(RenderCommandBuilder.CropBox(0, 0, size, 30)), size, size, "hp",
        )!!
        val graph = (lines + "[$out]format=rgb24[vout]").joinToString(";")
        val bytes = java.io.ByteArrayOutputStream()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-f", "lavfi", "-i",
                    "color=c=black:s=${size}x${size + 30}:r=10:d=2,drawbox=x=0:y=200:w=200:h=30:color=red:t=fill,drawbox=x=90:y=90:w=20:h=20:color=lime:t=fill",
                    "-filter_complex", graph, "-map", "[vout]", "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
                ),
                "HUD fixe de test",
            ),
            stdout = StdoutHandler.Binary { it.copyTo(bytes) },
        )
        val frame = size * size * 3
        val data = bytes.toByteArray()
        val last = data.size / frame - 1
        fun count(f: Int, channel: Int) = (0 until size * size).count { p ->
            val px = f * frame + p * 3
            (data[px + channel].toInt() and 0xFF) > 128 && (data[px + (channel + 1) % 3].toInt() and 0xFF) < 100
        }
        // Bandeau : même surface au début et à la fin (6 000 px), celle du seul bandeau recollé en haut.
        count(0, 0).toDouble() shouldBe (6000.0 plusOrMinus 200.0)
        count(last, 0).toDouble() shouldBe (6000.0 plusOrMinus 200.0)
        // Jeu : le carré vert grossit (×1,9 en surface ×3,6).
        (count(last, 1) > count(0, 1) * 3) shouldBe true
    }
})
