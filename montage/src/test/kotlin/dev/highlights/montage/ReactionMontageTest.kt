package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ReactionMontage
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.model.forReactions
import dev.highlights.core.session.Session
import dev.highlights.editing.story.Caption
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ReactionMontageTest : FunSpec({
    val media = MediaInfo(
        Path("partie.mp4"), 1, 30.minutes,
        video = VideoStream(0, "h264", 1920, 1080, 60.0),
        // Outplayed : mix, jeu, micro (rôles déduits du nombre de pistes).
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000), AudioStream(2, 1, "aac", 2, 48000), AudioStream(3, 2, "aac", 2, 48000)),
    )
    val settings = ReactionMontage()
    val grid = WindowGrid(1.seconds, 1.seconds, media.duration)

    fun timeline(events: List<TimelineEvent> = emptyList(), segments: List<TimelineSegment> = emptyList()) =
        ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = events, segments = segments)

    /** Micro muet (−90 dB), voix habituelle à −34 dB par moments, et des éclats à −20 dB aux secondes données. */
    fun levels(talk: List<ClosedFloatingPointRange<Double>>, bursts: List<ClosedFloatingPointRange<Double>>): MicLevels {
        val n = (media.duration / MicLevels.STEP).toInt()
        val db = DoubleArray(n) { i ->
            val t = i * 0.1 + 0.05
            when {
                bursts.any { t in it } -> -20.0
                talk.any { t in it } -> -34.0
                else -> -90.0
            }
        }
        return MicLevels(MicLevels.STEP, db)
    }

    val talk = (0 until 60).map { (it * 30.0)..(it * 30.0 + 8) }

    test("éclats : au-dessus de la voix habituelle de rise dB, assez longs, creux de syllabe tolérés") {
        // 100 s : un éclat de 1 s ; 200 s : deux souffles séparés de 0,2 s (un seul éclat) ; 300 s : 0,2 s, trop court.
        val found = ReactionFinder.find(levels(talk, listOf(100.0..101.0, 200.0..200.5, 200.7..201.2, 300.0..300.2)), timeline(), settings, emptySet())
        found.map { it.range.start.inWholeMilliseconds } shouldContainExactly listOf(100_000L, 200_000L)
        found[1].range.end shouldBe 201_200.milliseconds
        found.forEach { it.rise shouldBe 14.0 }
        found.forEach { it.kind shouldBe ReactionKind.VOICE }
    }

    test("voix habituelle propre à chaque partie : un micro réglé plus fort ne donne pas plus de réactions") {
        val quiet = levels(talk, listOf(100.0..101.0))
        val louder = MicLevels(quiet.step, DoubleArray(quiet.db.size) { quiet.db[it] + 8 })
        ReactionFinder.find(louder, timeline(), settings, emptySet()).map { it.range.start } shouldBe
            ReactionFinder.find(quiet, timeline(), settings, emptySet()).map { it.range.start }
        // Micro toujours muet : aucune réaction, pas d'erreur.
        ReactionFinder.find(MicLevels(MicLevels.STEP, DoubleArray(100) { -90.0 }), timeline(), settings, emptySet()) shouldBe emptyList()
    }

    test("YAMNet : un rire reconnu est une réaction même sans éclat, et l'emporte sur l'éclat qu'il recouvre") {
        val laugh = TimelineSegment(TimeRange(500.seconds, 502.seconds), "laughter", 0.39, "reactions")
        val found = ReactionFinder.find(levels(talk, listOf(501.0..501.5, 900.0..901.0)), timeline(segments = listOf(laugh)), settings, emptySet())
        found.map { it.range.start.inWholeSeconds } shouldContainExactly listOf(500L, 900L)
        found[0].kind shouldBe ReactionKind.LAUGH
        found[0].range shouldBe TimeRange(500.seconds, 502.seconds)
    }

    test("cause : un kill ou une mort juste avant compte, un événement trop ancien ou après non") {
        val events = listOf(
            TimelineEvent(97.seconds, "kill", 1.0, "ocr"),
            TimelineEvent(190.seconds, "death", 1.0, "ocr"),
            TimelineEvent(301.seconds, "kill", 1.0, "ocr"),
            TimelineEvent(399.seconds, "revive", 1.0, "ocr"),
        )
        val found = ReactionFinder.find(levels(talk, listOf(100.0..101.0, 200.0..201.0, 300.0..301.0, 400.0..401.0)), timeline(events), settings, setOf("kill", "death"))
        found.map { it.cause } shouldContainExactly listOf("kill", null, null, null)
        (found[0].strength - found[2].strength) shouldBe (settings.causeBonus plusOrMinus 1e-9)
    }

    test("espacement : de deux réactions proches, la plus forte seule") {
        val r = { s: Int, rise: Double -> Reaction(TimeRange(s.seconds, (s + 1).seconds), s.seconds, rise, ReactionKind.VOICE, strength = rise) }
        ReactionFinder.spaced(listOf(r(100, 11.0), r(105, 15.0), r(120, 12.0)), 8.seconds).map { it.range.start.inWholeSeconds } shouldContainExactly listOf(105L, 120L)
        ReactionFinder.merge(listOf(r(100, 11.0), r(101, 15.0)), 1500.milliseconds).single().let {
            it.range shouldBe TimeRange(100.seconds, 102.seconds)
            it.rise shouldBe 15.0
        }
    }

    test("ancres : chaque réaction devient un plan, classé par sa force ; les kills du jeu n'en ancrent pas") {
        val reactSettings = MontageSettings(maxDuration = 60.seconds).earlierDefaults().forReactions()
        val session = Session(
            createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
            timeline = timeline(listOf(TimelineEvent(97.seconds, "kill", 1.0, "ocr"), TimelineEvent(600.seconds, "kill", 1.0, "ocr"))),
            highlights = emptyList(),
        )
        val found = ReactionFinder.find(levels(talk, listOf(100.0..101.0, 200.0..203.0)), session.timeline, settings, setOf("kill"))
        val annotated = Reactions.annotate(session, found, settings)
        session.timeline.events shouldHaveSize 2
        val groups = MontagePlanner.groups(listOf(annotated), reactSettings)
        groups.map { it.kills.single().inWholeSeconds } shouldContainExactly listOf(100L, 200L)
        // La voix de la réaction est protégée (plan prolongé, musique baissée dessous).
        groups.forEach { g -> g.voiceSegments.isNotEmpty() shouldBe true }
        val ranked = Reactions.rescore(groups, listOf(SessionReactions(annotated, found)))
        ranked.maxBy { it.score }.score shouldBe 1.0
        Reactions.of(ranked[0], listOf(SessionReactions(annotated, found))) shouldBe found[0]
    }

    test("réglages : réaction en ancre, plans prolongés sur la voix, ni ralenti ni gel ni classement des kills") {
        val base = MontageSettings()
        val r = base.forReactions()
        r.killEvent shouldBe "reaction"
        r.reactionMontage.enabled shouldBe true
        r.reactions shouldBe true
        r.killOffset shouldBe Duration.ZERO
        r.mergeGap shouldBe Duration.ZERO
        r.slowMotion.enabled shouldBe false
        r.speedRamp.enabled shouldBe false
        r.fastParts.enabled shouldBe false
        r.fails.enabled shouldBe false
        r.matchCut.enabled shouldBe false
        r.killStyle.deathEvent shouldBe ""
        r.audio.musicUnderVoice shouldBe base.reactionMontage.musicUnderVoice
        r.text.labels shouldBe emptyList()
        base.reactionMontage.enabled shouldBe false
    }

    test("rendu : micro mixé par-dessus le jeu et sous-titres posés dans le temps du plan") {
        val s = MontageSettings(killOffset = Duration.ZERO).earlierDefaults().forReactions()
        val music = MusicAnalysis(
            Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
            listOf(MusicSection(0, 96, -14.0, 0.4), MusicSection(96, 240, -8.0, 1.0)), 96,
        )
        val group = KillGroup(media, listOf(100.seconds), 1.0, listOf(TimeRange(100.seconds, 101.seconds)), listOf(TimeRange(100.seconds, 101.seconds)))
        val plan = MontagePlanner.plan(listOf(group), music, s)
        val caption = Caption(TimeRange(100.seconds, 101.seconds), "il est dead")
        val request = MontageRenderRequest(plan, OutputFormat.VERTICAL, EditSettings(), EncoderProfile("libx264", listOf("-c:v", "libx264"), false), Path("out.mp4"), Path("f.txt"), captions = listOf(listOf(caption)))
        val graph = MontageRenderBuilder.build(request).filterGraph
        graph shouldContain "[0:a:1][0:a:2]amix=inputs=2:weights='1 2.000'"
        // Le sous-titre est dessiné (mot à mot quand un mot est mis en couleur), sur le temps où la phrase est dite.
        graph shouldContain "EST"
        graph shouldContain "between(t"
        // Sans montage des réactions : le son habituel, pas de mixage micro.
        val normal = MontageRenderBuilder.build(request.copy(plan = MontagePlanner.plan(listOf(group), music, MontageSettings(killOffset = Duration.ZERO).earlierDefaults()), captions = emptyList())).filterGraph
        normal shouldNotContain "weights='1 2.000'"
    }

    test("mesure du micro sur une vraie capture : piste micro décodée, salve trouvée").config(enabledIf = { TestMedia.available }) {
        val dir = tempdir().toPath()
        val file = TestMedia.generate(
            dir.resolve("micro.mp4"), 40,
            audioTracks = listOf(
                TestMedia.AudioTrackSpec("Game", bursts = listOf(5.0..6.0)),
                TestMedia.AudioTrackSpec("Mic", bursts = listOf(20.0..21.5), frequency = 300),
            ),
        )
        val ffmpeg = TestMedia.requireFfmpeg()
        val info = ffmpeg.probe(file)
        val levels = MicLevels.measure(ffmpeg, info, AudioLayout())
        levels shouldNotBe null
        (levels!!.db.size.toDouble()) shouldBeGreaterThan 390.0
        val found = ReactionFinder.find(levels, timeline(), settings, emptySet())
        found.map { it.range.start.inWholeMilliseconds / 1000.0 }.single().let { (it in 19.8..20.3) shouldBe true }
        // Une capture à une seule piste : pas de micro séparé, pas de mesure.
        val single = TestMedia.generate(dir.resolve("seule.mp4"), 10)
        MicLevels.measure(ffmpeg, ffmpeg.probe(single), AudioLayout()) shouldBe null
    }
})
