package dev.highlights.montage

import dev.highlights.core.InputException
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KillTimingTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))

    fun session(kills: List<Int>, detector: String = "game-events"): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(
            createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, detector) }),
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

    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds).earlierDefaults()
    val kills = listOf(60, 180, 300, 420, 540, 660, 780, 900)
    fun groups(s: MontageSettings = settings) = MontagePlanner.groups(listOf(session(kills)), s)
    fun ref(second: Int) = KillRef("partie.mp4", second.seconds)
    fun s(d: Duration) = d.inWholeMicroseconds / 1e6

    /** Invariants : chaque plan fait exactement son slot, et le kill d'ancrage sort là où le plan l'annonce. */
    fun checkSlots(p: MontagePlan) {
        val m = p.music
        p.clips.forEach { c ->
            withClue("plan ${c.slot}") {
                s(c.outputLength) shouldBe (s(m.beatTime(c.slot.endBeat) - m.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
                s(c.toOutput(c.anchor)) shouldBe (s(c.anchorOutput(m)) plusOrMinus 1e-6)
                val speedExtra = c.speeds.sumOf { s(it.range.length) * (1 / it.factor - 1) }
                s(c.sourceLength) + speedExtra + s(c.padBefore + c.padAfter) shouldBe (s(c.outputLength) plusOrMinus 1e-6)
            }
        }
    }

    test("caler un kill à l'image : l'instant choisi remplace la détection et garde la désignation d'origine") {
        val edited = MontageEdits.apply(groups(), MontageConstraints(anchors = mapOf(ref(300) to 300.083.seconds)), settings.killStyle)
        val g = edited.single { 300.083.seconds in it.kills }
        g.retimed[300.083.seconds] shouldBe KillRetime(300.seconds, byHand = true)
        g.detectedOf(300.083.seconds) shouldBe 300.seconds
        // Les autres kills ne bougent pas.
        edited.flatMap { it.kills }.filterNot { it == 300.083.seconds } shouldBe kills.filterNot { it == 300 }.map { it.seconds }
    }

    test("le kill calé tombe sur son temps dans le plan, chaque plan garde la longueur de son slot") {
        val c = MontageConstraints(anchors = mapOf(ref(300) to 299.9.seconds))
        val p = MontagePlanner.plan(MontageEdits.apply(groups(), c, settings.killStyle), music(), settings)
        checkSlots(p)
        val clip = p.clips.single { it.anchor == 299.9.seconds }
        // Le kill (instant choisi) tombe exactement sur un temps de la musique.
        s(clip.toOutput(clip.anchor) + p.music.beatTime(clip.slot.startBeat)) shouldBe (s(p.music.beatTime(clip.anchorBeat)) plusOrMinus 1e-6)
    }

    test("décalage du temps : le kill sort après son temps, les coupes restent sur les temps") {
        val c = MontageConstraints(beatShifts = mapOf(ref(300) to 33.milliseconds))
        val p = MontagePlanner.plan(MontageEdits.apply(groups(), c, settings.killStyle), music(), settings)
        checkSlots(p)
        val clip = p.clips.single { 300.seconds in it.group.kills }
        clip.beatShift shouldBe 33.milliseconds
        val onBeat = p.music.beatTime(clip.anchorBeat) - p.music.beatTime(clip.slot.startBeat)
        s(clip.toOutput(clip.anchor)) shouldBe (s(onBeat + 33.milliseconds) plusOrMinus 1e-6)
        p.clips.filter { it !== clip }.forEach { it.beatShift shouldBe Duration.ZERO }
        MontageEdits.check(p, p.clips.map { it.group })
    }

    test("le décalage appris d'un morceau décale tous ses kills, sauf celui décalé à la main") {
        val m = music().copy(killShift = (-25).milliseconds)
        val c = MontageConstraints(beatShifts = mapOf(ref(300) to 10.milliseconds))
        val p = MontagePlanner.plan(MontageEdits.apply(groups(), c, settings.killStyle), m, settings)
        checkSlots(p)
        p.clips.forEach { clip -> clip.beatShift shouldBe (if (300.seconds in clip.group.kills) 10.milliseconds else (-25).milliseconds) }
    }

    test("un calage trop loin du kill, ou un décalage du temps trop grand, est refusé avec le kill nommé") {
        shouldThrow<InputException> { MontageEdits.apply(groups(), MontageConstraints(anchors = mapOf(ref(300) to 301.5.seconds)), settings.killStyle) }
        shouldThrow<InputException> { MontageEdits.apply(groups(), MontageConstraints(beatShifts = mapOf(ref(300) to 300.milliseconds)), settings.killStyle) }
    }

    test("contraintes : écarter un kill retire son calage, le retirer d'une épingle le garde, zéro retire le décalage") {
        val c = MontageConstraints.NONE.anchor(ref(300), 300.05.seconds).shiftBeat(ref(300), 17.milliseconds).pin(ref(300), MontagePin.Drop)
        c.count shouldBe 3
        c.clear(ref(300)).anchors.keys shouldBe setOf(ref(300))
        c.exclude(ref(300)).let { it.anchors.isEmpty() shouldBe true; it.beatShifts.isEmpty() shouldBe true; it.excluded shouldBe setOf(ref(300)) }
        c.shiftBeat(ref(300), Duration.ZERO).beatShifts.isEmpty() shouldBe true
        c.clearTiming(ref(300)).let { it.anchors.isEmpty() shouldBe true; it.pins.size shouldBe 1 }
    }

    // ------------------------------------------------------------ apprentissage

    fun correction(i: Int, shift: Long, source: String = "game-events/event", profile: String = "valorant") =
        TimingCorrection("t", profile, "partie.mp4", 1000L * i, source, shiftMs = shift)

    test("décalage d'une source : rien sous le minimum de calages, appliqué dès qu'ils concordent") {
        val n = KillTimingLearner.MIN_SOURCE
        n shouldBe 8
        KillTimingLearner.learn((1 until n).map { correction(it, 60) }).sources.single().active shouldBe false
        val enough = (1..n + 1).map { correction(it, if (it % 2 == 0) 50 else 67) }
        val learned = KillTimingLearner.learn(enough)
        learned.sources.single().active shouldBe true
        learned.sourceOffset("valorant", "game-events/event") shouldBe 67.milliseconds
        // Une autre source, un autre profil : rien.
        learned.sourceOffset("valorant", "killfeed/event") shouldBe null
        learned.sourceOffset("cs2", "game-events/event") shouldBe null
    }

    test("décalage d'une source : des calages qui se contredisent, minuscules ou énormes ne donnent pas n'importe quoi") {
        KillTimingLearner.learn(listOf(-120L, 0, 130, -90, 110, 20).mapIndexed { i, v -> correction(i, v) }).sources.single().active shouldBe false
        KillTimingLearner.learn((1..6).map { correction(it, 5) }).sources.single().active shouldBe false
        val big = KillTimingLearner.learn((1..KillTimingLearner.MIN_SOURCE).map { correction(it, 450) }).sources.single()
        big.offset shouldBe KillTimingLearner.MAX_SOURCE_OFFSET
    }

    test("décalage d'un morceau : trois calages concordants suffisent, pas deux") {
        fun c(i: Int, ms: Long) = TimingCorrection("t", "valorant", "partie.mp4", 1000L * i, music = "rome.mp3|123", beat = i, beatShiftMs = ms)
        KillTimingLearner.MIN_SONG shouldBe 3
        KillTimingLearner.learn(listOf(c(1, 33), c(2, 33))).songOffset("rome.mp3|123") shouldBe null
        KillTimingLearner.learn(listOf(c(1, 33), c(2, 33), c(3, 33))).songOffset("rome.mp3|123") shouldBe 33.milliseconds
        KillTimingLearner.learn(listOf(c(1, 33), c(2, -60), c(3, 90))).songOffset("rome.mp3|123") shouldBe null
    }

    test("un décalage appris déplace les kills de sa source, jamais un kill calé à la main") {
        val learned = KillTimingLearner.learn((1..KillTimingLearner.MIN_SOURCE).map { correction(it, 50, profile = "wardogs") })
        val hand = MontageEdits.apply(groups(), MontageConstraints(anchors = mapOf(ref(300) to 300.01.seconds)), settings.killStyle)
        val moved = KillTimingLearner.applyTo(hand, learned, "wardogs")
        moved.flatMap { it.kills } shouldBe kills.map { if (it == 300) 300.01.seconds else it.seconds + 50.milliseconds }
        moved.single { 360.seconds > it.kills.first() && it.kills.first() > 290.seconds }.retimed.values.single().byHand shouldBe true
        moved.first().retimed.values.single() shouldBe KillRetime(60.seconds, byHand = false)
        // Un autre profil n'est pas touché.
        KillTimingLearner.applyTo(hand, learned, "valorant") shouldBe hand
    }

    test("source d'un kill : détecteur de l'événement, puis ce qui l'a recalé") {
        val g = groups().first()
        g.traits.single().source shouldBe "game-events"
        KillTimingLearner.sourceOf(KillTraits(source = "game-events")) shouldBe "game-events/event"
        KillTimingLearner.sourceOf(KillTraits(source = "game-events", shift = 40.milliseconds)) shouldBe "game-events/shot-sound"
        KillTimingLearner.sourceOf(KillTraits(source = "game-events/ammo-counter", fatal = 1.seconds)) shouldBe "game-events/ammo-counter"
        KillTimingLearner.sourceOf(KillTraits()) shouldBe null
    }

    test("les corrections d'un montage rendu : instant détecté, source, musique et temps du plan") {
        val c = MontageConstraints(anchors = mapOf(ref(300) to 300.05.seconds), beatShifts = mapOf(ref(300) to 17.milliseconds, ref(540) to (-17).milliseconds))
        val candidates = groups()
        val p = MontagePlanner.plan(MontageEdits.apply(candidates, c, settings.killStyle), music(), settings)
        val recorded = KillTimingLearner.record(candidates, p, c, "wardogs", "musique.mp3|1", "t")
        recorded shouldHaveSize 2
        val first = recorded.single { it.kill == 300_000L }
        first.shiftMs shouldBe 50L
        first.source shouldBe "game-events/event"
        first.beatShiftMs shouldBe 17L
        first.music shouldBe "musique.mp3|1"
        first.beat shouldBe p.clips.single { 300.05.seconds in it.group.kills }.anchorBeat
        recorded.single { it.kill == 540_000L }.let { it.shiftMs shouldBe null; it.beatShiftMs shouldBe -17L }
    }

    test("historique des calages : un kill recalé remplace sa correction, l'oubli met le fichier de côté") {
        val h = TimingHistory(tempdir().toPath().resolve("kill-timing.json"))
        h.record(listOf(correction(1, 40), correction(2, 50)))
        h.record(listOf(correction(1, 60)))
        h.load().corrections.map { it.kill to it.shiftMs } shouldBe listOf(2000L to 50L, 1000L to 60L)
        val aside = h.reset()
        aside shouldNotBe null
        h.load().corrections shouldBe emptyList()
    }

    test("instant d'une image lu dans la sortie de showinfo") {
        KillFrames.ptsTime("[Parsed_showinfo_1 @ 0000] n:   3 pts:  12345 pts_time:192.4500 duration: 1") shouldBe 192.45.seconds
        KillFrames.ptsTime("frame=  10 fps=0.0 q=4.0") shouldBe null
    }
})
