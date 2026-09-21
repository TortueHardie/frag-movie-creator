package dev.highlights.montage

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MontageScorerTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val other = media.copy(path = Path("autre.mp4"))

    /** Musique régulière : couplet puis drop, accent sur les premiers temps de mesure. */
    val music = MusicAnalysis(
        Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0,
        DoubleArray(240) { 1.0 }, DoubleArray(240) { if (it % 4 == 0) 1.0 else 0.4 },
        listOf(MusicSection(0, 96, -14.0, 0.3), MusicSection(96, 240, -8.0, 0.95)), 96,
    )

    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds)

    fun group(source: MediaInfo, vararg kills: Int) =
        KillGroup(source, kills.map { it.seconds }, 0.8, emptyList(), emptyList())

    fun plan(groups: List<KillGroup>, s: MontageSettings = settings) = MontagePlanner.plan(groups, music, s)

    val varied = listOf(
        group(media, 100, 102),
        group(other, 400),
        group(media, 700),
        group(other, 1000),
    )

    test("un montage conforme est bien noté, et chaque critère reste dans [0, 1]") {
        val score = MontageScorer.score(plan(varied))
        listOfNotNull(score.sync, score.accent, score.restraint, score.variety, score.fill, score.pacing, score.coverage, score.total)
            .forEach { (it in 0.0..1.0) shouldBe true }
        // Le moteur promet les kills d'ancrage sur un temps : c'est le critère qu'il doit saturer.
        score.sync shouldBeGreaterThan 0.99
        score.details.getValue("ancreEcartMaxMs") shouldBeLessThan 20.0
        score.total shouldBeGreaterThan 0.7
    }

    test("empiler les effets fait baisser la sobriété, pas le reste") {
        val sober = MontageScorer.score(plan(varied))
        val heavy = MontageScorer.score(plan(varied, settings.copy(effectDensity = EffectDensity.HEAVY)))

        heavy.restraint shouldBeLessThan sober.restraint
        heavy.total shouldBeLessThan sober.total
        // Les effets ne changent pas où tombent les kills.
        heavy.sync shouldBe sober.sync
        heavy.details.getValue("emphasesParPlan") shouldBeGreaterThan sober.details.getValue("emphasesParPlan")
    }

    test("des clips voisins tirés du même moment de la même partie font baisser la variété") {
        // Quatre groupes de la même capture, à quelques secondes les uns des autres.
        val monotonous = listOf(group(media, 100), group(media, 120), group(media, 140), group(media, 160))
        val score = MontageScorer.score(plan(monotonous))
        score.variety!! shouldBeLessThan 1.0
        score.details.getValue("voisinsSemblables") shouldBeGreaterThan 0.0

        MontageScorer.score(plan(varied)).variety shouldBe 1.0
    }

    test("une image gelée faute de source fait baisser la couverture") {
        // Kill au tout début de la capture : le plan ne peut pas être rempli avant lui.
        val score = MontageScorer.score(plan(listOf(group(media, 1), group(other, 400), group(media, 700))))
        score.coverage shouldBeLessThan 1.0
        score.details.getValue("gelSecondes") shouldBeGreaterThan 0.0
    }

    test("un critère non mesurable sort de la moyenne au lieu d'y entrer à 1") {
        // Rien que des multi-kills : tous les plans ont été étendus pour les contenir, la longueur ne vient plus de la
        // grille. Le rythme n'est alors pas mesurable, et ne doit pas gonfler le total.
        val multis = listOf(group(media, 100, 102), group(other, 400, 402), group(media, 700, 702))
        val score = MontageScorer.score(plan(multis))
        score.pacing shouldBe null

        // Le total reste la moyenne pondérée des seuls critères mesurés.
        val measured = listOf(
            0.25 to score.sync, 0.10 to score.accent, 0.20 to score.restraint,
            0.15 to score.variety!!, 0.10 to score.fill, 0.10 to score.coverage,
        )
        val expected = measured.sumOf { it.first * it.second } / measured.sumOf { it.first }
        score.total shouldBe (expected plusOrMinus 1e-3)
    }

    test("un montage qui n'occupe pas la durée demandée est pénalisé sur ce seul critère") {
        val short = settings.copy(maxDuration = 120.seconds)
        val score = MontageScorer.score(plan(varied, short))
        score.fill shouldBeLessThan 1.0
        score.sync shouldBeGreaterThan 0.99
    }
})
