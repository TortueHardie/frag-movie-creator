package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.model.WebcamFeed
import dev.highlights.core.model.WebcamSettings
import dev.highlights.editing.EditPlan
import dev.highlights.editing.PlannedClip
import dev.highlights.editing.RenderCommand
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.RenderRequest
import dev.highlights.editing.story.ShotRole
import dev.highlights.editing.story.StoryPlan
import dev.highlights.editing.story.StoryRenderBuilder
import dev.highlights.editing.story.StoryRenderRequest
import dev.highlights.editing.story.StoryShot
import dev.highlights.ffmpeg.ProcessFfmpegService
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Rendus FFmpeg réels avec une webcam : le jeu est rouge, la webcam bleue. Dans les trois rendus (simple, story, montage
 * kills), chaque image échantillonnée doit montrer la webcam bleue à sa place, même pendant un flash blanc, un punch-in
 * ou un zoom, et le jeu ailleurs. Une webcam collée dans l'image avant les effets blanchissait avec le flash.
 */
class WebcamIT : FunSpec({
    val enc = EncoderProfile("libx264", listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "30"), hardware = false)
    val small = VerticalSettings(size = FrameSize(360, 640))
    val edit = EditSettings(fps = 30, vertical = small, landscape = FrameSize(640, 360), audioStreams = listOf(0), webcam = WebcamSettings(enabled = true))

    lateinit var ffmpeg: ProcessFfmpegService
    lateinit var dir: Path
    lateinit var media: MediaInfo
    lateinit var feeds: Map<Path, WebcamFeed>

    beforeSpec {
        if (!TestMedia.available) return@beforeSpec
        ffmpeg = TestMedia.requireFfmpeg()
        dir = tempdir().toPath()
        val game = dir.resolve("partie.mp4")
        val cam = dir.resolve("partie_webcam.mp4")
        suspend fun make(color: String, size: String, out: Path) = ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-y", "-f", "lavfi", "-i", "color=c=$color:s=$size:r=30:d=40", "-f", "lavfi", "-i", "sine=f=440:d=40",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.toString(),
                ),
                "média de test $color",
            ),
        )
        make("red", "640x360", game)
        make("blue", "320x180", cam)
        media = ffmpeg.probe(game)
        feeds = mapOf(media.path to WebcamFeed(cam, 0, (-1).seconds, 40.seconds, 320, 180))
    }

    /** Lance [render] puis renvoie les images de la vidéo produite, en RVB, à 10 img/s. */
    suspend fun frames(render: RenderCommand, script: Path, output: Path, w: Int, h: Int): List<ByteArray> {
        script.writeText(render.filterGraph)
        ffmpeg.run(render.command)
        val bytes = java.io.ByteArrayOutputStream()
        ffmpeg.run(
            FfmpegCommand(listOf("-i", output.toString(), "-vf", "fps=10", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"), "images de test"),
            stdout = StdoutHandler.Binary { it.copyTo(bytes) },
        )
        val size = w * h * 3
        val data = bytes.toByteArray()
        return (0 until data.size / size).map { data.copyOfRange(it * size, (it + 1) * size) }
    }

    fun pixel(frame: ByteArray, w: Int, x: Int, y: Int): Triple<Int, Int, Int> {
        val p = (y * w + x) * 3
        return Triple(frame[p].toInt() and 0xFF, frame[p + 1].toInt() and 0xFF, frame[p + 2].toInt() and 0xFF)
    }

    fun blue(c: Triple<Int, Int, Int>) = c.third > 180 && c.first < 80 && c.second < 80
    fun reddish(c: Triple<Int, Int, Int>) = c.first > 150 && c.third < 150

    test("highlights simples en 16:9 : webcam dans le coin bas gauche, jeu ailleurs").config(enabledIf = { TestMedia.available }, timeout = 120.seconds) {
        val h = Highlight("h1", media.path, TimeRange(5.seconds, 8.seconds), 6.seconds, 1.0)
        val plan = EditPlan(listOf(PlannedClip(h, media)), edit.copy(formats = listOf(OutputFormat.LANDSCAPE)), Duration.ZERO)
        val out = dir.resolve("simple.mp4")
        val script = dir.resolve("simple.txt")
        val render = RenderCommandBuilder.build(
            RenderRequest(plan, OutputFormat.LANDSCAPE, enc, out, script, filterScriptOption = ffmpeg.filterScriptOption(), webcams = feeds),
        )
        val box = dev.highlights.editing.WebcamLayout.target(OutputFormat.LANDSCAPE, 640, 360, edit)!!
        val images = frames(render, script, out, 640, 360)
        images.size shouldBeGreaterThan 20
        images.forEach { f ->
            blue(pixel(f, 640, box.x + box.w / 2, box.y + box.h / 2)) shouldBe true
            reddish(pixel(f, 640, 400, 100)) shouldBe true
        }
    }

    test("story en 9:16 : bandeau bleu intact pendant le flash et le punch-in, jeu rouge dessous").config(enabledIf = { TestMedia.available }, timeout = 120.seconds) {
        val h1 = Highlight("h1", media.path, TimeRange(5.seconds, 9.seconds), 7.seconds, 1.0)
        val h2 = Highlight("h2", media.path, TimeRange(20.seconds, 24.seconds), 22.seconds, 1.0)
        val story = edit.copy(style = EditStyle.STORY)
        val plan = StoryPlan(
            shots = listOf(
                StoryShot(media, TimeRange(5.seconds, 8.seconds), h1, ShotRole.OPENING, shakes = listOf(1.seconds)),
                StoryShot(media, TimeRange(20.seconds, 23.seconds), h2, ShotRole.OPENING, zoom = 1.3, punchIns = listOf(TimeRange(0.5.seconds, 2.5.seconds))),
            ),
            moments = listOf(PlannedClip(h1, media), PlannedClip(h2, media)),
            settings = story.copy(story = story.story.copy(transition = story.story.transition.copy(flash = true, flashDuration = 300.milliseconds))),
        )
        val out = dir.resolve("story.mp4")
        val script = dir.resolve("story.txt")
        val render = StoryRenderBuilder.build(
            StoryRenderRequest(plan, OutputFormat.VERTICAL, enc, out, script, filterScriptOption = ffmpeg.filterScriptOption(), webcams = feeds),
        )
        val images = frames(render, script, out, 360, 640)
        images.size shouldBeGreaterThan 50
        // Fondu de fin exclu : il assombrit toute l'image, webcam comprise.
        images.dropLast(10).forEach { f ->
            // Bandeau : 30 % de 640 = 192 px.
            blue(pixel(f, 360, 180, 96)) shouldBe true
            blue(pixel(f, 360, 10, 180)) shouldBe true
        }
        // Le flash blanc de l'ouverture du second moment (3 s) blanchit le jeu, pas la webcam.
        val flash = images[30]
        val game = pixel(flash, 360, 180, 420)
        (game.second > 120 && game.third > 120) shouldBe true
        reddish(pixel(images[15], 360, 180, 420)) shouldBe true
    }

    test("montage kills en 9:16 : bandeau bleu intact pendant zooms et flashs").config(enabledIf = { TestMedia.available }, timeout = 180.seconds) {
        val music = dir.resolve("musique.wav")
        ffmpeg.run(FfmpegCommand(listOf("-y", "-f", "lavfi", "-i", "sine=f=220:d=120", music.toString()), "musique de test"))
        val analysis = MusicAnalysis(
            music, 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
            listOf(MusicSection(0, 16, -14.0, 0.4), MusicSection(16, 240, -8.0, 1.0)), 16,
        )
        val groups = listOf(
            KillGroup(media, listOf(10.seconds, 11.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(media, listOf(25.seconds), 0.5, emptyList(), emptyList()),
        )
        val settings = MontageSettings(killOffset = Duration.ZERO).earlierDefaults()
        val plan = MontagePlanner.plan(groups, analysis, settings.copy(flash = settings.flash.copy(enabled = true, onEveryCut = true)))
        val out = dir.resolve("montage.mp4")
        val script = dir.resolve("montage.txt")
        val render = MontageRenderBuilder.build(
            MontageRenderRequest(plan, OutputFormat.VERTICAL, edit, enc, out, script, filterScriptOption = ffmpeg.filterScriptOption(), webcams = feeds),
        )
        val images = frames(render, script, out, 360, 640)
        images.size shouldBeGreaterThan 10
        // Fondu de fin exclu : il assombrit toute l'image, webcam comprise.
        images.dropLast(8).forEach { f ->
            blue(pixel(f, 360, 180, 96)) shouldBe true
        }
        images.dropLast(8).count { reddish(pixel(it, 360, 180, 420)) } shouldBeGreaterThan images.size / 2
    }
})
