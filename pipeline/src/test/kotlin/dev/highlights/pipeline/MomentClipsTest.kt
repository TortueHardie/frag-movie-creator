package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import dev.highlights.editing.DefaultEditPlanner
import dev.highlights.editing.story.StoryPlanner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MomentClipsTest : FunSpec({
    fun session(length: Duration = 30.minutes, scores: (Int) -> Double = { 0.5 }): Session {
        val media = MediaInfo(Path("partie.mp4"), 1, length, video = VideoStream(0, "h264", 1920, 1080, 60.0))
        val grid = WindowGrid(1.seconds, 1.seconds, length)
        fun moment(id: String, at: Int, kills: Int, score: Double, enabled: Boolean = true) =
            Highlight(id, media.path, TimeRange((at - 3).seconds, (at + 3).seconds), at.seconds, score, events = mapOf("kill" to kills), enabled = enabled)
        return Session(
            createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count, scores), emptyMap(), events = listOf(TimelineEvent(600.seconds, "kill", 1.0, "n"))),
            highlights = listOf(moment("h001", 300, 1, 1.6), moment("h002", 600, 2, 1.2), moment("h003", 900, 1, 1.9), moment("h004", 1200, 3, 2.0, enabled = false)),
        )
    }
    val settings = MomentClips.settings(EditSettings())

    fun storyDuration(s: Session) = StoryPlanner.plan(DefaultEditPlanner.plan(s, settings), listOf(s)).outputDuration

    test("moment par défaut : le plus de kills parmi ceux cochés, puis le meilleur score ; ou celui qu'on nomme") {
        MomentClips.pick(session(), null).id shouldBe "h002"
        MomentClips.pick(session(), "h003").id shouldBe "h003"
        MomentClips.pick(session(), "h004").id shouldBe "h004"
        shouldThrow<InputException> { MomentClips.pick(session(), "h009") }.localizedMessage shouldContain "h009"
    }

    test("réglages du clip : story, vertical seul, accroche sur un seul moment") {
        settings.formats shouldBe listOf(OutputFormat.VERTICAL)
        settings.story.coldOpen.minMoments shouldBe 1
    }

    test("durée du clip entre 15 et 30 s, au plus près de 22 s, le moment toujours dedans") {
        val s = session()
        val moment = MomentClips.pick(s, null)
        val clip = MomentClips.window(s, moment, settings, 30.seconds)
        val range = clip.highlights.single().range
        (range.start <= moment.range.start && range.end >= moment.range.end) shouldBe true
        val d = storyDuration(clip)
        d shouldBeGreaterThanOrEqualTo 15.seconds
        d shouldBeLessThanOrEqualTo 30.seconds
    }

    test("beaucoup d'attente coupée : la fenêtre s'élargit pour garder au moins 15 s") {
        // Tout est « mort » sauf autour du kill : les jump cuts retirent l'attente, la fenêtre doit grandir.
        val s = session { i -> if (i in 595..605) 0.9 else 0.05 }
        val clip = MomentClips.window(s, MomentClips.pick(s, "h002"), settings, 30.seconds)
        storyDuration(clip) shouldBeLessThanOrEqualTo 30.seconds
        clip.highlights.single().range.length shouldBeGreaterThanOrEqualTo 22.seconds
        // La fenêtre s'allonge avant le kill : après lui, au plus 6 s (ou la fin du moment d'origine).
        (clip.highlights.single().range.end - 600.seconds) shouldBeLessThanOrEqualTo 6.seconds
    }

    test("plafond de la plateforme plus bas : le clip le respecte") {
        val s = session()
        storyDuration(MomentClips.window(s, MomentClips.pick(s, null), settings, 12.seconds)) shouldBeLessThanOrEqualTo 12.seconds
    }

    test("capture trop courte : le clip reste dans la capture") {
        val s = session(length = 20.seconds).let { it.copy(highlights = listOf(it.highlights.first().copy(range = TimeRange(7.seconds, 13.seconds), peak = 10.seconds))) }
        val range = MomentClips.window(s, s.highlights.single(), settings, 30.seconds).highlights.single().range
        range.start shouldBeGreaterThanOrEqualTo Duration.ZERO
        range.end shouldBeLessThanOrEqualTo 20.seconds
    }
})
