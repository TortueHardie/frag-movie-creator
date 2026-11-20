package dev.highlights.montage

import dev.highlights.core.HighlightsException
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
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * « Kill sur ce moment de la musique » : épingle à l'instant exact du morceau, et ce qui s'apprend de ces épingles
 * (jeux synthétiques à biais caché, tableau dans `build/pin-learning.md`).
 */
class MusicPinsTest : FunSpec({
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
    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds).earlierDefaults()
    val kills = listOf(60, 180, 300, 420, 540, 660, 780, 900)
    fun groups() = MontagePlanner.groups(listOf(session(kills)), settings)
    fun ref(second: Int) = KillRef("partie.mp4", second.seconds)
    fun s(d: Duration) = d.inWholeMicroseconds / 1e6
    fun plan(c: MontageConstraints, m: MusicAnalysis = music()) = MontagePlanner.plan(MontageEdits.apply(groups(), c, settings.killStyle), m, settings)

    test("kill posé sur un instant du morceau : il y tombe exactement, même entre deux temps ; le plan garde sa longueur") {
        val m = music()
        val base = plan(MontageConstraints.NONE, m)
        // Autour du temps où ce kill tombe déjà (sa place lui est libre) : 120 ms après, 150 ms avant, pile.
        val beat = base.clips.single { 300.seconds in it.group.kills }.anchorBeat
        for (offset in listOf(120.milliseconds, (-150).milliseconds, Duration.ZERO)) {
            val at = m.beatTime(beat) + offset
            val p = plan(MontageConstraints.NONE.pin(ref(300), MontagePin.At(at)), m)
            val c = p.clips.single { 300.seconds in it.group.kills }
            withClue("épingle à ${Durations(at)}") {
                c.anchorBeat shouldBe beat
                // L'instant du kill dans le morceau = début du plan dans le morceau + place du kill dans le plan.
                s(m.beatTime(c.slot.startBeat) + c.toOutput(c.anchor)) shouldBe (s(at) plusOrMinus 1e-3)
                s(c.outputLength) shouldBe (s(m.beatTime(c.slot.endBeat) - m.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
            }
        }
    }

    test("deux kills au même instant du morceau : refusé, comme deux épingles sur un même temps") {
        val m = music()
        val at = m.beatTime(plan(MontageConstraints.NONE, m).clips.single { 300.seconds in it.group.kills }.anchorBeat) + 60.milliseconds
        shouldThrow<HighlightsException> { plan(MontageConstraints.NONE.pin(ref(300), MontagePin.At(at)).pin(ref(540), MontagePin.At(at)), m) }
    }

    test("kills posés sur la musique : enregistrés avec leur écart au temps et la force de leur temps") {
        val m = music()
        val beat = plan(MontageConstraints.NONE, m).clips.single { 300.seconds in it.group.kills }.anchorBeat
        val p = plan(MontageConstraints.NONE.pin(ref(300), MontagePin.At(m.beatTime(beat) - 40.milliseconds)), m)
        val rec = PinLearner.record(p, "wardogs").single()
        rec.offsetMs shouldBe -40L
        rec.periodMs shouldBe 500L
        rec.kill shouldBe 300_000L
        rec.strength shouldBe m.strength(beat)
        val store = PinHistory(tempdir().toPath().resolve("sessions/kill-pins.json"))
        store.record(listOf(rec))
        store.record(listOf(rec.copy(offsetMs = -30)))
        store.load().single().offsetMs shouldBe -30L
        store.reset()
        store.load() shouldBe emptyList()
    }

    val report = StringBuilder()

    test("écart régulier caché (kills posés 40 ms avant le temps) : appliqué à partir de 8 kills, vérifié une de côté") {
        val r = Random(5)
        fun pin(i: Int, offset: Long) = PinExample("t", "wardogs", "m", "partie.mp4", i * 1000L, offset, 476, 0.6, 0.6)
        val examples = (1..14).map { pin(it, -40L + r.nextInt(-8, 9)) }
        report.appendLine("### Écart régulier caché : kills posés 40 ms avant le temps (±8 ms)").appendLine()
        report.appendLine("| kills posés | écart appris | une de côté (appris / sur le temps) | appliqué |").appendLine("|---|---|---|---|")
        var first: Int? = null
        for (k in 1..examples.size) {
            val l = PinLearner.learn(examples.take(k))
            if (l.offsetActive && first == null) first = k
            report.appendLine("| $k | ${l.offset?.inWholeMilliseconds} ms | ${l.offsetError?.inWholeMilliseconds ?: "-"} / ${l.baseError?.inWholeMilliseconds ?: "-"} ms | ${if (l.offsetActive) "oui" else "non"} |")
        }
        first shouldBe PinLearner.MIN_PINS
        val learned = PinLearner.learn(examples)
        (learned.killShift!!.inWholeMilliseconds in -45L..-35L) shouldBe true
        report.appendLine().appendLine("Appliqué dès $first kills posés.").appendLine()
        // Sans régularité (kills posés n'importe où autour du temps) : rien.
        PinLearner.learn((1..14).map { pin(it, r.nextLong(-150, 150)) }).offsetActive shouldBe false
        // Sur le temps : rien à corriger.
        PinLearner.learn((1..14).map { pin(it, r.nextLong(-3, 4)) }).offsetActive shouldBe false
    }

    test("préférence cachée pour les accents : appliquée quand la validation une de côté la confirme, et le placement suit") {
        val r = Random(9)
        fun pin(i: Int, strength: Double, slot: Double) = PinExample("t", "wardogs", "m", "partie.mp4", i * 1000L, 0, 500, strength, slot)
        val accent = (1..12).map { pin(it, 0.9 + 0.1 * r.nextDouble(), 0.6) }
        report.appendLine("### Préférence cachée pour les temps qui frappent").appendLine()
        report.appendLine("| kills posés | part prévue une de côté | appliqué |").appendLine("|---|---|---|")
        var first: Int? = null
        for (k in 1..accent.size) {
            val l = PinLearner.learn(accent.take(k))
            if (l.accentActive && first == null) first = k
            report.appendLine("| $k | ${"%.2f".format(l.accentShare)} | ${if (l.accentActive) "oui" else "non"} |")
        }
        first shouldBe PinLearner.MIN_PINS
        report.appendLine().appendLine("Appliqué dès $first kills posés.").appendLine()
        // Posés au hasard des temps : pas de préférence.
        PinLearner.learn((1..12).map { pin(it, r.nextDouble(), 0.5) }).accentActive shouldBe false
        // Le placement automatique se rapproche des temps forts (premier temps de mesure, attaque 1,0).
        val m = music()
        val plain = MontagePlanner.plan(groups(), m, settings)
        val tuned = MontagePlanner.plan(groups(), m, settings.copy(cuts = settings.cuts.copy(accentBonus = PinLearner.ACCENT_BONUS)))
        fun strong(p: MontagePlan) = p.clips.count { p.music.strength(it.anchorBeat) >= 1.0 }
        (strong(tuned) >= strong(plain)) shouldBe true
    }

    afterSpec { Path("build").createDirectories().resolve("pin-learning.md").writeText(report.toString()) }
})

private fun Durations(d: Duration) = dev.highlights.core.serialization.Durations.format(d)
