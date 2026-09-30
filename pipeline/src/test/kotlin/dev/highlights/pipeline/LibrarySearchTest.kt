package dev.highlights.pipeline

import dev.highlights.core.model.MediaInfo
import dev.highlights.montage.KillGroup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LibrarySearchTest : FunSpec({
    fun moment(at: String, kills: Int, headshots: Int = 0, ace: Boolean = false, clutch: Boolean = false, game: String = "valorant", start: Int = 100, oneTaps: Int = 0) =
        FoundMoment(
            Path("s-${at.take(10)}.json"), Path("D:/Captures/partie-${at.take(10)}.mp4"), game, game.uppercase(), Instant.parse(at),
            List(kills) { (start + it).seconds }, headshots, ace, clutch, kills.toDouble(), oneTaps,
        )

    val moments = listOf(
        moment("2026-09-27T21:00:00Z", 3, headshots = 3),
        moment("2026-09-27T22:00:00Z", 2, headshots = 1, clutch = true, oneTaps = 1),
        moment("2026-09-20T21:00:00Z", 5, headshots = 2, ace = true),
        moment("2026-09-27T01:00:00Z", 1, headshots = 1), // après minuit : soirée du 26
        moment("2026-09-27T20:00:00Z", 2, game = "wardogs"),
    )

    test("critères : taille du multi-kill, ace, clutch, tout en headshot, jeu et soirées") {
        fun find(q: MomentQuery) = moments.filter { q.matches(it, ZoneOffset.UTC) }.map { it.kills.size to it.game }
        find(MomentQuery(minKills = 3)) shouldBe listOf(3 to "VALORANT", 5 to "VALORANT")
        find(MomentQuery(ace = true)) shouldBe listOf(5 to "VALORANT")
        find(MomentQuery(clutch = true)) shouldBe listOf(2 to "VALORANT")
        find(MomentQuery(allHeadshots = true)) shouldBe listOf(3 to "VALORANT", 1 to "VALORANT")
        find(MomentQuery(game = "wardogs")) shouldBe listOf(2 to "WARDOGS")
        // Un seul one tap suffit : le montage onetaps écartera l'autre kill du doublé.
        find(MomentQuery(oneTaps = true)) shouldBe listOf(2 to "VALORANT")
        find(MomentQuery(from = LocalDate.of(2026, 9, 27))) shouldBe listOf(3 to "VALORANT", 2 to "VALORANT", 2 to "WARDOGS")
        find(MomentQuery(to = LocalDate.of(2026, 9, 26))) shouldBe listOf(5 to "VALORANT", 1 to "VALORANT")
    }

    test("montage d'une recherche : un groupe est gardé s'il contient un kill choisi, à une seconde près") {
        val pick = MomentPick.of(listOf(moment("2026-09-27T21:00:00Z", 3, start = 100)))
        pick.size shouldBe 3
        val media = MediaInfo(Path("D:/Captures/partie-2026-09-27.mp4"), 1, 40.minutes)
        fun group(vararg at: Int, m: MediaInfo = media) = KillGroup(m, at.map { it.seconds }, 0.5, emptyList(), emptyList())
        pick.keeps(group(100, 101, 102)) shouldBe true
        // Recalé sur le son du tir (-0,4 s) : c'est toujours le même kill.
        pick.keeps(KillGroup(media, listOf(99.6.seconds), 0.5, emptyList(), emptyList())) shouldBe true
        pick.keeps(group(300)) shouldBe false
        pick.keeps(group(100, m = media.copy(path = Path("D:/Captures/autre.mp4")))) shouldBe false
        pick.keeps(KillGroup(media, listOf(98.seconds + 900.milliseconds), 0.5, emptyList(), emptyList())) shouldBe false
    }
})
