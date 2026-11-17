package dev.highlights.editing.story

import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.TimeRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldStartWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Police des textes incrustés selon la langue : Impact (défaut des profils) tant qu'elle a les caractères, sinon une
 * police de Windows qui les a. Les cas qui lisent les vraies polices ne tournent que si elles sont installées.
 */
class VideoFontsTest : FunSpec({
    val impact = "C:/Windows/Fonts/impact.ttf"
    fun installed(vararg files: String) = files.all { Files.isRegularFile(Path.of(it)) }
    val windowsFonts = installed(impact, VideoFonts.JAPANESE, VideoFonts.KOREAN, VideoFonts.CHINESE)

    test("écriture du texte → replis essayés d'abord") {
        VideoFonts.preferred("더블 킬", Locale.ENGLISH) shouldStartWith listOf(VideoFonts.KOREAN)
        VideoFonts.preferred("ダブルキル", Locale.ENGLISH) shouldStartWith listOf(VideoFonts.JAPANESE)
        VideoFonts.preferred("ДАБЛ КИЛЛ", Locale.ENGLISH) shouldStartWith listOf(VideoFonts.HEAVY_LATIN)
        // Idéogrammes seuls : la langue de l'interface tranche entre japonais et chinois.
        VideoFonts.preferred("双杀", Locale.SIMPLIFIED_CHINESE) shouldStartWith listOf(VideoFonts.CHINESE)
        VideoFonts.preferred("王牌", Locale.JAPANESE) shouldStartWith listOf(VideoFonts.JAPANESE)
        VideoFonts.preferred("王牌", Locale.KOREAN) shouldStartWith listOf(VideoFonts.KOREAN)
    }

    test("police illisible par Java : gardée telle quelle, comme avant") {
        VideoFonts.forText("Z:/absente/police.ttf", "ダブルキル") shouldBe "Z:/absente/police.ttf"
    }

    test("français, anglais et russe restent en Impact").config(enabledIf = { installed(impact) }) {
        for (text in listOf("DOUBLÉ", "DOUBLE KILL", "QUINTUPLÉ", "ДАБЛ КИЛЛ", "ПЕНТАКИЛЛ", "ЭЙС", "KILL 12")) {
            VideoFonts.forText(impact, text) shouldBe impact
        }
    }

    test("japonais, coréen, chinois : la police de Windows de leur écriture").config(enabledIf = { windowsFonts }) {
        VideoFonts.forText(impact, "ダブルキル", Locale.JAPANESE) shouldBe VideoFonts.JAPANESE
        VideoFonts.forText(impact, "エース", Locale.ENGLISH) shouldBe VideoFonts.JAPANESE
        VideoFonts.forText(impact, "더블 킬", Locale.KOREAN) shouldBe VideoFonts.KOREAN
        VideoFonts.forText(impact, "双杀", Locale.SIMPLIFIED_CHINESE) shouldBe VideoFonts.CHINESE
        VideoFonts.forText(impact, "残局翻盘", Locale.SIMPLIFIED_CHINESE) shouldBe VideoFonts.CHINESE
        // Yu Gothic n'a pas 杀 (simplifié) : même en japonais, le repli passe à YaHei plutôt qu'à un carré vide.
        VideoFonts.forText(impact, "双杀", Locale.JAPANESE) shouldBe VideoFonts.CHINESE
    }

    test("une police configurée qui a les caractères l'emporte sur le repli de la langue").config(enabledIf = { windowsFonts }) {
        VideoFonts.forText(VideoFonts.CHINESE, "ダブルキル", Locale.JAPANESE) shouldBe VideoFonts.CHINESE
        VideoFonts.forText(VideoFonts.KOREAN, "DOUBLÉ") shouldBe VideoFonts.KOREAN
    }

    test("libellé de story en japonais : drawtext reçoit Yu Gothic, en français Impact").config(enabledIf = { windowsFonts }) {
        val at = TimeRange(1.seconds, 3.seconds)
        Captions.drawLabel("ダブルキル", at, impact, 0.08, "white", 1920, 1080, 0.3, 120.milliseconds) shouldContain "fontfile='C\\:/Windows/Fonts/YuGothB.ttc'"
        Captions.drawLabel("Doublé", at, impact, 0.08, "white", 1920, 1080, 0.3, 120.milliseconds) shouldContain "fontfile='C\\:/Windows/Fonts/impact.ttf'"
    }

    test("sous-titre coréen : chaque morceau dessiné en Malgun Gothic").config(enabledIf = { windowsFonts }) {
        val caption = Caption(TimeRange(1.seconds, 2.seconds), "와 대박 미쳤다")
        val filters = Captions.drawText(caption, CaptionSettings(font = impact, emphasis = listOf("대박")), 1920, 1080, 0.78)
        filters.size shouldBe 3
        filters.forEach { it shouldContain "malgunbd.ttf" }
    }
})
