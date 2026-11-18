package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.EncoderSettings
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.SessionStore
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecapIT : FunSpec({
    fun game(at: String, kills: Int, profile: String = "lol") = GameStats(
        sessionFile = Path("s.json"), source = Path("partie_${at.replace(':', '-')}.mp4"), profileId = profile, game = "League of Legends",
        playedAt = Instant.parse(at), duration = 40.minutes, kills = kills, deaths = null, headshots = null,
        multiKills = emptyMap(), aces = 0, clutches = null, bestRound = null, rounds = null,
    )

    test("textes de l'intercalaire et de l'écran de fin : numéro, kills, jeu et heure, meilleure partie") {
        I18n.withLanguage("fr") {
            RecapText.gameTitle(3, 14) shouldBe "Partie 3 – 14 kills"
            RecapText.gameTitle(1, 1) shouldBe "Partie 1 – 1 kill"
            RecapText.gameSubtitle(game("2026-11-16T21:09:00Z", 3), ZoneOffset.UTC) shouldBe "League of Legends · 21:09"
            val evening = EveningPlanner.of(listOf(game("2026-11-16T19:00:00Z", 5), game("2026-11-16T20:00:00Z", 14), game("2026-11-16T21:00:00Z", 9)), ZoneOffset.UTC).single()
            evening.games.map { it.kills } shouldBe listOf(5, 14, 9)
            RecapText.endTitle(evening) shouldBe "Soirée du 16/11"
            RecapText.endSubtitle(evening) shouldBe "28 kills · meilleure partie : n°2 (14)"
        }
        I18n.withLanguage("en") { RecapText.gameTitle(3, 14) shouldBe "Game 3 – 14 kills" }
    }

    test("récap de deux parties : cible partagée sur la soirée, un intercalaire par partie, un écran de fin").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val dir = root.resolve("League of Legends").also { Files.createDirectories(it) }
        val a = TestMedia.generate(dir.resolve("a.mp4"), durationSeconds = 50)
        val b = TestMedia.generate(dir.resolve("b.mp4"), durationSeconds = 40, audioTracks = listOf(TestMedia.AudioTrackSpec("Game", bursts = listOf(20.0..22.0))))
        Files.setLastModifiedTime(a, FileTime.from(Instant.parse("2026-09-20T20:00:00Z")))
        Files.setLastModifiedTime(b, FileTime.from(Instant.parse("2026-09-20T21:00:00Z")))
        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                workDir = root.resolve("work").toString(),
                encoder = EncoderSettings(preference = listOf("h264_amf", "libx264")),
            ),
            baseDir = root,
            source = null,
        )
        val pipeline = Pipelines.create(config, TestMedia.requireFfmpeg())
        val analyses = pipeline.analyzeAll(listOf(a, b), AnalyzeOptions(target = SelectionTarget(topN = 1)), ProgressReporter.NONE)
        val games = analyses.mapIndexed { i, o -> game("2026-09-20T2$i:00:00Z", 2 + i).copy(sessionFile = o.sessionFile, source = o.session.media.path) }
        val evening = Evening(LocalDate.parse("2026-09-20"), "League of Legends", "lol", analyses.map { it.sessionFile }, analyses.map { it.session.media.path }, 5, Instant.parse("2026-09-20T22:00:00Z"), games)

        val result = I18n.withLanguage("fr") { kotlinx.coroutines.runBlocking { pipeline.eveningRecap(evening, RecapOptions(formats = listOf(OutputFormat.SOURCE)), ProgressReporter.NONE) } }
        val video = result.videos.getValue(OutputFormat.SOURCE)
        video.fileName.toString() shouldContain "_recap"
        val report = SessionStore.json.decodeFromString(RecapReport.serializer(), result.report.readText())
        // Cible partagée : les moments de chaque partie sont ceux de la sélection commune à la soirée ; une partie avec
        // des kills (toutes ici) qui n'y garde rien a son meilleur moment.
        val shared = pipeline.reselectAll(analyses.map { SessionStore.load(it.sessionFile) }, null, null)
        report.games.map { it.moments } shouldBe shared.map { s -> s.highlights.count { it.enabled }.coerceAtLeast(1) }
        report.games.map { it.number } shouldBe listOf(1, 2)
        // Un carton par partie montrée, puis l'écran de fin ; le premier ouvre la vidéo.
        report.cards.size shouldBe report.games.size + 1
        report.cards.first() shouldBe 0.0
        // Durée : les parties, plus 1,5 s par intercalaire et 2,5 s pour la fin.
        val probed = TestMedia.requireFfmpeg().probe(video)
        (probed.duration.inWholeMilliseconds / 1000.0) shouldBe (result.duration.inWholeMilliseconds / 1000.0 plusOrMinus 0.5)
        (result.duration.inWholeMilliseconds / 1000.0 > 1.5 * report.games.size + 2.5) shouldBe true
    }
})
