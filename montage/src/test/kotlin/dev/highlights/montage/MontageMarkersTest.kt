package dev.highlights.montage

import dev.highlights.core.InputException
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MontageMarkersTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))

    fun session(kills: List<Int>): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(
            createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "game-events") }),
            highlights = emptyList(),
        )
    }

    fun music(bpm: Double = 120.0, seconds: Int = 120): MusicAnalysis {
        val n = (seconds * bpm / 60).toInt()
        val sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0))
        return MusicAnalysis(
            Path("musique.mp3"), seconds.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 }, sections = sections, dropBeat = 64,
        )
    }

    // Sans ralenti ni rampe : les repères se lisent sur la source brute.
    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds).earlierDefaults().let {
        it.copy(slowMotion = it.slowMotion.copy(enabled = false), speedRamp = it.speedRamp.copy(enabled = false), cuts = it.cuts.copy(jumpCuts = false))
    }
    val kills = listOf(60, 180, 300, 420, 540, 660, 780, 900)
    fun groups() = MontagePlanner.groups(listOf(session(kills)), settings)
    fun ref(second: Int) = KillRef("partie.mp4", second.seconds)
    fun s(d: Duration) = d.inWholeMicroseconds / 1e6
    fun plan(c: MontageConstraints) = MontagePlanner.plan(MontageEdits.apply(groups(), c, settings.killStyle), music(), settings)
    fun clipOf(p: MontagePlan, second: Int) = p.clips.single { second.seconds in it.group.kills }

    /** Chaque plan fait exactement son slot, le kill d'ancrage sort là où le plan l'annonce. */
    fun checkSlots(p: MontagePlan) {
        val m = p.music
        p.clips.forEach { c ->
            withClue("plan ${c.slot}") {
                s(c.outputLength) shouldBe (s(m.beatTime(c.slot.endBeat) - m.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
                s(c.toOutput(c.anchor)) shouldBe (s(c.anchorOutput(m)) plusOrMinus 1e-6)
                s(c.toOutput(c.end) + c.padAfter) shouldBe (s(c.outputLength) plusOrMinus 1e-6)
            }
        }
    }

    test("couper ici : le plan commence et finit où l'utilisateur l'a dit, garde sa longueur et son kill sur le temps") {
        val before = clipOf(plan(MontageConstraints.NONE), 300)
        val start = before.start + 150.milliseconds
        val end = before.end - 100.milliseconds
        val p = plan(MontageConstraints.NONE.cut(ref(300), start, end))
        val c = clipOf(p, 300)
        c.start shouldBe start
        c.end shouldBe end
        c.anchorBeat shouldBe before.anchorBeat
        // Moins de source pour la même place : un léger ralenti avant et après le kill.
        c.speeds.filter { it.range.end <= c.anchor }.single().factor shouldBe ((s(300.seconds - start) / s(c.toOutput(300.seconds))) plusOrMinus 1e-6)
        c.speeds.all { it.factor < 1.0 } shouldBe true
        checkSlots(p)
    }

    test("couper ici : une zone trop courte pour le plan (ralenti au-delà de ×2) est refusée avec un message clair") {
        // 0,2 s de capture pour un plan d'au moins deux temps (1 s) : même ralenti ×2, ça ne remplit pas.
        val e = shouldThrow<InputException> { plan(MontageConstraints.NONE.cut(ref(300), 299.9.seconds, 300.1.seconds)) }
        e.message shouldContain "partie.mp4@5:00"
        shouldThrow<InputException> { plan(MontageConstraints.NONE.cut(ref(300), start = 300.5.seconds)) }
    }

    test("section sans kill : son kill est écarté, et aucun plan ne la montre") {
        val skipKill = CaptureRange("partie.mp4", TimeRange(419.seconds, 421.seconds), RangeKind.SKIP)
        val p1 = plan(MontageConstraints(ranges = listOf(skipKill)))
        p1.clips.none { 420.seconds in it.group.kills } shouldBe true
        val before = clipOf(plan(MontageConstraints.NONE), 540)
        // Un temps mort juste avant le kill de 9:00, à l'intérieur de son plan.
        val gap = TimeRange(before.start + 50.milliseconds, before.start + 400.milliseconds)
        val p2 = plan(MontageConstraints(ranges = listOf(CaptureRange("partie.mp4", gap, RangeKind.SKIP))))
        val c = clipOf(p2, 540)
        c.start shouldBe gap.end
        (c.start >= gap.end || c.end <= gap.start) shouldBe true
        checkSlots(p2)
    }

    test("section à garder entière : un plan qui commencerait dedans la montre depuis son début (accéléré, ×2 au plus)") {
        val before = clipOf(plan(MontageConstraints.NONE), 660)
        val keep = TimeRange(before.start - 300.milliseconds, before.start + 200.milliseconds)
        val p = plan(MontageConstraints(ranges = listOf(CaptureRange("partie.mp4", keep, RangeKind.KEEP))))
        val c = clipOf(p, 660)
        c.start shouldBe keep.start
        c.speeds.single { it.range.end <= c.anchor }.factor shouldBe ((s(660.seconds - keep.start) / s(c.toOutput(660.seconds))) plusOrMinus 1e-6)
        checkSlots(p)
        // Trop longue pour la place : refusée.
        val huge = TimeRange(before.start - 20.seconds, before.start + 200.milliseconds)
        shouldThrow<InputException> { plan(MontageConstraints(ranges = listOf(CaptureRange("partie.mp4", huge, RangeKind.KEEP)))) }
    }

    test("coupe autour du kill seulement : début après le kill refusé") {
        shouldThrow<InputException> { plan(MontageConstraints.NONE.cut(ref(300), start = 301.seconds)) }
        shouldThrow<InputException> { plan(MontageConstraints.NONE.cut(ref(300), end = 299.seconds)) }
    }

    test("repères : désignation par texte, bornes complétées, retirées une à une") {
        val r = CaptureRange.parse("partie.mp4@1:02.5..1:10", RangeKind.SKIP)
        r.range shouldBe TimeRange(62.5.seconds, 70.seconds)
        r.toString() shouldBe "partie.mp4@1:02.500..1:10.000"
        shouldThrow<InputException> { CaptureRange.parse("partie.mp4@1:10..1:02", RangeKind.KEEP) }
        val c = MontageConstraints.NONE.cut(ref(300), start = 299.seconds).cut(ref(300), end = 301.seconds)
        c.cuts[ref(300)] shouldBe ClipCut(299.seconds, 301.seconds)
        c.clearCut(ref(300), start = true).cuts[ref(300)] shouldBe ClipCut(null, 301.seconds)
        c.clearCut(ref(300), start = true).clearCut(ref(300), start = false).cuts.isEmpty() shouldBe true
        val ranged = c.addRange(r).addRange(r)
        ranged.ranges.size shouldBe 1
        ranged.count shouldBe 2
        ranged.removeRange(0).ranges.isEmpty() shouldBe true
    }

    test("longueurs apprises des coupes : 8 concordantes par profil, sinon rien ; le kill est placé pour s'en approcher") {
        fun pref(i: Int, before: Long, after: Long, profile: String = "wardogs") = CutPreference("t", profile, "partie.mp4", i * 1000L, before, after)
        ClipLengthLearner.learn((1..7).map { pref(it, 600, 400) }).beforeFor("wardogs") shouldBe null
        val learned = ClipLengthLearner.learn((1..8).map { pref(it, 600L + it % 3 * 10, 400) })
        learned.beforeFor("wardogs") shouldBe 610.milliseconds
        learned.afterFor("wardogs") shouldBe 400.milliseconds
        learned.beforeFor("valorant") shouldBe null
        // Dispersées : pas appliquées.
        ClipLengthLearner.learn((1..10).map { pref(it, if (it % 2 == 0) 300 else 1500, 400) }).beforeFor("wardogs") shouldBe null
        // Le planificateur rapproche la place du kill de la longueur apprise.
        val m = music()
        val base = MontagePlanner.plan(groups(), m, settings)
        val target = 2.seconds
        val tuned = MontagePlanner.plan(groups(), m, settings.copy(cuts = settings.cuts.copy(preferredLead = target)))
        fun gap(p: MontagePlan) = p.clips.filter { it.beats >= 4 }.sumOf { abs(s(it.toOutput(it.anchor)) - s(target)) }
        (gap(tuned) <= gap(base)) shouldBe true
    }

    test("coupes enregistrées pour l'apprentissage : longueurs avant et après le kill d'ancrage") {
        val before = clipOf(plan(MontageConstraints.NONE), 300)
        val p = plan(MontageConstraints.NONE.cut(ref(300), before.start + 100.milliseconds, before.end - 100.milliseconds))
        val rec = ClipLengthLearner.record(p, "wardogs").single()
        rec.beforeMs shouldBe (300.seconds - before.start - 100.milliseconds).inWholeMilliseconds
        rec.afterMs shouldBe (before.end - 100.milliseconds - 300.seconds).inWholeMilliseconds
        rec.kill shouldBe 300_000L
    }
    test("couper ici, longue suite après le kill : le planificateur lui trouve la place (plan allongé, kill placé tôt)") {
        val p = plan(MontageConstraints.NONE.cut(ref(300), 299.5.seconds, 302.3.seconds))
        val c = clipOf(p, 300)
        c.start shouldBe 299.5.seconds
        c.end shouldBe 302.3.seconds
        c.speeds.all { it.factor in ClipMarkers.MIN_FACTOR..ClipMarkers.MAX_FACTOR } shouldBe true
        checkSlots(p)
    }
})
