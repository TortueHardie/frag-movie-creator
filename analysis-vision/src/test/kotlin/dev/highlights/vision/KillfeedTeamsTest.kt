package dev.highlights.vision

import dev.highlights.core.video.ZoneFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KillfeedTeamsTest : FunSpec({
    // Zone 330x118 comme en 480p : colonne de la victime à 63-37 px du bord droit, lignes de 16 px au pas de 17,6.
    val reader = TeamRowReader(from = 63, to = 37, pitch = 17.6)
    val ally: Byte = 255.toByte()
    val enemy: Byte = 128.toByte()
    val w = 330
    val h = 118

    /** Lignes du killfeed : (haut, couleur du tueur, couleur de la victime) ; tueur de 150 à 260, victime de 260 à 300. */
    fun frame(vararg rows: Triple<Int, Byte?, Byte>): ZoneFrame {
        val pixels = ByteArray(w * h)
        for ((top, killer, victim) in rows) for (y in top until top + 16) {
            if (killer != null) for (x in 150 until 260) pixels[y * w + x] = killer
            for (x in 260 until 300) pixels[y * w + x] = victim
        }
        return ZoneFrame(w, h, pixels)
    }

    test("la couleur de la victime donne son équipe") {
        val rows = reader.rows(frame(Triple(6, enemy, ally), Triple(24, ally, enemy)))
        rows.map { it.team } shouldBe listOf(FeedTeam.ALLY, FeedTeam.ENEMY)
    }

    test("deux lignes de la même équipe l'une sous l'autre en font deux") {
        // Sans écart entre elles : une bande de 34 px, deux lignes.
        val f = frame(Triple(6, enemy, ally), Triple(22, enemy, ally))
        val rows = reader.rows(f)
        rows.size shouldBe 2
        rows.all { it.team == FeedTeam.ALLY } shouldBe true
    }

    test("un panneau uni de l'interface, sans tueur de l'autre couleur, n'est pas une ligne") {
        reader.rows(frame(Triple(6, ally, ally))).shouldBeEmpty()
        reader.rows(frame(Triple(6, null, ally))).shouldBeEmpty()
    }

    test("une bande trop haute pour un nombre entier de lignes est du décor") {
        val pixels = ByteArray(w * h)
        for (y in 0 until 26) for (x in 150 until 300) pixels[y * w + x] = if (x < 260) enemy else ally
        reader.rows(ZoneFrame(w, h, pixels)).shouldBeEmpty()
    }

    test("une ligne qui monte n'est comptée qu'une fois, le décor bref pas du tout") {
        val tracker = TeamTracker(tolerance = 4, minSeen = 800.milliseconds, maxGap = 3.seconds, pendingGap = 500.milliseconds)
        // Une mort alliée en bas, qui monte d'une ligne au bout de 2 s ; une tache rouge vue 0,4 s.
        for (i in 0 until 25) {
            val t = (i * 200).milliseconds
            val rows = mutableListOf(TeamRow(FeedTeam.ALLY, if (t < 2.seconds) 31.0 else 14.0))
            if (i in 3..4) rows += TeamRow(FeedTeam.ENEMY, 60.0)
            tracker.add(t, rows)
        }
        tracker.deaths() shouldBe listOf(FeedTeam.ALLY to 0.seconds)
    }

    test("une nouvelle ligne sous une autre de la même équipe est une autre mort") {
        val tracker = TeamTracker(tolerance = 4, minSeen = 800.milliseconds, maxGap = 3.seconds, pendingGap = 500.milliseconds)
        for (i in 0 until 20) {
            val t = (i * 200).milliseconds
            tracker.add(t, listOfNotNull(TeamRow(FeedTeam.ALLY, 14.0), if (t >= 1.seconds) TeamRow(FeedTeam.ALLY, 31.0) else null))
        }
        tracker.deaths() shouldBe listOf(FeedTeam.ALLY to 0.seconds, FeedTeam.ALLY to 1.seconds)
    }
})
