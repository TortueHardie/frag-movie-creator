package dev.highlights.ui

import dev.highlights.core.model.ClipOrder
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MomentOrigin
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import dev.highlights.editing.DefaultEditPlanner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MomentRetouchTest : FunSpec({
    fun capture(name: String, created: Instant, vararg ranges: IntRange): SessionEntry {
        val media = MediaInfo(path = Path("$name.mp4"), sizeBytes = 1, duration = 10.minutes, creationTime = created)
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        val session = Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "test",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.2 }, emptyMap()),
            highlights = ranges.mapIndexed { i, r -> Highlight("h%03d".format(i + 1), media.path, TimeRange(r.first.seconds, r.last.seconds), r.first.seconds, 0.5) },
        )
        return SessionEntry(session, Path("$name.session.json"))
    }

    // Deux parties de la soirée, trois moments chacune : clés « 1-h001 »… avec plusieurs captures.
    val soiree = SessionState(listOf(
        capture("a", Instant.parse("2026-09-22T19:00:00Z"), 10..20, 50..60, 100..110),
        capture("b", Instant.parse("2026-09-22T21:00:00Z"), 30..40, 70..80, 200..210),
    ))
    val policy = SelectionPolicy(minClip = 4.seconds, maxClip = 20.seconds)

    test("sans ordre manuel, le montage suit la soirée") {
        MomentRetouch.montageOrder(soiree).map { it.key } shouldBe listOf("1-h001", "1-h002", "1-h003", "2-h001", "2-h002", "2-h003")
    }

    test("faire monter un moment de la deuxième partie : l'export enchaîne exactement l'ordre affiché") {
        var s = MomentRetouch.move(soiree, "2-h001", -1)
        s = MomentRetouch.move(s, "2-h001", -1)
        val shown = MomentRetouch.montageOrder(s).map { it.key }
        shown shouldBe listOf("1-h001", "2-h001", "1-h002", "1-h003", "2-h002", "2-h003")
        val exported = DefaultEditPlanner.plan(s.sessions, EditSettings(order = ClipOrder.MANUAL)).clips.map { c -> s.segments.first { it.highlight == c.highlight }.key }
        exported shouldBe shown
    }

    test("rallonger un moment le marque retouché et garde la sélection dessus") {
        val (s, key) = MomentRetouch.nudge(soiree, "1-h002", end = true, delta = 2.seconds, policy = policy)
        val moment = s.segments.first { it.key == key }.highlight
        moment.range shouldBe TimeRange(50.seconds, 62.seconds)
        moment.origin shouldBe MomentOrigin.ADJUSTED
        key shouldBe "1-m001"
    }

    test("un moment ajouté prend place après le dernier rang quand l'ordre est manuel") {
        val ordered = MomentRetouch.move(soiree, "1-h002", 1)
        val (s, key) = MomentRetouch.add(ordered, entry = 1, at = 300.seconds, policy = policy)!!
        s.segments.first { it.key == key }.highlight.origin shouldBe MomentOrigin.ADDED
        MomentRetouch.montageOrder(s).last().key shouldBe key
    }
})
