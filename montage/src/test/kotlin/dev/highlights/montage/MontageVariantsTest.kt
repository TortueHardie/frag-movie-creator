package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MontageVariantsTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))

    fun session(kills: List<Int>): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "n") }),
            highlights = emptyList(),
        )
    }

    /** Musique régulière à 120 BPM : intro calme, montée, drop jusqu'à la fin ; accent sur les premiers temps. */
    fun music(): MusicAnalysis {
        val n = 240
        val sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0))
        return MusicAnalysis(
            Path("musique.mp3"), 120.seconds, 120.0, List(n) { (it * 0.5).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 }, sections = sections, dropBeat = 64,
        )
    }

    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds)
    val kills = listOf(60, 180, 183, 186, 300, 420, 540, 660, 780, 900, 1020, 1140, 1260)
    val groups = MontagePlanner.groups(listOf(session(kills)), settings)

    test("la première variante est le montage que best aurait rendu") {
        val m = music()
        val variants = MontageVariants.distinct(groups, m, settings, 3)
        variants.shouldNotBeEmpty()
        val best = MontagePlanner.best(groups, m, settings)
        MontageVariants.signature(variants.first().plan) shouldBe MontageVariants.signature(best)
    }

    test("variantes assez différentes deux à deux, classées par note, liste pour n qui commence par celle pour n − 1") {
        val m = music()
        val three = MontageVariants.distinct(groups, m, settings, 3)
        three.forEachIndexed { i, a ->
            three.drop(i + 1).forEach { b -> (MontageVariants.distance(a.plan, b.plan) >= MontageVariants.MIN_DISTANCE) shouldBe true }
        }
        val two = MontageVariants.distinct(groups, m, settings, 2)
        two.map { MontageVariants.signature(it.plan) } shouldBe three.take(2).map { MontageVariants.signature(it.plan) }
    }

    test("distance : nulle pour le même montage, entre 0 et 1 sinon") {
        val m = music()
        val ranked = MontageVariants.ranked(groups, m, settings)
        val a = ranked.first().plan
        MontageVariants.distance(a, a) shouldBe 0.0
        ranked.forEach { r -> (MontageVariants.distance(a, r.plan) in 0.0..1.0) shouldBe true }
    }

    test("distance : le même montage décalé d'un temps vaut 0, d'un quart de montage s'en écarte") {
        val a = MontagePlanner.plan(groups, music(), settings)
        fun shifted(by: Int) = a.copy(clips = a.clips.map { it.copy(slot = it.slot.copy(startBeat = it.slot.startBeat + by, endBeat = it.slot.endBeat + by)) })
        MontageVariants.distance(a, shifted(1)) shouldBe 0.0
        (MontageVariants.distance(a, shifted(a.totalBeats / 4 + 2)) >= MontageVariants.MIN_DISTANCE) shouldBe true
    }

    test("distance : un plan de moins au début ne rend pas tous les autres différents") {
        val a = MontagePlanner.plan(groups, music(), settings)
        val n = a.clips.size
        (MontageVariants.clips(a, a.copy(clips = a.clips.drop(1).let { rest -> listOf(rest.first().copy(slot = a.clips.first().slot.copy(endBeat = rest.first().slot.endBeat))) + rest.drop(1) })) <= 1.0 / n + 1e-9) shouldBe true
    }

    test("empreinte : stable d'un calcul à l'autre, différente d'une variante à l'autre") {
        val m = music()
        val one = MontageVariants.distinct(groups, m, settings, 3)
        val again = MontageVariants.distinct(groups, m, settings, 3)
        one.map { MontageVariants.signature(it.plan) } shouldBe again.map { MontageVariants.signature(it.plan) }
        if (one.size > 1) MontageVariants.signature(one[0].plan) shouldNotBe MontageVariants.signature(one[1].plan)
    }

    test("poids appris donnés par les réglages : la note du montage les suit, les critères non") {
        val plan = MontagePlanner.best(groups, music(), settings)
        val score = MontageScorer.score(plan)
        val criteria = score.criteria()
        val heavy = MontageScorer.WEIGHTS + mapOf("fill" to 1.0)
        val reweighted = MontageScorer.score(plan.copy(settings = plan.settings.copy(scoreWeights = heavy)))
        reweighted.criteria() shouldBe criteria
        reweighted.total shouldBe (MontageScorer.total(criteria, heavy) plusOrMinus 1e-3)
        score.total shouldBe (MontageScorer.total(criteria) plusOrMinus 1e-3)
    }

    test("aperçu : tailles ramenées à 360 pixels sur le petit côté, proportions gardées") {
        val edit = MontagePreview.edit(EditSettings(maxBitrate = "16M"))
        edit.vertical.size shouldBe FrameSize(360, 640)
        edit.landscape shouldBe FrameSize(640, 360)
        edit.sourceHeight shouldBe 360
        edit.maxBitrate shouldBe null
        MontagePreview.format(listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)) shouldBe OutputFormat.VERTICAL
        MontagePreview.format(listOf(OutputFormat.SOURCE)) shouldBe OutputFormat.SOURCE
    }

    test("aperçu : chaque plan garde exactement son nombre d'images, sans flou par vecteurs") {
        val plan = MontagePlanner.best(groups, music(), settings)
        val edit = EditSettings()
        val encoder = EncoderProfile("h264_amf", listOf("-c:v", "h264_amf"), true)
        val full = MontageRenderBuilder.build(MontageRenderRequest(plan, OutputFormat.VERTICAL, edit, encoder, Path("out.mp4"), Path("f.txt")))
        val small = MontageRenderBuilder.build(
            MontageRenderRequest(MontagePreview.plan(plan), OutputFormat.VERTICAL, MontagePreview.edit(edit), MontagePreview.ENCODER, Path("a.mp4"), Path("g.txt")),
        )
        val frames = Regex("trim=end_frame=(\\d+)")
        frames.findAll(small.filterGraph).map { it.value }.toList() shouldBe frames.findAll(full.filterGraph).map { it.value }.toList()
        small.expectedDuration shouldBe full.expectedDuration
        small.filterGraph shouldNotContain "gblur@vb"
        small.command.args.joinToString(" ") shouldNotContain "h264_amf"
        MontagePreview.plan(plan).settings.motionBlur.usesVectors shouldBe false
    }

    test("fichiers des variantes et du choix : relus tels qu'écrits") {
        val m = music()
        val plans = MontageVariants.distinct(groups, m, settings, 3).map { it.plan }
        val dir = Files.createTempDirectory("variantes")
        val file = dir.resolve("wardogs_variantes.json")
        val written = MontageVariantsFile(
            createdAt = "2026-11-17T00:00:00Z", profile = "wardogs", music = "musique.mp3", sessions = listOf("partie.mp4"),
            variants = plans.mapIndexed { i, p -> MontageVariantEntry.of(i + 1, p, plans.take(i)) },
        )
        MontageVariantsFile.save(file, written)
        MontageVariantsFile.load(file) shouldBe written
        written.variants.first().distance shouldBe null
        written.variants.drop(1).forEach { (it.distance!! >= MontageVariants.MIN_DISTANCE) shouldBe true }

        val report = dir.resolve("wardogs_killmontage.json")
        MontageChoiceRecord.fileFor(report).fileName.toString() shouldBe "wardogs_killmontage.choix.json"
        val choice = MontageChoiceRecord(chosenAt = "2026-11-17T00:00:01Z", chosen = 1, signature = written.variants.first().signature, variantsFile = file.toString(), profile = "wardogs", music = "musique.mp3", variants = written.variants, outputs = listOf("out.mp4"), report = report.toString())
        MontageChoiceRecord.save(MontageChoiceRecord.fileFor(report), choice)
        MontageChoiceRecord.load(MontageChoiceRecord.fileFor(report)) shouldBe choice
    }
})
