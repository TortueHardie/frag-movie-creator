package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.Session
import dev.highlights.montage.MontageReport
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/** Montage kills complet : vidéo synthétique à kills connus + musique à 120 BPM (calme puis forte) → vidéo 9:16 calée sur les temps. */
class KillMontageIT : FunSpec({
    test("montage rendu, durée = nombre entier de temps, kills sur les temps").config(enabledIf = { TestMedia.available }, timeout = 5.seconds * 60) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = TestMedia.generate(Files.createDirectories(root.resolve("WARDOGS")).resolve("partie.mp4"), durationSeconds = 90)
        val music = root.resolve("musique.wav")
        val period = 0.5
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-y", "-f", "lavfi",
                    "-i", "aevalsrc='0.7*sin(2*PI*(50+90*exp(-mod(t\\,$period)*28))*mod(t\\,$period))*exp(-mod(t\\,$period)*12)*(0.4+0.6*gte(t\\,30))':s=44100:d=70",
                    music.toString(),
                ),
                "musique de test",
            ),
        )

        val media = ffmpeg.probe(video)
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        val session = Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "wardogs",
            timeline = ScoredTimeline(
                grid, List(grid.count) { 0.5 }, emptyMap(),
                events = listOf(20, 23, 60).map { TimelineEvent(it.seconds, "kill", 1.0, "notifications") },
                segments = listOf(TimelineSegment(TimeRange(61.seconds, 62.seconds), "laughter", 0.6, "reactions")),
            ),
            highlights = emptyList(),
        )
        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                workDir = root.resolve("work").toString(),
            ),
            baseDir = Path("../config").toAbsolutePath().normalize(),
            source = null,
        )
        val result = Pipelines.create(config, ffmpeg).killMontage(
            listOf(session), music, MontageOptions(formats = listOf(OutputFormat.VERTICAL), maxDuration = 30.seconds), ProgressReporter.NONE,
        )

        val out = ffmpeg.probe(result.videos.getValue(OutputFormat.VERTICAL))
        (out.video?.width to out.video?.height) shouldBe (1080 to 1920)
        val report = Json { ignoreUnknownKeys = true }.decodeFromString(MontageReport.serializer(), result.report.readText())
        report.bpm shouldBe (120.0 plusOrMinus 1.0)
        report.clips shouldHaveSize 2
        val beatPeriod = 60.0 / report.bpm
        val totalBeats = report.clips.sumOf { it.beats }
        // Frontières sur les temps détectés (résolution ~12 ms) : la durée suit le tempo à quelques ms près.
        report.durationSeconds shouldBe (totalBeats * beatPeriod plusOrMinus 0.05)
        (out.duration.inWholeMilliseconds / 1000.0) shouldBe (report.durationSeconds plusOrMinus 0.1)
        // Chaque kill (ralenti et rampe de vitesse compris) tombe sur un temps.
        report.clips.flatMap { it.killsInMontage }.forEach { k ->
            val beats = k / beatPeriod
            (beats - Math.round(beats)) shouldBe (0.0 plusOrMinus 0.03)
        }
        // La musique passe de calme à forte à 30 s : le multi-kill (meilleur groupe) tombe sur cette drop.
        val drop = report.clips.single { it.onDrop }
        drop.kills shouldHaveSize 2
        report.dropAtSeconds!! shouldBe (drop.killsInMontage.last() plusOrMinus 0.001)
        report.sections.isNotEmpty() shouldBe true
    }
})
