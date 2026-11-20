package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Kills posés sur la musique (étape 1), plans construits ensuite autour (étape 2) : jamais de refus pour place prise. */
class KillLayoutTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val kills = listOf(60, 180, 300, 420, 540, 660, 780, 900)
    val session = run {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        Session(
            createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "game-events") }),
            highlights = emptyList(),
        )
    }
    val n = 240
    val music = MusicAnalysis(
        Path("musique.mp3"), 120.seconds, 120.0, List(n) { (it * 0.5).seconds }, downbeatPhase = 0,
        beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
        sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)), dropBeat = 64,
    )
    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds).earlierDefaults()
    fun ref(second: Int) = KillRef("partie.mp4", second.seconds)
    fun s(d: Duration) = d.inWholeMicroseconds / 1e6

    fun plan(layout: Map<KillRef, Duration>): MontagePlan {
        val groups = MontageEdits.apply(MontagePlanner.groups(listOf(session), settings), MontageConstraints(layout = layout), settings.killStyle)
        return KillLayout.plan(groups, music, settings)
    }

    /** Chaque kill posé tombe exactement à son instant du morceau ; chaque plan fait son slot. */
    fun check(p: MontagePlan, layout: Map<KillRef, Duration>) {
        p.clips.size shouldBe layout.size
        p.clips.forEach { c ->
            val at = layout.entries.single { (r, _) -> (c.group.kills.last() - r.at).absoluteValue < 1.milliseconds }.value
            withClue("kill ${c.anchor}") {
                // Grille du plan (temps de coupe des plans courts compris).
                s(p.music.beatTime(c.slot.startBeat) + c.toOutput(c.anchor)) shouldBe (s(at) plusOrMinus 1e-3)
                s(c.outputLength) shouldBe (s(p.music.beatTime(c.slot.endBeat) - p.music.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
            }
        }
    }

    test("kills posés n'importe où (entre deux temps, écarts variés) : chacun à son instant exact, plans contigus") {
        val layout = mapOf(
            ref(60) to 20.13.seconds, ref(180) to 23.5.seconds, ref(300) to 25.02.seconds, ref(420) to 32.0.seconds,
            ref(540) to 40.77.seconds, ref(660) to 41.9.seconds,
        )
        val p = plan(layout)
        check(p, layout)
        // Les kills non posés ne sont pas dans le montage : c'est l'étape 1 qui décide.
        p.clips.none { 780.seconds in it.group.kills || 900.seconds in it.group.kills } shouldBe true
    }

    /** Ce qu'on voit d'un plan avant son kill (temps du montage) : son approche. */
    fun lead(p: MontagePlan, c: MontageClip) = s(c.toOutput(c.anchor))

    test("3 kills à un temps d'écart : chacun voit venir son kill (coupe entre deux temps), à son instant exact") {
        val layout = mapOf(ref(60) to 30.0.seconds, ref(180) to 30.5.seconds, ref(300) to 31.0.seconds)
        val p = plan(layout)
        check(p, layout)
        // Avant (20/11) la coupe tombait sur le temps du kill : le plan s'ouvrait sur lui, on ne le voyait pas venir.
        p.clips.drop(1).forEach { c -> lead(p, c) shouldBe (0.3 plusOrMinus 1e-3) }
        p.clips.zipWithNext().all { (a, b) -> a.slot.endBeat == b.slot.startBeat } shouldBe true
    }

    test("une suite de kills à un temps d'écart (le passage de 29 s de l'utilisateur) : chaque kill a au moins 0,25 s d'approche, le précédent sa fin") {
        // Sur la drop, un kill sur chaque temps pendant 8 s, comme posés sur les coups de batterie.
        val layout = (0 until 16).associate { k -> KillRef("partie.mp4", (60 + 60 * k).seconds) to (30.0 + 0.5 * k).seconds }
        val runSession = run {
            val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
            Session(
                createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
                timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = (0 until 16).map { TimelineEvent((60 + 60 * it).seconds, "kill", 1.0, "game-events") }),
                highlights = emptyList(),
            )
        }
        val groups = MontageEdits.apply(MontagePlanner.groups(listOf(runSession), settings), MontageConstraints(layout = layout), settings.killStyle)
        val p = KillLayout.plan(groups, music, settings)
        check(p, layout)
        p.clips.size shouldBe 16
        p.clips.drop(1).forEach { c -> withClue("kill ${c.anchor}") { (lead(p, c) >= 0.25) shouldBe true } }
        // La fin du précédent : le reste de l'écart, au moins 0,15 s.
        p.clips.dropLast(1).forEach { c -> withClue("fin ${c.anchor}") { (s(c.outputLength) - lead(p, c) >= 0.15) shouldBe true } }
    }

    test("2 kills à 100 ms d'écart (parties différentes) : plan court du second, coupe à 60 % de l'écart avant lui ; tous deux à leur instant") {
        val layout = mapOf(ref(60) to 30.02.seconds, ref(180) to 30.12.seconds)
        val p = plan(layout)
        check(p, layout)
        val second = p.clips.last()
        // Le plan court s'ouvre sur un temps de coupe ajouté, 60 ms (60 % de l'écart) avant le kill.
        s(p.music.beatTime(second.slot.startBeat)) shouldBe (30.06 plusOrMinus 1e-6)
        (second.slot.startBeat in p.music.virtualBeats) shouldBe true
        // Coupe du plan court à 0,2 ms d'un vrai temps (30,5 s) : elle se pose sur lui, le kill reste à son instant.
        val nearBeat = mapOf(ref(60) to 30.2.seconds, ref(180) to 30.5502.seconds)
        check(plan(nearBeat), nearBeat)
        // Jamais d'erreur, même posés au même instant.
        val same = plan(mapOf(ref(60) to 30.0.seconds, ref(180) to 30.0.seconds))
        same.clips.size shouldBe 2
    }

    test("2 kills proches de la même capture, écart réel ≈ écart voulu : un seul plan qui montre les deux") {
        val g = MontagePlanner.groups(listOf(session), settings).single { 300.seconds in it.kills }
        val first = g.copy(pin = MontagePin.At(30.0.seconds))
        val second = g.copy(kills = listOf(300.12.seconds), pin = MontagePin.At(30.12.seconds))
        val p = KillLayout.plan(listOf(first, second), music, settings)
        p.clips.size shouldBe 1
        val c = p.clips.single()
        c.group.kills shouldBe listOf(300.seconds, 300.12.seconds)
        s(p.music.beatTime(c.slot.startBeat) + c.toOutput(300.12.seconds)) shouldBe (30.12 plusOrMinus 1e-3)
        s(p.music.beatTime(c.slot.startBeat) + c.toOutput(300.seconds)) shouldBe (30.0 plusOrMinus 0.01)
    }

    test("l'ordre du montage suit les instants posés, pas l'ordre des parties") {
        val layout = mapOf(ref(900) to 20.0.seconds, ref(60) to 26.0.seconds, ref(420) to 23.0.seconds)
        val p = plan(layout)
        p.clips.map { it.group.kills.last() } shouldBe listOf(900.seconds, 420.seconds, 60.seconds)
        check(p, layout)
    }
})
