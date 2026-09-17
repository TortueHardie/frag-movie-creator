package dev.highlights.editing

import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.ClipOrder
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.HudOverlay
import dev.highlights.core.model.OverlayTarget
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TransitionSettings
import dev.highlights.core.model.TransitionType
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RenderCommandBuilderTest : FunSpec({
    val media = MediaInfo(
        path = Path("D:/captures/League of Legends/game 1.mkv"),
        sizeBytes = 1,
        duration = 30.minutes,
        video = VideoStream(0, "hevc", 2560, 1440, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000, "Game"), AudioStream(2, 1, "aac", 1, 48000, "Mic")),
    )

    fun session(vararg ranges: Pair<TimeRange, Double>, enabled: Set<Int> = ranges.indices.toSet()) = Session(
        createdAt = Instant.EPOCH,
        media = media,
        profileId = "lol",
        timeline = ScoredTimeline(WindowGrid(1.seconds, 1.seconds, 1.seconds), listOf(0.0), emptyMap()),
        highlights = ranges.mapIndexed { i, (r, s) -> Highlight("h$i", media.path, r, r.start, s, enabled = i in enabled) },
    )

    val encoder = EncoderProfile("h264_amf", listOf("-c:v", "h264_amf"), hardware = true)
    fun request(plan: EditPlan, format: OutputFormat = OutputFormat.LANDSCAPE) =
        RenderRequest(plan, format, encoder, Path("out/x.part.mp4"), Path("work/filters.txt"), hwaccel = "d3d11va")

    val clipA = TimeRange(60.seconds, 70.seconds) to 0.7
    val clipB = TimeRange(300.seconds, 312.seconds) to 0.9

    test("une entrée avec seek rapide par clip, graphe dans un fichier") {
        val plan = DefaultEditPlanner.plan(session(clipA, clipB), EditSettings(transition = TransitionSettings(TransitionType.CUT)))
        val cmd = RenderCommandBuilder.build(request(plan))
        cmd.command.args.shouldContainInOrder(
            "-hwaccel", "d3d11va", "-ss", "60.000", "-t", "10.000", "-i", media.path.toString(),
            "-hwaccel", "d3d11va", "-ss", "300.000", "-t", "12.000", "-i", media.path.toString(),
            "-/filter_complex", Path("work/filters.txt").toString(),
            "-c:v", "h264_amf",
        )
        cmd.filterGraph shouldContain "concat=n=2:v=1:a=1[vcat][acat]"
        cmd.filterGraph shouldContain "[0:a:0][0:a:1]amix=inputs=2"
        cmd.filterGraph shouldContain "loudnorm=I=-14.0"
        cmd.filterGraph shouldNotContain "trim=start"
        cmd.expectedDuration shouldBe 22.seconds
    }

    test("fondus : offsets xfade cumulés") {
        val c = TimeRange(400.seconds, 408.seconds) to 0.5
        val plan = DefaultEditPlanner.plan(session(clipA, clipB, c), EditSettings(transition = TransitionSettings(TransitionType.FADE, 500.milliseconds)))
        val cmd = RenderCommandBuilder.build(request(plan))
        cmd.filterGraph shouldContain "[v0][v1]xfade=transition=fade:duration=0.500:offset=9.500[vx1]"
        cmd.filterGraph shouldContain "[vx1][v2]xfade=transition=fade:duration=0.500:offset=21.000[vx2]"
        cmd.filterGraph shouldContain "[ax1][a2]acrossfade=d=0.500"
        cmd.expectedDuration shouldBe 29.seconds
    }

    test("pistes audio choisies et ordre par score") {
        val plan = DefaultEditPlanner.plan(session(clipA, clipB), EditSettings(audioStreams = listOf(0), order = ClipOrder.SCORE))
        plan.clips.map { it.highlight.id } shouldBe listOf("h1", "h0")
        val cmd = RenderCommandBuilder.build(request(plan))
        cmd.filterGraph shouldContain "[0:a:0]asetpts"
        cmd.filterGraph shouldNotContain "amix"
    }

    test("segments décochés exclus, erreur si plus rien") {
        DefaultEditPlanner.plan(session(clipA, clipB, enabled = setOf(1)), EditSettings()).clips.size shouldBe 1
        shouldThrow<HighlightsException> { DefaultEditPlanner.plan(session(clipA, enabled = emptySet()), EditSettings()) }
    }

    test("9:16 recadré au centre sur une source 1440p") {
        RenderCommandBuilder.cropBox(2560, 1440, 9.0 / 16, null) shouldBe RenderCommandBuilder.CropBox(875, 0, 810, 1440)
        val plan = DefaultEditPlanner.plan(session(clipA), EditSettings())
        RenderCommandBuilder.build(request(plan, OutputFormat.VERTICAL)).filterGraph shouldContain "crop=810:1440:875:0,scale=1080:1920"
    }

    test("format source : ratio de la capture conservé (21:9 → 2580x1080)") {
        val ultrawide = media.copy(video = VideoStream(0, "h264", 3440, 1440, 60.0))
        RenderCommandBuilder.videoChain(0, ultrawide, OutputFormat.SOURCE, EditSettings(), "v0").single() shouldContain "scale=2580:1080"
        RenderCommandBuilder.videoChain(0, media, OutputFormat.SOURCE, EditSettings(), "v0").single() shouldContain "scale=1920:1080"
    }

    test("9:16 crop and replace : zones du HUD découpées puis superposées") {
        val ultrawide = media.copy(video = VideoStream(0, "h264", 3440, 1440, 60.0))
        val settings = EditSettings(
            vertical = VerticalSettings(
                hud = listOf(
                    HudOverlay("minimap", CropRegion(0.0, 0.5, 0.1, 0.5), OverlayTarget(0.0, 0.5, 0.5)),
                    HudOverlay("off", CropRegion(0.0, 0.0, 0.1, 0.1), OverlayTarget(0.0, 0.0, 0.1), enabled = false),
                ),
            ),
        )
        val chain = RenderCommandBuilder.videoChain(2, ultrawide, OutputFormat.VERTICAL, settings, "v2")
        chain.first() shouldContain "split=2[c2base][c2hud0]"
        chain.contains("[c2base]crop=810:1440:1315:0,scale=1080:1920:flags=lanczos,setsar=1[c2l0]") shouldBe true
        // Zone 344x720 à (0, 720), mise à l'échelle sur 540 px de large → 540x1130, placée en (0, 790) bornée à l'image.
        chain.contains("[c2hud0]crop=344:720:0:720,scale=540:1130:flags=lanczos,setsar=1[c2o0]") shouldBe true
        chain.contains("[c2l0][c2o0]overlay=x=0:y=790:shortest=1[c2l1]") shouldBe true
        chain.last() shouldBe "[c2l1]format=yuv420p,settb=AVTB[v2]"
        chain.none { it.contains("hud1") } shouldBe true
    }

    test("9:16 recadré sur une zone, borné à l'image") {
        // Zone étroite collée au bord droit : la boîte est limitée à la largeur de la zone puis recalée dans l'image.
        val box = RenderCommandBuilder.cropBox(1920, 1080, 9.0 / 16, CropRegion(0.9, 0.0, 0.1, 1.0))
        box.w shouldBe 192
        (box.x + box.w <= 1920) shouldBe true
        box.h % 2 shouldBe 0
    }
})
