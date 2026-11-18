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

    test("bloc capacités : entier en 16:9 et en 21:9, hors du recadrage, collé dans la zone sûre de TikTok") {
        val block = vertical.hud.single { it.name == "abilities" }
        val r = ScreenGeometry.forVideo(block.source, vertical.reference, 1920, 1080, block.anchor)
        // 16:9 : de 518 à 1402 px (vie à gauche, munitions à droite), sur les 1080 lignes.
        (r.x * 1920) shouldBe (518.0 plusOrMinus 2.0)
        ((r.x + r.width) * 1920) shouldBe (1402.0 plusOrMinus 2.0)
        (r.y + r.height <= 1.0) shouldBe true
        // 21:9 : même taille par rapport à la hauteur, toujours centré.
        val wide = ScreenGeometry.forVideo(block.source, vertical.reference, 3440, 1440, block.anchor)
        (wide.width * 3440) shouldBe (883.2 * 1440 / 1080 plusOrMinus 2.0)
        (wide.x + wide.width / 2) shouldBe (0.5 plusOrMinus 0.001)
        // Le recadrage s'arrête au-dessus : la barre d'origine ne se voit pas en double sous la barre replacée.
        val crop = vertical.cropRegion!!
        (crop.y + crop.height <= block.source.y + 0.0001) shouldBe true
        // Zone sûre de TikTok (gauche 5 %, droite 14 %, bas 22 %) : le bloc replacé y tient en entier.
        val tiktok = dev.highlights.core.model.PlatformProfile.DEFAULTS.getValue("tiktok").safeArea
        val height = block.target.width * vertical.size.width * (block.source.height * 1080) / (block.source.width * 1920) / vertical.size.height
        (block.target.x >= tiktok.left) shouldBe true
        (block.target.x + block.target.width <= 1 - tiktok.right + 0.0001) shouldBe true
        (block.target.y + height <= 1 - tiktok.bottom + 0.0001) shouldBe true
    }

    test("zoom maximal (punch-in + approche) : réticule et icône du kill dans l'image, crâne hors du bloc replacé") {
        // Icône du kill sous le réticule : y 0,74 à 0,835 de la capture (mesuré sur 12 kills de « Valorant- 44 kills »).
        val skullTop = 0.74
        val skullBottom = 0.835
        val maxZoom = 1 + dev.highlights.core.model.ZoomEffect().amount + dev.highlights.core.model.FastMontage().zoomApproach
        val crop = vertical.cropRegion!!
        val focus = (vertical.zoomFocus!! - crop.y) / crop.height
        fun out(source: Double, zoom: Double) = focus + ((source - crop.y) / crop.height - focus) * zoom
        val block = vertical.hud.single { it.name == "abilities" }
        val blockBottom = block.target.y + block.target.width * vertical.size.width * (block.source.height * 1080) / (block.source.width * 1920) / vertical.size.height
        val barBottom = bar.target.width * vertical.size.width * (bar.source.height * 1080) / (bar.source.width * 1920) / vertical.size.height
        for (zoom in listOf(1.0, (1 + maxZoom) / 2, maxZoom)) {
            // Réticule sous le bandeau, crâne entier dans l'image et sous le bloc (jamais caché par lui).
            (out(0.5, zoom) > barBottom) shouldBe true
            (out(skullBottom, zoom) <= 1.0) shouldBe true
            (out(skullTop, zoom) >= blockBottom) shouldBe true
        }
        out(0.5, maxZoom) shouldBe (0.39 plusOrMinus 0.02)
    }

    test("21:9 : même bandeau en pixels, toujours centré") {
        val r = ScreenGeometry.forVideo(bar.source, vertical.reference, 3440, 1440, bar.anchor)
        // Le HUD suit la hauteur : 1152 px pour 1080 de haut font 1536 px pour 1440.
        (r.width * 3440) shouldBe (1536.0 plusOrMinus 2.0)
        (r.x + r.width / 2) shouldBe (0.5 plusOrMinus 0.001)
        r.y shouldBe bar.source.y
    }
})
