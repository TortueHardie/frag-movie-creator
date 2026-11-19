package dev.highlights.montage

import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.KillStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TeamRoundsTest : FunSpec({
    val gap = 40.seconds
    fun s(vararg v: Int) = v.map { it.seconds }
    fun clutches(allies: List<Duration>, enemies: List<Duration>, player: List<Duration> = emptyList(), min: Int = 1) =
        TeamRounds.clutches(allies, enemies, player, gap, teamSize = 5, minEnemies = min)

    test("seul face à trois, le joueur élimine l'équipe adverse : clutch") {
        // Deux adversaires tombent, puis les quatre alliés ; le joueur tue les trois derniers.
        val out = clutches(s(110, 112, 115, 120), s(100, 105, 130, 133, 140))
        out shouldBe listOf(TeamRounds.Clutch(alone = 120.seconds, enemies = 3, won = 140.seconds))
    }

    test("un 1 contre 1 gagné est un clutch par défaut ; avec clutchMinEnemies = 2 (comme le bandeau du jeu), non") {
        val allies = s(110, 112, 115, 135)
        val enemies = s(100, 105, 120, 130, 140)
        TeamRounds.clutchTimes(
            allies.map { TimelineEvent(it, "ally-death", 1.0, "teams") } + enemies.map { TimelineEvent(it, "enemy-death", 1.0, "teams") },
            emptyList(), KillStyle(), Duration.ZERO,
        ) shouldBe listOf(140.seconds + TeamRounds.AFTER_LAST)
        clutches(allies, enemies, min = 2).shouldBeEmpty()
    }

    test("le 1 contre 1 de la partie « Valorant- 44 kills » (19:07), tel que lu par killfeed-teams") {
        // Round commencé à 1078,4 s (barres de vie pleines) : trois alliés tombent, puis quatre adversaires, le dernier
        // allié à 1145 s ; le joueur, seul contre un, tue le dernier à 1147 s. Bandeau de fin de round : « WON ».
        val events = listOf(1078.4 to "round-start") +
            listOf(1083.25, 1091.45, 1094.05, 1145.05).map { it to "ally-death" } +
            listOf(1099.45, 1101.85, 1127.25, 1142.65, 1147.05).map { it to "enemy-death" } +
            // Fin du round précédent (perdu) : ses morts ne comptent pas dans celui-ci.
            listOf(1052.65, 1056.45).map { it to "ally-death" }
        val timeline = events.map { (t, k) -> TimelineEvent(t.seconds, k, 1.0, "teams") }.sortedBy { it.at }
        TeamRounds.clutchTimes(timeline, listOf(1055.25.seconds), KillStyle(), Duration.ZERO) shouldBe listOf(1147.05.seconds + TeamRounds.AFTER_LAST)
    }

    test("la ligne de la mort du joueur n'est pas celle d'un allié, et sa mort empêche le clutch") {
        // Quatre lignes alliées dont celle du joueur (121 s, sa mort lue à 121,4 s) : il n'est pas resté seul.
        clutches(s(110, 112, 115, 121), s(100, 105, 130, 133, 140), player = listOf(121.4.seconds)).shouldBeEmpty()
    }

    test("un round gagné sans que l'équipe adverse tombe (spike) n'est pas compté") {
        clutches(s(110, 112, 115, 120), s(100, 130)).shouldBeEmpty()
    }

    test("les morts d'un round ne s'ajoutent pas à celles du suivant") {
        // Round 1 : deux alliés tombent, l'équipe adverse est éliminée. Round 2 (après la phase d'achat) : deux autres
        // alliés, puis cinq adversaires : jamais seul, pas de clutch.
        val allies = s(100, 102, 200, 205)
        val enemies = s(90, 95, 104, 106, 108, 210, 212, 214, 216, 218)
        clutches(allies, enemies).shouldBeEmpty()
    }

    test("une équipe éliminée clôt le round même sans silence") {
        // Round vite enchaîné (silence de 30 s seulement) : l'élimination suffit à repartir de zéro.
        // Sans cette règle, les deux alliés du round 1 et les trois du round 2 laissaient le joueur « seul » face à cinq.
        val allies = s(95, 96, 131, 132, 133)
        val enemies = s(97, 98, 99, 100, 101, 140, 141, 142, 143, 144)
        clutches(allies, enemies).shouldBeEmpty()
    }

    test("un début de round lu à l'image sépare deux rounds sans silence (vidéo montée)") {
        // Round 1 : deux adversaires puis deux alliés tombent, fini au spike ; la vidéo coupe la phase d'achat, le round 2
        // commence 10 s après (115 s). Deux alliés y tombent, puis l'équipe adverse : le joueur n'a jamais été seul.
        val allies = s(100, 105, 125, 128)
        val enemies = s(90, 91, 130, 131, 132, 133, 134)
        TeamRounds.clutches(allies, enemies, emptyList(), gap, 5, 1, roundStarts = s(115)).shouldBeEmpty()
        // Sans le début de round, les quatre lignes alliées le faisaient croire seul : faux clutch.
        TeamRounds.clutches(allies, enemies, emptyList(), gap, 5, 1).size shouldBe 1
    }

    test("une mort pendant l'ultime de Phoenix n'en est pas une, partout") {
        val style = KillStyle()
        val events = listOf(
            TimelineEvent(100.seconds, "death", 1.0, "killfeed"),
            TimelineEvent(100.2.seconds, "ally-run-it-back", 1.0, "teams"),
            TimelineEvent(200.seconds, "death", 1.0, "killfeed"),
        )
        TeamRounds.withoutRevived(events, style).map { it.at } shouldBe listOf(100.2.seconds, 200.seconds)
        // Montage des morts (deathEvent vidé, morts montées comme des kills) : retirée aussi.
        TeamRounds.withoutRevived(events, style.copy(deathEvent = "")).count { it.kind == "death" } shouldBe 1
        // Réglage vidé : gardée.
        TeamRounds.withoutRevived(events, style.copy(revivedEvent = "")) shouldBe events
    }

    test("clutchTimes : rien sans morts lues dans le killfeed, sinon l'instant juste après le dernier adversaire") {
        val style = KillStyle()
        TeamRounds.clutchTimes(emptyList(), emptyList(), style, Duration.ZERO) shouldBe null
        val events = s(110, 112, 115, 120).map { TimelineEvent(it, "ally-death", 1.0, "teams") } +
            s(100, 105, 130, 133, 140).map { TimelineEvent(it, "enemy-death", 1.0, "teams") }
        TeamRounds.clutchTimes(events, emptyList(), style, Duration.ZERO) shouldBe listOf(140.seconds + TeamRounds.AFTER_LAST)
        TeamRounds.clutchTimes(events, emptyList(), style.copy(allyDeathEvent = ""), Duration.ZERO) shouldBe null
    }

    test("bandeau « CLUTCH » : une source de plus, même sans mort alliée lue (round gagné au spike)") {
        val banner = listOf(TimelineEvent(200.seconds, "clutch-banner", 1.0, "teams"))
        TeamRounds.clutchTimes(banner, emptyList(), KillStyle(), Duration.ZERO) shouldBe listOf(200.seconds)
        TeamRounds.clutchTimes(banner, emptyList(), KillStyle(clutchBannerEvent = ""), Duration.ZERO) shouldBe null
    }
})
