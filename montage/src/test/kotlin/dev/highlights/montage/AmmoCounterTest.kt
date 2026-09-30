package dev.highlights.montage

import dev.highlights.core.model.AmmoHud
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.TimeRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AmmoCounterTest : FunSpec({
    val hud = AmmoHud(CropRegion(0.65, 0.925, 0.05, 0.045))
    val w = 12
    val h = 6

    /** Image du compteur : [digits] allume des colonnes différentes selon la valeur, sur un fond [background]. */
    fun frame(value: Int, background: Int = 40): ByteArray = ByteArray(w * h) { i ->
        val column = i % w
        val lit = (column + value) % 3 == 0
        (if (lit) 250 else background).toByte()
    }

    test("une balle par changement du compteur, même affiché sur deux images") {
        // 25 pendant 10 images, 24 (transition sur deux images), puis 23 six images plus tard.
        val frames = List(10) { frame(25) } + frame(24) + List(6) { frame(24) } + List(5) { frame(23) }
        AmmoCounter.shots(frames, w * h, hud) shouldBe listOf(10, 17)
    }

    test("un fond qui bouge sans passer au blanc ne compte pas") {
        val frames = List(20) { frame(12, background = 40 + it * 5) }
        AmmoCounter.shots(frames, w * h, hud) shouldBe emptyList()
    }

    test("compteur éteint (couteau, capacité) : aucune balle") {
        val frames = List(20) { ByteArray(w * h) { 30 } }
        AmmoCounter.shots(frames, w * h, hud) shouldBe emptyList()
    }

    test("fenêtre bornée à mi-chemin des kills voisins") {
        val kills = listOf(100.seconds, 100.4.seconds, 110.seconds)
        AmmoCounter.window(100.4.seconds, kills, hud) shouldBe TimeRange(100.2.seconds, 100.4.seconds + 300.milliseconds)
        AmmoCounter.window(110.seconds, kills, hud) shouldBe TimeRange(109.2.seconds, 110.3.seconds)
        AmmoCounter.window(0.5.seconds, listOf(0.5.seconds), hud).start shouldBe 0.seconds
    }
})
