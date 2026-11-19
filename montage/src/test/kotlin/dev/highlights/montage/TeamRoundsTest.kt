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
    fun clutches(allies: List<Duration>, enemies: List<Duration>, player: List<Duration> = emptyList(), min: Int = 2) =
        TeamRounds.clutches(allies, enemies, player, gap, teamSize = 5, minEnemies = min)

    test("seul face à trois, le joueur élimine l'équipe adverse : clutch") {
        // Deux adversaires tombent, puis les quatre alliés ; le joueur tue les trois derniers.
        val out = clutches(s(110, 112, 115, 120), s(100, 105, 130, 133, 140))
        out shouldBe listOf(TeamRounds.Clutch(alone = 120.seconds, enemies = 3, won = 140.seconds))
    }

    test("un 1 contre 1 gagné n'est un clutch qu'avec clutchMinEnemies = 1, comme dans le jeu") {
        val allies = s(110, 112, 115, 135)
        val enemies = s(100, 105, 120, 130, 140)
        clutches(allies, enemies).shouldBeEmpty()
        clutches(allies, enemies, min = 1).size shouldBe 1
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

    test("clutchTimes : rien sans morts lues dans le killfeed, sinon l'instant juste après le dernier adversaire") {
        val style = KillStyle()
        TeamRounds.clutchTimes(emptyList(), emptyList(), style, Duration.ZERO) shouldBe null
        val events = s(110, 112, 115, 120).map { TimelineEvent(it, "ally-death", 1.0, "teams") } +
            s(100, 105, 130, 133, 140).map { TimelineEvent(it, "enemy-death", 1.0, "teams") }
        TeamRounds.clutchTimes(events, emptyList(), style, Duration.ZERO) shouldBe listOf(140.seconds + TeamRounds.AFTER_LAST)
        TeamRounds.clutchTimes(events, emptyList(), style.copy(allyDeathEvent = ""), Duration.ZERO) shouldBe null
    }
})
