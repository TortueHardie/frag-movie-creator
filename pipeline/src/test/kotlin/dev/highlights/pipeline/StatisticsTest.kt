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

    test("à terre (Wardogs) : pas une mort par défaut ; compté avec downedAsDeath") {
        val wardogs = listOf(ev(100, "kill"), ev(200, "kill"), ev(205, "downed"))
        val s = session("2026-09-27T20:00:00Z", events = wardogs)
        val off = Statistics.game(s, settings, Path("w"), "Wardogs", Statistics.sources(listOf(s), settings.killStyle.deathKinds), emptySet())
        off.deaths shouldBe null
        val on = settings.copy(killStyle = settings.killStyle.copy(downedAsDeath = true))
        val sources = Statistics.sources(listOf(s), on.killStyle.deathKinds)
        sources shouldBe setOf("game-events")
        Statistics.game(s, on, Path("w"), "Wardogs", sources, emptySet()).deaths shouldBe 1
    }

    test("kills du killfeed, tirs à la tête entendus dans le son du jeu : seuls ceux qui tuent comptent") {
        val killfeed = events.filter { it.kind != "headshot" }.map { it.copy(detectorId = "killfeed") }
        // Deux tirs à la tête sur des kills, un troisième qui n'a pas tué (240 s, loin de tout kill).
        val heard = listOf(ev(100, "headshot", "headshot-sound"), ev(102, "headshot", "headshot-sound"), ev(240, "headshot", "headshot-sound"))
        val g = Statistics.game(
            session("2026-09-27T20:00:00Z", events = (killfeed + heard).sortedBy { it.at }), settings, Path("d"), "VALORANT",
            setOf("game-events", "killfeed"), setOf("game-events", "headshot-sound"),
        )
        g.headshots shouldBe 2
        g.headshotRate!! shouldBe (0.5 plusOrMinus 1e-9)
    }

    test("clutchs annoncés : connus d'une analyse à jour dont les kills viennent du jeu, inconnus sinon") {
        val announced = settings.copy(killStyle = settings.killStyle.copy(clutchEvent = "clutch"))
        val withClutch = session("2026-09-27T20:00:00Z", events = events + ev(102, "clutch").copy(at = 102.02.seconds))
        val sources = Statistics.sources(listOf(withClutch), "clutch")
        Statistics.game(withClutch, announced, Path("a"), "VALORANT", clutchSources = sources).clutches shouldBe 1
        // Partie à jour sans clutch : 0, pas inconnu.
        Statistics.game(session("2026-09-27T21:00:00Z", events = events), announced, Path("b"), "VALORANT", clutchSources = sources).clutches shouldBe 0
        // Analysée avant que le profil lise les clutchs : inconnus.
        Statistics.game(session("2026-09-27T21:00:00Z", events = events), announced, Path("c"), "VALORANT", outdated = true, clutchSources = sources).clutches shouldBe null
        // Capture OBS : kills du killfeed, qui n'annonce pas les clutchs.
        val obs = session("2026-09-27T21:00:00Z", events = events.map { it.copy(detectorId = "killfeed") })
        Statistics.game(obs, announced, Path("d"), "VALORANT", clutchSources = sources).clutches shouldBe null
        // La même, avec les morts des deux équipes lues dans le killfeed : clutchs réels, connus (ici aucun).
        val teams = session("2026-09-27T21:00:00Z", events = (obs.timeline.events + ev(50, "ally-death").copy(detectorId = "teams")).sortedBy { it.at })
        Statistics.game(teams, announced, Path("d2"), "VALORANT", clutchSources = sources).clutches shouldBe 0
        // Clutchs déduits (profil sans clutchEvent) : toujours connus, même d'une analyse ancienne (ici, le triplé d'un round survécu).
        Statistics.game(obs, settings, Path("e"), "VALORANT", outdated = true, clutchSources = emptySet()).clutches shouldBe 1
    }

    test("une mort pendant l'ultime de Phoenix ne compte pas dans le K/D") {
        val evs = listOf(ev(100, "kill", "killfeed"), ev(200, "death", "killfeed"), ev(300, "death", "killfeed"), ev(300, "ally-run-it-back", "teams"))
        Statistics.game(session("2026-09-27T21:00:00Z", events = evs), settings, Path("p"), "VALORANT", setOf("killfeed")).deaths shouldBe 1
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

    test("horaires de fichier : deux captures sans date à une minute d'écart restent deux parties ; la même capture, une seule") {
        fun game(file: String, at: String, length: Duration, kills: Int, reliable: Boolean, size: Long) = GameStats(
            Path("$file.json"), Path("D:/Vidéo/$file.mp4"), "valorant", "VALORANT", Instant.parse(at), length, kills, 0, 0, emptyMap(), 0, 0, 0, 0,
            reliableTime = reliable, sizeBytes = size,
        )
        // Les deux parties VALORANT du 17/11 : pas de date dans les métadonnées, copiées à une minute d'écart.
        val copied = listOf(
            game("Valorant_game", "2026-11-17T21:08:40Z", 37.minutes, 15, reliable = false, size = 300_231_393),
            game("Valorant- 44 kills", "2026-11-17T21:09:17Z", 31.minutes, 46, reliable = false, size = 271_139_128),
        )
        Statistics.distinctGames(copied).map { it.kills } shouldBe listOf(15, 46)
        Statistics.evenings(copied, ZoneOffset.UTC).single().kills shouldBe 61
        // La même capture analysée deux fois (deux sessions, même fichier) : une partie.
        val twice = listOf(copied[0], copied[0].copy(sessionFile = Path("autre.json"), kills = 14))
        Statistics.distinctGames(twice).map { it.kills } shouldBe listOf(15)
        // Une copie de la capture ailleurs : même taille, même durée, une partie.
        val copy = copied[1].copy(sessionFile = Path("copie.json"), source = Path("E:/Sauvegarde/Valorant- 44 kills.mp4"), playedAt = Instant.parse("2026-11-18T09:00:00Z"))
        Statistics.distinctGames(listOf(copied[1], copy)) shouldHaveSize 1
        // Chevauchement réel entre deux enregistreurs datés (Outplayed et OBS) : toujours la même partie.
        val recorded = listOf(
            game("VALORANT_11-17-2026_21-08-00-0", "2026-11-17T21:08:00Z", 44.minutes, 19, reliable = true, size = 1),
            game("2026-11-17 21-09-00", "2026-11-17T21:09:00Z", 40.minutes, 17, reliable = true, size = 2),
        )
        Statistics.distinctGames(recorded).map { it.kills } shouldBe listOf(19)
        // Un horaire fiable et l'autre non : rien ne prouve que c'est la même partie, deux parties.
        Statistics.distinctGames(listOf(recorded[0], recorded[1].copy(reliableTime = false))) shouldHaveSize 2
    }

    test("heure d'enregistrement lue dans le nom : Outplayed, OBS, ShadowPlay ; rien dans un nom quelconque") {
        val utc = ZoneOffset.UTC
        dev.highlights.core.model.MediaInfo.nameTime("WARDOGS_09-22-2026_22-55-31-983.mp4", utc) shouldBe Instant.parse("2026-09-22T22:55:31Z")
        dev.highlights.core.model.MediaInfo.nameTime("REMATCH 09-27-2026 0-47-38-983_1.mp4", utc) shouldBe Instant.parse("2026-09-27T00:47:38Z")
        dev.highlights.core.model.MediaInfo.nameTime("2026-11-17 21-08-40.mkv", utc) shouldBe Instant.parse("2026-11-17T21:08:40Z")
        dev.highlights.core.model.MediaInfo.nameTime("VALORANT 2026.11.17 - 21.08.40.02.DVR.mp4", utc) shouldBe Instant.parse("2026-11-17T21:08:40Z")
        dev.highlights.core.model.MediaInfo.nameTime("Valorant- 44 kills.mp4", utc) shouldBe null
        dev.highlights.core.model.MediaInfo.nameTime("Valorant_game.mp4", utc) shouldBe null
        // Une date impossible n'en est pas une.
        dev.highlights.core.model.MediaInfo.nameTime("partie 13-45-2026 25-00-00.mp4", utc) shouldBe null
    }
})
