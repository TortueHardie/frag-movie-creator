package dev.highlights.montage

import dev.highlights.core.model.MatchCut
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MatchCutterTest : FunSpec({
    val w = MatchCuts.WIDTH
    val h = MatchCuts.HEIGHT

    /**
     * Images d'une animation : un motif (l'arme) qui glisse d'un pixel par image sur un décor fixe propre à chaque
     * clip. L'image j montre le stade [phase0] + j ; [vertical] : le motif descend au lieu d'avancer.
     */
    fun animation(phase0: Int, count: Int, seed: Int, vertical: Boolean = false): List<FloatArray> {
        val random = Random(seed)
        val background = FloatArray(w * h) { random.nextInt(256).toFloat() }
        // Motif de 24 px sur 64 x 36 : l'arme occupe une bonne part de la zone, le décor le reste.
        val s = 24
        val sprite = Random(99).let { r -> FloatArray(s * s) { r.nextInt(256).toFloat() } }
        return List(count) { j ->
            val p = phase0 + j
            val (ox, oy) = if (vertical) 20 to (p % 12) else p to 6
            background.copyOf().also { img ->
                for (y in 0 until s) for (x in 0 until s) img[(oy + y) * w + ox + x] = sprite[y * s + x]
            }
        }
    }

    val shifts = -4..4
    val margin = 5
    val count = shifts.last - shifts.first + 2 * margin

    test("même animation décalée de trois images : la coupe se déplace pour tomber au même stade") {
        val a = animation(10, count, seed = 1)
        val b = animation(7, count, seed = 2)
        val choice = MatchCuts.best(a, b, shifts, shifts, margin)!!
        // Stade montré de part et d'autre de la coupe : 10 + tail d'un côté, 7 + head de l'autre.
        (10 + choice.tail) shouldBe (7 + choice.head)
        withClue("ressemblance ${choice.similarity}") { (choice.similarity > MatchCut().minSimilarity + 0.1) shouldBe true }
        (abs(choice.tail) + abs(choice.head)) shouldBe 3
    }

    test("gestes différents : ressemblance faible") {
        val a = animation(10, count, seed = 1)
        val b = animation(0, count, seed = 2, vertical = true)
        val choice = MatchCuts.best(a, b, shifts, shifts, margin)!!
        withClue("ressemblance ${choice.similarity}") { (choice.similarity < MatchCut().minSimilarity) shouldBe true }
    }

    test("arme au repos : pas d'animation à raccorder") {
        val still = animation(10, 1, seed = 1).single()
        MatchCuts.best(List(count) { still }, List(count) { still }, shifts, shifts, margin) shouldBe null
    }

    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val music = MusicAnalysis(
        Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
        listOf(MusicSection(0, 96, -14.0, 0.4), MusicSection(96, 240, -8.0, 1.0)), 96,
    )
    val settings = MontageSettings(killOffset = Duration.ZERO)
    val plan = MontagePlanner.plan(
        listOf(
            KillGroup(media, listOf(100.seconds, 102.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(media, listOf(500.seconds), 0.5, emptyList(), emptyList()),
            KillGroup(media, listOf(900.seconds), 0.4, emptyList(), emptyList()),
        ),
        music, settings,
    )

    fun ms(d: Duration) = d.inWholeMicroseconds / 1000.0

    test("déplacement du début ou de la fin : kills et durée de sortie inchangés") {
        for (clip in plan.clips) {
            val head = MatchCuts.headShifts(clip, settings.matchCut)
            val tail = MatchCuts.tailShifts(clip, settings.matchCut)
            head shouldNotBe null
            tail shouldNotBe null
            val moved = MatchCuts.shiftTail(MatchCuts.shiftHead(clip, MatchCuts.FRAME * head!!.first), MatchCuts.FRAME * tail!!.last)
            moved.start shouldBe clip.start + MatchCuts.FRAME * head.first
            moved.end shouldBe clip.end + MatchCuts.FRAME * tail.last
            moved.kills shouldBe clip.kills
            moved.outputKills().zip(clip.outputKills()).forEach { (x, y) -> ms(x) shouldBe (ms(y) plusOrMinus 0.01) }
            ms(moved.toOutput(moved.end)) shouldBe (ms(clip.toOutput(clip.end)) plusOrMinus 0.01)
            // Les rampes ajoutées restent dans l'écart permis.
            (moved.speeds - clip.speeds.toSet()).size shouldBe 2
            (moved.speeds - clip.speeds.toSet()).forEach { abs(it.factor - 1) shouldBe (0.05 plusOrMinus 0.0501) }
        }
    }

    test("pas de marge : image gelée au bord ou kill collé à la coupe") {
        val clip = plan.clips.first()
        MatchCuts.tailShifts(clip.copy(padAfter = 100.milliseconds), settings.matchCut) shouldBe null
        MatchCuts.headShifts(clip.copy(padBefore = 100.milliseconds), settings.matchCut) shouldBe null
        MatchCuts.headShifts(clip.copy(start = clip.kills.first() - 200.milliseconds), settings.matchCut) shouldBe null
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
