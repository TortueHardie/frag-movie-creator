package dev.highlights.export

import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import dev.highlights.editing.EditPlan
import dev.highlights.editing.PlannedClip
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CaptionPassagesTest : FunSpec({
    fun media(name: String) = MediaInfo(path = Path(name), sizeBytes = 1, duration = 10.minutes)
    fun clip(m: MediaInfo, start: Int, end: Int) =
        PlannedClip(Highlight("h$start", m.path, TimeRange(start.seconds, end.seconds), start.seconds, 0.5), m)

    test("moments proches d'une même capture lus d'un bloc, moments lointains et autres captures à part") {
        val a = media("a.mp4")
        val b = media("b.mp4")
        // Capture de test du 22/11 : moments 16–22 s et 27–34,7 s, 5 s d'écart.
        val plan = EditPlan(listOf(clip(a, 27, 34), clip(a, 16, 22), clip(a, 120, 130), clip(b, 20, 25)), EditSettings(), Duration.ZERO)
        Exporter.passages(plan, 8.seconds).map { it.first.path.toString() to it.second } shouldBe listOf(
            "a.mp4" to TimeRange(16.seconds, 34.seconds),
            "a.mp4" to TimeRange(120.seconds, 130.seconds),
            "b.mp4" to TimeRange(20.seconds, 25.seconds),
        )
        // Contexte de 2 s : 5 s d'écart, deux passages.
        Exporter.passages(plan, 2.seconds).size shouldBe 4
    }
})
