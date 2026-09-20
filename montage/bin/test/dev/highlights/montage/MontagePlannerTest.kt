package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SpeedRampEffect
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MontagePlannerTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))

    fun session(kills: List<Int>, speech: List<TimeRange> = emptyList(), scores: (Int) -> Double = { 0.5 }): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "wardogs",
            timeline = ScoredTimeline(
                grid, List(grid.count, scores), emptyMap(),
                events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "n") },
                segments = speech.map { TimelineSegment(it, "speech", 1.0, "voice") },
            ),
            highlights = emptyList(),
        )
    }

    /** Musique régulière à [bpm] : intro calme, montée, puis drop (section intense) jusqu'à la fin ; accent sur les premiers temps. */
    fun music(bpm: Double = 120.0, seconds: Int = 120, intro: Int = 32, build: Int = 32): MusicAnalysis {
        val n = (seconds * bpm / 60).toInt()
        val sections = listOf(
            MusicSection(0, intro, -20.0, 0.1),
            MusicSection(intro, intro + build, -14.0, 0.5),
            MusicSection(intro + build, n, -8.0, 1.0),
        )
        return MusicAnalysis(
            Path("musique.mp3"), seconds.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 }, sections = sections, dropBeat = intro + build,
        )
    }

    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds)
    fun seconds(d: Duration) = d.inWholeMicroseconds / 1e6
    fun plan(kills: List<Int>, m: MusicAnalysis = music(), s: MontageSettings = settings, speech: List<TimeRange> = emptyList()) =
        MontagePlanner.plan(MontagePlanner.groups(listOf(session(kills, speech)), s), m, s)

    val manyKills = listOf(60, 180, 183, 186, 300, 420, 540, 660, 780, 900, 1020, 1140, 1260)

    /** Invariants de tout plan : slots contigus, clips à la taille du slot, kill d'ancrage sur son temps, ralenti qui tient. */
    fun check(p: MontagePlan) {
        val m = p.music
        p.clips.zipWithNext().forEach { (a, b) -> a.slot.endBeat shouldBe b.slot.startBeat }
        (seconds(p.duration) <= seconds(p.settings.maxDuration) + 1e-6) shouldBe true
        p.clips.zip(p.clipOffsets()).forEach { (c, offset) ->
            withClue("clip ${c.slot}") {
                seconds(c.outputLength) shouldBe (seconds(m.beatTime(c.slot.endBeat) - m.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
                seconds(offset) shouldBe (seconds(m.beatTime(c.slot.startBeat) - p.musicStart) plusOrMinus 1e-6)
                // Le dernier kill tombe sur son temps, ralenti et gel compris.
                seconds(c.toOutput(c.anchor)) shouldBe (seconds(m.beatTime(c.anchorBeat) - m.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
                // Source + étirement du ralenti + gels = durée du slot.
                val speedExtra = c.speeds.sumOf { seconds(it.range.length) * (1 / it.factor - 1) }
                seconds(c.sourceLength) + speedExtra + seconds(c.padBefore + c.padAfter) shouldBe (seconds(c.outputLength) plusOrMinus 1e-6)
                (c.beatsPre >= 1 && c.beatsPost >= 1) shouldBe true
                c.kills.isNotEmpty() shouldBe true
                c.speeds.zipWithNext().forEach { (a, b) -> (a.range.end <= b.range.start) shouldBe true }
                c.speeds.forEach { (it.range.start >= c.start && it.range.end <= c.end) shouldBe true }
                c.ramps.forEach { kotlin.math.abs(it.factor - 1) shouldBe (0.0 plusOrMinus p.settings.speedRamp.maxChange + 1e-9) }
                c.slow?.let { slow ->
                    val tail = seconds(m.beatTime(c.slot.endBeat) - m.beatTime(c.anchorBeat))
                    (tail >= seconds(slow.range.end - c.anchor) / slow.factor + seconds(p.settings.cuts.minTail) - 1e-6) shouldBe true
                }
            }
        }
    }

    test("les clips remplissent exactement leur slot : coupes et kill d'ancrage sur les temps réels de la musique") {
        val p = plan(manyKills)
        check(p)
        // 11 groupes (un triple kill) ; le slot de la drop a absorbé un voisin pour le triple kill.
        p.clips shouldHaveSize 10
        (seconds(p.duration) > 40.0) shouldBe true
    }

    test("montée en puissance : le multi-kill tombe sur la drop, tous ses kills visibles, plans courts dans la partie intense") {
        val p = plan(manyKills)
        val drop = p.clips.single { it.slot.dropBeat != null }
        drop.kills shouldHaveSize 3
        drop.anchorBeat shouldBe p.music.dropBeat
        seconds(p.dropAt!!) shouldBe (seconds(p.clipOffsets()[p.clips.indexOf(drop)] + drop.toOutput(drop.anchor)) plusOrMinus 1e-6)
        // Le slot a été étendu vers l'arrière pour contenir les 6 s de kills.
        (drop.beats > p.clips.filter { it.slot.dropBeat == null }.minOf { it.beats }) shouldBe true
        val high = p.clips.filter { it.slot.dropBeat == null && p.music.sectionAt(it.slot.startBeat).level == Intensity.HIGH }
        val mid = p.clips.filter { p.music.sectionAt(it.slot.startBeat).level == Intensity.MID }
        high.isNotEmpty() shouldBe true
        mid.isNotEmpty() shouldBe true
        (mid.minOf { it.beats } >= high.maxOf { it.beats }) shouldBe true
    }

    test("tempo variable : les coupes suivent les temps détectés, pas une période moyenne") {
        // Temps qui ralentissent progressivement (musique jouée live).
        val n = 240
        var t = 0.0
        val beats = List(n) { i -> t.also { t += 0.5 + 0.0005 * i } }.map { it.seconds }
        val m = music().copy(beats = beats, sections = listOf(MusicSection(0, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)), dropBeat = 64)
        val p = plan(manyKills, m)
        check(p)
        p.clipOffsets().drop(1).zip(p.clips.drop(1)).forEach { (offset, c) ->
            seconds(offset) shouldBe (seconds(beats[c.slot.startBeat] - p.musicStart) plusOrMinus 1e-6)
        }
    }

    test("peu de clips : tous utilisés, plans plus longs") {
        val p = plan(listOf(100, 400, 700))
        check(p)
        p.clips shouldHaveSize 3
        p.clips.forEach { it.beats shouldBeGreaterThanOrEqual 8 }
    }

    test("beaucoup de clips : budget respecté, les mieux classés gardés") {
        val kills = (1..80).map { it * 20 } + listOf(1700, 1702)
        val p = plan(kills)
        check(p)
        (p.clips.size < kills.size) shouldBe true
        p.clips.single { it.slot.dropBeat != null }.kills shouldHaveSize 2
    }

    test("ordre chronologique : clips dans l'ordre de la partie") {
        val chrono = settings.copy(order = MontageOrder.CHRONOLOGICAL)
        val p = plan(listOf(900, 100, 500), s = chrono)
        check(p)
        p.clips.map { it.kills.first().inWholeSeconds } shouldBe listOf(100L, 500L, 900L)
    }

    test("aucun kill : erreur explicite") {
        shouldThrow<dev.highlights.core.HighlightsException> { MontagePlanner.plan(emptyList(), music(), settings) }
    }

    test("début de vidéo : image gelée plutôt que kill décalé") {
        val p = plan(listOf(1))
        check(p)
        val clip = p.clips.single()
        clip.padBefore.isPositive() shouldBe true
        clip.start shouldBe Duration.ZERO
    }

    test("fin de vidéo : image gelée après le kill") {
        val p = plan(listOf(media.duration.inWholeSeconds.toInt() - 1))
        check(p)
        val clip = p.clips.single()
        clip.padAfter.isPositive() shouldBe true
        clip.end shouldBe media.duration
    }

    test("réaction qui déborde : le clip dure jusqu'à la fin de la phrase") {
        val p = plan(listOf(100, 400, 700), speech = listOf(TimeRange(700.5.seconds, 705.seconds)))
        check(p)
        val talk = p.clips.single { it.kills.first() == 700.seconds }
        (seconds(talk.end) >= 705.0) shouldBe true
    }

    /** Écart (s) entre un instant du montage et le temps musical le plus proche. */
    fun offBeat(p: MontagePlan, clip: MontageClip, t: Duration): Double {
        val abs = seconds(p.musicStart + p.clipOffsets()[p.clips.indexOf(clip)] + t)
        return p.music.beats.minOf { kotlin.math.abs(seconds(it) - abs) }
    }

    test("rampe de vitesse : chaque kill d'un multi-kill tombe sur un temps, vitesse dans la limite") {
        val kills = listOf(60, 300, 420, 540, 660, 780, 900) + listOf(180.0, 183.2, 186.0).map { it }
        val session = session(emptyList()).let { s ->
            s.copy(timeline = s.timeline.copy(events = kills.map { TimelineEvent((it.toDouble() * 1000).toLong().let { ms -> Duration.parse("${ms}ms") }, "kill", 1.0, "n") }))
        }
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(session), settings), music(), settings)
        check(p)
        val triple = p.clips.single { it.group.kills.size == 3 }
        triple.kills shouldHaveSize 3
        triple.ramps.isNotEmpty() shouldBe true
        triple.outputKills().forEach { k -> offBeat(p, triple, k) shouldBe (0.0 plusOrMinus 1e-3) }
        // Sans rampe : seul le dernier kill est sur un temps.
        val off = settings.copy(speedRamp = SpeedRampEffect(enabled = false))
        val q = MontagePlanner.plan(MontagePlanner.groups(listOf(session), off), music(), off)
        val plain = q.clips.single { it.group.kills.size == 3 }
        plain.ramps shouldHaveSize 0
        offBeat(q, plain, plain.toOutput(plain.anchor)) shouldBe (0.0 plusOrMinus 1e-3)
        (offBeat(q, plain, plain.toOutput(183.2.seconds)) > 0.05) shouldBe true
    }

    test("rampe impossible dans la limite : vitesse inchangée") {
        val session = session(emptyList()).let { s ->
            s.copy(timeline = s.timeline.copy(events = listOf(100.seconds, 100.9.seconds, 500.seconds).map { TimelineEvent(it, "kill", 1.0, "n") }))
        }
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(session), settings), music(), settings)
        check(p)
        p.clips.single { it.group.kills.size == 2 }.ramps shouldHaveSize 0
    }

    test("suite de plans de même longueur : kill toujours au même endroit du plan") {
        val p = plan((1..20).map { it * 60 })
        check(p)
        p.clips.filter { it.slot.dropBeat == null }.groupBy { it.slot.section to it.beats }.values.forEach { same ->
            same.map { it.beatsPre }.toSet() shouldHaveSize 1
        }
    }

    test("multi-kill trop long pour tout slot : les premiers kills sont coupés, le dernier reste sur son temps") {
        val tight = settings.copy(cuts = settings.cuts.copy(maxBeats = 4, minLead = 250.seconds / 1000))
        val p = plan(listOf(100, 104, 108, 500), s = tight)
        check(p)
        val multi = p.clips.single { it.group.kills.size == 3 }
        (multi.kills.size < 3) shouldBe true
        multi.kills.last() shouldBe 108.seconds
    }
})
