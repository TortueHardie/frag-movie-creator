package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import dev.highlights.editing.CutInput
import dev.highlights.editing.SourceCuts
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MontageRenderBuilderTest : FunSpec({
    val media = MediaInfo(
        Path("partie.mp4"), 1, 30.minutes,
        video = VideoStream(0, "h264", 3440, 1440, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000), AudioStream(2, 1, "aac", 2, 48000)),
    )
    val settings = MontageSettings(killOffset = Duration.ZERO)
    val music = MusicAnalysis(
        Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
        listOf(MusicSection(0, 96, -14.0, 0.4), MusicSection(96, 240, -8.0, 1.0)), 96,
    )

    fun plan(s: MontageSettings = settings): MontagePlan {
        val groups = listOf(
            KillGroup(media, listOf(100.seconds, 102.seconds), 1.0, emptyList(), listOf(TimeRange(101.seconds, 102.8.seconds))),
            KillGroup(media, listOf(500.seconds), 0.5, emptyList(), emptyList()),
        )
        return MontagePlanner.plan(groups, music, s)
    }

    val edit = EditSettings(audioStreams = listOf(0))

    fun build(p: MontagePlan, format: OutputFormat = OutputFormat.VERTICAL, cuts: SourceCuts = SourceCuts.NONE) = MontageRenderBuilder.build(
        MontageRenderRequest(p, format, edit, EncoderProfile("h264_amf", listOf("-c:v", "h264_amf"), true), Path("out.mp4"), Path("f.txt"), cuts = cuts),
    )

    test("pré-découpes lues à la place des sources : les intervalles annoncés sont ceux du graphe") {
        val p = plan()
        val cuts = MontageRenderBuilder.sourceCuts(p, edit.fps)
        val inputs = cuts.withIndex().associate { (i, cut) -> cut to CutInput(Path("cuts/cut_$i.mp4"), 1.seconds) }
        val args = build(p, cuts = SourceCuts.of(inputs)).command.args

        // Un fichier de découpe par clip, départ recalé sur son début, et plus aucune lecture de la capture d'origine.
        cuts.size shouldBe p.clips.size
        inputs.values.forEach { args shouldContainInOrder listOf("-ss", "1.000000", "-i", it.path.toString()) }
        args shouldNotContain media.path.toString()
    }

    test("effets, ralenti, textes et mixage présents") {
        val cmd = build(plan())
        val g = cmd.filterGraph
        g shouldContain "setpts=(PTS-STARTPTS)/0.5000"
        g shouldContain "atempo=0.5000"
        g shouldContain "eval=frame:flags=bilinear"
        g shouldContain "fade=t=in:st=0:d=0.120:color=white"
        g shouldContain "text='DOUBLÉ'"
        g shouldContain "text='KILL 3'"
        g shouldContain """fontfile='C\:/Windows/Fonts/impact.ttf'"""
        g shouldContain "concat=n=2:v=1:a=0[vcat]"
        g shouldContain "amix=inputs=2:normalize=0:duration=longest"
        g shouldContain "amix=inputs=2:normalize=0:duration=first"
        g shouldContain "[vcat]fade=t=out"
        // La musique baisse pendant la réaction du premier clip.
        g shouldContain "if(gt(between(t"
        cmd.command.args.count { it == "-i" } shouldBe 3
    }

    test("longueurs alignées à l'image, sans dérive") {
        val p = plan()
        val cmd = build(p)
        val frames = Math.round(p.duration.inWholeMicroseconds * 60 / 1_000_000.0)
        (cmd.expectedDuration.inWholeMicroseconds * 60 / 1_000_000.0) shouldBe frames.toDouble()
    }

    test("effets désactivables") {
        val off = settings.copy(
            zoom = settings.zoom.copy(enabled = false), flash = settings.flash.copy(enabled = false),
            slowMotion = settings.slowMotion.copy(enabled = false), text = settings.text.copy(enabled = false),
        )
        val g = build(plan(off), OutputFormat.SOURCE).filterGraph
        g shouldNotContain "eval=frame:flags=bilinear"
        g shouldNotContain "color=white"
        g shouldNotContain "atempo"
        g shouldNotContain "drawtext"
    }

    test("volume du jeu : fort aux kills et pendant la voix") {
        val expr = MontageRenderBuilder.volumeExpression(0.3, 1.0, 1.0, listOf(2.seconds), listOf(TimeRange(3.seconds, 4.seconds)))
        expr shouldBe """max(max(0.3000\,1.0000*between(t\,1.850\,2.600))\,1.0000*between(t\,3.000\,4.000))"""
    }
})
