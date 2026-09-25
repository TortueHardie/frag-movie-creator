package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
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
     * de la zone ; décalée de [shift] pixels à droite pour un tir à la hanche.
     */
    fun frames(count: Int, aiming: IntRange, shift: Int = 0): List<FloatArray> {
        val s = 20
        val half = Random(99).let { r -> FloatArray(s * size / 2) { r.nextInt(256).toFloat() } }
        val o = (size - s) / 2
        return List(count) { j ->
            val random = Random(j)
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
    val scope = settings.matchCut

    test("visée avant le kill : elle commence quand le viseur se pose") {
        val f = frames(40, 12..39)
        val curve = ScopeCuts.aimCurve(f, killIndex = 36, reference = 12, stillShare = scope.stillShare, minSymmetry = scope.minSymmetry)!!
        ScopeCuts.aimStart(curve, 36, scope.minSimilarity) shouldBe 12
    }

    test("visée après le kill : elle finit quand le joueur baisse son arme, malgré la flamme d'un tir") {
        val f = frames(40, 0..24).toMutableList()
        // Flamme du tir juste après le kill : une image brouillée au milieu de la visée.
        f[16] = frames(1, IntRange.EMPTY).single()
        val curve = ScopeCuts.aimCurve(f, killIndex = 15, reference = 12, stillShare = scope.stillShare, minSymmetry = scope.minSymmetry)!!
        ScopeCuts.aimEnd(curve, 15, scope.minSimilarity) shouldBe 24
    }

    test("tir à la hanche : l'arme sur le côté, pas de visée") {
        val f = frames(40, 0..39, shift = 16)
        ScopeCuts.aimCurve(f, killIndex = 20, reference = 12, stillShare = scope.stillShare, minSymmetry = scope.minSymmetry) shouldBe null
    }

    test("pas de visée au moment du kill : rien à raccorder") {
        val f = frames(40, IntRange.EMPTY)
        val curve = ScopeCuts.aimCurve(f, killIndex = 20, reference = 12, stillShare = scope.stillShare, minSymmetry = -1.0)!!
        ScopeCuts.aimStart(curve, 20, scope.minSimilarity) shouldBe null
        ScopeCuts.aimEnd(curve, 20, scope.minSimilarity) shouldBe null
    }

    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
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
        val cut = ScopeCuts.cut(out, into, aimEnd, aimStart, scope)!!
        cut.end shouldBe aimEnd
        cut.into.start shouldBe aimStart
        ScopeCuts.cut(out, into, null, aimStart, scope) shouldBe null
        ScopeCuts.cut(out, into, aimEnd, null, scope) shouldBe null
        // Visée trop courte : il faudrait ralentir le début bien au-delà de ×0,5.
        ScopeCuts.cut(out, into, aimEnd, into.kills.first() - 210.milliseconds, scope) shouldBe null
    }

    test("pas de place : image gelée au bord ou kill collé à la coupe") {
        val clip = plan.clips.first()
        ScopeCuts.retimeTail(clip.copy(padAfter = 100.milliseconds), clip.anchor + 300.milliseconds) shouldBe null
        ScopeCuts.retimeHead(clip.copy(padBefore = 100.milliseconds), clip.kills.first() - 300.milliseconds) shouldBe null
        ScopeCuts.retimeHead(clip, clip.kills.first() - 100.milliseconds) shouldBe null
        ScopeCuts.retimeTail(clip, clip.anchor + 50.milliseconds) shouldBe null
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
