package dev.highlights.pipeline

import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ClipsTest : FunSpec({
    val settings = MontageSettings(killOffset = Duration.ZERO)
    val zone = ZoneId.systemDefault()

    /** Clip FTHRClips sauvegardé à [saved] (heure locale), de [length], avec des kills aux secondes [kills]. */
    fun clip(saved: String, length: Duration, vararg kills: Int, game: String = "VALORANT"): Session {
        val at = LocalDateTime.parse(saved)
        val name = "${game}_clip_from_${at.format(DateTimeFormatter.ofPattern("ddMMMyyyy_HH-mm-ss", Locale.ENGLISH))}.mp4"
        val media = MediaInfo(Path("D:/Clips/$game/$name"), 1000L + kotlin.math.abs(saved.hashCode().toLong()), length, video = VideoStream(0, "h264", 1920, 1080, 60.0))
        val grid = WindowGrid(1.seconds, 1.seconds, length)
        val events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "game-events") }
        val highlights = kills.mapIndexed { i, k ->
            Highlight("h$i", media.path, TimeRange((k - 3).coerceAtLeast(0).seconds, (k + 2).seconds.coerceAtMost(length)), k.seconds, 0.8)
        }
        return Session(createdAt = Instant.EPOCH, media = media, profileId = "valorant", timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = events), highlights = highlights)
    }

    fun stats(s: Session) = Statistics.game(s, settings, Path("${s.media.path.fileName}.session.json"), "VALORANT")

    test("deux appuis sur la touche à 20 s d'écart : les kills communs ne comptent qu'une fois, les nouveaux restent") {
        // Clip A : 21:00:00 → 21:01:00, kills à 21:00:30 et 21:00:50. Clip B : 21:00:20 → 21:01:20, il revoit ces deux
        // kills (à 10 s et 30 s de son début) et en ajoute un à 21:01:10.
        val a = clip("2026-11-22T21:01:00", 60.seconds, 30, 50)
        val b = clip("2026-11-22T21:01:20", 60.seconds, 10, 30, 50)
        val cleaned = ClipRepeats.remove(listOf(b, a))
        cleaned[1] shouldBe a
        cleaned[0].timeline.events.map { it.at } shouldBe listOf(50.seconds)
        cleaned.sumOf { stats(it).kills } shouldBe 3
        // Les mêmes kills à une seconde près (heure du nom à la seconde) : toujours des doublons.
        val late = clip("2026-11-22T21:01:21", 60.seconds, 10, 30)
        ClipRepeats.remove(listOf(a, late))[1].timeline.events shouldHaveSize 0
    }

    test("des clips qui ne se touchent pas, ou d'un autre jeu, gardent tous leurs kills") {
        val a = clip("2026-11-22T21:01:00", 60.seconds, 30, 50)
        val next = clip("2026-11-22T21:02:05", 60.seconds, 1, 2)
        ClipRepeats.remove(listOf(a, next)) shouldBe listOf(a, next)
        val other = clip("2026-11-22T21:01:00", 60.seconds, 30, 50).let { it.copy(profileId = "cs2") }
        ClipRepeats.remove(listOf(a, other)) shouldBe listOf(a, other)
    }

    test("récap : un moment déjà montré par le clip d'avant est décoché") {
        val a = clip("2026-11-22T21:01:00", 60.seconds, 30)
        val b = clip("2026-11-22T21:01:20", 60.seconds, 10, 50)
        val shown = ClipRepeats.withoutRepeatedMoments(listOf(a, b))
        shown[0].highlights.map { it.enabled } shouldBe listOf(true)
        shown[1].highlights.map { it.enabled } shouldBe listOf(false, true)
    }

    test("statistiques : pas de plancher de 5 min pour les clips, pas de faux doublon entre clips qui se suivent") {
        val clips = listOf(
            clip("2026-11-22T21:01:00", 60.seconds, 30, 50),
            clip("2026-11-22T21:01:20", 60.seconds, 10, 30, 50),
            clip("2026-11-22T21:20:00", 45.seconds, 20),
            clip("2026-11-22T22:30:00", 45.seconds, 5, 6),
        )
        val games = ClipRepeats.remove(clips).map(::stats)
        games.all { it.clip } shouldBe true
        Statistics.distinctGames(games) shouldHaveSize 4
        // Même poids, même durée (deux clips de 45 s au même débit) : deux clips, pas une copie ; la copie garde son nom.
        Statistics.distinctGames(games.map { it.copy(sizeBytes = 5_000_000) }) shouldHaveSize 4
        Statistics.distinctGames(listOf(games[0], games[0].copy(sessionFile = Path("copie.json"), source = Path("E:/Copie/${games[0].source.fileName}")))) shouldHaveSize 1
        val evening = Statistics.evenings(games).single()
        evening.kills shouldBe 6
        evening.clips shouldBe 4
        // Trois clips en 20 min font une séance ; 70 min plus tard, une autre.
        evening.parts.map { it.games.size } shouldBe listOf(3, 1)
        evening.killsPerGame shouldBe 3.0
        // Morts d'un clip inconnues (il ne montre pas celles d'entre deux clips) : pas de K/D de 6 sur la soirée.
        val withDeath = clips[0].let { s -> s.copy(timeline = s.timeline.copy(events = s.timeline.events + TimelineEvent(55.seconds, "death", 1.0, "game-events"))) }
        Statistics.game(withDeath, settings, Path("d.json"), "VALORANT", setOf("game-events")).deaths shouldBe null
        evening.kd shouldBe null
    }

    test("un clip pris pendant une partie entière enregistrée en est un morceau : la partie reste") {
        val game = GameStats(
            Path("g.json"), Path("2026-11-22 21-00-00.mp4"), "valorant", "VALORANT", Instant.parse("2026-11-22T20:00:00Z"), 40.minutes, 12, 5, 3, emptyMap(), 0, 0, 0, 0,
        )
        val inside = game.copy(sessionFile = Path("c.json"), source = Path("c.mp4"), playedAt = Instant.parse("2026-11-22T20:39:30Z"), duration = 60.seconds, kills = 13, clip = true)
        // Un clip sauvegardé une minute après la fin (heure à la sauvegarde) : encore la même partie.
        val after = inside.copy(sessionFile = Path("d.json"), source = Path("d.mp4"), playedAt = Instant.parse("2026-11-22T20:41:00Z"))
        val later = inside.copy(sessionFile = Path("e.json"), source = Path("e.mp4"), playedAt = Instant.parse("2026-11-22T21:30:00Z"))
        Statistics.distinctGames(listOf(inside, after, game, later)).map { it.sessionFile } shouldBe listOf(Path("g.json"), Path("e.json"))
    }

    test("séances : une partie entière coupe une suite de clips ; un autre jeu aussi") {
        fun g(at: String, clip: Boolean, game: String = "VALORANT", length: Duration = if (clip) 1.minutes else 40.minutes) = GameStats(
            Path("${at.replace(':', '-')}-$game.json"), Path("${at.replace(':', '-')}.mp4"), game.lowercase(), game, Instant.parse(at), length, 1, null, null, emptyMap(), 0, 0, null, null, clip = clip,
        )
        val parts = PlaySessions.of(
            listOf(
                g("2026-11-22T20:00:00Z", true),
                g("2026-11-22T20:10:00Z", true),
                g("2026-11-22T20:20:00Z", false),
                g("2026-11-22T21:05:00Z", true),
                g("2026-11-22T21:06:00Z", true, game = "Wardogs"),
                g("2026-11-22T21:30:00Z", true),
            ),
        )
        parts.map { p -> p.games.size to p.clips } shouldBe listOf(2 to true, 1 to false, 1 to true, 1 to true, 1 to true)
        // Sans fil d'un autre jeu entre eux, deux clips à 25 min d'écart sont une seule séance.
        PlaySessions.of(listOf(g("2026-11-22T21:05:00Z", true), g("2026-11-22T21:30:00Z", true))) shouldHaveSize 1
        PlaySessions.of(listOf(g("2026-11-22T21:05:00Z", true), g("2026-11-22T22:30:00Z", true))) shouldHaveSize 2
    }

    test("récap : « meilleure séance » quand la meilleure est une séance de clips, « meilleure partie » sinon") {
        fun g(at: String, clip: Boolean, kills: Int) = GameStats(
            Path("${at.replace(':', '-')}.json"), Path("${at.replace(':', '-')}.mp4"), "valorant", "VALORANT", Instant.parse(at), if (clip) 1.minutes else 40.minutes,
            kills, null, null, emptyMap(), 0, 0, null, null, clip = clip,
        )
        fun evening(games: List<GameStats>) = Evening(
            java.time.LocalDate.of(2026, 11, 22), "VALORANT", "valorant", games.map { it.sessionFile }, games.map { it.source }, games.sumOf { it.kills }, Instant.parse("2026-11-22T23:00:00Z"), games,
        )
        val game = g("2026-11-22T19:00:00Z", false, 5)
        val clips = listOf(g("2026-11-22T21:00:00Z", true, 4), g("2026-11-22T21:05:00Z", true, 3))
        dev.highlights.core.i18n.I18n.withLanguage("fr") {
            RecapText.endSubtitle(evening(listOf(game) + clips)) shouldBe "12 kills · meilleure séance : n°2 (7)"
            RecapText.endSubtitle(evening(listOf(game.copy(kills = 9)) + clips)) shouldBe "16 kills · meilleure partie : n°1 (9)"
        }
        dev.highlights.core.i18n.I18n.withLanguage("en") {
            RecapText.endSubtitle(evening(listOf(game) + clips)) shouldBe "12 kills · best session: #2 (7)"
        }
    }
})
