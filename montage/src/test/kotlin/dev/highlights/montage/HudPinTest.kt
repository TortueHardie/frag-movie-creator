package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.HudOverlay
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.OverlayTarget
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.model.VideoStream
import dev.highlights.editing.HudPin
import dev.highlights.editing.RenderCommandBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HudPinTest : FunSpec({
    test("le HUD est recollé fixe par-dessus les effets qui bougent l'image") {
        val box = RenderCommandBuilder.CropBox(0, 0, 1080, 150)
        val (lines, out) = HudPin.statements("s0", listOf("tpad=start_duration=1"), listOf("scale=w='z'", "crop=1080:1920"), listOf(box), "hp0")!!
        lines shouldBe listOf(
            "[s0]tpad=start_duration=1,split=2[hp0m][hp0f]",
            "[hp0m]scale=w='z',crop=1080:1920[hp0z]",
            "[hp0f]crop=1080:150:0:0[hp0c0]",
            "[hp0z][hp0c0]overlay=x=0:y=0:shortest=1[hp0o0]",
        )
        out shouldBe "hp0o0"
        // Rien qui bouge, ou pas de HUD : rien à faire.
        HudPin.statements("s0", emptyList(), emptyList(), listOf(box), "hp0") shouldBe null
        HudPin.statements("s0", emptyList(), listOf("crop"), emptyList(), "hp0") shouldBe null
    }

    test("montage 9:16 avec bandeau recollé : bandeau recollé après le zoom, avant couleurs, textes et compteur") {
        val media = MediaInfo(
            Path("partie.mp4"), 1, 30.minutes,
            video = VideoStream(0, "h264", 1920, 1080, 60.0),
            audio = listOf(AudioStream(1, 0, "aac", 2, 48000)),
        )
        val hud = HudOverlay("topbar", CropRegion(0.2, 0.008, 0.6, 0.08), OverlayTarget(0.0, 0.0, 1.0))
        val edit = EditSettings(audioStreams = listOf(0), vertical = VerticalSettings(cropRegion = CropRegion(0.0, 0.09, 1.0, 0.91), hud = listOf(hud)))
        RenderCommandBuilder.hudTargets(OutputFormat.VERTICAL, media, edit).size shouldBe 1
        val settings = dev.highlights.core.model.MontageSettings(killOffset = Duration.ZERO, effectDensity = dev.highlights.core.model.EffectDensity.HEAVY).earlierDefaults()
        val music = MusicAnalysis(
            Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
            listOf(MusicSection(0, 96, -14.0, 0.4), MusicSection(96, 240, -8.0, 1.0)), 96,
        )
        val plan = MontagePlanner.plan(listOf(KillGroup(media, listOf(100.seconds, 102.seconds), 1.0, emptyList(), listOf(TimeRange(101.seconds, 102.8.seconds)))), music, settings)
        val graph = MontageRenderBuilder.build(
            MontageRenderRequest(plan, OutputFormat.VERTICAL, edit, EncoderProfile("h264_amf", listOf("-c:v", "h264_amf"), true), Path("out.mp4"), Path("f.txt")),
        ).filterGraph
        // Premier plan zoomé : son HUD est recollé par-dessus le zoom.
        val n = Regex("""\[hp(\d+)m]""").find(graph)!!.groupValues[1]
        val zoom = graph.indexOf("\n[hp${n}m]") + 1
        val pin = graph.indexOf("[hp${n}z][hp${n}c0]overlay")
        graph.substring(zoom, graph.indexOf("\n", zoom)).contains("eval=frame") shouldBe true
        (pin shouldBeGreaterThan zoom)
        // Après le recollage : textes (DOUBLÉ) et compteur, sur le label de sortie du HUD fixe.
        val after = graph.indexOf("[hp${n}o0]", pin + 1)
        (after shouldBeGreaterThan pin)
        graph.indexOf("drawtext", after) shouldBeGreaterThan after
    }
})
