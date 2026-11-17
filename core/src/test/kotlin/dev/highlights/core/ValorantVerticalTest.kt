package dev.highlights.core

import dev.highlights.core.model.ScreenGeometry
import dev.highlights.core.profile.ProfileRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path

/**
 * Bandeau du haut de VALORANT en 9:16 (défaut 5 de la QA du 17/11) : mesuré en 16:9, il doit rester entier et centré
 * en 16:9 comme en 21:9, à la même taille en pixels par rapport à la hauteur, et le recadrage doit passer sous lui.
 */
class ValorantVerticalTest : FunSpec({
    val vertical = ProfileRepository.loadDirectory(Path("../config/profiles")).byId("valorant").edit.vertical
    val bar = vertical.hud.single { it.name == "topbar" }

    test("16:9 : bandeau entier et centré, recadrage sous lui") {
        val r = ScreenGeometry.forVideo(bar.source, vertical.reference, 1920, 1080, bar.anchor)
        (r.x * 1920) shouldBe (384.0 plusOrMinus 1.0)
        (r.width * 1920) shouldBe (1152.0 plusOrMinus 1.0)
        val crop = ScreenGeometry.forVideo(vertical.cropRegion!!, vertical.reference, 1920, 1080)
        (crop.y >= bar.source.y + bar.source.height) shouldBe true
    }

    test("21:9 : même bandeau en pixels, toujours centré") {
        val r = ScreenGeometry.forVideo(bar.source, vertical.reference, 3440, 1440, bar.anchor)
        // Le HUD suit la hauteur : 1152 px pour 1080 de haut font 1536 px pour 1440.
        (r.width * 3440) shouldBe (1536.0 plusOrMinus 2.0)
        (r.x + r.width / 2) shouldBe (0.5 plusOrMinus 0.001)
        r.y shouldBe bar.source.y
    }
})
