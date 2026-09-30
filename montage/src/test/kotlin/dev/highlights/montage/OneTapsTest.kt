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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class OneTapsTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))
    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds).forOneTaps()

    fun event(at: Double, kind: String) = TimelineEvent(at.seconds, kind, 1.0, "n")

    /** Kills, avec pour chacun le nombre de balles entendues (null : aucune) et s'il est à la tête. */
    fun session(kills: List<Triple<Double, Int?, Boolean>>, headshotSource: Boolean = true): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        val events = kills.flatMap { (at, shots, headshot) ->
            // Les balles d'avant partent toutes les 150 ms, la dernière tue 50 ms avant l'annonce.
            val fired = (0 until (shots ?: 0)).map { event(at - 0.05 - it * 0.15, "shot") }
            listOf(event(at, "kill")) + fired + listOfNotNull(event(at, "headshot").takeIf { headshot && headshotSource })
        }
        return Session(
            createdAt = Instant.EPOCH, media = media, profileId = "valorant",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = events.sortedBy { it.at }),
            highlights = emptyList(),
        )
    }

    fun select(s: Session, st: MontageSettings = settings) = OneTaps.select(MontagePlanner.groups(listOf(s), st), listOf(s), st)

    test("réglages onetaps : un kill par groupe, plans courts, ni ralenti ni accroche") {
        settings.mergeGap shouldBe Duration.ZERO
        settings.slowMotion.enabled shouldBe false
        settings.speedRamp.enabled shouldBe false
        settings.hook shouldBe false
        settings.cuts.high shouldBe settings.oneTaps.cut
        settings.cuts.singleBeat shouldBe true
        settings.musicPace shouldBe settings.oneTaps.musicPace
        // Deux kills à 0,4 s d'écart : deux groupes, deux plans.
        MontagePlanner.groups(listOf(session(listOf(Triple(100.0, 1, true), Triple(100.4, 1, true)))), settings).size shouldBe 2
    }

    test("ne garde que les kills d'une balle à la tête") {
        val s = session(
            listOf(
                Triple(100.0, 1, true), // one tap
                Triple(200.0, 3, true), // rafale finie à la tête
                Triple(300.0, 1, false), // une balle au corps
                Triple(400.0, null, true), // tir pas entendu
                Triple(500.0, 1, true), // one tap
            ),
        )
        val selection = select(s)
        selection.strict shouldBe true
        selection.groups.flatMap { it.kills } shouldBe listOf(100.seconds, 500.seconds)
    }

    test("sans tirs à la tête connus : repli sur les kills d'une balle, ou refus si on l'interdit") {
        val s = session(listOf(Triple(100.0, 1, true), Triple(200.0, 3, true), Triple(300.0, 1, false)), headshotSource = false)
        val selection = select(s)
        selection.strict shouldBe false
        selection.groups.flatMap { it.kills } shouldBe listOf(100.seconds, 300.seconds)
        val strict = settings.copy(oneTaps = settings.oneTaps.copy(allowWithoutHeadshots = false))
        shouldThrow<InputException> { select(s, strict) }.message shouldContain "Tirs à la tête inconnus"
    }

    test("sans aucun tir entendu : on demande de réanalyser") {
        val s = session(listOf(Triple(100.0, null, true), Triple(200.0, null, true)))
        shouldThrow<InputException> { select(s) }.message shouldContain "Réanalysez"
    }

    test("aucun one tap : on le dit") {
        shouldThrow<InputException> { select(session(listOf(Triple(100.0, 4, true)))) }.message shouldContain "Aucun one tap"
    }

    /** Deux minutes de musique : intro, montée, drop au temps 64. */
    fun music(bpm: Double, file: String = "musique.mp3"): MusicAnalysis {
        val n = (120 * bpm / 60).toInt()
        return MusicAnalysis(
            Path(file), 120.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
            sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)),
            dropBeat = 64,
        )
    }

    val twelve = (0 until 12).map { Triple(60.0 + it * 90, 1, true) }

    test("plan : un kill par temps, sur le temps qui ouvre le plan, la coupe avancée du contexte d'avant") {
        val groups = select(session(twelve)).groups
        val plan = MontagePlanner.plan(groups, music(130.0), settings)
        plan.clips.size shouldBe 12
        plan.clips.all { it.slow == null && it.speeds.isEmpty() } shouldBe true
        val single = plan.clips.filter { it.beats == 1 }
        // La drop garde son plan d'élan, le premier plan n'a pas de précédent où prendre le contexte : les autres, un temps.
        (single.size >= plan.clips.size - 2) shouldBe true
        single.all { it.anchorBeat == it.slot.startBeat && it.leadIn == settings.cuts.minLead } shouldBe true
        plan.clips.first().leadIn shouldBe Duration.ZERO
        (plan.duration < 10.seconds) shouldBe true
        // Rendu : la coupe vers un plan d'un temps avance du contexte (plus l'image d'avance habituelle).
        val frame = (1_000_000L / 60).microseconds
        val leads = MontageRenderBuilder.leads(plan, 60)
        plan.clips.forEachIndexed { i, c ->
            if (i > 0 && c.beats == 1) (leads[i] - c.leadIn - frame).absoluteValue shouldBeLessThan frame
        }
        // Le kill reste sur son temps : la note de synchronisation n'y perd rien.
        MontageScorer.score(plan).sync shouldBe (1.0 plusOrMinus 1e-6)
    }

    test("plan : musique trop rapide pour un temps, deux temps par plan") {
        val groups = select(session(twelve)).groups
        val plan = MontagePlanner.plan(groups, music(160.0), settings)
        plan.clips.size shouldBe 12
        plan.clips.none { it.beats == 1 || it.leadIn.isPositive() } shouldBe true
        plan.clips.count { it.beats == 2 } shouldBe (plan.clips.size - plan.clips.count { it.slot.dropBeat != null })
    }

    test("plans d'un temps seulement si on les demande") {
        val groups = select(session(twelve)).groups
        val plan = MontagePlanner.plan(groups, music(130.0), settings.copy(cuts = settings.cuts.copy(singleBeat = false)))
        plan.clips.none { it.beats == 1 } shouldBe true
    }

    test("choix de la musique : celle qui garde des plans d'un temps passe devant, à kills égaux") {
        val groups = select(session(twelve)).groups
        val ranked = MusicChoice.rank(groups, listOf(music(160.0, "rapide-mais-trop.mp3"), music(130.0, "rapide.mp3")), settings) { false }
        ranked.first().music.file shouldBe Path("rapide.mp3")
        (ranked.first().pace > ranked.last().pace) shouldBe true
        // Sans préférence (montage kills ordinaire), aucune avance.
        MusicChoice.rank(groups, listOf(music(130.0)), settings.copy(musicPace = null)) { false }.single().pace shouldBe 0.0
    }
})
