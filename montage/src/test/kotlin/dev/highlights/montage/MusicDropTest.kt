package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MusicDropTest : FunSpec({
    // Comme Rome Is Burning : 126 BPM, la drop détectée deux temps après la vraie (temps 66 au lieu de 64).
    val bpm = 126.0
    val period = 60.0 / bpm
    fun music(): MusicAnalysis {
        val n = 200
        val sections = listOf(MusicSection(0, 18, -27.0, 0.14), MusicSection(18, 66, -20.0, 0.25), MusicSection(66, 194, -10.0, 0.95), MusicSection(194, n, -15.0, 0.5))
        return MusicAnalysis(
            Path("rome.mp3"), (n * period).seconds, bpm, List(n) { (it * period).seconds }, downbeatPhase = 2,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { 0.6 },
            sections = MusicAnalyzer.classify(sections, 66), dropBeat = 66,
        )
    }
    fun s(d: Duration) = d.inWholeMicroseconds / 1e6

    test("drop posée sur un temps détecté : ce temps devient la drop, la section de la drop commence sur lui") {
        val m = music()
        val at = m.beats[64]
        val d = MusicDrops.apply(m, at)
        d.dropBeat shouldBe 64
        d.beatTime(64) shouldBe at
        d.beats shouldBe m.beats
        d.dropByHand shouldBe at
        d.sectionAt(64).startBeat shouldBe 64
        d.sectionAt(64).kind shouldBe SectionKind.DROP
        // La frontière la plus proche a été déplacée (deux temps), pas de section en plus.
        d.sections.size shouldBe m.sections.size
        d.sections.count { it.kind == SectionKind.DROP } shouldBe 1
    }

    test("drop posée entre deux temps : la grille est recalée autour, l'instant devient un temps exact, rien ne bouge au-delà de 8 temps") {
        val m = music()
        val at = m.beats[64] + 100.milliseconds
        val d = MusicDrops.apply(m, at)
        d.dropBeat shouldBe 64
        s(d.beatTime(64)) shouldBe (s(at) plusOrMinus 1e-6)
        // Atténué : à mi-chemin, la moitié du décalage.
        s(d.beats[68] - m.beats[68]) shouldBe (0.05 plusOrMinus 1e-6)
        d.beats[64 + MusicDrops.REALIGN_BEATS] shouldBe m.beats[64 + MusicDrops.REALIGN_BEATS]
        d.beats[64 - MusicDrops.REALIGN_BEATS] shouldBe m.beats[64 - MusicDrops.REALIGN_BEATS]
        // Les temps restent dans l'ordre, et l'écart entre deux temps ne change que d'un huitième du décalage.
        d.beats.zipWithNext().all { (a, b) -> b > a } shouldBe true
        d.beats.zipWithNext().maxOf { (a, b) -> s(b - a) } shouldBe ((period + 0.1 / MusicDrops.REALIGN_BEATS) plusOrMinus 1e-6)
    }

    test("drop loin de toute frontière : la section est coupée en deux sur la drop") {
        val m = music()
        val d = MusicDrops.apply(m, m.beats[130])
        d.dropBeat shouldBe 130
        d.sections.size shouldBe m.sections.size + 1
        d.sectionAt(130).startBeat shouldBe 130
        d.sectionAt(130).kind shouldBe SectionKind.DROP
        d.sections.zipWithNext().all { (a, b) -> a.endBeat == b.startBeat } shouldBe true
    }

    test("« depuis le début » ne remplace pas une drop posée à la main") {
        val m = music()
        val d = MusicDrops.apply(m, m.beats[150])
        MusicAnalyzer.fromStart(d, 30.seconds) shouldBe d
    }

    test("le planificateur met la drop du plan sur l'instant posé") {
        val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        val session = Session(
            createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = listOf(60, 180, 300, 420, 540, 660, 780, 900).map { TimelineEvent(it.seconds, "kill", 1.0, "game-events") }),
            highlights = emptyList(),
        )
        val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds).earlierDefaults()
        val groups = MontagePlanner.groups(listOf(session), settings)
        val m = music()
        val at = m.beats[64] + 40.milliseconds
        val before = MontagePlanner.plan(groups, m, settings)
        val after = MontagePlanner.plan(groups, MusicDrops.apply(m, at), settings)
        s(before.music.beatTime(before.music.dropBeat)) shouldBe (s(m.beats[66]) plusOrMinus 1e-6)
        val dropClip = after.clips.single { it.slot.dropBeat != null }
        s(after.music.beatTime(dropClip.slot.dropBeat!!)) shouldBe (s(at) plusOrMinus 1e-6)
        s(after.musicStart + after.dropAt!!) shouldBe (s(at) plusOrMinus 1e-6)
    }

    test("drops retenues par morceau (nom + taille), oubliées une à une ou toutes, mises de côté") {
        val dir = tempdir().toPath()
        val song = dir.resolve("Rome.mp3").also { it.writeBytes(ByteArray(100)) }
        val other = dir.resolve("Autre.mp3").also { it.writeBytes(ByteArray(50)) }
        val store = MusicDropStore(dir.resolve("sessions/music-drops.json"))
        store.dropOf(song) shouldBe null
        store.set(song, 30.523.seconds)
        store.set(other, 12.seconds)
        store.dropOf(song) shouldBe 30.523.seconds
        // Même morceau copié ailleurs : même drop.
        val copy = dir.resolve("copie").also { it.toFile().mkdirs() }.resolve("rome.mp3").also { it.writeBytes(ByteArray(100)) }
        store.dropOf(copy) shouldBe 30.523.seconds
        store.set(song, 30.6.seconds)
        store.load().drops.size shouldBe 2
        store.dropOf(song) shouldBe 30.6.seconds
        store.forget(MusicFeelStore.keyOf(other)!!)
        store.dropOf(other) shouldBe null
        store.set(song, null)
        store.dropOf(song) shouldBe null
        store.set(song, 31.seconds)
        val aside = store.reset()
        aside shouldNotBe null
        aside!!.exists() shouldBe true
        store.dropOf(song) shouldBe null
    }

    test("instant saisi en m:ss.mmm ou en secondes, affiché en m:ss.mmm") {
        DropTime.parse("0:30.523") shouldBe 30.523.seconds
        DropTime.parse("1:23.456") shouldBe 83.456.seconds
        DropTime.parse("30,5") shouldBe 30.5.seconds
        DropTime.parse("  2:05 ") shouldBe 125.seconds
        DropTime.parse("1:75.0") shouldBe null
        DropTime.parse("abc") shouldBe null
        DropTime.format(30.523.seconds) shouldBe "0:30.523"
        DropTime.format(83.456.seconds) shouldBe "1:23.456"
    }

    test("forme d'onde : crêtes ramenées au maximum du morceau") {
        val samples = ShortArray(1000) { i -> if (i in 500 until 510) 16000 else 4000 }
        val peaks = MusicPcm(1000, samples).peaks(10)
        peaks[5] shouldBe 1f
        (peaks[0] - 0.25f).let { kotlin.math.abs(it) < 1e-3f } shouldBe true
        MusicPcm(1000, samples).indexOf(0.25.seconds) shouldBe 250
    }
})

