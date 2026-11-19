package dev.highlights.editing

import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.HudOverlay
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.OverlayTarget
import dev.highlights.core.model.SafeArea
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WebcamCorner
import dev.highlights.core.model.WebcamFeed
import dev.highlights.core.model.WebcamSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WebcamLayoutTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val on = EditSettings(webcam = WebcamSettings(enabled = true))
    val feed = WebcamFeed(Path("cam.mp4"), 0, (-1.5).seconds, 30.minutes, 1280, 720)

    test("9:16 : bandeau en haut sur toute la largeur, 30 % de la hauteur, pair") {
        WebcamLayout.target(OutputFormat.VERTICAL, 1080, 1920, on) shouldBe RenderCommandBuilder.CropBox(0, 0, 1080, 576)
    }

    test("16:9 : coin en bas à gauche par défaut, 24 % de la largeur, marge de 2 %") {
        WebcamLayout.target(OutputFormat.LANDSCAPE, 1920, 1080, on) shouldBe RenderCommandBuilder.CropBox(38, 1080 - 258 - 38, 460, 258)
        val topRight = on.copy(webcam = on.webcam.copy(corner = WebcamCorner.TOP_RIGHT))
        WebcamLayout.target(OutputFormat.LANDSCAPE, 1920, 1080, topRight) shouldBe RenderCommandBuilder.CropBox(1920 - 460 - 38, 38, 460, 258)
    }

    test("16:9 d'une capture 21:9 : coin pris dans l'image du jeu, pas à cheval sur la bande noire") {
        val ultrawide = media.copy(video = VideoStream(0, "h264", 3440, 1440, 60.0))
        // Jeu ramené à 1920 x 802, bandes de 139 px : la webcam reste au-dessus de la bande du bas.
        WebcamLayout.target(OutputFormat.LANDSCAPE, 1920, 1080, on, ultrawide) shouldBe RenderCommandBuilder.CropBox(38, 139 + 802 - 258 - 38, 460, 258)
        WebcamLayout.target(OutputFormat.LANDSCAPE, 1920, 1080, on, media) shouldBe RenderCommandBuilder.CropBox(38, 1080 - 258 - 38, 460, 258)
    }

    test("webcam éteinte : pas d'emplacement, pas d'entrée, géométrie inchangée") {
        val off = EditSettings()
        WebcamLayout.target(OutputFormat.VERTICAL, 1080, 1920, off) shouldBe null
        val args = mutableListOf<String>()
        WebcamLayout.input(args, mapOf(media.path to feed), media, 10.seconds, 3.seconds, off) shouldBe null
        args.isEmpty() shouldBe true
        RenderCommandBuilder.geometry(OutputFormat.VERTICAL, 1920, 1080, off) shouldNotContain "pad="
    }

    test("entrée de la webcam : même extrait décalé, rang = nombre d'entrées déjà posées") {
        val args = mutableListOf("-i", "a.mp4", "-i", "b.mp4")
        val input = WebcamLayout.input(args, mapOf(media.path to feed), media, 10.seconds, 3.seconds, on)!!
        input.index shouldBe 2
        input.lead shouldBe Duration.ZERO
        args.drop(4) shouldBe listOf("-ss", "8.500000", "-t", "3.000", "-i", "cam.mp4")
    }

    test("webcam commencée après l'extrait : lue depuis son début, noir devant") {
        val args = mutableListOf<String>()
        val input = WebcamLayout.input(args, mapOf(media.path to feed), media, 1.seconds, 3.seconds, on)!!
        input.lead shouldBe 500.milliseconds
        args shouldBe listOf("-ss", "0.000000", "-t", "2.500", "-i", "cam.mp4")
        WebcamLayout.piece(input, RenderCommandBuilder.CropBox(0, 0, 1080, 576), OutputFormat.VERTICAL, on) shouldContain "tpad=start_duration=0.500:color=black"
    }

    test("aucune image de webcam sur l'extrait : pas d'entrée (une entrée vide couperait le plan)") {
        val short = feed.copy(duration = 5.seconds, offset = Duration.ZERO)
        WebcamLayout.input(mutableListOf(), mapOf(media.path to short), media, 10.seconds, 3.seconds, on) shouldBe null
        WebcamLayout.input(mutableListOf(), mapOf(media.path to feed.copy(offset = (-20).seconds)), media, 10.seconds, 3.seconds, on) shouldBe null
    }

    test("9:16 avec bandeau : jeu recadré sur la hauteur restante, posé sous le bandeau") {
        val g = RenderCommandBuilder.geometry(OutputFormat.VERTICAL, 1920, 1080, on)
        // 1080 x 1344 (1920 - 576) : rapport 0,804 → 866 px de large (pair) sur 1080 de haut.
        g shouldContain "crop=866:1080:527:0"
        g shouldContain "scale=1080:1344"
        g shouldContain "pad=1080:1920:0:576"
    }

    test("webcam recadrée au rapport de l'emplacement, sur la zone choisie") {
        val input = WebcamLayout.Input(3, feed, Duration.ZERO)
        val face = on.copy(webcam = on.webcam.copy(crop = CropRegion(0.25, 0.0, 0.5, 1.0)))
        val piece = WebcamLayout.piece(input, RenderCommandBuilder.CropBox(0, 0, 1080, 576), OutputFormat.VERTICAL, face)
        piece shouldContain "[3:v:0]setpts=PTS-STARTPTS,fps=60"
        // Zone 640x720 au rapport 1080:576 (1,875) → 640 x 340, centrée sur la zone.
        piece shouldContain "crop=640:340:320:190"
        piece shouldContain "scale=1080:576"
        piece shouldContain "tpad=stop_mode=clone:stop=-1"
    }

    test("liseré : sous le bandeau en 9:16, tout autour du coin en 16:9") {
        val input = WebcamLayout.Input(1, feed, Duration.ZERO)
        val bordered = on.copy(webcam = on.webcam.copy(border = 4))
        WebcamLayout.piece(input, RenderCommandBuilder.CropBox(0, 0, 1080, 576), OutputFormat.VERTICAL, bordered) shouldContain "scale=1080:572:flags=lanczos,setsar=1,pad=1080:576:0:0:color=white"
        WebcamLayout.piece(input, RenderCommandBuilder.CropBox(38, 784, 460, 258), OutputFormat.LANDSCAPE, bordered) shouldContain "scale=452:250:flags=lanczos,setsar=1,pad=460:258:4:4:color=white"
    }

    test("rendu simple : webcam collée à sa place après l'étalonnage du jeu") {
        val input = WebcamLayout.Input(1, feed, Duration.ZERO)
        val graded = on.copy(grade = on.grade.copy(saturation = 1.2))
        val chain = RenderCommandBuilder.videoChain(0, media, OutputFormat.LANDSCAPE, graded, "v0", webcam = input).joinToString(";")
        chain shouldContain "[c0l0]eq=saturation=1.200"
        chain shouldContain "[c0gr][c0cam]overlay=x=38:y=784:shortest=1,format=yuv420p,settb=AVTB[v0]"
        // La webcam elle-même n'est pas étalonnée.
        chain.substringAfter("[1:v:0]").substringBefore("[c0cam]") shouldNotContain "eq="
    }

    test("trame empilée : webcam sous l'image et le HUD, rendue à la largeur de l'image") {
        val hud = HudOverlay("bandeau", CropRegion(0.0, 0.0, 0.2, 0.1), OverlayTarget(0.0, 0.05, 0.5))
        val settings = on.copy(vertical = VerticalSettings(hud = listOf(hud)))
        val input = WebcamLayout.Input(2, feed, Duration.ZERO)
        val chain = RenderCommandBuilder.videoChain(0, media, OutputFormat.VERTICAL, settings, "g0", pinHud = true, webcam = input).joinToString(";")
        chain shouldContain ",pad=1080:576:0:0[c0cam]"
        chain shouldContain "vstack=inputs=2:shortest=1"
        chain shouldContain "[c0gr][c0cam]vstack=inputs=2:shortest=1,format=yuv420p,settb=AVTB[g0]"
    }

    test("HUD et point visé par les zooms descendent dans la partie du jeu, sous le bandeau") {
        val hud = HudOverlay("bandeau", CropRegion(0.0, 0.0, 0.2, 0.1), OverlayTarget(0.0, 0.0, 0.5))
        val settings = on.copy(vertical = VerticalSettings(hud = listOf(hud)))
        RenderCommandBuilder.hudTargets(OutputFormat.VERTICAL, media, settings).single().y shouldBe 576
        val (_, y) = RenderCommandBuilder.zoomFocus(OutputFormat.VERTICAL, media, on)
        y shouldBe ((576 + 0.5 * 1344) / 1920.0 plusOrMinus 1e-9)
        // Le jeu est réduit à 1344 px de haut pour 1080 px de capture.
        RenderCommandBuilder.pixelScale(OutputFormat.VERTICAL, media, on) shouldBe (1344.0 / 1080 plusOrMinus 1e-9)
    }

    test("trame empilée : la webcam en est sortie, l'image retrouve sa taille sans HUD") {
        val graph = mutableListOf<String>()
        val input = WebcamLayout.Input(2, feed, Duration.ZERO)
        val box = RenderCommandBuilder.CropBox(0, 0, 1080, 576)
        val (game, cam) = WebcamLayout.detach(graph, "s0", input, box, 0, 1080, 1920, "wd0")
        game shouldBe "wd0g"
        cam shouldBe "wd0c"
        graph shouldBe listOf("[s0]split=2[wd0m][wd0w]", "[wd0m]crop=1080:1920:0:0[wd0g]", "[wd0w]crop=1080:576:0:1920[wd0c]")
        WebcamLayout.detach(mutableListOf(), "s0", null, box, 0, 1080, 1920, "wd0") shouldBe ("s0" to null)
    }

    test("webcam d'un plan à sa longueur en images ; plan sans webcam : image transparente") {
        val graph = mutableListOf<String>()
        val box = RenderCommandBuilder.CropBox(0, 0, 1080, 576)
        WebcamLayout.finish(graph, "wd0c", box, listOf("tpad=start_mode=clone:start_duration=0.200"), 90, 60, "wc0")
        WebcamLayout.finish(graph, null, box, emptyList(), 45, 60, "wc1")
        graph[0] shouldContain "[wd0c]tpad=start_mode=clone:start_duration=0.200,tpad=stop_mode=clone:stop=-1,setpts=PTS-STARTPTS,trim=end_frame=90"
        graph[1] shouldContain "color=c=black@0.0:s=1080x576:r=60"
        graph[1] shouldContain "trim=end_frame=45"
        WebcamLayout.overlay(graph, "vcat", listOf("wc0", "wc1"), box) shouldBe "wcam"
        graph.last() shouldBe "[vcat][wcat]overlay=x=0:y=0:shortest=1[wcam]"
        WebcamLayout.overlay(mutableListOf(), "vcat", emptyList(), box) shouldBe "vcat"
    }

    test("9:16 avec bandeau : la zone sûre des textes commence sous la webcam ; 16:9 inchangé") {
        WebcamLayout.settingsFor(on, OutputFormat.VERTICAL).safeArea.top shouldBe (0.31 plusOrMinus 1e-9)
        WebcamLayout.settingsFor(on, OutputFormat.LANDSCAPE) shouldBe on
        val platform = on.copy(safeArea = SafeArea(top = 0.4))
        WebcamLayout.settingsFor(platform, OutputFormat.VERTICAL).safeArea.top shouldBe 0.4
    }
})
