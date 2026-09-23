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
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.editing.PlannedClip
import dev.highlights.editing.story.ShotRole
import dev.highlights.editing.story.StoryPlan
import dev.highlights.editing.story.StoryRenderBuilder
import dev.highlights.editing.story.StoryRenderRequest
import dev.highlights.editing.story.StoryShot
import dev.highlights.ffmpeg.FfmpegEncoderSelector
import kotlin.io.path.writeText
import kotlin.time.Duration
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

/**
 * Régression : un plan zoomé dès sa première image (cadrage alterné d'un jump cut) sortait avec des pixels non carrés
 * (le zoom arrondit largeur et hauteur séparément), et concat refusait tout le montage. Le premier test ne le voyait
 * pas : la vidéo de test n'a pas de jump cut.
 */
class StoryZoomIT : FunSpec({
    test("plans zoomés et secoués dès la première image, de tailles de source différentes : le rendu passe").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        // Ultrawide, comme la capture où le problème est apparu.
        val source = TestMedia.generate(root.resolve("partie.mp4"), durationSeconds = 20, size = "860x360")
        val media = ffmpeg.probe(source)
        val h = Highlight("h1", source, TimeRange(2.seconds, 12.seconds), 5.seconds, 0.8)
        val settings = EditSettings(style = EditStyle.STORY, fps = 30)
        val plan = StoryPlan(
            shots = listOf(
                StoryShot(media, TimeRange(2.seconds, 5.seconds), h, ShotRole.OPENING, zoom = 1.08),
                StoryShot(media, TimeRange(7.seconds, 10.seconds), h, ShotRole.JUMP, zoom = 1.08, shakes = listOf(Duration.ZERO)),
                StoryShot(media, TimeRange(10.seconds, 12.seconds), h, ShotRole.JUMP, punchIns = listOf(TimeRange(Duration.ZERO, 1.seconds))),
            ),
            moments = listOf(PlannedClip(h, media)),
            settings = settings,
        )
        val encoder = FfmpegEncoderSelector(ffmpeg, EncoderSettings(preference = listOf("libx264"))).select()
        val out = root.resolve("story.mp4")
        val script = root.resolve("filters.txt")
        val render = StoryRenderBuilder.build(StoryRenderRequest(plan, OutputFormat.SOURCE, encoder, out, script, filterScriptOption = ffmpeg.filterScriptOption()))
        script.writeText(render.filterGraph)
        ffmpeg.run(render.command)
        (ffmpeg.probe(out).duration.inWholeMilliseconds / 1000.0) shouldBe (8.0 plusOrMinus 0.3)
    }
})

private fun TimeRange.inWholeMs(): Double = length.inWholeMilliseconds.toDouble()
