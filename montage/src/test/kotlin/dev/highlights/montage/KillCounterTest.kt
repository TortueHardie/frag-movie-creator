package dev.highlights.montage

import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.HudOverlay
import dev.highlights.core.model.KillCounterMode
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.OverlayTarget
import dev.highlights.core.model.SafeArea
import dev.highlights.core.model.VerticalSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KillCounterTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes)
    val slot = CutSlot(0, 8, 0, null)

    fun clip(kills: List<Duration>, ace: Boolean = false) = MontageClip(
        group = KillGroup(media, kills, 1.0, emptyList(), emptyList(), outcome = RoundOutcome(ace = ace)),
        slot = slot, anchorBeat = 4, anchor = kills.last(), start = kills.first() - 2.seconds, end = kills.last() + 2.seconds,
        padBefore = Duration.ZERO, padAfter = Duration.ZERO, speeds = emptyList(), outputLength = 4.seconds,
    )
    val clips = listOf(clip(listOf(100.seconds)), clip(listOf(200.seconds, 201.seconds, 202.seconds), ace = true))

    test("par plan : chaque kill fait monter le nombre, tenu jusqu'au suivant ; ACE sur le dernier kill d'un ace") {
        val marks = KillCounter.marks(clips, 1, listOf(1.seconds, 2.seconds, 3.seconds), 4.seconds, KillCounterMode.CLIP)
        marks shouldBe listOf(
            KillCounter.Mark(1, 1.seconds, 2.seconds, ace = false),
            KillCounter.Mark(2, 2.seconds, 3.seconds, ace = false),
            KillCounter.Mark(3, 3.seconds, 4.seconds, ace = true),
        )
        KillCounter.marks(clips, 1, listOf(1.seconds), 4.seconds, KillCounterMode.OFF) shouldBe emptyList()
    }

    test("cumulé : le total d'avant reste affiché jusqu'au premier kill du plan, rien avant le tout premier kill") {
        KillCounter.marks(clips, 0, listOf(2.seconds), 4.seconds, KillCounterMode.MONTAGE) shouldBe listOf(KillCounter.Mark(1, 2.seconds, 4.seconds, ace = false))
        KillCounter.marks(clips, 1, listOf(1.seconds, 2.seconds, 3.seconds), 4.seconds, KillCounterMode.MONTAGE).map { it.count } shouldBe listOf(1, 2, 3, 4)
    }

    test("place : en 9:16 dans la zone sûre, en haut à droite, ou à gauche si le HUD recollé y est") {
        val marks = listOf(KillCounter.Mark(3, 1.seconds, 2.seconds, ace = true))
        val safe = SafeArea(top = 0.10, bottom = 0.22, left = 0.05, right = 0.14)
        val right = KillCounter.filters(marks, "C:/Windows/Fonts/impact.ttf", OutputFormat.VERTICAL, 1920, safe)
        right.first() shouldContain "x=w*0.8600-text_w"
        right.first() shouldContain "y=h*0.1000"
        right.size shouldBe 2
        right[1] shouldContain "text='ACE'"
        // Le kill feed de Wardogs, recollé en haut à gauche : le coin droit reste libre.
        val wardogs = VerticalSettings(
            reference = FrameSize(3440, 1440),
            hud = listOf(HudOverlay("killfeed", CropRegion(0.008, 0.445, 0.13, 0.12), OverlayTarget(0.02, 0.07, 0.60))),
        )
        KillCounter.rightCornerTaken(wardogs) shouldBe false
        // Un kill feed recollé en haut à droite (celui de VALORANT, en haut à droite de l'écran du jeu) : à gauche.
        val topRight = wardogs.copy(hud = listOf(HudOverlay("killfeed", CropRegion(0.80, 0.08, 0.19, 0.12), OverlayTarget(0.50, 0.07, 0.48))))
        KillCounter.rightCornerTaken(topRight) shouldBe true
        KillCounter.filters(marks, "C:/Windows/Fonts/impact.ttf", OutputFormat.VERTICAL, 1920, safe, topRight).first() shouldContain "x=w*0.0500"
    }
})
