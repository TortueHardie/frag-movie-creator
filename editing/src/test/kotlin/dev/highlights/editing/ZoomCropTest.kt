package dev.highlights.editing

import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.model.VideoStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

class ZoomCropTest : FunSpec({
    test("le recadrage d'un zoom animé se recalcule à partir du zoom, jamais de iw (figé à la première image)") {
        val crop = ZoomCrop.crop(1080, 1920, "(1+0.2*t)")
        crop shouldBe "crop=1080:1920:x='(trunc(1080*(1+0.2*t)/2)*2-1080)/2':y='(trunc(1920*(1+0.2*t)/2)*2-1920)/2'"
        crop shouldNotContain "iw"
        ZoomCrop.filters(100, 100, "z", "bicubic", dx = "d") shouldBe listOf(
            "scale=w='trunc(100*z/2)*2':h='trunc(100*z/2)*2':eval=frame:flags=bicubic",
            """crop=100:100:x='clip((trunc(100*z/2)*2-100)/2+d\,0\,(trunc(100*z/2)*2-100))':y='(trunc(100*z/2)*2-100)/2'""",
        )
    }

    test("zoom vers un point hors du centre : la marge retirée avant lui suit sa place dans le cadre") {
        ZoomCrop.crop(100, 100, "z", focusY = 0.4444) shouldBe
            """crop=100:100:x='(trunc(100*z/2)*2-100)/2':y='clip((trunc(100*z/2)*2-100)*0.4444\,0\,(trunc(100*z/2)*2-100))'"""
    }

    test("réticule : au centre du cadre, sauf en 9:16 décalé (sous le bandeau du haut)") {
        val media = MediaInfo(Path("x.mp4"), 1, 10.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
        RenderCommandBuilder.crosshair(OutputFormat.VERTICAL, media, EditSettings()) shouldBe (0.5 to 0.5)
        RenderCommandBuilder.crosshair(OutputFormat.SOURCE, media, EditSettings()) shouldBe (0.5 to 0.5)
        // Recadrage de 1080 px de haut à partir de 108 px (10 % sous le haut) : le centre de la capture, à 540 px, est à
        // 432 px du haut du cadre de 972 px, soit 0,444.
        val shifted = EditSettings(vertical = VerticalSettings(cropRegion = CropRegion(0.0, 0.1, 1.0, 0.9)))
        val (x, y) = RenderCommandBuilder.crosshair(OutputFormat.VERTICAL, media, shifted)
        x shouldBe (0.5 plusOrMinus 0.002)
        y shouldBe (0.4444 plusOrMinus 0.002)
    }
})
