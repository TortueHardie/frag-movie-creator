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
    val enemy: Byte = 191.toByte()
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
        tracker.deaths() shouldBe listOf(TeamDeath(FeedTeam.ALLY, 0.seconds))
    }

    test("une nouvelle ligne sous une autre de la même équipe est une autre mort") {
        val tracker = TeamTracker(tolerance = 4, minSeen = 800.milliseconds, maxGap = 3.seconds, pendingGap = 500.milliseconds)
        for (i in 0 until 20) {
            val t = (i * 200).milliseconds
            tracker.add(t, listOfNotNull(TeamRow(FeedTeam.ALLY, 14.0), if (t >= 1.seconds) TeamRow(FeedTeam.ALLY, 31.0) else null))
        }
        tracker.deaths() shouldBe listOf(TeamDeath(FeedTeam.ALLY, 0.seconds), TeamDeath(FeedTeam.ALLY, 1.seconds))
    }

    test("une mort affichée à la place d'une ligne effacée est une autre mort (durée de vie des lignes)") {
        // Clip 2580x1080 du 10/01 : Tejo tué à 15,8 s, ligne effacée vers 20,6 s ; Jett tué à 21,3 s, même place.
        fun deaths(expiry: LineExpiry?): Int {
            val tracker = TeamTracker(tolerance = 6, minSeen = 800.milliseconds, maxGap = 3.seconds, pendingGap = 500.milliseconds, expiry = expiry)
            for (i in 0..24) tracker.add(15.8.seconds + (200 * i).milliseconds, listOf(TeamRow(FeedTeam.ENEMY, 20.0)))
            for (i in 0..15) tracker.add(21.3.seconds + (200 * i).milliseconds, listOf(TeamRow(FeedTeam.ENEMY, 20.0)))
            return tracker.deaths().size
        }
        deaths(null) shouldBe 1
        deaths(LineExpiry(5300.milliseconds)) shouldBe 2
    }

    test("le nom de la victime qui traverse la colonne ne coupe pas sa ligne") {
        val f = frame(Triple(24, enemy, ally))
        val light: Byte = 63.toByte()
        // Lettres claires sur les deux tiers de la colonne, au milieu de la ligne.
        for (y in 29 until 35) for (x in 267 until 284) f.pixels[y * w + x] = light
        reader.rows(f).map { it.team } shouldBe listOf(FeedTeam.ALLY)
    }

    test("le cercle de Phoenix déborde de la ligne en haut et en bas ; l'icône de tir à la tête, non") {
        val white: Byte = 127.toByte()
        fun withIcon(top: Int, bottom: Int): ZoneFrame {
            val f = frame(Triple(24, enemy, ally))
            for (y in top until bottom) for (x in 250 until 262) if (y < 24 || y >= 40 || x == 250 || x == 261) f.pixels[y * w + x] = white
            return f
        }
        reader.rows(withIcon(22, 42)).single().ring shouldBe true
        reader.rows(withIcon(27, 37)).single().ring shouldBe false
    }

    test("une ligne où le cercle revient sur une part des images est une mort de Phoenix pendant son ultime") {
        val tracker = TeamTracker(tolerance = 4, minSeen = 800.milliseconds, maxGap = 3.seconds, pendingGap = 500.milliseconds, ringSightings = 4, ringShare = 0.2)
        // Cercle sur 6 images sur 20 (il clignote) ; une autre ligne ne l'a qu'une fois (icône de tir à la tête).
        for (i in 0 until 20) tracker.add((i * 200).milliseconds, listOf(TeamRow(FeedTeam.ALLY, 14.0, ring = i % 3 == 0 && i < 18), TeamRow(FeedTeam.ENEMY, 31.0, ring = i == 5)))
        tracker.deaths() shouldBe listOf(TeamDeath(FeedTeam.ALLY, 0.seconds, revived = true), TeamDeath(FeedTeam.ENEMY, 0.seconds))
    }

    test("début de round : barres de vie alliées pleines 3 s après au moins 10 s sans l'être") {
        val times = (0 until 200).map { (it * 500).milliseconds }
        // Pleines au début (premier round, pas compté), vides de 5 à 40 s, pleines de 40 à 60 s, un éclair plein à 70 s.
        val full = times.map { it < 5.seconds || it in 40.seconds..60.seconds || it in 70.seconds..71.seconds }
        roundStarts(times, full, held = 3.seconds, before = 10.seconds) shouldBe listOf(40.seconds)
    }

    test("barres pleines : assez de colonnes allumées") {
        val f = ZoneFrame(10, 2, ByteArray(20).also { for (x in 0 until 6) it[x] = 255.toByte() })
        fullBar(f, 0.5) shouldBe true
        fullBar(f, 0.7) shouldBe false
    }

    test("bandeau : compté une fois, s'il reste reconnu au moins 1 s") {
        val times = (0 until 40).map { (it * 200).milliseconds }
        // Reconnu de 1 à 2,4 s (le bandeau), puis un éclair de 0,4 s à 5 s.
        val seen = times.map { it in 1.seconds..2.4.seconds || it in 5.seconds..5.4.seconds }
        bannerAppearances(times, seen, 1.seconds) shouldBe listOf(1.seconds)
        // Le même bandeau perdu un instant (2,6 s), revu de 2,8 à 4 s : compté une fois.
        val again = times.map { it in 1.seconds..2.4.seconds || it in 2.8.seconds..4.seconds }
        bannerAppearances(times, again, 1.seconds) shouldBe listOf(1.seconds, 2.8.seconds)
        bannerAppearances(times, again, 1.seconds, apart = 10.seconds) shouldBe listOf(1.seconds)
    }
})
