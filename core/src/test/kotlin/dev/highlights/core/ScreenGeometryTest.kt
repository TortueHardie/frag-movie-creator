package dev.highlights.core

import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.RegionAnchor
import dev.highlights.core.model.ScreenGeometry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class ScreenGeometryTest : FunSpec({
    val ultrawide = 3440.0 / 1440 // 2,389:1
    val wide = 1920.0 / 1080 // 1,778:1

    /** Distance au bord gauche en pixels, pour une image de hauteur 1080. */
    fun leftPx(region: CropRegion, aspect: Double) = region.x * aspect * 1080
    fun rightPx(region: CropRegion, aspect: Double) = (1 - region.x - region.width) * aspect * 1080
    fun widthPx(region: CropRegion, aspect: Double) = region.width * aspect * 1080

    test("même format : zone inchangée") {
        val region = CropRegion(0.78, 0.03, 0.2, 0.18)
        ScreenGeometry.rescale(region, ultrawide, ultrawide) shouldBe region
        ScreenGeometry.forVideo(region, null, 1920, 1080) shouldBe region
    }

    test("élément collé à droite : même distance au bord et même taille en pixels") {
        val measured = CropRegion(0.786458, 0.037360, 0.208333, 0.186800)
        val converted = ScreenGeometry.rescale(measured, ultrawide, wide, RegionAnchor.RIGHT)

        rightPx(converted, wide) shouldBe (rightPx(measured, ultrawide) plusOrMinus 0.5)
        widthPx(converted, wide) shouldBe (widthPx(measured, ultrawide) plusOrMinus 0.5)
        // En 16:9 la zone occupe une plus grande part de la largeur, et commence plus à gauche.
        converted.width shouldBe (measured.width * ultrawide / wide plusOrMinus 1e-9)
        converted.y shouldBe measured.y
        converted.height shouldBe measured.height
    }

    test("élément collé à gauche : même distance au bord gauche") {
        val measured = CropRegion(0.008, 0.445, 0.13, 0.12)
        val converted = ScreenGeometry.rescale(measured, ultrawide, wide, RegionAnchor.LEFT)
        leftPx(converted, wide) shouldBe (leftPx(measured, ultrawide) plusOrMinus 0.5)
        widthPx(converted, wide) shouldBe (widthPx(measured, ultrawide) plusOrMinus 0.5)
    }

    test("élément centré : il reste centré") {
        val measured = CropRegion(0.482558, 0.621528, 0.034884, 0.076389)
        val converted = ScreenGeometry.rescale(measured, ultrawide, wide, RegionAnchor.CENTER)
        (converted.x + converted.width / 2) shouldBe (0.5 plusOrMinus 1e-9)
        widthPx(converted, wide) shouldBe (widthPx(measured, ultrawide) plusOrMinus 0.5)
    }

    test("ancrage déduit : bord le plus proche, centre si la zone est centrée") {
        ScreenGeometry.resolveAnchor(RegionAnchor.AUTO, CropRegion(0.80, 0.0, 0.18, 0.1)) shouldBe RegionAnchor.RIGHT
        ScreenGeometry.resolveAnchor(RegionAnchor.AUTO, CropRegion(0.01, 0.0, 0.10, 0.1)) shouldBe RegionAnchor.LEFT
        ScreenGeometry.resolveAnchor(RegionAnchor.AUTO, CropRegion(0.4826, 0.6, 0.0349, 0.07)) shouldBe RegionAnchor.CENTER
        // Un ancrage explicite n'est jamais deviné.
        ScreenGeometry.resolveAnchor(RegionAnchor.LEFT, CropRegion(0.9, 0.0, 0.05, 0.1)) shouldBe RegionAnchor.LEFT
    }

    test("passage d'un 16:9 à un ultrawide : la zone rétrécit en proportion") {
        val measured = CropRegion(0.0, 0.0, 0.25, 0.2)
        val converted = ScreenGeometry.rescale(measured, wide, ultrawide, RegionAnchor.LEFT)
        converted.width shouldBe (0.25 * wide / ultrawide plusOrMinus 1e-9)
    }

    test("zone qui déborde après conversion : ramenée dans l'image") {
        val measured = CropRegion(0.0, 0.0, 0.9, 0.2)
        val converted = ScreenGeometry.rescale(measured, ultrawide, wide, RegionAnchor.RIGHT)
        (converted.x >= 0.0) shouldBe true
        (converted.x + converted.width <= 1.0) shouldBe true
    }

    test("résolution de référence : conversion à partir d'une taille de capture") {
        val measured = CropRegion(0.95, 0.9, 0.016, 0.027)
        val converted = ScreenGeometry.forVideo(measured, FrameSize(3440, 1440), 1920, 1080)
        rightPx(converted, wide) shouldBe (rightPx(measured, ultrawide) plusOrMinus 0.5)
    }
})
