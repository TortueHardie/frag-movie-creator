package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MotionBlur
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class VectorBlurTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))
    val group = KillGroup(media, listOf(101.seconds), 1.0, emptyList(), emptyList())

    /** Plan de 100 s à 102 s de la source, une seconde ralentie ×2 au milieu (100,5 s → 101,5 s) : 3 s en sortie. */
    fun clip(padBefore: Duration = Duration.ZERO) = MontageClip(
        group, CutSlot(0, 4, 0), 2, 101.seconds, 100.seconds, 102.seconds, padBefore, Duration.ZERO,
        listOf(SpeedSegment(TimeRange(100.5.seconds, 101.5.seconds), 0.5, SpeedKind.SLOW)), 3.seconds + padBefore,
    )

    /** Caméra qui tourne vers la droite à [speed] largeurs de capture par seconde, partout sur la mesure. */
    fun track(speed: Double) = MotionTrack(99.seconds, List(600) { FlickMeter.Motion(speed, 0.0) })

    test("instant de la source sous chaque instant du plan : ralenti, image gelée, fin de source") {
        val c = clip()
        VectorBlur.sourceAt(c, 250.milliseconds) shouldBe (100.25.seconds to 1.0)
        // Dans le ralenti : 1 s de sortie couvre 0,5 s de source.
        VectorBlur.sourceAt(c, 1.5.seconds) shouldBe (101.seconds to 0.5)
        VectorBlur.sourceAt(c, 2.75.seconds) shouldBe (101.75.seconds to 1.0)
        VectorBlur.sourceAt(c, 3.5.seconds) shouldBe null
        // Image gelée avant la source (capture trop courte) : rien ne bouge.
        VectorBlur.sourceAt(clip(padBefore = 1.seconds), 500.milliseconds) shouldBe null
        VectorBlur.sourceAt(clip(padBefore = 1.seconds), 1.25.seconds) shouldBe (100.25.seconds to 1.0)
    }

    test("traînée de la longueur parcourue entre deux images, deux fois plus courte au ralenti") {
        // 2 largeurs/s, capture large de 2580 pixels en sortie, 60 img/s : 86 pixels par image.
        val sigmas = VectorBlur.sigmas(clip(), track(2.0), Duration.ZERO, 180, 60, 2580.0, 2580, MotionBlur())
        val shutter = 0.017 * 60
        sigmas[15].first shouldBe (86.0 * shutter / sqrt(12.0) plusOrMinus 0.01)
        sigmas[15].second shouldBe 0.0
        sigmas[90].first shouldBe (43.0 * shutter / sqrt(12.0) plusOrMinus 0.01)
    }

    test("traînée plafonnée, et nulle quand le décor glisse à peine") {
        val fast = VectorBlur.sigmas(clip(), track(50.0), Duration.ZERO, 180, 60, 2580.0, 2580, MotionBlur())
        fast[15].first shouldBe (0.08 * 2580 / sqrt(12.0) plusOrMinus 0.01)
        val still = VectorBlur.sigmas(clip(), track(0.05), Duration.ZERO, 180, 60, 2580.0, 2580, MotionBlur())
        still.all { it == 0.0 to 0.0 } shouldBe true
        VectorBlur.filters("vb0", still, 60).shouldBeEmpty()
    }

    test("autour du kill, la traînée est réduite : on voit l'ennemi tomber") {
        // Plan de 100 s à 102 s, sans ralenti ; kill à 1 s dans le plan.
        val plain = MontageClip(group, CutSlot(0, 4, 0), 2, 101.seconds, 100.seconds, 102.seconds, Duration.ZERO, Duration.ZERO, emptyList(), 2.seconds)
        val sigmas = VectorBlur.sigmas(plain, track(2.0), Duration.ZERO, 120, 60, 2580.0, 2580, MotionBlur(), listOf(1.seconds))
        val far = sigmas[30].first // 0,5 s : loin du kill
        sigmas[54].first shouldBe (far * 0.25 plusOrMinus 0.01) // 100 ms avant le kill
        sigmas[72].first shouldBe (far * 0.25 plusOrMinus 0.01) // 200 ms après
        sigmas[78].first shouldBe far // 300 ms après : traînée entière
    }

    test("flou de coupe proportionné au plan : deux images sur un plan d'un temps, trois sur un plan long") {
        MotionBlur().cutFrames(60, 22) shouldBe 2
        MotionBlur().cutFrames(60, 120) shouldBe 3
        MotionBlur().cutFrames(60, 5) shouldBe 1
        MotionBlur(cuts = false).cutFrames(60, 120) shouldBe 0
    }

    test("commandes envoyées seulement quand le flou change, puis le flou lui-même") {
        val filters = VectorBlur.filters("vb3", listOf(0.0 to 0.0, 4.2 to 0.0, 4.2 to 0.0, 0.0 to 1.0), 60)
        filters.size shouldBe 2
        filters[0] shouldBe "sendcmd=c='0.0167 gblur@vb3 sigma 4.0, gblur@vb3 sigmaV 0.0;0.0500 gblur@vb3 sigma 0.0, gblur@vb3 sigmaV 1.0'"
        filters[1] shouldContain "gblur@vb3=sigma=0:sigmaV=0"
    }
})
