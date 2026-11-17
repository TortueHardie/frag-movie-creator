package dev.highlights.editing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

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
})
