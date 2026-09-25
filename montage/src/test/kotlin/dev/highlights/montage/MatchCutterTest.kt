package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MatchCutterTest : FunSpec({
    val size = ScopeCuts.SIZE

    /**
     * Images de la zone du viseur : le décor change à chaque image (la vue suit la cible) ; sur les images [aiming],
     * l'arme (un motif fixe, symétrique comme une arme vue de derrière) est posée par-dessus, au centre et jusqu'en bas
     * de la zone ; décalée de [shift] pixels à droite pour un tir à la hanche. [weapon] choisit l'arme, [scene] le décor.
     */
    fun frames(count: Int, aiming: IntRange, shift: Int = 0, weapon: Int = 99, scene: Int = 0): List<FloatArray> {
        val s = 20
        val half = Random(weapon).let { r -> FloatArray(s * size / 2) { r.nextInt(256).toFloat() } }
        val o = (size - s) / 2
        return List(count) { j ->
            val random = Random(j + 1000 * scene)
            FloatArray(size * size) { random.nextInt(256).toFloat() }.also { img ->
                if (j in aiming) for (y in size / 3 until size) for (x in 0 until s) {
                    val col = if (x < s / 2) x else s - 1 - x
                    val at = o + x + shift
                    if (at < size) img[y * size + at] = half[(y - size / 3) % (size / 2) * (s / 2) + col]
                }
            }
        }
    }

    val settings = MontageSettings(killOffset = Duration.ZERO)
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val scope = settings.matchCut

    test("visée avant le kill : elle commence quand le viseur se pose") {
        val f = frames(40, 12..39)
        val curve = ScopeCuts.aimCurve(f, killIndex = 36, reference = 12, stillShare = scope.stillShare, minSymmetry = scope.minSymmetry)!!.curve
        ScopeCuts.aimStart(curve, 36, scope.minSimilarity) shouldBe 12
    }

    test("visée après le kill : elle finit quand le joueur baisse son arme, malgré la flamme d'un tir") {
        val f = frames(40, 0..24).toMutableList()
        // Flamme du tir juste après le kill : une image brouillée au milieu de la visée.
        f[16] = frames(1, IntRange.EMPTY).single()
        val curve = ScopeCuts.aimCurve(f, killIndex = 15, reference = 12, stillShare = scope.stillShare, minSymmetry = scope.minSymmetry)!!.curve
        ScopeCuts.aimEnd(curve, 15, scope.minSimilarity) shouldBe 24
    }

    test("tir à la hanche : l'arme sur le côté, pas de visée") {
        val f = frames(40, 0..39, shift = 16)
        ScopeCuts.aimCurve(f, killIndex = 20, reference = 12, stillShare = scope.stillShare, minSymmetry = scope.minSymmetry) shouldBe null
    }

    test("arme à la hanche : même arme d'un clip à l'autre, poses semblables ; autre arme, poses différentes") {
        fun pose(weapon: Int, scene: Int) =
            ScopeCuts.aimCurve(frames(40, 0..39, shift = 16, weapon = weapon, scene = scene), 20, 12, scope.stillShare, minSymmetry = -1.0)!!.pose
        val a = pose(99, 1)
        val same = pose(99, 2)
        val other = pose(7, 3)
        (ScopeCuts.similarity(a, same) > 0.7) shouldBe true
        (ScopeCuts.similarity(a, other) < 0.3) shouldBe true
        val hip = scope.copy(minSymmetry = -1.0, minPoseMatch = 0.7)
        fun group(p: Pose) = KillGroup(media, listOf(100.seconds), 1.0, emptyList(), emptyList(), aim = Aim(TimeRange(99.seconds, 100.seconds), TimeRange(100.seconds, 101.seconds), p, p))
        ScopeCuts.compatible(group(a), group(same), hip) shouldBe true
        ScopeCuts.compatible(group(a), group(other), hip) shouldBe false
        // Sans exigence sur la pose (visée), toute pose tenue se raccorde.
        ScopeCuts.compatible(group(a), group(other), scope) shouldBe true
    }

    test("arme au repos : la fenêtre du début s'arrête où le recul commence") {
        // Arme au repos à la hanche, puis secouée par le tir sur les 8 images qui précèdent le kill.
        val rest = frames(40, 0..39, shift = 16, scene = 4)
        val shaking = frames(40, 0..39, shift = 10, scene = 5)
        val f = rest.take(32) + shaking.drop(32)
        val held = ScopeCuts.restCurve(f, stillShare = 0.3, killIndex = 31, reference = 12, threshold = scope.minSimilarity)!!
        val runs = ScopeCuts.runs(held.curve, scope.minSimilarity)
        runs.last().last shouldBe 31
        (runs.last().first <= 2) shouldBe true
    }

    test("arme au repos : pas d'arme en main au kill, pas de repos") {
        // Le sol seul, presque uniforme ; l'arme n'apparaît qu'au moment du kill : la médiane ne montre que le sol.
        val weapon = frames(10, 0..9, shift = 16, scene = 6)
        val floor = List(30) { j -> Random(j).let { r -> FloatArray(size * size) { 100f + r.nextInt(4) } } }
        ScopeCuts.restCurve(floor + weapon, stillShare = 1.0, killIndex = 40, reference = 8, threshold = scope.minSimilarity) shouldBe null
    }

    test("arme en main : le repère du HUD est trouvé où qu'il soit dans la zone, et seulement s'il y est") {
        // Repère : trois barres claires sur fond sombre, comme l'icône du chargeur de VALORANT.
        val tw = 12
        val th = 10
        val icon = FloatArray(tw * th) { i -> if ((i % tw) % 4 == 1 && i / tw in 1..8) 230f else 40f }
        val template = WeaponTemplate(tw, th, icon)
        val zw = 40
        val zh = 24
        fun zone(withIcon: Boolean, x0: Int = 17, y0: Int = 9) = Random(3).let { r ->
            FloatArray(zw * zh) { 60f + r.nextInt(30) }.also { z ->
                if (withIcon) for (y in 0 until th) for (x in 0 until tw) z[(y0 + y) * zw + x0 + x] = icon[y * tw + x]
            }
        }
        (template.score(zone(true), zw, zh) > 0.95) shouldBe true
        (template.score(zone(true, x0 = 3, y0 = 2), zw, zh) > 0.95) shouldBe true
        (template.score(zone(false), zw, zh) < 0.6) shouldBe true
    }

    test("arme en main : une image sans arme ne compte pas dans la pose") {
        val held = HeldPose(DoubleArray(6) { 0.9 }, Pose(DoubleArray(4), BooleanArray(4) { true }))
        val armed = booleanArrayOf(true, true, false, true, true)
        ScopeCuts.disarm(held, armed).curve.toList() shouldBe listOf(0.9, 0.9, -1.0, 0.9, 0.9, -1.0)
        ScopeCuts.disarm(held, null).curve.toList() shouldBe held.curve.toList()
    }

    test("pas de visée au moment du kill : rien à raccorder") {
        val f = frames(40, IntRange.EMPTY)
        val curve = ScopeCuts.aimCurve(f, killIndex = 20, reference = 12, stillShare = scope.stillShare, minSymmetry = -1.0)!!.curve
        ScopeCuts.aimStart(curve, 20, scope.minSimilarity) shouldBe null
        ScopeCuts.aimEnd(curve, 20, scope.minSimilarity) shouldBe null
    }

    val music = MusicAnalysis(
        Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
        listOf(MusicSection(0, 96, -14.0, 0.4), MusicSection(96, 240, -8.0, 1.0)), 96,
    )
    val plan = MontagePlanner.plan(
        listOf(
            KillGroup(media, listOf(100.seconds, 102.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(media, listOf(500.seconds), 0.5, emptyList(), emptyList()),
            KillGroup(media, listOf(900.seconds), 0.4, emptyList(), emptyList()),
        ),
        music, settings,
    )

    fun ms(d: Duration) = d.inWholeMicroseconds / 1000.0

    test("début et fin ramenés dans la visée : kills et durée de sortie inchangés, portions ralenties") {
        for (clip in plan.clips) {
            val kill = clip.kills.first()
            val head = ScopeCuts.retimeHead(clip, kill - 300.milliseconds)!!
            val moved = ScopeCuts.retimeTail(head, clip.anchor + 200.milliseconds)!!
            moved.start shouldBe kill - 300.milliseconds
            moved.end shouldBe clip.anchor + 200.milliseconds
            moved.kills shouldBe clip.kills
            moved.outputKills().zip(clip.outputKills()).forEach { (x, y) -> ms(x) shouldBe (ms(y) plusOrMinus 0.01) }
            ms(moved.toOutput(moved.end)) shouldBe (ms(clip.toOutput(clip.end)) plusOrMinus 0.01)
            (moved.speeds.first().factor < 1.0) shouldBe true
            (moved.speeds.last().factor < 1.0) shouldBe true
        }
    }

    test("raccord : refusé sans visée d'un côté ou quand le ralenti irait trop loin") {
        val (out, into) = plan.clips[0] to plan.clips[1]
        // Visée sur les trois quarts de la portion : un ralenti de ×0,6 à ×0,75, dans la limite de ×0,5.
        val aimEnd = out.anchor + (out.end - out.anchor) * 0.75
        val aimStart = into.kills.first() - (into.kills.first() - into.start) * 0.75
        val tail = TimeRange(out.anchor, aimEnd)
        val head = TimeRange(aimStart, into.kills.first())
        val cut = ScopeCuts.cut(out, into, tail, head, settings)!!
        cut.end shouldBe aimEnd
        cut.into.start shouldBe aimStart
        ScopeCuts.cut(out, into, null, head, settings) shouldBe null
        ScopeCuts.cut(out, into, tail, null, settings) shouldBe null
        // Visée trop courte : il faudrait ralentir le début bien au-delà de ×0,5.
        ScopeCuts.cut(out, into, tail, TimeRange(into.kills.first() - 210.milliseconds, into.kills.first()), settings) shouldBe null
    }

    test("ralenti désactivé : un raccord ne ralentit pas plus qu'une rampe, et ce n'est pas un ralenti") {
        val noSlow = settings.copy(slowMotion = settings.slowMotion.copy(enabled = false))
        val flat = MontagePlanner.plan(plan.clips.map { it.group }.distinct(), music, noSlow)
        val (out, into) = flat.clips[0] to flat.clips[1]
        val tail = TimeRange(out.anchor, out.anchor + (out.end - out.anchor) * 0.75)
        val head = TimeRange(into.kills.first() - (into.kills.first() - into.start) * 0.75, into.kills.first())
        // Il faudrait ralentir à ×0,6-0,75 : au-delà d'une rampe (±15 %).
        ScopeCuts.cut(out, into, tail, head, noSlow) shouldBe null
        val gentle = ScopeCuts.cut(out, into, TimeRange(out.anchor, out.end - 100.milliseconds), TimeRange(into.start + 100.milliseconds, into.kills.first()), noSlow)!!
        listOfNotNull(gentle.tailSpeed, gentle.headSpeed).forEach { (it >= 0.85) shouldBe true }
        gentle.into.speeds.first().kind shouldBe SpeedKind.RAMP
        // Ni ralenti ni rampe : seulement les raccords qui ne changent aucune vitesse.
        val still = noSlow.copy(speedRamp = noSlow.speedRamp.copy(enabled = false))
        ScopeCuts.speeds(still) shouldBe 1.0..1.0
        ScopeCuts.cut(out, into, TimeRange(out.anchor, out.end - 100.milliseconds), TimeRange(into.start + 100.milliseconds, into.kills.first()), still) shouldBe null
    }

    test("pas de place : image gelée au bord ou kill collé à la coupe") {
        val clip = plan.clips.first()
        ScopeCuts.retimeTail(clip.copy(padAfter = 100.milliseconds), clip.anchor + 300.milliseconds) shouldBe null
        ScopeCuts.retimeHead(clip.copy(padBefore = 100.milliseconds), clip.kills.first() - 300.milliseconds) shouldBe null
        ScopeCuts.retimeHead(clip, clip.kills.first() - 100.milliseconds) shouldBe null
        ScopeCuts.retimeTail(clip, clip.anchor + 50.milliseconds) shouldBe null
    }

    // Le joueur épaule 0,6 s avant son premier kill et baisse son arme 0,35 s après le dernier : trop peu pour la fin
    // d'un plan placé sans tenir compte de la visée.
    fun aimed(g: KillGroup) = g.copy(
        aim = Aim(
            head = TimeRange(g.kills.first() - 600.milliseconds, g.kills.first()),
            tail = TimeRange(g.kills.last(), g.kills.last() + 350.milliseconds),
        ),
    )
    val groups = plan.clips.map { it.group }.distinct()

    test("planification : le kill est placé pour que le plan tienne dans la visée") {
        val group = groups.single { it.kills.size == 1 && it.kills.first() == 500.seconds }
        val slot = CutSlot(104, 112, 1, null)
        fun pre(c: MontageClip) = music.beatTime(c.anchorBeat) - music.beatTime(c.slot.startBeat)
        fun post(c: MontageClip) = music.beatTime(c.slot.endBeat) - music.beatTime(c.anchorBeat)
        val free = MontagePlanner.clipFor(slot to group, music, settings, 2, 1)
        (post(free) > 600.milliseconds) shouldBe true
        (pre(free) > 1200.milliseconds) shouldBe true
        val tail = MontagePlanner.clipFor(slot to group, music, settings, 2, 1, aim = MontagePlanner.AimFit(post = Duration.ZERO..600.milliseconds))
        (post(tail) <= 600.milliseconds) shouldBe true
        val head = MontagePlanner.clipFor(slot to group, music, settings, 2, 1, aim = MontagePlanner.AimFit(pre = Duration.ZERO..1200.milliseconds))
        (pre(head) <= 1200.milliseconds) shouldBe true
    }

    test("planification : les coupes entre plans qui visent sont raccordées, kills sur leur temps") {
        val planned = ScopeCuts.apply(MontagePlanner.plan(groups.map(::aimed), music, settings))
        (planned.clips.count { it.matchCut } >= 1) shouldBe true
        planned.clips.forEach { c ->
            ms(c.toOutput(c.end)) shouldBe (ms(c.outputLength) plusOrMinus 0.01)
            ms(c.outputKills().last()) shouldBe (ms(music.beatTime(c.anchorBeat) - music.beatTime(c.slot.startBeat)) plusOrMinus 1.0)
        }
    }

    test("planification : sans voisin qui vise, rien ne change") {
        val lone = groups.mapIndexed { i, g -> if (i == 0) aimed(g) else g }
        MontagePlanner.plan(lone, music, settings).clips.map { it.anchorBeat } shouldBe plan.clips.map { it.anchorBeat }
        MontagePlanner.aimFit(null, aimed(groups[0]), groups[1], settings) shouldBe MontagePlanner.AimFit.NONE
    }

    test("coupe raccordée : ni flash ni whip") {
        val flick = KillTraits(flick = 1.0, direction = FlickDirection.RIGHT)
        val clips = plan.clips.map { c -> c.copy(group = c.group.copy(traits = c.group.kills.map { flick })) }
        val matched = plan.copy(clips = clips.mapIndexed { i, c -> if (i == 1) c.copy(matchCut = true) else c })
        MontageRenderBuilder.whips(plan.copy(clips = clips))[1] shouldBe FlickDirection.RIGHT
        MontageRenderBuilder.whips(matched)[1] shouldBe null
        MontageRenderBuilder.flashes(matched)[1] shouldBe false
    }
})
