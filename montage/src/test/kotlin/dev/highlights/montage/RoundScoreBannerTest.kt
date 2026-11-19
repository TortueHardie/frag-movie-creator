package dev.highlights.montage

import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.HudOverlay
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.OverlayTarget
import dev.highlights.core.model.RegionAnchor
import dev.highlights.core.model.SafeArea
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VerticalSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RoundScoreBannerTest : FunSpec({
    fun e(at: Int, kind: String) = TimelineEvent(at.seconds, kind, 1.0, "score")
    // Capture commencée à 2-1, puis 3-1 à 100 s, 3-2 à 200 s.
    val events = listOf(e(0, RoundScore.WON), e(0, RoundScore.WON), e(0, RoundScore.LOST), e(100, RoundScore.WON), e(200, RoundScore.LOST), e(150, "kill"))

    test("score à un instant : le compte des rounds jusque-là, depuis le dernier 0-0") {
        RoundScore.at(events, 50.seconds) shouldBe RoundScore(2, 1)
        RoundScore.at(events, 150.seconds) shouldBe RoundScore(3, 1)
        RoundScore.at(events, 150.seconds)!!.round shouldBe 5
        RoundScore.at(events, 250.seconds) shouldBe RoundScore(3, 2)
        RoundScore.at(events + e(300, RoundScore.RESET) + e(400, RoundScore.LOST), 500.seconds) shouldBe RoundScore(0, 1)
        RoundScore.at(listOf(e(10, "kill")), 50.seconds) shouldBe null
    }

    test("plan : le score du HUD au fil du plan (celui du round que le kill joue, puis le suivant s'il change), rien sans score ni pour la vitrine") {
        val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes)
        val other = MediaInfo(Path("autre.mp4"), 1, 30.minutes)
        fun clip(m: MediaInfo, kills: List<Duration>, showcase: Boolean = false) = MontageClip(
            group = KillGroup(m, kills, 1.0, emptyList(), emptyList()),
            slot = CutSlot(0, 8, 0, null), anchorBeat = 4, anchor = kills.last(), start = kills.first() - 2.seconds, end = kills.last() + 2.seconds,
            padBefore = Duration.ZERO, padAfter = Duration.ZERO, speeds = emptyList(), outputLength = 4.seconds, showcase = showcase,
        )
        val scores = RoundScores(mapOf(media.path to events))
        // Plan de 93 à 99 s, kills à 95 et 97 s : 2-1 tout le plan (le HUD ne change qu'à 100 s).
        scores.marks(clip(media, listOf(95.seconds, 97.seconds)), Duration.ZERO, 6.seconds) shouldBe
            listOf(RoundScores.Mark(RoundScore(2, 1), Duration.ZERO, 6.seconds))
        // Plan de 96 à 101 s, kill à 98 s qui gagne le round : 2-1, puis 3-1 à 100 s (4 s dans le plan, + 0,2 s de coupe avancée).
        scores.marks(clip(media, listOf(98.seconds, 99.seconds)), 200.milliseconds, 5.seconds) shouldBe listOf(
            RoundScores.Mark(RoundScore(2, 1), Duration.ZERO, 4.2.seconds),
            RoundScores.Mark(RoundScore(3, 1), 4.2.seconds, 5.seconds),
        )
        scores.marks(clip(other, listOf(120.seconds)), Duration.ZERO, 4.seconds) shouldBe emptyList()
        scores.marks(clip(media, listOf(120.seconds), showcase = true), Duration.ZERO, 4.seconds) shouldBe emptyList()
    }

    test("place en 9:16 : sous la barre du haut recollée de VALORANT, à 10 % au moins, dans la zone sûre") {
        val valorant = VerticalSettings(
            reference = FrameSize(1920, 1080),
            hud = listOf(
                HudOverlay("topbar", CropRegion(0.20, 0.008, 0.60, 0.080), OverlayTarget(0.0, 0.0, 1.0), anchor = RegionAnchor.CENTER),
                HudOverlay("abilities", CropRegion(0.27, 0.89, 0.46, 0.105), OverlayTarget(0.05, 0.72, 0.81), anchor = RegionAnchor.CENTER),
            ),
        )
        RoundScoreBanner.top(OutputFormat.VERTICAL, SafeArea.NONE, valorant) shouldBe 0.10
        RoundScoreBanner.top(OutputFormat.VERTICAL, SafeArea(top = 0.13, bottom = 0.2, left = 0.05, right = 0.1), valorant) shouldBe 0.13
        // Un élément haut recollé au centre (barre plus haute) : le bandeau passe dessous.
        val tall = valorant.copy(hud = listOf(HudOverlay("topbar", CropRegion(0.20, 0.0, 0.60, 0.20), OverlayTarget(0.0, 0.0, 1.0))))
        RoundScoreBanner.top(OutputFormat.VERTICAL, SafeArea.NONE, tall) shouldBe (1080.0 * 0.20 * 1080 / (0.60 * 1920) / 1920 + 0.01).plusOrMinus(1e-9)
        RoundScoreBanner.top(OutputFormat.LANDSCAPE, SafeArea.NONE, valorant) shouldBe 0.012
    }

    test("filtres : cartouche tout le plan, numéro du round et score aux couleurs des équipes, sur leur ligne de base") {
        val marks = listOf(RoundScores.Mark(RoundScore(7, 5), Duration.ZERO, 2.seconds), RoundScores.Mark(RoundScore(8, 5), 2.seconds, 3.seconds))
        val f = RoundScoreBanner.filters(marks, 3.seconds, "C:/Windows/Fonts/impact.ttf", OutputFormat.VERTICAL, 1080, 1920)
        f.size shouldBe 9
        f[0] shouldContain "drawbox="
        f[0] shouldContain "y=192:"
        f[0] shouldContain "enable='between(t\\,0.000\\,3.000)'"
        f[1] shouldContain "text='ROUND 13'"
        f[2] shouldContain "text='7'"
        f[4] shouldContain "text='5'"
        // Tiret et chiffres sur la même ligne de base : le tiret ne monte plus en haut des chiffres.
        f[3] shouldContain "-max_glyph_a"
        f[2].substringAfter(":y=").substringBefore(":") shouldBe f[3].substringAfter(":y=").substringBefore(":")
        (1..4).forEach { f[it] shouldContain "enable='between(t\\,0.000\\,2.000)'" }
        f[5] shouldContain "text='ROUND 14'"
        f[6] shouldContain "text='8'"
        RoundScoreBanner.filters(emptyList(), 3.seconds, "C:/Windows/Fonts/impact.ttf", OutputFormat.VERTICAL, 1080, 1920) shouldBe emptyList()
    }
})
