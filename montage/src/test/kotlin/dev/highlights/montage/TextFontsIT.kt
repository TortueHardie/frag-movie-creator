package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.TimeRange
import dev.highlights.editing.story.Captions
import dev.highlights.editing.story.VideoFonts
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Textes incrustés rendus pour de vrai dans chaque langue : le libellé de multi-kill du montage et celui des
 * highlights story passent dans FFmpeg avec la police choisie pour leur écriture. Un caractère absent de la police
 * devient le même carré vide quel que soit le caractère : deux caractères différents dessinés seuls doivent donc
 * donner deux images différentes. Le témoin (Impact forcée sur du japonais) montre que la mesure voit bien les carrés.
 * Les images restent dans `build/text-fonts` pour les regarder.
 */
class TextFontsIT : FunSpec({
    val impact = "C:/Windows/Fonts/impact.ttf"
    val out = Path.of("build/text-fonts").toAbsolutePath()
    val ready = { TestMedia.available && listOf(impact, VideoFonts.JAPANESE, VideoFonts.KOREAN, VideoFonts.CHINESE).all { Files.isRegularFile(Path.of(it)) } }

    /** Image 640×360 sur fond gris (le contour noir du texte doit se voir) avec un seul filtre drawtext, à t = 0,5 s. */
    suspend fun render(ffmpeg: FfmpegService, filter: String, name: String): BufferedImage {
        out.createDirectories()
        val png = out.resolve("$name.png")
        val script = out.resolve("$name.txt")
        script.writeText("[0:v]$filter[v]")
        ffmpeg.run(
            FfmpegCommand(
                listOf("-y", "-f", "lavfi", "-i", "color=c=0x404040:s=640x360:d=1:r=30") +
                    ffmpeg.filterScriptOption().let { listOf(it, script.toString()) } +
                    listOf("-map", "[v]", "-ss", "0.5", "-frames:v", "1", png.toString()),
                "texte incrusté $name",
            ),
        )
        return ImageIO.read(png.toFile())
    }

    /** Pixels presque blancs : le corps des lettres. */
    fun whitePixels(image: BufferedImage): Int {
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) and 0xFF) > 200) n++
        return n
    }

    /** Écart moyen (0..255) entre deux images de même taille, sur le canal bleu. */
    fun difference(a: BufferedImage, b: BufferedImage): Double {
        var sum = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) sum += abs((a.getRGB(x, y) and 0xFF) - (b.getRGB(x, y) and 0xFF))
        return sum.toDouble() / (a.width * a.height)
    }

    /** Libellé du montage (même filtre que le rendu) avec Impact configurée : la police suit la langue du texte. */
    fun montageLabel(text: String) = MontageRenderBuilder.drawText(impact, text, Duration.ZERO, 72, "h*0.30", border = 4)

    test("témoin : en Impact, deux caractères japonais différents donnent le même carré").config(enabledIf = { ready() }, timeout = 2.seconds * 60) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val path = VideoFonts.drawtextPath(impact)
        fun boxed(c: String) = "drawtext=fontfile='$path':text='$c':fontsize=120:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2"
        difference(render(ffmpeg, boxed("ダ"), "temoin_impact_1"), render(ffmpeg, boxed("ブ"), "temoin_impact_2")) shouldBe 0.0
    }

    for ((tag, label) in listOf("ru" to "ДАБЛ КИЛЛ", "ja" to "ダブルキル", "ko" to "더블 킬", "zh_CN" to "双杀")) {
        test("$tag : le libellé du montage « $label » est dessiné, pas en carrés").config(enabledIf = { ready() }, timeout = 2.seconds * 60) {
            val ffmpeg = TestMedia.requireFfmpeg()
            I18n.withLanguage(tag) { I18n.text("video.streak.2") } shouldBe label
            val whole = I18n.withLanguage(tag) { montageLabel(label) }
            whitePixels(render(ffmpeg, whole, "montage_$tag")) shouldBeGreaterThan 800
            // Deux caractères différents du libellé, dessinés seuls : deux images différentes.
            val (a, b) = label.filterNot { it == ' ' }.toList().distinct().take(2).map { it.toString() }
            val first = render(ffmpeg, I18n.withLanguage(tag) { montageLabel(a) }, "montage_${tag}_1")
            val second = render(ffmpeg, I18n.withLanguage(tag) { montageLabel(b) }, "montage_${tag}_2")
            difference(first, second) shouldBeGreaterThan 0.5
        }
    }

    for ((tag, label) in listOf("ja" to "エース", "ru" to "ЭЙС", "ko" to "에이스", "zh_CN" to "ACE")) {
        test("$tag : libellé des highlights story « $label »").config(enabledIf = { ready() }, timeout = 2.seconds * 60) {
            val ffmpeg = TestMedia.requireFfmpeg()
            I18n.withLanguage(tag) { I18n.text("video.ace") } shouldBe label
            val filter = I18n.withLanguage(tag) {
                Captions.drawLabel(label, TimeRange(Duration.ZERO, 1.seconds), impact, 0.12, "0xFFD400", 640, 360, 0.5, 120.milliseconds)
            }
            val image = render(ffmpeg, filter, "story_$tag")
            // Libellé jaune : du rouge et du vert sans bleu, sur assez de pixels pour faire des lettres.
            var yellow = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                if ((rgb shr 16 and 0xFF) > 200 && (rgb shr 8 and 0xFF) > 170 && (rgb and 0xFF) < 80) yellow++
            }
            yellow shouldBeGreaterThan 300
        }
    }
})
