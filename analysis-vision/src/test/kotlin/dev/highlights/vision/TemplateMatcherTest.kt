package dev.highlights.vision

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.random.Random

class TemplateMatcherTest : FunSpec({
    /** Croix claire 9x9 (traits de 3 px) sur fond donné. */
    fun cross(background: (Int, Int) -> Int = { _, _ -> 30 }, w: Int = 40, h: Int = 30, ox: Int = 12, oy: Int = 8): GrayImage {
        val px = ByteArray(w * h) { i -> background(i % w, i / w).toByte() }
        for (y in 0 until 9) for (x in 0 until 9) {
            if (x in 3..5 || y in 3..5) px[(oy + y) * w + ox + x] = 240.toByte()
        }
        return GrayImage(w, h, px)
    }
    val template = run {
        val c = cross(w = 9, h = 9, ox = 0, oy = 0)
        BrightTemplate(c, 200, "croix")
    }

    test("forme claire retrouvée malgré un décor bruité, position exacte") {
        val random = Random(1)
        val roi = cross(background = { _, _ -> random.nextInt(0, 180) })
        val m = BrightMatcher.match(roi, template)
        m.score shouldBe 1.0
        (m.x to m.y) shouldBe (12 to 8)
    }

    test("décor clair uniforme : score faible, pas de fausse détection") {
        val roi = GrayImage(40, 30, ByteArray(40 * 30) { 230.toByte() })
        BrightMatcher.match(roi, template).score shouldBeLessThan 0.75
    }

    test("autre forme claire : score nettement plus bas") {
        val px = ByteArray(40 * 30) { 20 }
        for (y in 8 until 17) for (x in 12 until 21) px[y * 40 + x] = 240.toByte() // carré plein
        BrightMatcher.match(GrayImage(40, 30, px), template).score shouldBeLessThan 0.8
    }

    test("NCC : correspondance parfaite sur la même image") {
        val img = cross()
        val tpl = PreparedTemplate(GrayImage(9, 9, ByteArray(81) { i -> img[12 + i % 9, 8 + i / 9].toByte() }), "croix")
        TemplateMatcher.match(img, tpl).score shouldBeGreaterThan 0.999
    }

    test("PNG en niveaux de gris lu sans conversion gamma") {
        val file = tempdir().toPath().resolve("gris.png")
        val image = BufferedImage(4, 1, BufferedImage.TYPE_BYTE_GRAY)
        listOf(0, 100, 128, 255).forEachIndexed { x, v -> image.raster.setSample(x, 0, 0, v) }
        ImageIO.write(image, "png", file.toFile())
        val loaded = GrayImage.load(file)
        (0 until 4).map { loaded[it, 0] } shouldBe listOf(0, 100, 128, 255)
    }

    test("redimensionnement") {
        val r = cross(w = 9, h = 9, ox = 0, oy = 0).resized(2.0)
        (r.width to r.height) shouldBe (18 to 18)
        r[8, 8] shouldBeGreaterThan 200
    }
})

private infix fun Int.shouldBeGreaterThan(other: Int) = this.toDouble() shouldBeGreaterThan other.toDouble()
