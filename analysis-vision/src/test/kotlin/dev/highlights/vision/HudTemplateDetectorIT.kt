package dev.highlights.vision

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Vidéo synthétique 1280x720 : un « HUD » (carré blanc) visible sauf de 20 s à 30 s, et une « icône de kill »
 * (croix blanche) affichée 3 s à partir de 12 s et de 41 s.
 */
class HudTemplateDetectorIT : FunSpec({
    test("événements et présence détectés sur une vraie vidéo, sans réencodage").config(enabledIf = { TestMedia.available }, timeout = 3.seconds * 60) {
        val dir = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = dir.resolve("hud.mp4")
        val draw = listOf(
            // HUD : carré blanc 30x30 en bas à droite, absent pendant le « menu »
            "drawbox=x=1220:y=660:w=30:h=30:color=white:t=fill:enable='not(between(t,20,30))'",
            // Icône : croix 30x30 (deux barres de 10 px) au centre
            "drawbox=x=625:y=400:w=30:h=10:color=white:t=fill:enable='between(t,12,15)+between(t,41,44)'",
            "drawbox=x=635:y=390:w=10:h=30:color=white:t=fill:enable='between(t,12,15)+between(t,41,44)'",
        ).joinToString(",")
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-y", "-f", "lavfi", "-i", "color=c=0x303030:s=1280x720:r=30:d=60", "-vf", draw,
                    "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p", video.toString(),
                ),
                "vidéo HUD de test",
            ),
        )

        fun png(name: String, w: Int, h: Int, lit: (Int, Int) -> Boolean) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            for (y in 0 until h) for (x in 0 until w) img.raster.setSample(x, y, 0, if (lit(x, y)) 255 else 48)
            ImageIO.write(img, "png", dir.resolve(name).toFile())
        }
        png("icon.png", 34, 34) { x, y -> (x in 2..31 && y in 12..21) || (x in 12..21 && y in 2..31) }
        png("hud.png", 34, 34) { x, y -> x in 2..31 && y in 2..31 }

        val media = ffmpeg.probe(video)
        val grid = WindowGrid(1.seconds, 500.milliseconds, media.duration)
        val registry = DetectorRegistry.fromServiceLoader()
        fun ctx() = AnalysisContext(media, grid, ffmpeg, dir, ProgressReporter.NONE, configDir = dir)

        val events = registry.create(
            "hud-template", "kills",
            paramsOf(
                """
                referenceHeight: 720
                hwaccel: null
                templates:
                  - name: kill
                    file: icon.png
                    method: bright
                    region: { x: ${600 / 1280.0}, y: ${370 / 720.0}, width: ${80 / 1280.0}, height: ${80 / 720.0} }
                    threshold: 0.8
                """,
            ),
        ).analyze(ctx())
        events.events.map { it.kind } shouldBe listOf("kill", "kill")
        (events.events[0].at.inWholeMilliseconds / 1000.0) shouldBe (12.5 plusOrMinus 1.0)
        (events.events[1].at.inWholeMilliseconds / 1000.0) shouldBe (41.5 plusOrMinus 1.0)

        val presence = registry.create(
            "hud-template", "in-game",
            paramsOf(
                """
                mode: presence
                presenceHold: 1s
                referenceHeight: 720
                hwaccel: null
                templates:
                  - name: hud
                    file: hud.png
                    method: bright
                    region: ${region(1200, 640, 70, 70)}
                    threshold: 0.8
                """,
            ),
        ).analyze(ctx())
        presence.raw[grid.indicesCovering(10.seconds).first] shouldBe 1.0
        presence.raw[grid.indicesCovering(25.seconds).first] shouldBe 0.0
        presence.raw[grid.indicesCovering(50.seconds).first] shouldBe 1.0
    }
})

private fun region(x: Int, y: Int, w: Int, h: Int) =
    CropRegion(x / 1280.0, y / 720.0, w / 1280.0, h / 720.0).let { "{ x: ${it.x}, y: ${it.y}, width: ${it.width}, height: ${it.height} }" }

private fun paramsOf(yaml: String) = dev.highlights.core.analysis.DetectorParams(
    dev.highlights.core.config.ConfigYaml.yaml.parseToYamlNode(yaml.trimIndent()),
)
