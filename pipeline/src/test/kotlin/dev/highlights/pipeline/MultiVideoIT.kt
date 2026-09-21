package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.EncoderSettings
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.SessionStore
import dev.highlights.export.ExportReport
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/**
 * Plusieurs captures, un seul montage : la cible vaut pour l'ensemble, les parties se suivent dans l'ordre où elles
 * ont été jouées (pas dans celui des fichiers donnés), et deux captures de tailles différentes se concatènent.
 */
class MultiVideoIT : FunSpec({
    test("analyse commune puis export des parties dans l'ordre d'enregistrement").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val dir = root.resolve("League of Legends").also { Files.createDirectories(it) }
        // « b » a été jouée avant « a », et n'a pas le même format (4:3 contre 16:9).
        val a = TestMedia.generate(dir.resolve("a.mp4"), durationSeconds = 60)
        val b = TestMedia.generate(
            dir.resolve("b.mp4"),
            durationSeconds = 40,
            size = "480x360",
            audioTracks = listOf(TestMedia.AudioTrackSpec("Game", bursts = listOf(20.0..22.0))),
        )
        Files.setLastModifiedTime(b, FileTime.from(Instant.parse("2026-09-20T20:00:00Z")))
        Files.setLastModifiedTime(a, FileTime.from(Instant.parse("2026-09-20T21:00:00Z")))

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

        // Trois salves en tout (deux dans a, une dans b) : « top 2 » en garde deux pour l'ensemble, pas deux par partie.
        val analyses = pipeline.analyzeAll(listOf(a, b), AnalyzeOptions(target = SelectionTarget(topN = 2)), ProgressReporter.NONE)
        analyses.map { it.session.media.path.name } shouldBe listOf("b.mp4", "a.mp4")
        analyses.sumOf { it.session.highlights.size } shouldBe 2
        // La sélection commune est celle qui est enregistrée.
        analyses.sumOf { SessionStore.load(it.sessionFile).highlights.size } shouldBe 2

        val all = pipeline.reselectAll(analyses.map { it.session }, null, SelectionTarget(topN = 5))
        all.map { it.highlights.size } shouldBe listOf(1, 2)

        val export = pipeline.export(all, ExportOptions(formats = listOf(OutputFormat.SOURCE)), ProgressReporter.NONE)
        val out = TestMedia.requireFfmpeg().probe(export.videos.getValue(OutputFormat.SOURCE))
        // Taille du premier clip (celui de b, en 4:3) ; a y est ramenée avec des bandes noires.
        out.video?.width shouldBe 480
        out.video?.height shouldBe 360
        (out.duration.inWholeMilliseconds / 1000.0) shouldBe (export.duration.inWholeMilliseconds / 1000.0 plusOrMinus 0.5)

        val report = Json { ignoreUnknownKeys = true }.decodeFromString(ExportReport.serializer(), export.report.readText())
        report.sources.map { Path(it).name } shouldBe listOf("b.mp4", "a.mp4")
        report.highlights shouldHaveSize 3
        report.highlights.map { Path(it.source!!).name } shouldBe listOf("b.mp4", "a.mp4", "a.mp4")
    }
})
