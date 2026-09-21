package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.EncoderSettings
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Montage « story » rendu pour de vrai : le graphe (jump cuts, accroche, punch-in, secousses, bruitages générés,
 * musique de fond) doit être accepté par FFmpeg, et la vidéo produite doit durer ce que le plan annonce.
 */
class StoryEditIT : FunSpec({
    test("montage story avec accroche, effets, bruitages et musique : rendu 16:9 et 9:16").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val source = TestMedia.generate(root.resolve("captures").also { Files.createDirectories(it) }.resolve("partie.mp4"), durationSeconds = 60)
        val ffmpeg = TestMedia.requireFfmpeg()
        val music = root.resolve("fond.m4a")
        ffmpeg.run(FfmpegCommand(listOf("-y", "-f", "lavfi", "-i", "sine=f=220:d=8", "-c:a", "aac", music.toString()), "musique de test"))

        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                workDir = root.resolve("work").toString(),
                encoder = EncoderSettings(preference = listOf("libx264")),
            ),
            baseDir = root,
            source = null,
        )
        val pipeline = Pipelines.create(config, ffmpeg)
        val analysis = pipeline.analyze(source, AnalyzeOptions(target = SelectionTarget(topN = 5)), ProgressReporter.NONE)
        analysis.session.highlights.size shouldBe 2

        // Une réaction et un kill ajoutés à la main : la vidéo de test n'a ni micro ni événements de jeu.
        val first = analysis.session.highlights.first()
        val session = analysis.session.copy(
            timeline = analysis.session.timeline.copy(
                segments = listOf(TimelineSegment(TimeRange(first.peak, first.peak + 1.5.seconds), "laughter", 0.9, "reactions")),
                events = listOf(TimelineEvent(first.peak - 0.5.seconds, "kill", 1.0, "test")),
            ),
        )

        val export = pipeline.export(
            listOf(session),
            ExportOptions(formats = listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL), style = EditStyle.STORY, music = music),
            ProgressReporter.NONE,
        )
        val landscape = ffmpeg.probe(export.videos.getValue(OutputFormat.SOURCE))
        landscape.video?.width shouldBe 640
        (landscape.duration.inWholeMilliseconds / 1000.0) shouldBe (export.duration.inWholeMilliseconds / 1000.0 plusOrMinus 0.3)
        // Les salves de la vidéo de test durent 2 s dans un bruit de fond calme : les jump cuts retirent l'attente
        // autour, si bien que le montage, accroche de 3 s comprise, est plus court que les moments bruts.
        val moments = analysis.session.highlights.sumOf { it.range.inWholeMs() }
        export.duration.inWholeMilliseconds.toInt() shouldBeGreaterThan 3000 + 2 * 2000
        export.duration.inWholeMilliseconds.toDouble() shouldBeLessThan moments

        val vertical = ffmpeg.probe(export.videos.getValue(OutputFormat.VERTICAL))
        vertical.video?.width shouldBe 1080
        vertical.video?.height shouldBe 1920
        (vertical.duration.inWholeMilliseconds / 1000.0) shouldBe (export.duration.inWholeMilliseconds / 1000.0 plusOrMinus 0.3)
    }
})

private fun TimeRange.inWholeMs(): Double = length.inWholeMilliseconds.toDouble()
