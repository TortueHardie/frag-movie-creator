package dev.highlights.vision

import dev.highlights.core.video.ZoneFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KillfeedTest : FunSpec({
    // Formes à l'échelle de la zone lue (réglages VALORANT réduits de moitié).
    val shape = FrameShape(
        minHeight = 17, maxHeight = 26, minWidth = 30, maxWidth = 200, minFill = 0.15, maxFill = 0.6,
        narrowWidth = 20, narrowFill = 0.3, minTopEdge = 0.6, topRows = 2, deathEdge = 20, selfShare = 0.1,
    )
    val reader = FeedReader(threshold = 60, victimWidth = 60, bridge = 1, shape = shape)
    val yellow = 140.toByte()

    /**
     * Zone 300x120 ; chaque cadre : contour coloré de 38x22 (2 px en haut et en bas, 4 sur les côtés), vide au centre
     * comme derrière un visage. [gap] : colonne manquante dans le trait du haut, comme un contour mal échantillonné.
     */
    fun frame(vararg frames: Pair<Int, Int>, gap: Int? = null, width: Int = 38, side: Int = 4): ZoneFrame {
        val w = 300
        val pixels = ByteArray(w * 120)
        for ((x0, y0) in frames) {
            for (y in y0 until y0 + 22) for (x in x0 until x0 + width) {
                val border = y - y0 < 2 || y0 + 22 - y <= 2 || x - x0 < side || x0 + width - x <= side
                if (border && x != gap) pixels[y * w + x] = yellow
            }
        }
        return ZoneFrame(w, 120, pixels)
    }

    test("un cadre à gauche est un kill, un cadre dans la colonne de la victime une mort") {
        val rows = reader.rows(frame(100 to 20, 250 to 60))
        rows.map { it.role } shouldBe listOf(KillfeedRole.KILL, KillfeedRole.DEATH)
        rows[0].left shouldBe 100
        rows[0].top shouldBe 20
        rows[0].bottom shouldBe 42
    }

    test("un contour percé d'un pixel reste un seul cadre") {
        // Colonne 119 absente partout : sans comblement, deux moitiés de 19 px, trop étroites.
        reader.rows(frame(100 to 20, gap = 119)).map { it.left to it.right } shouldBe listOf(100 to 137)
        FeedReader(60, 60, bridge = 0, shape = shape).rows(frame(100 to 20, gap = 119)).shouldBeEmpty()
    }

    test("un aplat coloré, une tache sans bord supérieur, trop petite ou coupée par le bord ne sont pas des lignes") {
        val w = 300
        val pixels = ByteArray(w * 120)
        // Aplat plein (mur jaune) : remplissage 100 %.
        for (y in 10 until 32) for (x in 50 until 90) pixels[y * w + x] = yellow
        // Tache de 20x6.
        for (y in 40 until 46) for (x in 150 until 170) pixels[y * w + x] = yellow
        // Cadre sans trait du haut (deux montants et le bas).
        for (y in 60 until 82) for (x in 50 until 88) if (x < 54 || x >= 84 || y >= 80) pixels[y * w + x] = yellow
        reader.rows(ZoneFrame(w, 120, pixels)).shouldBeEmpty()
        reader.rows(frame(0 to 60)).shouldBeEmpty()
    }

    test("un cadre étroit compte s'il est plein (fond du portrait), pas s'il est vide (icône)") {
        // 24 px de large, sous minWidth : montants de 6 px, rempli à 59 %.
        reader.rows(frame(100 to 20, width = 24, side = 6)).map { it.role } shouldBe listOf(KillfeedRole.KILL)
        // Même taille, montants d'un pixel : rempli à 25 %.
        reader.rows(frame(100 to 20, width = 24, side = 1)).shouldBeEmpty()
    }

    test("une mort est collée au bord droit ; une ligne jaune des deux côtés n'est pas un kill") {
        // Centrée dans la colonne de la victime mais finie 37 px avant le bord : une icône qui passe.
        reader.rows(frame(225 to 20)).shouldBeEmpty()
        // Joueur tueur et victime (ultime de Clove) : seule la mort reste.
        reader.rows(frame(100 to 20, 250 to 20)).map { it.role } shouldBe listOf(KillfeedRole.DEATH)
    }

    fun tracker() = FeedTracker(tolerance = 6, minSightings = 3, maxGap = 1200.milliseconds, deathSpacing = 10.seconds)
    fun at(i: Int): Duration = 200.milliseconds * i
    fun kill(x: Int, y: Int) = FeedRow(KillfeedRole.KILL, y, y + 22, x, x + 37)

    test("une ligne qui monte quand une plus ancienne disparaît reste le même kill") {
        val t = tracker()
        repeat(5) { t.add(at(it), listOf(kill(100, 10))) }
        repeat(5) { t.add(at(5 + it), listOf(kill(100, 10), kill(120, 46))) }
        // La première disparaît, la seconde monte à sa place.
        repeat(5) { t.add(at(10 + it), listOf(kill(120, 10))) }
        t.appearances() shouldBe listOf(KillfeedRole.KILL to at(0), KillfeedRole.KILL to at(5))
    }

    test("une nouvelle ligne à la même position horizontale, sous la précédente, est un nouveau kill") {
        val t = tracker()
        repeat(4) { t.add(at(it), listOf(kill(100, 10))) }
        repeat(4) { t.add(at(4 + it), listOf(kill(100, 10), kill(100, 46))) }
        t.appearances().size shouldBe 2
    }

    test("une ligne masquée un instant n'est pas comptée deux fois ; vue trop peu, elle est écartée") {
        val t = tracker()
        repeat(4) { t.add(at(it), listOf(kill(100, 10))) }
        repeat(4) { t.add(at(4 + it), emptyList()) }
        repeat(4) { t.add(at(8 + it), listOf(kill(100, 10))) }
        t.add(at(20), listOf(kill(30, 60)))
        t.add(at(21), listOf(kill(30, 60)))
        t.appearances() shouldBe listOf(KillfeedRole.KILL to at(0))
    }

    test("une seule mort par round : la ligne réapparue après l'écran de mort est ignorée") {
        val t = tracker()
        val death = FeedRow(KillfeedRole.DEATH, 10, 32, 250, 290)
        repeat(4) { t.add(at(it), listOf(death)) }
        repeat(10) { t.add(at(4 + it), emptyList()) }
        repeat(4) { t.add(at(14 + it), listOf(death)) }
        repeat(4) { t.add(at(100 + it), listOf(death)) }
        t.appearances() shouldBe listOf(KillfeedRole.DEATH to at(0), KillfeedRole.DEATH to at(100))
    }

    test("passages relus : de deux images clés avant une ligne vue à l'image clé suivante, fusionnés s'ils se touchent") {
        val times = (0..20).map { it.seconds }
        fun seen(vararg at: Int) = times.indices.map { it in at }
        reviewWindows(times, seen(10, 11), end = 21.seconds, join = 1.seconds) shouldBe listOf(8.seconds..12.seconds)
        // Image clé 12 masquée par un flash : 13 prolonge le même passage.
        reviewWindows(times, seen(10, 11, 13), 21.seconds, 1.seconds) shouldBe listOf(8.seconds..14.seconds)
        reviewWindows(times, seen(3, 15), 21.seconds, 1.seconds) shouldBe listOf(1.seconds..4.seconds, 13.seconds..16.seconds)
        // Bords de la capture.
        reviewWindows(times, seen(0, 20), 21.seconds, 1.seconds) shouldBe listOf(Duration.ZERO..1.seconds, 18.seconds..21.seconds)
        reviewWindows(times, seen(), 21.seconds, 1.seconds).shouldBeEmpty()
    }

    test("une image sans pixel du joueur ne donne rien") {
        reader.rows(ZoneFrame(300, 120, ByteArray(300 * 120))).shouldBeEmpty()
    }
})
