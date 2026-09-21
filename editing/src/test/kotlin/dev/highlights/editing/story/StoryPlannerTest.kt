package dev.highlights.editing.story

import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.JumpCutSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import dev.highlights.editing.DefaultEditPlanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StoryPlannerTest : FunSpec({
    val media = MediaInfo(
        path = Path("D:/captures/partie.mp4"),
        sizeBytes = 1,
        duration = 10.minutes,
        video = VideoStream(0, "h264", 1920, 1080, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000, "Game")),
    )

    /** Timeline à fenêtres d'une seconde : [loud] = secondes à score 1, les autres à [quiet]. */
    fun timeline(
        seconds: Int,
        loud: Set<Int>,
        quiet: Double = 0.05,
        segments: List<TimelineSegment> = emptyList(),
        events: List<TimelineEvent> = emptyList(),
    ) = ScoredTimeline(
        grid = WindowGrid(1.seconds, 1.seconds, seconds.seconds),
        total = (0 until seconds).map { if (it in loud) 1.0 else quiet },
        contributions = emptyMap(),
        events = events,
        segments = segments,
    )

    fun highlight(range: TimeRange, peak: Duration, score: Double = 0.8, id: String = "h1") =
        Highlight(id, media.path, range, peak, score)

    fun session(t: ScoredTimeline, vararg highlights: Highlight) =
        Session(createdAt = Instant.EPOCH, media = media, profileId = "default", timeline = t, highlights = highlights.toList())

    fun storyPlan(s: Session, settings: EditSettings = EditSettings(style = EditStyle.STORY)) =
        StoryPlanner.plan(DefaultEditPlanner.plan(listOf(s), settings), listOf(s))

    fun sec(s: Double) = (s * 1000).toLong().milliseconds

    val cuts = JumpCutSettings()

    test("un temps mort au milieu d'un moment devient un jump cut, avec une marge de chaque côté") {
        // Action de 10 à 14 s, attente de 14 à 20 s, action de 20 à 24 s.
        val t = timeline(60, loud = (10..13).toSet() + (20..23).toSet())
        val range = TimeRange(10.seconds, 24.seconds)
        val pieces = StoryPlanner.liveRanges(range, highlight(range, 12.seconds), t, cuts)
        pieces shouldContainExactly listOf(
            TimeRange(10.seconds, sec(14.3)),
            TimeRange(sec(19.7), 24.seconds),
        )
    }

    test("une phrase au milieu du creux est gardée entière, seuls les silences autour sont coupés") {
        val t = timeline(
            60,
            loud = (10..13).toSet() + (20..23).toSet(),
            segments = listOf(TimelineSegment(TimeRange(sec(15.5), sec(18.5)), "speech", 1.0, "voice")),
        )
        val range = TimeRange(10.seconds, 24.seconds)
        StoryPlanner.liveRanges(range, highlight(range, 12.seconds), t, cuts) shouldContainExactly listOf(
            TimeRange(10.seconds, sec(14.3)),
            TimeRange(sec(15.2), sec(18.8)),
            TimeRange(sec(19.7), 24.seconds),
        )
    }

    test("un creux trop court pour valoir une coupe est gardé") {
        val t = timeline(60, loud = (10..13).toSet() + (15..20).toSet())
        val range = TimeRange(10.seconds, 21.seconds)
        StoryPlanner.liveRanges(range, highlight(range, 12.seconds), t, cuts) shouldContainExactly listOf(range)
    }

    test("l'attente avant l'action est rognée au début du moment") {
        val t = timeline(60, loud = (16..19).toSet())
        val range = TimeRange(10.seconds, 20.seconds)
        // Le pic retient 1,5 s avant lui (15,5 s), puis la marge : départ à 15,2 s.
        StoryPlanner.liveRanges(range, highlight(range, 17.seconds), t, cuts) shouldContainExactly listOf(TimeRange(sec(15.2), 20.seconds))
    }

    test("un kill dans le creux le retient, marge comprise") {
        val t = timeline(
            60,
            loud = (10..13).toSet() + (24..27).toSet(),
            events = listOf(TimelineEvent(19.seconds, "kill", 1.0, "outplayed")),
        )
        val range = TimeRange(10.seconds, 28.seconds)
        StoryPlanner.liveRanges(range, highlight(range, 12.seconds), t, cuts) shouldContainExactly listOf(
            TimeRange(10.seconds, sec(14.3)),
            TimeRange(sec(17.2), sec(20.8)),
            TimeRange(sec(23.7), 28.seconds),
        )
    }

    test("jump cuts désactivés : le moment reste d'un seul tenant") {
        val t = timeline(60, loud = (10..13).toSet() + (20..23).toSet())
        val range = TimeRange(10.seconds, 24.seconds)
        StoryPlanner.liveRanges(range, highlight(range, 12.seconds), t, cuts.copy(enabled = false)) shouldContainExactly listOf(range)
    }

    test("accroche du meilleur moment, puis les moments dans l'ordre, flash à chaque nouveau moment") {
        val t = timeline(120, loud = (10..17).toSet() + (60..69).toSet())
        val weak = highlight(TimeRange(10.seconds, 18.seconds), 14.seconds, score = 0.6, id = "h1")
        val best = highlight(TimeRange(60.seconds, 70.seconds), 65.seconds, score = 0.9, id = "h2")
        val plan = storyPlan(session(t, weak, best))

        plan.shots.map { it.role } shouldContainExactly listOf(ShotRole.COLD_OPEN, ShotRole.OPENING, ShotRole.OPENING)
        // 3 s autour du pic du meilleur moment, 60 % avant.
        plan.shots.first().range shouldBe TimeRange(sec(63.2), sec(66.2))
        plan.shots.first().highlight.id shouldBe "h2"
        plan.shots.drop(1).map { it.highlight.id } shouldContainExactly listOf("h1", "h2")
        plan.outputDuration shouldBe 3.seconds + 8.seconds + 10.seconds
    }

    test("pas d'accroche pour un seul moment") {
        val t = timeline(60, loud = (10..17).toSet())
        val plan = storyPlan(session(t, highlight(TimeRange(10.seconds, 18.seconds), 14.seconds)))
        plan.shots.map { it.role } shouldContainExactly listOf(ShotRole.OPENING)
    }

    test("cadrage alterné d'un jump cut à l'autre, temps retiré compté") {
        val t = timeline(60, loud = (10..13).toSet() + (20..23).toSet() + (30..33).toSet())
        val plan = storyPlan(session(t, highlight(TimeRange(10.seconds, 34.seconds), 12.seconds)))
        plan.shots.map { it.role } shouldContainExactly listOf(ShotRole.OPENING, ShotRole.JUMP, ShotRole.JUMP)
        plan.shots.map { it.zoom } shouldContainExactly listOf(1.0, 1.08, 1.0)
        plan.removed shouldBe sec(5.4) * 2
    }

    test("punch-in sur un rire, secousse sur le kill et le pic, voix et pic pour la musique") {
        val t = timeline(
            60,
            loud = (10..19).toSet(),
            segments = listOf(
                TimelineSegment(TimeRange(15.seconds, 17.seconds), "laughter", 0.9, "reactions"),
                TimelineSegment(TimeRange(sec(11.0), sec(11.3)), "shout", 0.9, "reactions"),
                TimelineSegment(TimeRange(12.seconds, 14.seconds), "speech", 1.0, "voice"),
            ),
            events = listOf(TimelineEvent(sec(13.1), "kill", 1.0, "outplayed")),
        )
        val plan = storyPlan(session(t, highlight(TimeRange(10.seconds, 20.seconds), 13.seconds)))
        val shot = plan.shots.single()
        // Le cri de 300 ms est trop court pour un punch-in.
        shot.punchIns shouldContainExactly listOf(TimeRange(5.seconds, 7.seconds))
        // Pic à 13 s et kill à 13,1 s : trop proches pour deux secousses.
        shot.shakes shouldContainExactly listOf(3.seconds)
        shot.drops shouldContainExactly listOf(3.seconds)
        shot.voice shouldHaveSize 3
    }

    test("séries de kills : DOUBLÉ au 2e, TRIPLÉ au 3e, la série se rompt au-delà de 5 s") {
        val kills = listOf(10.0, 12.0, 16.5, 30.0, 31.0).map { TimelineEvent(sec(it), "kill", 1.0, "outplayed") }
        val labels = StoryPlanner.streakLabels(timeline(60, loud = emptySet(), events = kills), EditSettings().story.labels)
        labels shouldContainExactly listOf(12.seconds to "DOUBLÉ", sec(16.5) to "TRIPLÉ", 31.seconds to "DOUBLÉ")
    }

    test("libellé posé dans le plan qui montre le kill, même si le début de la série est coupé") {
        val kills = listOf(8.0, 12.0, 15.0).map { TimelineEvent(sec(it), "kill", 1.0, "outplayed") }
        val t = timeline(60, loud = (10..19).toSet(), events = kills)
        val shot = storyPlan(session(t, highlight(TimeRange(10.seconds, 20.seconds), 13.seconds))).shots.single()
        shot.labels shouldContainExactly listOf(2.seconds to "DOUBLÉ", 5.seconds to "TRIPLÉ")
    }

    test("effets désactivés : ni punch-in ni secousse") {
        val t = timeline(
            60,
            loud = (10..19).toSet(),
            segments = listOf(TimelineSegment(TimeRange(15.seconds, 17.seconds), "laughter", 0.9, "reactions")),
        )
        val base = EditSettings(style = EditStyle.STORY)
        val settings = base.copy(story = base.story.copy(punchIn = base.story.punchIn.copy(enabled = false), shake = base.story.shake.copy(enabled = false)))
        val shot = storyPlan(session(t, highlight(TimeRange(10.seconds, 20.seconds), 13.seconds)), settings).shots.single()
        shot.punchIns.shouldBeEmpty()
        shot.shakes.shouldBeEmpty()
    }
})
