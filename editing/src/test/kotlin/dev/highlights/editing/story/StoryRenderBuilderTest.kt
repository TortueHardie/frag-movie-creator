package dev.highlights.editing.story

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import dev.highlights.editing.PlannedClip
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StoryRenderBuilderTest : FunSpec({
    val media = MediaInfo(
        path = Path("D:/captures/partie.mp4"),
        sizeBytes = 1,
        duration = 10.minutes,
        video = VideoStream(0, "h264", 1920, 1080, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000, "Game")),
    )
    val h1 = Highlight("h1", media.path, TimeRange(10.seconds, 24.seconds), 12.seconds, 0.6)
    val h2 = Highlight("h2", media.path, TimeRange(60.seconds, 70.seconds), 65.seconds, 0.9)
    val settings = EditSettings(style = EditStyle.STORY, fps = 60)
    val encoder = EncoderProfile("libx264", listOf("-c:v", "libx264"), hardware = false)

    fun plan(edit: EditSettings = settings) = StoryPlan(
        shots = listOf(
            StoryShot(media, TimeRange(sec(63.2), sec(66.2)), h2, ShotRole.COLD_OPEN, shakes = listOf(1800.milliseconds)),
            StoryShot(media, TimeRange(10.seconds, sec(14.3)), h1, ShotRole.OPENING, voice = listOf(TimeRange(1.seconds, 2.seconds))),
            StoryShot(media, TimeRange(sec(19.7), 24.seconds), h1, ShotRole.JUMP, zoom = 1.08, punchIns = listOf(TimeRange(1.seconds, 3.seconds))),
            StoryShot(media, TimeRange(60.seconds, 70.seconds), h2, ShotRole.OPENING, shakes = listOf(5.seconds), drops = listOf(5.seconds)),
        ),
        moments = listOf(PlannedClip(h1, media), PlannedClip(h2, media)),
        settings = edit,
    )

    fun build(p: StoryPlan = plan(), format: OutputFormat = OutputFormat.SOURCE) =
        StoryRenderBuilder.build(StoryRenderRequest(p, format, encoder, Path("out/x.part.mp4"), Path("work/filters.txt")))

    test("une entrée par plan, plans concaténés, durée = somme des plans") {
        val cmd = build()
        cmd.command.args.shouldContainInOrder(
            "-ss", "63.200000", "-t", "3.000", "-i", media.path.toString(),
            "-ss", "10.000000", "-t", "4.300", "-i", media.path.toString(),
            "-ss", "19.700000", "-t", "4.300", "-i", media.path.toString(),
            "-ss", "60.000000", "-t", "10.000", "-i", media.path.toString(),
        )
        cmd.filterGraph shouldContain "concat=n=4:v=1:a=1[vcat][acat]"
        cmd.expectedDuration shouldBe sec(21.6)
    }

    test("flash à l'ouverture de chaque moment, sauf en tout début de vidéo") {
        val flashes = build().lines().filter { "color=white" in it }.map { it.substringAfterLast("[") }
        flashes shouldBe listOf("v1]", "v3]")
    }

    test("cadrage alterné et punch-in : scale évalué à chaque image puis recadrage au centre") {
        val line = build().lines().single { it.endsWith("[v2]") }
        line shouldContain "scale=w='trunc(1920*(1.0800*(1+0.1500*"
        line shouldContain ":eval=frame"
        line shouldContain "crop=1920:1080:(iw-1920)/2:(ih-1080)/2"
    }

    test("secousse : recadrage mobile borné à l'image, pas de bord noir") {
        val line = build().lines().single { it.endsWith("[v3]") }
        line shouldContain "crop=1920:1080:x='clip((iw-1920)/2+"
        line shouldContain "\\,0\\,iw-1920)'"
    }

    test("plan en plein cadre sans effet : aucune mise à l'échelle superflue") {
        val line = build().lines().single { it.endsWith("[v1]") }
        line shouldNotContain "eval=frame"
    }

    test("son adouci à chaque coupe, whoosh sur les changements de moment, impact sur les secousses") {
        val graph = build().filterGraph
        graph shouldContain "afade=t=in:d=0.015,afade=t=out:st=4.285:d=0.015"
        // Un whoosh à chaque nouveau moment (après l'accroche à 3 s, puis à 11,6 s), qui culmine 300 ms plus tard sur
        // la coupe ; un impact sous chaque secousse (1,8 s, puis 11,6 + 5 s).
        graph shouldContain "anoisesrc="
        graph shouldContain "asplit=2[whs0][whs1]"
        graph shouldContain "[whs0]adelay=2700|2700[wh0]"
        graph shouldContain "[whs1]adelay=11300|11300[wh1]"
        graph shouldContain "aevalsrc="
        graph shouldContain "[ims0]adelay=1800|1800[im0]"
        graph shouldContain "[ims1]adelay=16600|16600[im1]"
        graph shouldContain "[acat][wh0][wh1][im0][im1]amix=inputs=5:normalize=0:duration=first"
        graph shouldContain "loudnorm=I=-14.0"
    }

    test("bruitages désactivés : le son du jeu seul") {
        val edit = settings.copy(story = settings.story.copy(sfx = settings.story.sfx.copy(enabled = false)))
        val graph = build(plan(edit)).filterGraph
        graph shouldNotContain "anoisesrc"
        graph shouldContain "[acat]afade=t=out"
    }

    test("musique de fond en boucle, baissée sous la voix et coupée sur le pic") {
        val edit = settings.copy(story = settings.story.copy(music = settings.story.music.copy(file = "D:/musique/fond.mp3")))
        val cmd = build(plan(edit))
        cmd.command.args.shouldContainInOrder("-stream_loop", "-1", "-i", "D:/musique/fond.mp3")
        val music = cmd.lines().single { it.endsWith("[music]") }
        music shouldContain "[4:a]"
        // Voix du 2e plan : 3 s + 1 s = 4 s ; pic du dernier plan : 3 + 4,3 + 4,3 + 5 = 16,6 s.
        music shouldContain "min(max((t-3.920)/0.0800"
        music shouldContain "*(1-(min(max((t-16.580)/0.0200"
    }

    test("fondu au noir final, image et son") {
        val graph = build().filterGraph
        graph shouldContain "[vcat]fade=t=out:st=21.000:d=0.600"
        graph shouldContain "afade=t=out:st=21.000:d=0.600"
    }

    test("sous-titres dessinés après le cadrage : ils ne suivent ni le zoom ni la secousse") {
        val base = plan()
        val captioned = base.copy(shots = base.shots.mapIndexed { i, s -> if (i == 2) s.copy(captions = listOf(Caption(TimeRange(1.seconds, 2.seconds), "vas-y"))) else s })
        val line = build(captioned).lines().single { it.endsWith("[v2]") }
        line.indexOf("drawtext") shouldBeGreaterThan line.indexOf("crop=")
        line shouldContain "text='VAS-Y'"
        // En vertical, le texte remonte (le bas de l'image est souvent couvert par l'interface de la plateforme).
        build(captioned, OutputFormat.VERTICAL).lines().single { it.endsWith("[v2]") } shouldContain "y=h*0.660-text_h/2"
    }

    test("9:16 : même montage recadré") {
        val cmd = build(format = OutputFormat.VERTICAL)
        cmd.filterGraph shouldContain "scale=1080:1920"
        cmd.filterGraph shouldContain "crop=1080:1920:(iw-1080)/2:(ih-1920)/2"
    }
})

/** Instructions du graphe, sans le « ; » qui les sépare. */
private fun dev.highlights.editing.RenderCommand.lines() = filterGraph.lines().map { it.removeSuffix(";") }

private fun sec(s: Double) = (s * 1000).toLong().milliseconds
