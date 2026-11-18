package dev.highlights.montage

import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.model.FastParts
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.MusicFeel
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

    /**
     * Intro calme (0,1), montée (0,5), drop intense (1,0) jusqu'à la fin, à [bpm]. [calm] : temps de la drop où le son
     * tombe à -14 dB (la basse qui s'arrête), au lieu de 0 dB.
     */
    fun music(bpm: Double, calm: IntRange = IntRange.EMPTY): MusicAnalysis {
        val n = (120 * bpm / 60).toInt()
        val drop = 64
        return MusicAnalysis(
            Path("musique.mp3"), 120.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { if (it in calm) 0.2 else if (it < drop) 0.3 else 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
            sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, drop, -14.0, 0.5), MusicSection(drop, n, -8.0, 1.0)),
            dropBeat = drop,
        )
    }

    // Vitrine coupée : elle ajoute des plans sans kill devant, sans rapport avec ce qui est vérifié ici.
    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 40.seconds).let { it.copy(showcase = it.showcase.copy(enabled = false)) }
    fun plan(bpm: Double = 126.0, s: MontageSettings = settings, calm: IntRange = IntRange.EMPTY) =
        MontagePlanner.plan(MontagePlanner.groups(listOf(session), s), music(bpm, calm), s)

    test("parties rapides : la drop et ce qui suit, si elles sont intenses, quel que soit le tempo") {
        FastSections.of(music(126.0), settings)!!.sections shouldBe setOf(2)
        // Musique lente : rapide aussi après sa drop (choix de l'utilisateur du 18/11, règle des 120 BPM retirée).
        FastSections.of(music(90.0), settings)!!.sections shouldBe setOf(2)
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

    test("musique lente : rapide après la drop, au rythme du style avant") {
        val p = plan(bpm = 90.0)
        p.clips.any { it.fastPart } shouldBe true
        p.clips.filter { it.fastPart }.all { it.slot.startBeat >= 64 } shouldBe true
    }

    test("creux d'une partie intense : deux mesures basses en sont un, une seule non") {
        val parts = settings.fastParts
        FastSections.calmBreaks(music(126.0, calm = 96 until 104), parts) shouldBe listOf(96 until 104)
        FastSections.calmBreaks(music(126.0, calm = 96 until 100), parts) shouldBe emptyList()
        // Avant la drop, ce n'est jamais un creux de partie rapide.
        FastSections.calmBreaks(music(126.0, calm = 40 until 48), parts) shouldBe emptyList()
        FastSections.calmBreaks(music(126.0), parts) shouldBe emptyList()
    }

    test("creux : section à part, plus calme que le seuil, au rythme du style ; le rythme rapide reprend après") {
        val split = FastSections.withCalmBreaks(music(126.0, calm = 96 until 104), settings.fastParts)
        split.sections.map { it.startBeat } shouldBe listOf(0, 32, 64, 96, 104)
        split.sections[3].kind shouldBe SectionKind.BREAKDOWN
        (split.sections[3].intensity < settings.fastParts.minIntensity) shouldBe true
        FastSections.of(music(126.0, calm = 96 until 104), settings)!!.sections shouldBe setOf(2, 4)

        val p = plan(calm = 96 until 104, s = settings.copy(maxDuration = 120.seconds))
        val inBreak = p.clips.filter { it.slot.startBeat in 96 until 104 }
        inBreak.shouldNotBeEmpty()
        inBreak.none { it.fastPart } shouldBe true
        p.clips.any { it.fastPart && it.slot.startBeat < 96 } shouldBe true
        p.clips.any { it.fastPart && it.slot.startBeat >= 104 } shouldBe true
    }

    test("peu de kills : le montage garde une longueur normale au lieu de tenir en quelques temps") {
        // Trois kills isolés sur une musique lente : un plan d'un temps chacun ferait 2 s, sous montage.length.min.
        val few = session.copy(timeline = session.timeline.copy(events = session.timeline.events.take(3)))
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(few), settings), music(90.0), settings)
        p.clips.none { it.fastPart } shouldBe true
        p.clips.sumOf { it.kills.size } shouldBe 3
        // Le montage au rythme du style, tel quel (10,7 s ici : trois plans de 4, 8 et 4 temps à 90 BPM).
        val styled = settings.copy(fastParts = settings.fastParts.copy(enabled = false))
        p.clips.map { it.slot } shouldBe MontagePlanner.plan(MontagePlanner.groups(listOf(few), styled), music(90.0), styled).clips.map { it.slot }
        (p.duration >= 10.seconds) shouldBe true
        // Même entrée, même plan : le garde-fou ne dépend que du plan.
        MontagePlanner.plan(MontagePlanner.groups(listOf(few), settings), music(90.0), settings).clips.map { it.slot } shouldBe p.clips.map { it.slot }
        // Avec assez de kills, le rythme rapide reste.
        plan(bpm = 90.0).clips.any { it.fastPart } shouldBe true
    }

    test("ressenti de la musique : calme, jamais rapide ; rapide, dès une intensité plus basse") {
        fun with(feel: MusicFeel) = settings.copy(fastParts = settings.fastParts.copy(feel = feel))
        FastSections.of(music(126.0), with(MusicFeel.CALM)) shouldBe null
        plan(s = with(MusicFeel.CALM)).clips.none { it.fastPart } shouldBe true
        // Une partie moyenne après la drop (0,5) : rapide seulement pour une musique ressentie rapide.
        val medium = music(126.0).let { m -> m.copy(sections = m.sections.dropLast(1) + MusicSection(64, 160, -9.0, 1.0) + MusicSection(160, m.beats.size, -11.0, 0.5)) }
        FastSections.of(medium, settings)!!.sections shouldBe setOf(2)
        FastSections.of(medium, with(MusicFeel.FAST))!!.sections shouldBe setOf(2, 3)
        FastSections.of(medium, with(MusicFeel.AUTO))!!.sections shouldBe setOf(2)
    }

    test("ancienne configuration : minBpm encore accepté, sans effet") {
        val parts = ConfigYaml.yaml.decodeFromString(FastParts.serializer(), "minBpm: 120\nminIntensity: 0.7")
        parts.minBpm shouldBe 120.0
        FastSections.of(music(90.0), settings.copy(fastParts = parts))!!.sections shouldBe setOf(2)
    }
})
