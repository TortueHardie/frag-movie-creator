package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.ConfigException
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.config.PublishSettings
import dev.highlights.core.config.YouTubeSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.montage.MontageReport
import dev.highlights.montage.MontageReportClip
import dev.highlights.publish.VideoKind
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.LocalDate
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class YouTubePublisherIT : FunSpec({
    test("titre et description tirés du rapport écrit avec la vidéo").config(enabledIf = { TestMedia.available }, timeout = 2.seconds * 60) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val out = Files.createDirectories(root.resolve("out"))
        val video = TestMedia.generate(root.resolve("source.mp4"), durationSeconds = 5).copyTo(out.resolve("valorant_2026-09-27_killmontage_9x16.mp4"))
        fun clip(kills: Int, headshots: Int = 0, ace: Boolean = false) =
            MontageReportClip("partie.mp4", "00:00", "00:01", List(kills) { "00:00" }, List(kills) { 0.0 }, 4, slowMotion = false, headshots = headshots, ace = ace)
        val report = MontageReport(
            generatedAt = "2026-09-27T23:00:00Z", music = "D:/Musique/R2D2.mp3", bpm = 161.4, musicStart = "00:10", durationSeconds = 5.0,
            outputs = listOf(video.toString()), clips = listOf(clip(1, 1), clip(3, 2), clip(5, 3, ace = true), clip(1)), profile = "valorant",
        )
        out.resolve("valorant_2026-09-27_killmontage.json").writeText(Json.encodeToString(MontageReport.serializer(), report))
        // Un autre rapport du même dossier, qui ne parle pas de cette vidéo.
        out.resolve("autre.json").writeText("""{"outputs":["ailleurs.mp4"],"clips":[]}""")

        val configDir = Files.createDirectories(root.resolve("config"))
        fun config(api: Boolean) = LoadedConfig(
            AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null), outputDir = out.toString(), profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                publish = PublishSettings(YouTubeSettings(api = api)),
            ),
            configDir, null,
        )
        fun publisher(api: Boolean) = config(api).let { c ->
            YouTubePublisher(c, ffmpeg, { ProfileRepository.loadDirectory(c.profilesDir) }, {}, tokenFile = root.resolve("token.json"))
        }
        publisher(api = true).configured shouldBe false
        // Le fichier téléchargé depuis la console Google Cloud, déposé à côté d'app.yaml, suffit.
        configDir.resolve("client_secret_42.json").writeText("""{"installed":{"client_id":"42","client_secret":"s"}}""")
        publisher(api = true).configured shouldBe true
        publisher(api = true).connected shouldBe false
        // API désactivée (par défaut) : envoi manuel, même avec un identifiant.
        publisher(api = false).configured shouldBe false
        shouldThrow<ConfigException> { publisher(api = false).login() }.message shouldContain "publish.youtube.api"
        val publisher = publisher(api = false)

        val facts = publisher.facts(video)
        facts.kind shouldBe VideoKind.KILL_MONTAGE
        facts.game shouldBe "VALORANT"
        facts.date shouldBe LocalDate.of(2026, 9, 27)
        facts.kills shouldBe 10
        facts.headshots shouldBe 6
        facts.multiKills shouldBe mapOf(3 to 1, 5 to 1)
        facts.aces shouldBe 1
        facts.music shouldBe "R2D2"
        val m = publisher.suggest(video)
        m.title shouldBe "ACE — 10 kills en ${facts.duration.inWholeSeconds} s | VALORANT"
        m.description.lines().first() shouldBe "10 kills, dont 6 headshots · 1 ace, 1 quintuplé, 1 triplé."
    }
})
