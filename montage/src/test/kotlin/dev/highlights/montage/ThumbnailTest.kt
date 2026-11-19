package dev.highlights.montage

import dev.highlights.core.i18n.I18n
import dev.highlights.export.ExportReport
import dev.highlights.export.ReportHighlight
import dev.highlights.export.ReportOutput
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ThumbnailTest : FunSpec({
    fun clip(kills: List<String>, ace: Boolean = false, clutch: Boolean = false, headshots: Int = 0, onDrop: Boolean = false, showcase: Boolean = false, source: String = "D:/a.mp4") =
        MontageReportClip(
            source = source, start = "00:00", end = "00:10", kills = kills, killsInMontage = kills.map { 1.0 }, beats = 4,
            slowMotion = false, ace = ace, clutch = clutch, headshots = headshots, onDrop = onDrop, showcase = showcase,
        )

    fun report(vararg clips: MontageReportClip) = MontageReport("", "m.mp3", 120.0, "0:00", 30.0, outputs = listOf("v.mp4"), clips = clips.toList())

    test("rapport de montage synthétique : l'ace l'emporte sur un triplé, l'instant est le dernier kill du plan") {
        val r = report(
            clip(listOf("01:00", "01:01", "01:02")),
            clip(listOf("05:00", "05:02"), ace = true),
            clip(listOf("09:00"), headshots = 1),
            clip(emptyList(), showcase = true),
        )
        val moments = ThumbnailPicker.fromMontage(r)
        moments.size shouldBe 3
        val best = ThumbnailPicker.best(moments)!!
        best.ace shouldBe true
        best.kill shouldBe 302.seconds
        ThumbnailPicker.strength(moments[0]) shouldBe 3.0
        ThumbnailPicker.strength(moments[2]) shouldBe (1.3 plusOrMinus 1e-9)
    }

    test("clutch, tirs à la tête et drop départagent ; à égalité le premier vu") {
        val clutch = ThumbnailMoment(Path("a"), 1.seconds, kills = 2, clutch = true)
        val triple = ThumbnailMoment(Path("a"), 2.seconds, kills = 3)
        ThumbnailPicker.best(listOf(triple, clutch)) shouldBe clutch
        val first = ThumbnailMoment(Path("a"), 3.seconds, kills = 1)
        val second = ThumbnailMoment(Path("a"), 4.seconds, kills = 1)
        ThumbnailPicker.best(listOf(first, second)) shouldBe first
        val drop = second.copy(onDrop = true)
        ThumbnailPicker.best(listOf(first, drop)) shouldBe drop
    }

    test("candidats : les moments à 0,5 près du plus fort, six au plus, le plus fort d'abord") {
        val singles = (1..10).map { ThumbnailMoment(Path("a"), it.seconds, kills = 1) }
        val double = ThumbnailMoment(Path("a"), 20.seconds, kills = 2)
        ThumbnailPicker.contenders(singles + double) shouldContainExactly listOf(double)
        ThumbnailPicker.contenders(singles).map { it.kill } shouldContainExactly (1..6).map { it.seconds }
        ThumbnailPicker.contenders(emptyList()) shouldBe emptyList()
    }

    test("rapport highlights : moments montés dans l'ordre, au pic, kills connus par leurs événements") {
        fun h(id: String, order: Int?, peak: Double?, kills: Int) = ReportHighlight(
            id, order, "", "", "", peak?.minus(4) ?: 10.0, peak?.plus(4) ?: 20.0, 8.0, 0.9, emptyMap(), peakSeconds = peak, events = if (kills > 0) mapOf("kill" to kills) else emptyMap(),
        )
        val r = ExportReport(
            "", "D:/partie.mp4", profile = "wardogs", encoder = "x", outputs = listOf(ReportOutput("16:9", "v.mp4")), totalDurationSeconds = 20.0,
            highlights = listOf(h("b", 2, 300.0, 2), h("a", 1, 100.0, 1), h("c", null, 500.0, 5), h("d", 3, null, 0)),
            skippedHighlights = emptyList(),
        )
        val moments = ThumbnailPicker.fromHighlights(r)
        moments.map { it.kill } shouldContainExactly listOf(100.seconds, 300.seconds, 15.seconds)
        ThumbnailPicker.best(moments)!!.kills shouldBe 2
        moments.first().source shouldBe Path("D:/partie.mp4")
    }

    test("texte : ACE, CLUTCH, la série, sinon les kills de la vidéo, rien sous deux kills — dans la langue choisie") {
        I18n.withLanguage("fr") {
            ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 2, ace = true), 9) shouldBe "9 KILLS"
            ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 1, clutch = true), 9) shouldBe "CLUTCH"
            ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 3), 9) shouldBe "TRIPLÉ"
            ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 7), 9) shouldBe "QUINTUPLÉ"
            ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 1), 15) shouldBe "15 KILLS"
            ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 1), 1) shouldBe ""
        }
        I18n.withLanguage("en") {
            ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 3), 9) shouldBe "TRIPLE KILL"
        }
        // Montage des morts : le texte de la mort, jamais « N KILLS ».
        ThumbnailPicker.label(ThumbnailMoment(Path("a"), 1.seconds, 1, text = "OUPS"), 8) shouldBe "OUPS"
        val fails = ThumbnailPicker.fromMontage(MontageReport("", "m.mp3", 120.0, "0:00", 30.0, outputs = listOf("v.mp4"),
            clips = listOf(MontageReportClip("D:/a.mp4", "00:00", "00:10", listOf("00:05"), listOf(1.0), 4, slowMotion = true, failText = "RIP"))))
        fails.single().text shouldBe "RIP"
    }

    test("instant : la plus nette, notification comprise, jamais un écran noir") {
        val kill = 10.seconds
        val samples = listOf(
            FrameSample(9.7.seconds, sharpness = 900.0, luma = 90.0), // avant le kill, nette
            FrameSample(10.1.seconds, sharpness = 800.0, luma = 90.0), // après : notification à l'écran (×1,2 = 960)
            FrameSample(10.5.seconds, sharpness = 200.0, luma = 90.0), // flick : floue
            FrameSample(11.seconds, sharpness = 5000.0, luma = 10.0), // écran noir bruité
        )
        ThumbnailFrames.choose(samples, kill) shouldBe 10.1.seconds
        // Une scène sombre (luminosité 40) compte pour moitié d'une claire.
        ThumbnailFrames.readability(FrameSample(9.seconds, 1000.0, 40.0), kill) shouldBe (500.0 plusOrMinus 1e-9)
        ThumbnailFrames.choose(listOf(FrameSample(9.seconds, 1.0, 5.0)), kill) shouldBe null
    }

    test("netteté : une image à contours francs l'emporte sur la même, floue ; luminosité moyenne") {
        val w = 40
        val h = 40
        val sharp = ByteArray(w * h) { i -> if ((i % w / 4 + i / w / 4) % 2 == 0) 0 else 255.toByte() }
        val blurred = ByteArray(w * h) { i -> (((i % w).toDouble() / w) * 255).toInt().toByte() }
        (ThumbnailFrames.sharpness(sharp, w, h) > 100 * ThumbnailFrames.sharpness(blurred, w, h).coerceAtLeast(1.0)) shouldBe true
        ThumbnailFrames.luma(ByteArray(4) { 100 }) shouldBe 100.0
    }

    test("rendu : recadré au format de la miniature autour du centre, punch-in, couleurs, texte borné par la largeur") {
        val wide = ThumbnailRenderer.filter(ThumbnailStyle.SOBER, 3440, 1440, 1280, 720, "15 KILLS", Locale.FRENCH)
        // 1440 / 1,15 = 1252 de haut, 16:9 → 2226 de large, centré dans les 3440.
        wide shouldContain "crop=2226:1252:607:94"
        wide shouldContain "scale=1280:720"
        wide shouldContain "eq=saturation=1.080:contrast=1.050"
        wide shouldContain "text='15 KILLS'"
        val tall = ThumbnailRenderer.filter(ThumbnailStyle.VIVID, 3440, 1440, 1080, 1920, "ACE", Locale.FRENCH)
        tall shouldContain "crop=622:1106:1409:167"
        tall shouldContain "vibrance=intensity=0.500"
        tall shouldContain "vignette="
        tall shouldContain "fontcolor=0xFFD400"
        ThumbnailRenderer.filter(ThumbnailStyle.SOBER, 1920, 1080, 1280, 720, "", Locale.FRENCH) shouldNotContain "drawtext"
        // Apostrophe et deux-points ne cassent pas le filtre.
        ThumbnailRenderer.filter(ThumbnailStyle.SOBER, 1920, 1080, 1280, 720, "L'ACE: 5", Locale.FRENCH) shouldContain "text='L\u2019ACE\\: 5'"
    }

    test("habillages : identifiants de la ligne de commande") {
        ThumbnailStyle.entries.map { it.id } shouldContainExactly listOf("sober", "vivid", "big")
        ThumbnailStyle.parse("BIG") shouldBe ThumbnailStyle.BIG_TEXT
        ThumbnailStyle.parse("fluo") shouldBe null
    }

    test("fenêtre mesurée autour du kill") {
        ThumbnailFrames.BEFORE shouldBe 400.milliseconds
        ThumbnailFrames.AFTER shouldBe 1200.milliseconds
    }
})
