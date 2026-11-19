package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.CardBackground
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.IntroOutroSettings
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.editing.CardRequest
import dev.highlights.editing.EditPlan
import dev.highlights.editing.IntroOutroCards
import dev.highlights.editing.PlannedClip
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.RenderRequest
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Cartes d'ouverture et de fin rendues pour de vrai : le graphe passe dans FFmpeg, la vidéo s'allonge exactement de
 * la durée des cartes, et dans un montage l'ouverture joue l'amorce de la musique (pas de silence avant le premier plan).
 */
class IntroOutroIT : FunSpec({
    val encoder = EncoderProfile("libx264", listOf("-c:v", "libx264", "-preset", "ultrafast"), false)

    /** Logo de test inventé : un disque et deux lettres, sur fond transparent. */
    fun logo(file: Path): Path {
        val image = BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(255, 90, 40)
        g.fillOval(40, 0, 120, 120)
        g.color = Color.WHITE
        g.font = g.font.deriveFont(48f)
        g.drawString("ZX", 70, 78)
        g.dispose()
        ImageIO.write(image, "png", file.toFile())
        return file
    }

    suspend fun duration(file: Path): Double = TestMedia.requireFfmpeg().probe(file).duration.inWholeMilliseconds / 1000.0

    /** Volume moyen (dB) de [file] entre [from] et [to] secondes. */
    suspend fun meanVolume(file: Path, from: Double, to: Double): Double {
        var mean = Double.NaN
        TestMedia.requireFfmpeg().run(
            FfmpegCommand(
                listOf("-ss", "%.3f".format(Locale.ROOT, from), "-t", "%.3f".format(Locale.ROOT, to - from), "-i", file.toString(), "-af", "volumedetect", "-vn", "-f", "null", "-"),
                "mesure du volume",
            ),
            onStderrLine = { line -> Regex("""mean_volume: (-?[\d.]+) dB""").find(line)?.let { mean = it.groupValues[1].toDouble() } },
        )
        return mean
    }

    test("highlights : ouverture et fin ajoutées, durée exacte, aperçu PNG").config(enabledIf = { TestMedia.available }, timeout = 5.seconds * 60) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = TestMedia.generate(root.resolve("partie.mp4"), durationSeconds = 20)
        val media = ffmpeg.probe(video)
        val cards = IntroOutroSettings(enabled = true, logo = logo(root.resolve("logo.png")).toString(), name = "ZeroX", background = CardBackground.BLUR)
        val edit = EditSettings(fps = 30, sourceHeight = 360, intro = cards)
        val clips = listOf(5.seconds, 12.seconds).mapIndexed { i, at ->
            PlannedClip(Highlight("h$i", video, TimeRange(at, at + 3.seconds), at + 1.seconds, 0.5 + i * 0.1), media)
        }
        val plan = EditPlan(clips, edit, Duration.ZERO)
        val out = root.resolve("highlights.mp4")
        val script = root.resolve("filtres.txt")
        val render = RenderCommandBuilder.build(RenderRequest(plan, OutputFormat.SOURCE, encoder, out, script, filterScriptOption = ffmpeg.filterScriptOption()))
        script.writeText(render.filterGraph)
        ffmpeg.run(render.command)
        render.expectedDuration shouldBe 9500.milliseconds
        duration(out) shouldBe (9.5 plusOrMinus 0.1)

        val (w, h) = RenderCommandBuilder.outputSize(OutputFormat.VERTICAL, media, edit)
        val png = root.resolve("carte.png")
        val pngScript = root.resolve("carte.txt")
        val preview = IntroOutroCards.preview(CardRequest.of(edit, w, h, IntroOutroCards.stills(clips).first)!!, true, pngScript, png, ffmpeg.filterScriptOption())
        pngScript.writeText(preview.filterGraph)
        ffmpeg.run(preview.command)
        val image = ImageIO.read(png.toFile())
        (image.width to image.height) shouldBe (w to h)
    }

    test("montage : l'ouverture dure des temps entiers et joue l'amorce de la musique").config(enabledIf = { TestMedia.available }, timeout = 5.seconds * 60) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = TestMedia.generate(root.resolve("partie.mp4"), durationSeconds = 40, audioTracks = listOf(TestMedia.AudioTrackSpec("Game", emptyList())))
        val musicFile = root.resolve("musique.wav")
        ffmpeg.run(FfmpegCommand(listOf("-y", "-f", "lavfi", "-i", "sine=f=220:d=60", musicFile.toString()), "musique de test"))
        val media = ffmpeg.probe(video)
        val music = MusicAnalysis(
            musicFile, 60.seconds, 120.0, List(120) { (it * 0.5).seconds }, 0, DoubleArray(120) { 1.0 }, DoubleArray(120) { 0.8 },
            listOf(MusicSection(0, 48, -14.0, 0.4), MusicSection(48, 120, -8.0, 1.0)), 48,
        )
        val groups = listOf(
            KillGroup(media, listOf(10.seconds, 12.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(media, listOf(25.seconds), 0.5, emptyList(), emptyList()),
        )
        val cards = IntroOutroSettings(enabled = true, introLength = 1300.milliseconds, name = "ZeroX", background = CardBackground.COLOR)
        val edit = EditSettings(fps = 30, sourceHeight = 360, intro = cards)
        val plan = MontagePlanner.plan(groups, music, MontageSettings(killOffset = Duration.ZERO))
        // 1,3 s à 120 BPM : trois temps, 1,5 s.
        IntroOutroCards.beatLength(cards.introLength, plan.period) shouldBe 1500.milliseconds
        val out = root.resolve("montage.mp4")
        val script = root.resolve("filtres.txt")
        val render = MontageRenderBuilder.build(MontageRenderRequest(plan, OutputFormat.SOURCE, edit, encoder, out, script, filterScriptOption = ffmpeg.filterScriptOption()))
        script.writeText(render.filterGraph)
        ffmpeg.run(render.command)
        duration(out) shouldBe ((render.expectedDuration.inWholeMilliseconds / 1000.0) plusOrMinus 0.1)
        if (plan.musicStart >= 1500.milliseconds) {
            // La musique (sinus continu) est déjà là à la fin de l'ouverture : elle ne démarre pas sur le premier plan.
            meanVolume(out, 1.0, 1.45) shouldBeGreaterThan -40.0
        } else {
            meanVolume(out, 0.0, 0.2) shouldBeLessThan -60.0
        }
    }
})
