package dev.highlights.pipeline

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StatisticsTest : FunSpec({
    val settings = MontageSettings(killOffset = Duration.ZERO)

    fun session(at: String, length: Duration = 40.minutes, events: List<TimelineEvent>, name: String = at): Session {
        val media = MediaInfo(Path("${name.replace(':', '-')}.mp4"), 1, length, creationTime = Instant.parse(at), video = VideoStream(0, "h264", 1920, 1080, 60.0))
        val grid = WindowGrid(1.seconds, 1.seconds, length)
        return Session(createdAt = Instant.EPOCH, media = media, profileId = "valorant", timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = events), highlights = emptyList())
    }
    fun ev(s: Int, kind: String, detector: String = "game-events") = TimelineEvent(s.seconds, kind, 1.0, detector)

    // Round 1 : triple kill (100, 101, 102), survécu jusqu'au round suivant. Round 2 : un kill puis la mort.
    val events = listOf(
        ev(100, "kill"), ev(100, "headshot"), ev(101, "kill"), ev(102, "kill"), ev(102, "headshot"),
        ev(300, "kill"), ev(305, "death"),
    )

    test("bilan d'une partie : kills, morts, tirs à la tête, multi-kills et meilleur round, comme le montage kills") {
        val g = Statistics.game(session("2026-09-27T20:00:00Z", events = events), settings, Path("a.session.json"), "VALORANT", setOf("game-events"), setOf("game-events"))
        g.kills shouldBe 4
        g.deaths shouldBe 1
        g.headshots shouldBe 2
        g.headshotRate!! shouldBe (0.5 plusOrMinus 1e-9)
        g.kd!! shouldBe (4.0 plusOrMinus 1e-9)
        g.multiKills shouldBe mapOf(3 to 1)
        g.bestRound shouldBe 3
        g.rounds shouldBe 2
    }

    test("kills du killfeed de secours : morts connues, tirs à la tête inconnus plutôt que 0 %") {
        val killfeed = events.filter { it.kind != "headshot" }.map { it.copy(detectorId = "killfeed") }
        val g = Statistics.game(session("2026-09-27T20:00:00Z", events = killfeed), settings, Path("b"), "VALORANT", setOf("game-events", "killfeed"), setOf("game-events"))
        g.deaths shouldBe 1
        g.headshots shouldBe null
        g.headshotRate shouldBe null
        // Profil qui ne voit jamais de mort : ni morts, ni rounds, ni meilleur round.
        val blind = Statistics.game(session("2026-09-27T20:00:00Z", events = killfeed), settings, Path("c"), "Wardogs", emptySet(), emptySet())
        blind.deaths shouldBe null
        blind.bestRound shouldBe null
        blind.rounds shouldBe null
        Statistics.sources(listOf(session("2026-09-27T20:00:00Z", events = killfeed)), "death") shouldBe setOf("killfeed")
    }

    test("soirées : une partie après minuit compte pour la veille, un jeu par soirée") {
        fun game(at: String, name: String, kills: Int, deaths: Int?, headshots: Int? = null) = GameStats(
            Path("${at.replace(':', '-')}.json"), Path("${at.replace(':', '-')}.mp4"), name.lowercase(), name, Instant.parse(at), 40.minutes, kills, deaths, headshots, emptyMap(), 0, 0, null, null,
        )
        val games = listOf(
            game("2026-09-26T22:00:00Z", "VALORANT", 20, 10, 10),
            game("2026-09-27T01:30:00Z", "VALORANT", 10, 10, null), // après minuit : même soirée
            game("2026-09-27T21:00:00Z", "VALORANT", 12, 6, 6),
            game("2026-09-27T20:00:00Z", "Wardogs", 3, null),
        )
        val evenings = Statistics.evenings(games, ZoneOffset.UTC)
        evenings.map { it.date to it.game } shouldBe listOf(
            LocalDate.of(2026, 9, 27) to "VALORANT", LocalDate.of(2026, 9, 27) to "Wardogs", LocalDate.of(2026, 9, 26) to "VALORANT",
        )
        val saturday = evenings.last()
        saturday.games shouldHaveSize 2
        saturday.killsPerGame shouldBe (15.0 plusOrMinus 1e-9)
        saturday.kd!! shouldBe (1.5 plusOrMinus 1e-9)
        // Tirs à la tête : seulement sur la partie où ils sont connus (10 sur 20), pas 10 sur 30.
        saturday.headshotRate!! shouldBe (0.5 plusOrMinus 1e-9)
        evenings[1].kd shouldBe null
    }

    test("la même partie enregistrée deux fois ne compte qu'une, la plus riche en kills") {
        fun game(at: String, length: Duration, kills: Int, name: String = "VALORANT") = GameStats(
            Path("${at.replace(':', '-')}-$kills.json"), Path("${at.replace(':', '-')}.mp4"), "valorant", name, Instant.parse(at), length, kills, 0, 0, emptyMap(), 0, 0, 0, 0,
        )
        val kept = Statistics.distinctGames(
            listOf(
                game("2026-09-27T18:07:00Z", 44.minutes, 21),
                game("2026-09-27T18:08:00Z", 40.minutes, 23),
                game("2026-09-27T18:52:00Z", 40.minutes, 12), // commence à la fin de la précédente : autre partie
                game("2026-09-27T18:08:00Z", 40.minutes, 5, name = "Wardogs"), // autre jeu
            ),
        )
        kept.map { it.kills } shouldBe listOf(23, 5, 12)
    }
})
