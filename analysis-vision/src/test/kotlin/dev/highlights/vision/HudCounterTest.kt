package dev.highlights.vision

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

class HudCounterTest : FunSpec({
    val decoder = CounterDecoder(maxValue = 60, maxStep = 5, stepCost = 3.0, resetCost = 25.0, missCost = 1.0)
    fun kills(path: IntArray) = (1 until path.size).sumOf { maxOf(0, path[it] - path[it - 1]) }

    test("décodage : une lecture fausse isolée ne crée pas de kill, un palier tenu si") {
        // 4 tenu, un « 8 » lu une image (2 pris pour 3 sur fond clair), puis 5 tenu.
        val reads = List(10) { 4 } + listOf(8) + List(3) { 4 } + List(10) { 5 }
        val path = decoder.decode(reads)
        kills(path) shouldBe 1
        CounterDecoder.changes(path).map { it.second to it.third } shouldBe listOf(4 to 5)
        path[14] shouldBe 5
    }

    test("décodage : illisible au milieu, saut de quatre (équipe entière) gardé") {
        val reads = List(8) { 0 } + List(4) { null } + List(8) { 4 }
        kills(decoder.decode(reads)) shouldBe 4
    }

    test("décodage : retour à zéro tenu = nouvelle partie, pas une perte ; zéros lus « 2 » du salon ignorés") {
        val salon = List(30) { if (it % 4 == 1) 2 else 0 }
        val reads = List(10) { 10 } + List(10) { 11 } + salon + List(10) { 1 } + List(10) { 2 }
        val path = decoder.decode(reads)
        kills(path) shouldBe 3
        CounterDecoder.changes(path).map { it.second to it.third } shouldBe listOf(10 to 11, 11 to 0, 0 to 1, 1 to 2)
    }

    test("décodage : une valeur fausse tenue quelques images puis démentie ne compte pas (13 lu 18)") {
        val reads = List(20) { 13 } + List(4) { 18 } + List(20) { 13 }
        kills(decoder.decode(reads)) shouldBe 0
    }

    test("lecture : nombre calé à droite, une ou deux positions, décalé d'un pixel") {
        // Chiffres synthétiques 5x7 (3x7 pour le 1), plus une colonne de marge : pixels allumés tirés au sort par chiffre.
        fun glyph(d: Int): GrayImage {
            val w = if (d == 1) 5 else 7
            val h = 9
            val random = java.util.Random(d.toLong())
            return GrayImage(w, h, ByteArray(w * h) { i ->
                val x = i % w
                val y = i / w
                val on = x in 1 until w - 1 && y in 1..7 && random.nextBoolean()
                (if (on) 230 else 60).toByte()
            })
        }
        val digits = (0..9).associateWith(::glyph)
        val reader = CounterReader(digits, threshold = 0.9, slack = 4)
        fun canvas(value: Int, shift: Int): GrayImage {
            val w = 19
            val h = 11
            val px = ByteArray(w * h) { 60 }
            var right = w - 3 - shift // bord droit du dernier chiffre (avant sa marge)
            for (c in value.toString().reversed()) {
                val g = digits.getValue(c.digitToInt())
                val x0 = right + 2 - g.width
                for (y in 0 until g.height) for (x in 0 until g.width) px[(y + 1) * w + x0 + x] = g.pixels[y * g.width + x]
                right = x0 + 1 - 2 // une colonne d'écart avant le chiffre suivant
            }
            return GrayImage(w, h, px)
        }
        for (v in listOf(0, 4, 7, 10, 11, 13, 29)) for (shift in 0..1) {
            reader.read(canvas(v, shift))?.value shouldBe v
        }
        reader.read(GrayImage(19, 11, ByteArray(19 * 11) { 60 })) shouldBe null
    }

    test("lecture : chiffres du profil Fortnite lisibles l'un sur l'autre") {
        val dir = Path.of("../config/templates/fortnite")
        if (dir.exists()) {
            val reader = CounterReader.load(dir, 0.75, 4)
            // Chaque chiffre posé seul au bord droit d'une zone de 19x11 sur fond uniforme se relit lui-même.
            for (d in 0..9) {
                val g = GrayImage.load(dir.resolve("digit_$d.png"))
                val w = 19
                val h = 11
                val bg = g[0, 0].toByte()
                val px = ByteArray(w * h) { bg }
                val x0 = w - 1 - g.width
                for (y in 0 until g.height) for (x in 0 until g.width) px[(y + 1) * w + x0 + x] = g.pixels[y * g.width + x]
                val read = reader.read(GrayImage(w, h, px))
                read shouldNotBe null
                read!!.value shouldBe d
            }
        }
    }

    test("mises à terre : le kill est à la mise à terre, l'élimination qui achève ne compte pas une seconde fois") {
        val s = { x: Double -> x.seconds }
        // 366 et 370 à terre, achevés à 382,5 et 388 : deux kills, à 366 et 370 (la plus ancienne d'abord).
        // 578,5 sans mise à terre avant : kill à 578,5. 693 à terre jamais crédité : kill quand même.
        val pairing = KnockPairing.pair(
            increments = listOf(s(382.5) to 1, s(388.0) to 1, s(578.5) to 1),
            knocks = listOf(s(370.0), s(366.0), s(693.0)),
            window = 30.seconds, after = 1.seconds, unpaired = true,
        )
        pairing.kills shouldBe listOf(s(366.0), s(370.0), s(578.5), s(693.0))
        pairing.paired shouldBe 2
        pairing.lone shouldBe 1
        pairing.free shouldBe 1
        KnockPairing.pair(listOf(s(382.5) to 1), listOf(s(366.0), s(693.0)), 30.seconds, 1.seconds, unpaired = false).kills shouldBe listOf(s(366.0))
    }

    test("mises à terre : une équipe éliminée d'un coup reste un quadruplé à l'instant du compteur, ses mises à terre absorbées") {
        val s = { x: Double -> x.seconds }
        val pairing = KnockPairing.pair(listOf(s(39.5) to 4), listOf(s(23.0), s(27.0), s(32.0)), 30.seconds, 1.seconds, unpaired = true)
        pairing.kills shouldBe List(4) { s(39.5) }
        pairing.free shouldBe 0
        pairing.wipes shouldBe 1
    }

    test("mises à terre : hors fenêtre, rien n'est rattaché ; la notification vue juste après le compteur l'est") {
        val s = { x: Double -> x.seconds }
        KnockPairing.pair(listOf(s(100.0) to 1), listOf(s(60.0)), 30.seconds, 1.seconds, unpaired = false).kills shouldBe listOf(s(100.0))
        KnockPairing.pair(listOf(s(1172.5) to 1), listOf(s(1173.0)), 30.seconds, 1.seconds, unpaired = false).kills shouldBe listOf(s(1173.0))
    }

    test("lecture : un chiffre absent du dossier (jamais vu par l'assistant) est illisible, les autres se lisent") {
        val src = Path.of("../config/templates/fortnite")
        if (src.exists()) {
            val dir = java.nio.file.Files.createTempDirectory("chiffres")
            (0..9).filter { it != 6 }.forEach { java.nio.file.Files.copy(src.resolve("digit_$it.png"), dir.resolve("digit_$it.png")) }
            val reader = CounterReader.load(dir, 0.75, 4)
            val g = GrayImage.load(src.resolve("digit_3.png"))
            val px = ByteArray(19 * 11) { g[0, 0].toByte() }
            val x0 = 19 - 1 - g.width
            for (y in 0 until g.height) for (x in 0 until g.width) px[(y + 1) * 19 + x0 + x] = g.pixels[y * g.width + x]
            reader.read(GrayImage(19, 11, px))?.value shouldBe 3
        }
    }
})
