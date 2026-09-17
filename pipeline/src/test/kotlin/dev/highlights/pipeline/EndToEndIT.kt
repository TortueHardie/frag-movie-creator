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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/** Analyse + export complet sur une vidéo synthétique avec deux salves sonores à 15 s et 42 s. */
class EndToEndIT : FunSpec({
    test("process : deux moments détectés, montage 16:9 et 9:16, rapport JSON, source intacte").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val source = TestMedia.generate(
            root.resolve("League of Legends").also { Files.createDirectories(it) }.resolve("partie.mp4"),
            durationSeconds = 60,
        )
        val sourceStamp = source.getLastModifiedTime()
        val sourceSize = Files.size(source)

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

        val outcome = pipeline.process(
            source,
            AnalyzeOptions(target = SelectionTarget(topN = 5)),
            ExportOptions(formats = listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)),
            ProgressReporter.NONE,
        )

        outcome.analysis.profile.id shouldBe "lol"
        val highlights = outcome.analysis.session.highlights
        highlights shouldHaveSize 2
        (highlights[0].peak.inWholeMilliseconds / 1000.0) shouldBe (16.0 plusOrMinus 2.5)
        (highlights[1].peak.inWholeMilliseconds / 1000.0) shouldBe (43.0 plusOrMinus 2.5)

        val export = outcome.export.shouldNotBeNull()
        val landscape = export.videos.getValue(OutputFormat.SOURCE)
        Regex("""lol_\d{4}-\d{2}-\d{2}_highlights\.mp4""").matches(landscape.name) shouldBe true

        val ffmpeg = TestMedia.requireFfmpeg()
        val out = ffmpeg.probe(landscape)
        out.video?.width shouldBe 1920
        out.video?.height shouldBe 1080
        (out.duration.inWholeMilliseconds / 1000.0) shouldBe (export.duration.inWholeMilliseconds / 1000.0 plusOrMinus 0.5)
        val vertical = ffmpeg.probe(export.videos.getValue(OutputFormat.VERTICAL))
        vertical.video?.width shouldBe 1080
        vertical.video?.height shouldBe 1920

        val report = Json { ignoreUnknownKeys = true }.decodeFromString(ExportReport.serializer(), export.report.readText())
        report.highlights shouldHaveSize 2
        report.outputs shouldHaveSize 2

        // Relecture : on décoche un segment et on relance uniquement l'export.
        val session = SessionStore.load(outcome.analysis.sessionFile)
        val edited = session.copy(highlights = session.highlights.mapIndexed { i, h -> if (i == 0) h.copy(enabled = false) else h })
        val reexport = pipeline.export(edited, ExportOptions(formats = listOf(OutputFormat.SOURCE)), ProgressReporter.NONE)
        reexport.videos.getValue(OutputFormat.SOURCE).name.contains("_highlights_2") shouldBe true
        reexport.duration shouldBe highlights[1].range.length

        source.getLastModifiedTime() shouldBe sourceStamp
        Files.size(source) shouldBe sourceSize
        root.resolve("out").listDirectoryEntries("*.part.mp4") shouldHaveSize 0
        root.resolve("work").listDirectoryEntries() shouldHaveSize 0
        source.exists() shouldBe true
    }
})
