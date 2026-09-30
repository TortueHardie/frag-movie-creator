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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
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

    test("plan : des plans de 2 temps, le kill sur le premier, sans ralenti") {
        val kills = (0 until 12).map { Triple(60.0 + it * 90, 1, true) }
        val s = session(kills)
        val groups = select(s).groups
        val bpm = 130.0
        val n = (120 * bpm / 60).toInt()
        val music = MusicAnalysis(
            Path("musique.mp3"), 120.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
            sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)),
            dropBeat = 64,
        )
        val plan = MontagePlanner.plan(groups, music, settings)
        plan.clips.size shouldBe 12
        plan.clips.all { it.slow == null && it.speeds.isEmpty() } shouldBe true
        // La drop garde son plan d'élan (4 temps et plus) ; tous les autres font 2 temps, moins d'une seconde.
        plan.clips.count { it.beats == 2 } shouldBe (plan.clips.size - plan.clips.count { it.slot.dropBeat != null })
        (plan.duration < 15.seconds) shouldBe true
    }
})
