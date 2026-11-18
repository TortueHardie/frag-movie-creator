package dev.highlights.montage

import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.FailMontage
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FailMontageTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))

    fun session(vararg events: Pair<Int, String>): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(
            createdAt = Instant.EPOCH, media = media, profileId = "valorant",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = events.map { (t, k) -> TimelineEvent(t.seconds, k, 1.0, "killfeed") }),
            highlights = emptyList(),
        )
    }

    fun music(bpm: Double = 120.0, seconds: Int = 180): MusicAnalysis {
        val n = (seconds * bpm / 60).toInt()
        return MusicAnalysis(
            Path("musique.mp3"), seconds.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
            sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)), dropBeat = 64,
        )
    }

    val base = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds)
    val fails = base.forFails()

    test("réglages : les morts ancrent les plans, ce qui classe les kills est coupé, ralenti partout") {
        fails.killEvent shouldBe "death"
        fails.anchorEvents shouldBe emptySet()
        fails.fails.enabled shouldBe true
        fails.mergeGap shouldBe Duration.ZERO
        fails.effectDensity shouldBe EffectDensity.HEAVY
        fails.slowMotion.enabled shouldBe true
        fails.matchCut.enabled shouldBe false
        fails.cuts.jumpCuts shouldBe false
        fails.killStyle.deathEvent shouldBe ""
        fails.killStyle.headshotEvent shouldBe ""
        fails.killStyle.confirmEvent shouldBe ""
        // Wardogs : « à terre » compte seulement s'il compte comme une mort.
        base.copy(killStyle = base.killStyle.copy(downedAsDeath = true)).forFails().anchorEvents shouldBe setOf("downed")
        base.fails.enabled shouldBe false
    }

    test("sélection : les morts seulement, chacune son groupe ; une mort juste après un kill (échange) reste") {
        val s = session(100 to "kill", 101 to "death", 300 to "death", 302 to "death", 500 to "kill", 700 to "downed")
        val groups = MontagePlanner.groups(listOf(s), fails)
        groups.map { g -> g.kills.map { it.inWholeSeconds } } shouldContainExactly listOf(listOf(101L), listOf(300L), listOf(302L))
        // Morts à terre comptées comme des morts : le plan de 700 s s'ajoute.
        val downed = base.copy(killStyle = base.killStyle.copy(downedAsDeath = true)).forFails()
        MontagePlanner.groups(listOf(s), downed).map { it.kills.single().inWholeSeconds } shouldContainExactly listOf(101L, 300L, 302L, 700L)
        // Montage kills normal : les morts n'ancrent rien.
        MontagePlanner.groups(listOf(s), base).flatMap { g -> g.kills.map { it.inWholeSeconds } } shouldContainExactly listOf(100L, 500L)
    }

    test("ancrage : la mort tombe sur son temps, ralenti puis image figée, le plan remplit exactement son slot") {
        val m = music()
        val deaths = listOf(120, 400, 700, 1000, 1300)
        val plan = MontagePlanner.plan(MontagePlanner.groups(listOf(session(*deaths.map { it to "death" }.toTypedArray())), fails), m, fails)
        plan.clips.size shouldNotBe 0
        // Chaque mort a son image figée ; le ralenti, un tiers d'entre elles (arrondi au-dessus), au moins une.
        plan.clips.all { c -> c.speeds.any { it.kind == SpeedKind.FREEZE } } shouldBe true
        plan.clips.count { it.slow != null } shouldBe kotlin.math.ceil(plan.clips.size / 3.0).toInt()
        plan.clips.forEach { c ->
            withClue("plan ${c.slot}") {
                val slot = m.beatTime(c.slot.endBeat) - m.beatTime(c.slot.startBeat)
                (c.outputLength.inWholeMicroseconds / 1e6) shouldBe ((slot.inWholeMicroseconds / 1e6) plusOrMinus 1e-6)
                // La mort sur son temps, gel compris.
                (c.toOutput(c.anchor).inWholeMicroseconds / 1e6) shouldBe ((c.anchorOutput(m).inWholeMicroseconds / 1e6) plusOrMinus 1e-6)
                // Le gel suit le ralenti (ou la mort, quand le ralenti n'a pas la place) et dure ce que demandent les réglages.
                c.speeds.singleOrNull { it.kind == SpeedKind.FREEZE }?.let { freeze ->
                    freeze.range.start shouldBe (c.slow?.range?.end ?: c.anchor)
                    (freeze.outputLength.inWholeMilliseconds) shouldBe fails.fails.freeze.inWholeMilliseconds
                }
                // Les secondes qui précèdent la mort sont là : au moins le contexte minimal, hors ralenti.
                (c.anchor - c.start >= fails.fails.minLead) shouldBe true
            }
        }
    }

    test("ralenti : les morts les plus fortes, un tiers ; l'échange (kill juste avant) passe devant") {
        val m = music()
        // Six morts ; deux sont des échanges (un kill 1,5 s et 2 s avant), une autre suit un kill de trop loin (10 s).
        val s = session(
            300 to "death", 598 to "kill", 600 to "death", 890 to "kill", 900 to "death",
            1198 to "kill", 1200 to "death", 1500 to "death", 1800 to "death",
        )
        val groups = MontagePlanner.groups(listOf(s), fails)
        groups.filter { it.trade }.map { it.kills.single().inWholeSeconds } shouldContainExactly listOf(600L, 1200L)
        val plan = MontagePlanner.plan(groups, m, fails)
        val slowed = plan.clips.filter { it.slow != null }.map { it.anchor.inWholeSeconds }.toSet()
        slowed.size shouldBe kotlin.math.ceil(plan.clips.size / 3.0).toInt()
        // Les échanges présents dans le montage ont le ralenti.
        plan.clips.filter { it.group.trade }.forEach { (it.anchor.inWholeSeconds in slowed) shouldBe true }
        // Au moins une, même pour une seule mort ; toutes avec slowShare = 1.
        val one = MontagePlanner.plan(MontagePlanner.groups(listOf(session(300 to "death")), fails), m, fails)
        one.clips.single().slow shouldNotBe null
        val all = base.copy(fails = FailMontage(slowShare = 1.0)).forFails()
        MontagePlanner.plan(MontagePlanner.groups(listOf(s), all), m, all).clips.all { it.slow != null } shouldBe true
    }

    test("force d'une mort : échange, drop, intensité, ouverture") {
        val slot = CutSlot(0, 8, 0, dropBeat = null)
        val g = MontagePlanner.groups(listOf(session(300 to "death")), fails).single()
        MontagePlanner.failStrength(1, slot, g.copy(score = 0.0)) shouldBe 0.0
        MontagePlanner.failStrength(1, slot, g.copy(score = 0.0, trade = true)) shouldBe 1.0
        MontagePlanner.failStrength(1, slot.copy(dropBeat = 4), g.copy(score = 0.0)) shouldBe 0.75
        MontagePlanner.failStrength(1, slot, g.copy(score = 2.0)) shouldBe 0.5
        MontagePlanner.failStrength(0, slot, g.copy(score = 0.0)) shouldBe 0.25
    }

    test("sans gel demandé, aucun segment figé") {
        val noFreeze = base.copy(fails = FailMontage(freeze = Duration.ZERO)).forFails()
        val plan = MontagePlanner.plan(MontagePlanner.groups(listOf(session(120 to "death", 600 to "death")), noFreeze), music(), noFreeze)
        plan.clips.flatMap { it.speeds }.none { it.kind == SpeedKind.FREEZE } shouldBe true
    }

    test("textes : reproductibles d'un rendu à l'autre, jamais deux fois de suite le même, langue de l'interface") {
        val m = music()
        val deaths = (1..12).map { it * 120 }
        fun texts(): List<String?> {
            val plan = MontagePlanner.plan(MontagePlanner.groups(listOf(session(*deaths.map { it to "death" }.toTypedArray())), fails), m, fails)
            return FailLabels.assign(plan.clips, FailLabels.available(fails.fails))
        }
        I18n.withLanguage("fr") {
            val labels = FailLabels.available(fails.fails)
            labels shouldContainExactly listOf("OUPS", "RIP", "AÏE", "GAME OVER", "BIEN ESSAYÉ", "RATÉ")
            val first = texts()
            first shouldBe texts()
            first.zipWithNext().forEach { (a, b) -> (a != b) shouldBe true }
            first.forEach { (it in labels) shouldBe true }
        }
        I18n.withLanguage("en") { FailLabels.available(fails.fails).first() shouldBe "OOPS" }
        // Libellés du profil : ceux-là ; liste vide : pas de texte.
        FailLabels.available(FailMontage(labels = listOf("NOOB"))) shouldContainExactly listOf("NOOB")
        val plan = MontagePlanner.plan(MontagePlanner.groups(listOf(session(120 to "death", 600 to "death")), fails), m, fails)
        FailLabels.assign(plan.clips, emptyList()) shouldHaveSize plan.clips.size
        FailLabels.assign(plan.clips, emptyList()).all { it == null } shouldBe true
    }

    test("la même mort garde son texte quand la musique change : il tient à la mort, pas à sa place") {
        val clipsA = MontagePlanner.plan(MontagePlanner.groups(listOf(session(120 to "death")), fails), music(120.0), fails).clips
        val clipsB = MontagePlanner.plan(MontagePlanner.groups(listOf(session(120 to "death")), fails), music(95.0), fails).clips
        FailLabels.seed(clipsA.single()) shouldBe FailLabels.seed(clipsB.single())
    }
})
