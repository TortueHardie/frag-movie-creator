package dev.highlights.montage

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SpeedRampEffect
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.TimelineSegment
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MontagePlannerTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))

    fun session(kills: List<Int>, speech: List<TimeRange> = emptyList(), scores: (Int) -> Double = { 0.5 }): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "wardogs",
            timeline = ScoredTimeline(
                grid, List(grid.count, scores), emptyMap(),
                events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "n") },
                segments = speech.map { TimelineSegment(it, "speech", 1.0, "voice") },
            ),
            highlights = emptyList(),
        )
    }

    /** Musique régulière à [bpm] : intro calme, montée, puis drop (section intense) jusqu'à la fin ; accent sur les premiers temps. */
    fun music(bpm: Double = 120.0, seconds: Int = 120, intro: Int = 32, build: Int = 32): MusicAnalysis {
        val n = (seconds * bpm / 60).toInt()
        val sections = listOf(
            MusicSection(0, intro, -20.0, 0.1),
            MusicSection(intro, intro + build, -14.0, 0.5),
            MusicSection(intro + build, n, -8.0, 1.0),
        )
        return MusicAnalysis(
            Path("musique.mp3"), seconds.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 }, sections = sections, dropBeat = intro + build,
        )
    }

    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds)
    fun seconds(d: Duration) = d.inWholeMicroseconds / 1e6
    fun plan(kills: List<Int>, m: MusicAnalysis = music(), s: MontageSettings = settings, speech: List<TimeRange> = emptyList()) =
        MontagePlanner.plan(MontagePlanner.groups(listOf(session(kills, speech)), s), m, s)

    val manyKills = listOf(60, 180, 183, 186, 300, 420, 540, 660, 780, 900, 1020, 1140, 1260)

    /** Invariants de tout plan : slots contigus, clips à la taille du slot, kill d'ancrage sur son temps, ralenti qui tient. */
    fun check(p: MontagePlan) {
        val m = p.music
        p.clips.zipWithNext().forEach { (a, b) -> a.slot.endBeat shouldBe b.slot.startBeat }
        (seconds(p.duration) <= seconds(p.settings.maxDuration) + 1e-6) shouldBe true
        p.clips.zip(p.clipOffsets()).forEach { (c, offset) ->
            withClue("clip ${c.slot}") {
                seconds(c.outputLength) shouldBe (seconds(m.beatTime(c.slot.endBeat) - m.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
                seconds(offset) shouldBe (seconds(m.beatTime(c.slot.startBeat) - p.musicStart) plusOrMinus 1e-6)
                // Le dernier kill tombe sur son temps, ralenti et gel compris.
                seconds(c.toOutput(c.anchor)) shouldBe (seconds(m.beatTime(c.anchorBeat) - m.beatTime(c.slot.startBeat)) plusOrMinus 1e-6)
                // Source + étirement du ralenti + gels = durée du slot.
                val speedExtra = c.speeds.sumOf { seconds(it.range.length) * (1 / it.factor - 1) }
                seconds(c.sourceLength) + speedExtra + seconds(c.padBefore + c.padAfter) shouldBe (seconds(c.outputLength) plusOrMinus 1e-6)
                (c.beatsPre >= 1 && c.beatsPost >= 1) shouldBe true
                c.kills.isNotEmpty() shouldBe true
                c.speeds.zipWithNext().forEach { (a, b) -> (a.range.end <= b.range.start) shouldBe true }
                c.speeds.forEach { (it.range.start >= c.start && it.range.end <= c.end) shouldBe true }
                c.ramps.forEach { kotlin.math.abs(it.factor - 1) shouldBe (0.0 plusOrMinus p.settings.speedRamp.maxChange + 1e-9) }
                c.slow?.let { slow ->
                    val tail = seconds(m.beatTime(c.slot.endBeat) - m.beatTime(c.anchorBeat))
                    (tail >= seconds(slow.range.end - c.anchor) / slow.factor + seconds(p.settings.cuts.minTail) - 1e-6) shouldBe true
                }
            }
        }
    }

    test("les clips remplissent exactement leur slot : coupes et kill d'ancrage sur les temps réels de la musique") {
        val p = plan(manyKills)
        check(p)
        // 11 groupes (un triple kill) ; le slot de la drop a absorbé un voisin pour le triple kill.
        p.clips shouldHaveSize 10
        (seconds(p.duration) > 40.0) shouldBe true
    }

    test("montée en puissance : le multi-kill tombe sur la drop, tous ses kills visibles, plans courts dans la partie intense") {
        val p = plan(manyKills)
        val drop = p.clips.single { it.slot.dropBeat != null }
        drop.kills shouldHaveSize 3
        drop.anchorBeat shouldBe p.music.dropBeat
        seconds(p.dropAt!!) shouldBe (seconds(p.clipOffsets()[p.clips.indexOf(drop)] + drop.toOutput(drop.anchor)) plusOrMinus 1e-6)
        // Le slot a été étendu vers l'arrière pour contenir les 6 s de kills.
        (drop.beats > p.clips.filter { it.slot.dropBeat == null }.minOf { it.beats }) shouldBe true
        val high = p.clips.filter { it.slot.dropBeat == null && p.music.sectionAt(it.slot.startBeat).level == Intensity.HIGH }
        val mid = p.clips.filter { p.music.sectionAt(it.slot.startBeat).level == Intensity.MID }
        high.isNotEmpty() shouldBe true
        mid.isNotEmpty() shouldBe true
        (mid.minOf { it.beats } >= high.maxOf { it.beats }) shouldBe true
    }

    test("tempo variable : les coupes suivent les temps détectés, pas une période moyenne") {
        // Temps qui ralentissent progressivement (musique jouée live).
        val n = 240
        var t = 0.0
        val beats = List(n) { i -> t.also { t += 0.5 + 0.0005 * i } }.map { it.seconds }
        val m = music().copy(beats = beats, sections = listOf(MusicSection(0, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)), dropBeat = 64)
        val p = plan(manyKills, m)
        check(p)
        p.clipOffsets().drop(1).zip(p.clips.drop(1)).forEach { (offset, c) ->
            seconds(offset) shouldBe (seconds(beats[c.slot.startBeat] - p.musicStart) plusOrMinus 1e-6)
        }
    }

    test("peu de clips : tous utilisés, plans plus longs") {
        val p = plan(listOf(100, 400, 700))
        check(p)
        p.clips shouldHaveSize 3
        p.clips.forEach { it.beats shouldBeGreaterThanOrEqual 8 }
    }

    test("beaucoup de clips : budget respecté, les mieux classés gardés") {
        val kills = (1..80).map { it * 20 } + listOf(1700, 1702)
        val p = plan(kills)
        check(p)
        (p.clips.size < kills.size) shouldBe true
        p.clips.single { it.slot.dropBeat != null }.kills shouldHaveSize 2
    }

    test("ordre chronologique : clips dans l'ordre de la partie") {
        val chrono = settings.copy(order = MontageOrder.CHRONOLOGICAL)
        val p = plan(listOf(900, 100, 500), s = chrono)
        check(p)
        p.clips.map { it.kills.first().inWholeSeconds } shouldBe listOf(100L, 500L, 900L)
    }

    test("aucun kill : erreur explicite") {
        shouldThrow<dev.highlights.core.HighlightsException> { MontagePlanner.plan(emptyList(), music(), settings) }
    }

    test("début de vidéo : image gelée plutôt que kill décalé") {
        val p = plan(listOf(1))
        check(p)
        val clip = p.clips.single()
        clip.padBefore.isPositive() shouldBe true
        clip.start shouldBe Duration.ZERO
    }

    test("fin de vidéo : image gelée après le kill") {
        val p = plan(listOf(media.duration.inWholeSeconds.toInt() - 1))
        check(p)
        val clip = p.clips.single()
        clip.padAfter.isPositive() shouldBe true
        clip.end shouldBe media.duration
    }

    test("réactions mises en avant : le clip dure jusqu'à la fin de la phrase") {
        val p = plan(listOf(100, 400, 700), s = settings.copy(reactions = true), speech = listOf(TimeRange(700.5.seconds, 705.seconds)))
        check(p)
        val talk = p.clips.single { it.kills.first() == 700.seconds }
        (seconds(talk.end) >= 705.0) shouldBe true
    }

    test("par défaut la voix ne décide de rien : plans et son comme sans micro") {
        val speech = listOf(TimeRange(700.5.seconds, 705.seconds))
        val groups = MontagePlanner.groups(listOf(session(listOf(100, 400, 700), speech)), settings)
        groups.all { it.voiceSegments.isEmpty() && it.protectedSegments.isEmpty() } shouldBe true
        plan(listOf(100, 400, 700), speech = speech).clips.map { it.end } shouldBe plan(listOf(100, 400, 700)).clips.map { it.end }
    }

    /** Écart (s) entre un instant du montage et le temps musical le plus proche. */
    fun offBeat(p: MontagePlan, clip: MontageClip, t: Duration): Double {
        val abs = seconds(p.musicStart + p.clipOffsets()[p.clips.indexOf(clip)] + t)
        return p.music.beats.minOf { kotlin.math.abs(seconds(it) - abs) }
    }

    test("rampe de vitesse : chaque kill d'un multi-kill tombe sur un temps, vitesse dans la limite") {
        val kills = listOf(60, 300, 420, 540, 660, 780, 900) + listOf(180.0, 183.4, 186.0).map { it }
        val session = session(emptyList()).let { s ->
            s.copy(timeline = s.timeline.copy(events = kills.map { TimelineEvent((it.toDouble() * 1000).toLong().let { ms -> Duration.parse("${ms}ms") }, "kill", 1.0, "n") }))
        }
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(session), settings), music(), settings)
        check(p)
        val triple = p.clips.single { it.group.kills.size == 3 }
        triple.kills shouldHaveSize 3
        triple.ramps.isNotEmpty() shouldBe true
        triple.outputKills().forEach { k -> offBeat(p, triple, k) shouldBe (0.0 plusOrMinus 1e-3) }
        // Sans rampe : seul le dernier kill est sur un temps.
        val off = settings.copy(speedRamp = SpeedRampEffect(enabled = false))
        val q = MontagePlanner.plan(MontagePlanner.groups(listOf(session), off), music(), off)
        val plain = q.clips.single { it.group.kills.size == 3 }
        plain.ramps shouldHaveSize 0
        offBeat(q, plain, plain.toOutput(plain.anchor)) shouldBe (0.0 plusOrMinus 1e-3)
        (offBeat(q, plain, plain.toOutput(183.4.seconds)) > 0.05) shouldBe true
    }

    test("rampe impossible dans la limite : vitesse inchangée") {
        val session = session(emptyList()).let { s ->
            s.copy(timeline = s.timeline.copy(events = listOf(100.seconds, 100.9.seconds, 500.seconds).map { TimelineEvent(it, "kill", 1.0, "n") }))
        }
        // Ramener le premier kill sur un temps demanderait bien plus de 1 % de vitesse : on le laisse où il est.
        val tight = settings.copy(speedRamp = SpeedRampEffect(maxChange = 0.01))
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(session), tight), music(), tight)
        check(p)
        p.clips.single { it.group.kills.size == 2 }.ramps shouldHaveSize 0
    }

    test("accroche : le meilleur groupe hors drop ouvre le montage") {
        // Scores croissants : les derniers kills sont les mieux notés, donc placés en fin de montage sans accroche.
        val s = session(manyKills, scores = { it / 2000.0 })
        val on = MontagePlanner.plan(MontagePlanner.groups(listOf(s), settings), music(), settings)
        check(on)
        on.clips.first().rank shouldBe on.clips.filter { it.slot.dropBeat == null }.maxOf { it.rank }

        val without = settings.copy(hook = false)
        val off = MontagePlanner.plan(MontagePlanner.groups(listOf(s), without), music(), without)
        check(off)
        (off.clips.first().rank < on.clips.first().rank) shouldBe true
    }

    test("le montage finit sur le meilleur groupe restant, sans règle dédiée") {
        // L'importance d'un slot croît avec sa position dans la section : les meilleurs atterrissent déjà à la fin.
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(session(manyKills, scores = { it / 2000.0 })), settings), music(), settings)
        check(p)
        val ordinary = p.clips.drop(1).filter { it.slot.dropBeat == null }
        ordinary.last().rank shouldBe ordinary.maxOf { it.rank }
    }

    test("plancher de qualité : les groupes faibles sont écartés, jamais tous") {
        // Scores croissants : les kills du début de partie sont les plus faibles.
        val weakFirst = session(manyKills, scores = { it / 2000.0 })
        val floor = settings.copy(minScore = 0.3)
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(weakFirst), floor), music(), floor)
        check(p)
        p.clips.forEach { (it.group.score >= 0.3) shouldBe true }
        (p.clips.size < manyKills.size) shouldBe true

        // Plancher inatteignable : le montage n'est pas vide pour autant.
        val impossible = settings.copy(minScore = 1.0)
        val q = MontagePlanner.plan(MontagePlanner.groups(listOf(weakFirst), impossible), music(), impossible)
        check(q)
        q.clips.isNotEmpty() shouldBe true
    }

    test("variété : deux clips voisins ne viennent pas du même moment de la partie") {
        val kills = listOf(60, 90, 120, 150, 600, 900, 1200, 1500)
        fun neighbours(gap: Duration): List<Long> {
            val s = settings.copy(varietyGap = gap, mergeGap = 5.seconds)
            val p = MontagePlanner.plan(MontagePlanner.groups(listOf(session(kills)), s), music(), s)
            check(p)
            return p.clips.map { it.group.kills.first().inWholeSeconds }
        }
        fun tooClose(order: List<Long>, gap: Long) = order.zipWithNext().count { (a, b) -> kotlin.math.abs(a - b) < gap }

        // Sans la règle, deux kills séparés de 30 s finissent côte à côte ; avec, plus aucun voisin semblable.
        (tooClose(neighbours(Duration.ZERO), 45) > 0) shouldBe true
        tooClose(neighbours(45.seconds), 45) shouldBe 0
    }

    test("montée : les plans raccourcissent en approchant de la drop") {
        // Même musique, mais la section avant la drop est reconnue comme une montée.
        val m = music()
        val rising = m.copy(sections = m.sections.mapIndexed { i, s -> if (i == 1) s.copy(kind = SectionKind.BUILD_UP) else s })
        fun lengths(a: MusicAnalysis, s: MontageSettings = settings): List<Int> {
            val period = a.beatPeriod
            val minBeats = maxOf(2, Math.ceil(s.cuts.minLead / period).toInt() + Math.ceil(s.cuts.minTail / period).toInt())
            return CutGrid.build(a, s.cuts, minBeats).filter { it.section == 1 && it.dropBeat == null }.map { it.beats }
        }

        val flat = lengths(m)
        val ramp = lengths(rising)
        flat.toSet() shouldHaveSize 1
        // La montée part de plans plus longs que la normale et redescend jusqu'à elle.
        (ramp.first() > flat.first()) shouldBe true
        (ramp.last() < ramp.first()) shouldBe true
        ramp.last() shouldBe flat.first()
        // Réglage désactivé : la montée retrouve des plans réguliers.
        lengths(rising, settings.copy(cuts = settings.cuts.copy(accelerateBuildUp = false))) shouldBe flat
    }

    test("densité d'effets : le ralenti va aux plans forts, pas à tous") {
        val p = plan(manyKills)
        check(p)
        // Un plan ordinaire (ni drop, ni multi-kill, ni accroche) n'est pas ralenti : son emphase sera le zoom.
        p.clips.forEachIndexed { i, c ->
            withClue("clip $i") {
                if (!MontagePlanner.isStrong(i, c.slot, c.kills.size)) (c.slow == null) shouldBe true
            }
        }
        val strong = p.clips.count { it.slow != null }
        (strong < p.clips.size) shouldBe true

        // Tout activé : le ralenti revient partout où il tient.
        val heavy = plan(manyKills, s = settings.copy(effectDensity = EffectDensity.HEAVY))
        check(heavy)
        (heavy.clips.count { it.slow != null } > strong) shouldBe true

        // Au minimum : seul le plan de la drop le garde.
        val sober = plan(manyKills, s = settings.copy(effectDensity = EffectDensity.SOBER))
        check(sober)
        sober.clips.filter { it.slow != null }.map { it.slot.dropBeat != null } shouldBe listOf(true)
    }

    test("un multi-kill dont le début serait coupé ne vaut pas un ralenti") {
        // Slots courts : le groupe de deux kills n'en montre qu'un, le plan n'a donc rien d'un moment fort.
        val tight = settings.copy(cuts = settings.cuts.copy(maxBeats = 4, minLead = 250.milliseconds))
        val p = plan(listOf(100, 104, 300, 500, 700, 900, 1100), s = tight)
        check(p)
        val trimmed = p.clips.single { it.group.kills.size > 1 }
        trimmed.kills shouldHaveSize 1
        // Ni la drop, ni l'accroche : sans multi-kill visible, pas de ralenti.
        if (trimmed.slot.dropBeat == null && p.clips.indexOf(trimmed) != 0) (trimmed.slow == null) shouldBe true
    }

    test("ralenti : décélération par paliers et plein régime retrouvé sur un temps") {
        val p = plan(listOf(100, 300, 500))
        check(p)
        val clip = p.clips.first { it.slow != null }
        val steps = clip.slowSteps
        // Vitesse décroissante jusqu'au ralenti plein, au lieu d'un seul changement net.
        steps.size shouldBeGreaterThanOrEqual 2
        steps.map { it.factor } shouldBe steps.map { it.factor }.sortedDescending()
        steps.last().factor shouldBe p.settings.slowMotion.factor
        // La relance tombe sur un temps de la musique.
        offBeat(p, clip, clip.toOutput(steps.last().range.end)) shouldBe (0.0 plusOrMinus 1e-3)
    }

    test("ralenti : un seul palier et sans calage redonnent le changement de vitesse net") {
        val blunt = settings.copy(slowMotion = settings.slowMotion.copy(rampSteps = 1, snapToBeat = false))
        val p = MontagePlanner.plan(MontagePlanner.groups(listOf(session(listOf(100, 300, 500))), blunt), music(), blunt)
        check(p)
        val clip = p.clips.first { it.slow != null }
        clip.slowSteps shouldHaveSize 1
        // Budget complet consommé après le kill, sans recherche de temps.
        seconds(clip.slow!!.range.end - clip.anchor) shouldBe (seconds(blunt.slowMotion.after) plusOrMinus 1e-6)
    }

    test("suite de plans de même longueur : kill toujours au même endroit du plan") {
        val p = plan((1..20).map { it * 60 })
        check(p)
        p.clips.filter { it.slot.dropBeat == null }.groupBy { it.slot.section to it.beats }.values.forEach { same ->
            same.map { it.beatsPre }.toSet() shouldHaveSize 1
        }
    }

    test("multi-kill trop long pour tout slot : les premiers kills sont coupés, le dernier reste sur son temps") {
        val tight = settings.copy(cuts = settings.cuts.copy(maxBeats = 4, minLead = 250.seconds / 1000))
        val p = plan(listOf(100, 104, 108, 500), s = tight)
        check(p)
        val multi = p.clips.single { it.group.kills.size == 3 }
        (multi.kills.size < 3) shouldBe true
        multi.kills.last() shouldBe 108.seconds
    }

    test("tirs à la tête : repérés par leur événement et comptés dans le rang") {
        val s = session(listOf(100, 400)).let {
            it.copy(timeline = it.timeline.copy(events = it.timeline.events + TimelineEvent(400.1.seconds, "headshot", 1.0, "n")))
        }
        val groups = MontagePlanner.groups(listOf(s), settings)
        groups.map { it.traits.single().headshot } shouldBe listOf(false, true)
        groups[1].style shouldBe (settings.killStyle.headshotBonus plusOrMinus 1e-9)
        (groups[1].rank > groups[0].rank) shouldBe true
    }

    fun withDeaths(s: Session, deaths: List<Double>) =
        s.copy(timeline = s.timeline.copy(events = (s.timeline.events + deaths.map { TimelineEvent(it.seconds, "death", 1.0, "n") }).sortedBy { it.at }))

    test("rounds : une mort clôt le sien, un long silence aussi") {
        val kills = listOf(100, 110, 200, 205, 400).map { it.seconds }
        val rounds = MontagePlanner.rounds(kills, listOf(112.seconds, 600.seconds), 40.seconds)
        rounds.map { r -> r.kills.map { it.inWholeSeconds } to r.died } shouldBe listOf(
            listOf(100L, 110L) to true,
            listOf(200L, 205L) to false,
            listOf(400L) to false,
        )
    }

    test("un kill aussitôt suivi de sa propre mort recule") {
        val groups = MontagePlanner.groups(listOf(withDeaths(session(listOf(100, 400)), listOf(101.5))), settings)
        groups.map { it.outcome.traded } shouldBe listOf(true, false)
        groups[0].style shouldBe (-settings.killStyle.deathPenalty plusOrMinus 1e-9)
        (groups[0].rank < groups[1].rank) shouldBe true
    }

    test("une mort qui tombe bien plus tard ne coûte rien") {
        val group = MontagePlanner.groups(listOf(withDeaths(session(listOf(100)), listOf(110.0))), settings).single()
        group.outcome shouldBe RoundOutcome.NONE
    }

    test("ace : le groupe qui finit un round de cinq kills monte devant les autres") {
        // Cinq kills espacés de 10 s : cinq groupes, un seul round (pas de mort, pas de silence de 40 s).
        val groups = MontagePlanner.groups(listOf(withDeaths(session(listOf(100, 110, 120, 130, 140, 400)), listOf(145.0))), settings)
        groups.map { it.outcome.ace } shouldBe listOf(false, false, false, false, true, false)
        groups.maxBy { it.rank } shouldBe groups[4]
        // Le joueur meurt au milieu : deux rounds, pas d'ace.
        val split = MontagePlanner.groups(listOf(withDeaths(session(listOf(100, 110, 120, 130, 140)), listOf(125.0))), settings)
        split.none { it.outcome.ace } shouldBe true
    }

    test("clutch : un round survécu fini sur un multi-kill monte, pas s'il meurt ensuite") {
        val survived = MontagePlanner.groups(listOf(withDeaths(session(listOf(100, 130, 132)), listOf(300.0))), settings)
        survived.map { it.outcome.clutch } shouldBe listOf(false, true)
        survived[1].style shouldBe (settings.killStyle.clutchBonus plusOrMinus 1e-9)
        val died = MontagePlanner.groups(listOf(withDeaths(session(listOf(100, 130, 132)), listOf(133.0))), settings)
        died.map { it.outcome } shouldBe listOf(RoundOutcome.NONE, RoundOutcome(traded = true))
    }

    test("une mort sépare deux kills rapprochés en deux groupes") {
        val groups = MontagePlanner.groups(listOf(withDeaths(session(listOf(100, 103)), listOf(101.0))), settings)
        groups.map { g -> g.kills.map { it.inWholeSeconds } } shouldBe listOf(listOf(100L), listOf(103L))
    }

    test("sans événement de mort : ni round, ni ace, ni clutch") {
        val off = settings.copy(killStyle = settings.killStyle.copy(deathEvent = ""))
        val groups = MontagePlanner.groups(listOf(withDeaths(session(listOf(100, 110, 120, 130, 140)), listOf(141.0))), off)
        groups.all { it.outcome == RoundOutcome.NONE } shouldBe true
    }

    test("le bilan du round survit au recalage des kills") {
        val group = MontagePlanner.groups(listOf(withDeaths(session(listOf(100)), listOf(101.0))), settings).single()
        val inspected = MontagePlanner.withTraits(group, listOf(99.8.seconds), listOf(KillTraits(headshot = true)), settings.killStyle)
        inspected.outcome.traded shouldBe true
        inspected.style shouldBe (settings.killStyle.headshotBonus - settings.killStyle.deathPenalty plusOrMinus 1e-9)
    }

    test("kills enchaînés : bonus par kill qui suit le précédent de près") {
        val group = MontagePlanner.groups(listOf(session(listOf(100))), settings).single()
        val quick = MontagePlanner.withTraits(group, listOf(100.seconds, 100.6.seconds, 103.seconds), List(3) { KillTraits() }, settings.killStyle)
        quick.style shouldBe (settings.killStyle.quickBonus plusOrMinus 1e-9)
    }

    test("un one-tap en flick passe devant un double kill ordinaire et décroche la drop") {
        val m = music()
        val double = MontagePlanner.groups(listOf(session(listOf(100, 102))), settings).single()
        val others = MontagePlanner.groups(listOf(session(listOf(500, 800))), settings)
        val single = others.first()
        val flick = MontagePlanner.withTraits(single, single.kills, listOf(KillTraits(headshot = true, flick = 1.0)), settings.killStyle)
        (flick.rank > double.rank) shouldBe true
        val p = MontagePlanner.plan(listOf(double, flick, others.last()), m, settings)
        check(p)
        p.clips.single { it.slot.dropBeat != null }.group shouldBe flick
    }

    test("flick : le plan a droit au ralenti même hors drop, accroche et multi-kill") {
        // Sans bonus, le flick ne change pas la place des groupes : seul le ralenti diffère.
        val s = settings.copy(killStyle = settings.killStyle.copy(flickBonus = 0.0))
        val groups = MontagePlanner.groups(listOf(session(listOf(100, 400, 700))), s)
        val before = MontagePlanner.plan(groups, music(), s)
        val ordinary = before.clips.withIndex().first { (i, c) -> i > 0 && c.slot.dropBeat == null && c.kills.size == 1 }
        ordinary.value.slow shouldBe null
        val flicky = groups.map { g ->
            if (g == ordinary.value.group) MontagePlanner.withTraits(g, g.kills, listOf(KillTraits(flick = 0.8)), s.killStyle) else g
        }
        val after = MontagePlanner.plan(flicky, music(), s)
        check(after)
        val clip = after.clips[ordinary.index]
        clip.flick shouldBe true
        (clip.slow != null) shouldBe true
    }

    test("frappes : un kill d'un multi-kill vise le contretemps marqué plutôt que le temps faible") {
        val n = 240
        val m = music().copy(halfAccent = DoubleArray(n) { 0.9 })
        // Kills à 1,3 s d'écart : le contretemps (2,5 temps avant la drop) ne demande que 4 % de vitesse, le temps le
        // plus proche (3 temps avant, un temps faible) 13 %.
        val s = settings.copy(slowMotion = settings.slowMotion.copy(enabled = false))
        val session = session(emptyList()).let {
            it.copy(timeline = it.timeline.copy(events = listOf(100.seconds, 101.3.seconds).map { t -> TimelineEvent(t, "kill", 1.0, "n") }))
        }
        fun beatPosition(p: MontagePlan): Double {
            val clip = p.clips.single()
            val at = seconds(p.musicStart + p.clipOffsets().single() + clip.toOutput(100.seconds))
            return at / 0.5
        }
        val onHits = MontagePlanner.plan(MontagePlanner.groups(listOf(session), s), m, s)
        check(onHits)
        (beatPosition(onHits) % 1.0) shouldBe (0.5 plusOrMinus 1e-3)
        val beatsOnly = s.copy(speedRamp = SpeedRampEffect(onHits = false))
        val plain = MontagePlanner.plan(MontagePlanner.groups(listOf(session), beatsOnly), m, beatsOnly)
        val position = beatPosition(plain)
        (position - Math.round(position)) shouldBe (0.0 plusOrMinus 1e-3)
    }

    test("variantes : le plan retenu est au moins aussi bien noté que le plan de base, sans perdre de clips") {
        val m = music()
        val groups = MontagePlanner.groups(listOf(session(manyKills)), settings)
        val base = MontagePlanner.plan(groups, m, settings)
        val best = MontagePlanner.best(groups, m, settings)
        check(best)
        (MontageScorer.score(best).total >= MontageScorer.score(base).total) shouldBe true
        (best.clips.size >= kotlin.math.ceil(base.clips.size * 0.85)) shouldBe true
        val off = MontagePlanner.best(groups, m, settings.copy(variants = false))
        off.variant shouldBe PlanVariant.BASE
        off.clips shouldBe base.clips
    }

    test("variante imposée : échelle de grille et place de la drop") {
        val m = music()
        val groups = MontagePlanner.groups(listOf(session(manyKills)), settings)
        val coarse = MontagePlanner.plan(groups, m, settings, PlanVariant(scale = 4.0))
        check(coarse)
        coarse.variant.scale shouldBe 4.0
        // Grille ×4 : des plans plus longs qu'avec la grille ×1.
        val fine = MontagePlanner.plan(groups, m, settings, PlanVariant(scale = 1.0))
        (coarse.clips.map { it.beats }.average() > fine.clips.map { it.beats }.average()) shouldBe true
        // La drop avancée dans le montage : elle tombe plus tôt, en part de sa durée.
        val early = MontagePlanner.plan(groups, m, settings, PlanVariant(dropShift = -0.3))
        val late = MontagePlanner.plan(groups, m, settings, PlanVariant(dropShift = 0.3))
        (early.dropAt!! / early.duration < late.dropAt!! / late.duration) shouldBe true
    }

})
