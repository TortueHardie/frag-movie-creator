package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Rythme rapide par partie de la musique (décision de l'utilisateur du 18/11) : intro et montée au rythme du style,
 * drop et après au rythme rapide, un kill par plan, sans ralenti.
 */
class FastSectionsTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))

    /** Un kill toutes les 40 s : assez de kills isolés pour remplir la partie rapide. */
    val session = WindowGrid(1.seconds, 1.seconds, media.duration).let { grid ->
        Session(
            createdAt = Instant.EPOCH, media = media, profileId = "valorant",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = (1..40).map { TimelineEvent((it * 40).seconds, "kill", 1.0, "n") }),
            highlights = emptyList(),
        )
    }

    /** Intro calme (0,1), montée (0,5), drop intense (1,0) jusqu'à la fin, à [bpm]. */
    fun music(bpm: Double): MusicAnalysis {
        val n = (120 * bpm / 60).toInt()
        val drop = 64
        return MusicAnalysis(
            Path("musique.mp3"), 120.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
            sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, drop, -14.0, 0.5), MusicSection(drop, n, -8.0, 1.0)),
            dropBeat = drop,
        )
    }

    // Vitrine coupée : elle ajoute des plans sans kill devant, sans rapport avec ce qui est vérifié ici.
    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 40.seconds).let { it.copy(showcase = it.showcase.copy(enabled = false)) }
    fun plan(bpm: Double = 126.0, s: MontageSettings = settings) = MontagePlanner.plan(MontagePlanner.groups(listOf(session), s), music(bpm), s)

    test("parties rapides : la drop et ce qui suit, si elles sont intenses et le tempo assez vif") {
        FastSections.of(music(126.0), settings)!!.sections shouldBe setOf(2)
        // Musique lente (« HEAVENLY JUMPSTYLE (Slowed) », 111 BPM) : le rythme du style partout.
        FastSections.of(music(111.0), settings) shouldBe null
        // Coupé, ou montage déjà entièrement rapide : rien à faire par partie.
        FastSections.of(music(126.0), settings.copy(fastParts = settings.fastParts.copy(enabled = false))) shouldBe null
        FastSections.of(music(126.0), settings.forFast()) shouldBe null
    }

    test("avant la drop, le rythme du style ; après, un kill par temps, sans ralenti ni rampe") {
        val p = plan()
        val (fast, calm) = p.clips.partition { it.fastPart }
        fast.shouldNotBeEmpty()
        calm.shouldNotBeEmpty()
        // Les plans rapides sont dans la partie intense, les autres avant. Le rythme rapide part de la drop elle-même
        // (« fast from the drop », 18/11) : le plan de la drop est rapide, sans ralenti, ouvert sur la drop, et porte
        // le meilleur groupe.
        fast.all { it.slot.section == 2 } shouldBe true
        calm.all { it.slot.section < 2 } shouldBe true
        val drop = fast.single { it.slot.dropBeat != null }
        drop.slow shouldBe null
        drop.slot.startBeat shouldBe 64
        drop.group.rank shouldBe p.clips.maxOf { it.group.rank }
        // Un kill par temps : des plans d'un temps (126 BPM : 0,48 s, assez pour le contexte du rythme rapide).
        (fast.count { it.beats == 1 } * 2 >= fast.size) shouldBe true
        fast.all { it.slow == null && it.ramps.isEmpty() } shouldBe true
        // Plans plus longs avant la drop.
        (calm.minOf { it.beats } > fast.maxOf { it.beats }) shouldBe true
    }

    test("réglage coupé : tout le montage au rythme du style, comme avant") {
        plan(s = settings.copy(fastParts = settings.fastParts.copy(enabled = false))).clips.none { it.fastPart } shouldBe true
    }

    test("musique lente : aucun plan au rythme rapide") {
        plan(bpm = 111.0).clips.none { it.fastPart } shouldBe true
    }
})
