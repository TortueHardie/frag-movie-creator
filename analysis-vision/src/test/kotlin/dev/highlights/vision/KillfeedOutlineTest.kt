package dev.highlights.vision

import dev.highlights.core.video.ZoneFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

class KillfeedOutlineTest : FunSpec({
    // Réglages par défaut (mesurés en 480p), lus à l'échelle 1.
    val reader = OutlineReader(threshold = 25, bridge = 2, shape = OutlineParams())
    val red = 60.toByte()
    val w = 300
    val h = 120

    /** Contour d'une ligne CS2 : traits de 2 px en haut et en bas, côtés de 1 px (absents si [sides] est faux). */
    fun outline(pixels: ByteArray, left: Int, top: Int, width: Int, height: Int = 16, sides: Boolean = true) {
        for (y in top until top + height) for (x in left until left + width) {
            val bar = y - top < 2 || top + height - y <= 2
            val side = sides && (x == left || x == left + width - 1)
            if (bar || side) pixels[y * w + x] = red
        }
    }

    /** Fond plein d'une mort : toute la ligne colorée, sauf des lettres blanches (un pixel sur quatre). */
    fun filled(pixels: ByteArray, left: Int, top: Int, width: Int, height: Int = 16) {
        for (y in top until top + height) for (x in left until left + width) if ((x + y) % 4 != 0) pixels[y * w + x] = red
    }

    test("une ligne encadrée est un kill, une ligne sur fond plein une mort") {
        val pixels = ByteArray(w * h)
        outline(pixels, 150, 33, 140)
        filled(pixels, 180, 70, 110)
        val rows = reader.rows(ZoneFrame(w, h, pixels)).sortedBy { it.top }
        rows.map { it.role } shouldBe listOf(KillfeedRole.KILL, KillfeedRole.DEATH)
        rows[0].top shouldBe 33
        rows[0].bottom shouldBe 49
        rows[0].left shouldBe 150
        rows[1].top shouldBe 70
    }

    test("les côtés effacés par un fond clair ne gênent pas : deux traits suffisent") {
        val pixels = ByteArray(w * h)
        outline(pixels, 150, 33, 140, sides = false)
        reader.rows(ZoneFrame(w, h, pixels)).map { it.role } shouldBe listOf(KillfeedRole.KILL)
    }

    test("deux lignes du joueur l'une sur l'autre (doublé) sont deux kills") {
        val pixels = ByteArray(w * h)
        // La seconde commence juste sous la première : trait du bas et trait du haut se touchent (bande de 4 px).
        outline(pixels, 160, 33, 130)
        outline(pixels, 120, 49, 170)
        val rows = reader.rows(ZoneFrame(w, h, pixels)).sortedBy { it.top }
        rows.map { it.role } shouldBe listOf(KillfeedRole.KILL, KillfeedRole.KILL)
        // La seconde est datée de son trait du haut, à 2 px près (la bande commune commence au bas de la première).
        rows.map { it.top } shouldBe listOf(33, 47)
        rows.map { it.left } shouldBe listOf(160, 120)
    }

    test("un trait seul, un aplat trop haut ou des traits de largeurs différentes ne sont pas des lignes") {
        val pixels = ByteArray(w * h)
        // Trait isolé (rebord rouge d'un toit).
        for (y in 5 until 7) for (x in 100 until 250) pixels[y * w + x] = red
        // Aplat rouge de 40 px de haut (panneau, écran rouge de dégâts).
        for (y in 30 until 70) for (x in 100 until 250) pixels[y * w + x] = red
        // Deux traits de largeurs très différentes à 16 px l'un de l'autre.
        for (y in 90 until 92) for (x in 100 until 290) pixels[y * w + x] = red
        for (y in 104 until 106) for (x in 240 until 290) pixels[y * w + x] = red
        reader.rows(ZoneFrame(w, h, pixels)).shouldBeEmpty()
    }

    test("un trait collé au bord de l'écran ou loin de lui n'est pas une ligne du killfeed") {
        val pixels = ByteArray(w * h)
        outline(pixels, 150, 33, 150)
        outline(pixels, 100, 70, 100)
        reader.rows(ZoneFrame(w, h, pixels)).shouldBeEmpty()
    }

    test("une ligne plus longue qui prend la place d'une autre est un nouveau kill") {
        fun row(left: Int) = FeedRow(KillfeedRole.KILL, 33, 49, left, 289)
        fun appearances(growth: Int?): Int {
            val tracker = FeedTracker(12, 3, kotlin.time.Duration.parse("3s"), kotlin.time.Duration.parse("10s"), newLineGrowth = growth)
            // Ligne courte (bord gauche mesuré entre 200 et 230 : son trait s'efface par morceaux), puis une longue au même endroit.
            for (i in 0 until 20) tracker.add((i * 100).milliseconds, listOf(row(if (i % 3 == 0) 230 else 200)))
            for (i in 20 until 40) tracker.add((i * 100).milliseconds, listOf(row(120)))
            return tracker.appearances().size
        }
        appearances(45) shouldBe 2
        appearances(null) shouldBe 1
    }
})
