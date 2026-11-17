package dev.highlights.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class EveningsTest : FunSpec({
    val zone = ZoneOffset.UTC
    fun game(at: String, length: Duration, kills: Int, profile: String = "wardogs", name: String = "Wardogs") = GameStats(
        sessionFile = Path("sessions/${at.replace(':', '-')}.session.json"), source = Path("captures/${at.replace(':', '-')}.mp4"), profileId = profile, game = name,
        playedAt = Instant.parse(at), duration = length, kills = kills, deaths = null, headshots = null,
        multiKills = emptyMap(), aces = 0, clutches = null, bestRound = null, rounds = null,
    )

    val games = listOf(
        game("2026-11-16T19:00:00Z", 40.minutes, 12),
        game("2026-11-16T21:30:00Z", 45.minutes, 20),
        // 1 h du matin : la même soirée (jusqu'à 6 h).
        game("2026-11-17T01:00:00Z", 30.minutes, 15),
        // Un autre jeu le même soir : une autre soirée.
        game("2026-11-16T20:00:00Z", 35.minutes, 8, profile = "valorant", name = "VALORANT"),
        game("2026-11-17T20:00:00Z", 40.minutes, 3),
    )
    val evenings = EveningPlanner.of(games, zone)

    test("découpage des soirées : de 6 h à 6 h, un jeu à la fois, fin = fin de la dernière partie") {
        evenings.map { it.date to it.profileId } shouldBe listOf(
            LocalDate.parse("2026-11-17") to "wardogs",
            LocalDate.parse("2026-11-16") to "valorant",
            LocalDate.parse("2026-11-16") to "wardogs",
        )
        val night = evenings.single { it.date == LocalDate.parse("2026-11-16") && it.profileId == "wardogs" }
        night.kills shouldBe 47
        night.sessionFiles.size shouldBe 3
        night.sources.first() shouldBe Path("captures/2026-11-16T19-00-00Z.mp4")
        night.end shouldBe Instant.parse("2026-11-17T01:30:00Z")
    }

    test("déclenchement : seulement après le délai sans nouvelle partie, rien pendant qu'une capture s'écrit") {
        val night = evenings.single { it.date == LocalDate.parse("2026-11-16") && it.profileId == "wardogs" }
        val all = { _: Evening -> true }
        val never = { _: Evening -> false }
        fun due(now: String, busy: Boolean = false, minKills: Int = 5) =
            EveningPlanner.due(listOf(night), never, Instant.parse(now), 60.minutes, minKills, all, busy)
        due("2026-11-17T02:29:00Z").shouldBeEmpty()
        due("2026-11-17T02:30:00Z") shouldBe listOf(night)
        due("2026-11-17T09:00:00Z", busy = true).shouldBeEmpty()
        due("2026-11-17T09:00:00Z", minKills = 48).shouldBeEmpty()
        EveningPlanner.nextCheck(listOf(night), never, Instant.parse("2026-11-17T01:45:00Z"), 60.minutes, 5, all) shouldBe Instant.parse("2026-11-17T02:30:00Z")
        EveningPlanner.nextCheck(listOf(night), never, Instant.parse("2026-11-17T03:00:00Z"), 60.minutes, 5, all) shouldBe null
    }

    test("seules les soirées d'une capture arrivée dans le dossier surveillé : activer l'option ne remonte pas l'historique") {
        val arrived = setOf(Path("captures/2026-11-16T21-30-00Z.mp4"))
        val due = EveningPlanner.due(evenings, { false }, Instant.parse("2026-11-20T00:00:00Z"), 1.hours, 0, { e -> e.sources.any { it in arrived } }, busy = false)
        due.map { it.date to it.profileId } shouldBe listOf(LocalDate.parse("2026-11-16") to "wardogs")
    }

    test("pas de double montage : une soirée montée (ou en échec) est retenue, d'un lancement à l'autre") {
        val dir = Files.createTempDirectory("evenings")
        val file = dir.resolve("sessions").resolve("evening-montages.json")
        val night = evenings.single { it.date == LocalDate.parse("2026-11-16") && it.profileId == "wardogs" }
        val other = evenings.single { it.profileId == "valorant" }
        val history = EveningHistory(file)
        history.done(night) shouldBe false
        history.record(night, dir.resolve("wardogs_2026-11-16_killmontage_9x16.mp4"))
        history.record(other, null, "aucune musique")
        history.record(night, dir.resolve("encore.mp4"))
        val reopened = EveningHistory(file)
        reopened.done(night) shouldBe true
        reopened.done(other) shouldBe true
        reopened.entries().size shouldBe 2
        val now = Instant.parse("2026-11-20T00:00:00Z")
        EveningPlanner.due(evenings, reopened::done, now, 1.hours, 0, { true }, busy = false).map { it.date.toString() } shouldBe listOf("2026-11-17")
    }
})
