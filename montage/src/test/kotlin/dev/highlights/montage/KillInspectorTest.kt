package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.KillStyle
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ShotAlign
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KillInspectorTest : FunSpec({
    val rate = ShotLocator.RATE
    val align = ShotAlign()
    val start = 10.seconds
    fun seconds(d: Duration) = d.inWholeMicroseconds / 1e6

    /** Demi-seconde de fond sonore faible, avec des tirs (bruit bref qui décroît) aux instants donnés (s, depuis le début). */
    fun sound(vararg shots: Pair<Double, Double>): FloatArray {
        val random = Random(7)
        val out = FloatArray(rate / 2) { ((random.nextDouble() * 2 - 1) * 0.002).toFloat() }
        for ((at, amplitude) in shots) {
            val from = (at * rate).toInt()
            for (i in 0 until (0.03 * rate).toInt()) {
                if (from + i >= out.size) break
                out[from + i] += ((random.nextDouble() * 2 - 1) * amplitude * exp(-i / (0.008 * rate))).toFloat()
            }
        }
        return out
    }

    test("tir : l'attaque nette près de l'instant annoncé") {
        val shot = ShotLocator.locate(sound(0.32 to 0.8), rate, start, 10.40.seconds, align)!!
        seconds(shot) shouldBe (10.32 plusOrMinus 0.006)
    }

    test("tir : rien de net, l'instant annoncé est gardé") {
        ShotLocator.locate(sound(0.32 to 0.003), rate, start, 10.40.seconds, align) shouldBe null
        ShotLocator.locate(sound(), rate, start, 10.40.seconds, align) shouldBe null
    }

    test("tir : hors de la fenêtre de recherche, ignoré") {
        // 10,02 s : 380 ms avant l'annonce, au-delà des 300 ms cherchées.
        ShotLocator.locate(sound(0.02 to 0.8), rate, start, 10.40.seconds, align) shouldBe null
    }

    test("rafale : le tir le plus proche de l'annonce, à force égale") {
        val shot = ShotLocator.locate(sound(0.14 to 0.8, 0.36 to 0.8), rate, start, 10.40.seconds, align)!!
        seconds(shot) shouldBe (10.36 plusOrMinus 0.006)
    }

    /** Images [width]x[height] découpées dans une texture plus large, au décalage horizontal donné pour chaque image. */
    fun frames(offsets: List<Int>, width: Int = FlickMeter.WIDTH, height: Int = 144): List<ByteArray> {
        val random = Random(11)
        val wide = width * 4
        val texture = ByteArray(wide * height) { random.nextInt(256).toByte() }
        return offsets.map { x -> ByteArray(width * height) { i -> texture[(i / width) * wide + x + i % width] } }
    }

    test("décalage : retrouve le glissement d'un profil") {
        val random = Random(3)
        val a = DoubleArray(200) { random.nextDouble() }
        val b = DoubleArray(200) { i -> a.getOrElse(i + 7) { 0.0 } }
        abs(FlickMeter.shift(a, b, 40)) shouldBe 7
        FlickMeter.shift(a, a, 40) shouldBe 0
    }

    val style = KillStyle()
    val fps = FlickMeter.FPS.toDouble()

    test("flick : balayage rapide puis arrêt juste avant le kill") {
        // Immobile, puis 20 px par image pendant 8 images (4,7 largeurs par seconde), puis arrêt sur la cible.
        val offsets = List(30) { i -> 20 * (i - 8).coerceIn(0, 8) }
        val speeds = FlickMeter.speeds(frames(offsets), FlickMeter.WIDTH, 144, fps)
        speeds[10] shouldBe (20.0 / FlickMeter.WIDTH * fps plusOrMinus 0.01)
        (FlickMeter.score(speeds, 24, style) > 0.8) shouldBe true
    }

    test("flick : une vue immobile n'en est pas un") {
        val speeds = FlickMeter.speeds(frames(List(30) { 0 }), FlickMeter.WIDTH, 144, fps)
        FlickMeter.score(speeds, 24, style) shouldBe 0.0
    }

    test("flick : une visée qui suit encore la cible au moment du kill ne compte qu'à moitié") {
        val offsets = List(30) { i -> 20 * i }.map { it.coerceAtMost(FlickMeter.WIDTH * 3) }
        val speeds = FlickMeter.speeds(frames(offsets), FlickMeter.WIDTH, 144, fps)
        (FlickMeter.score(speeds, 24, style) <= 0.5) shouldBe true
    }

    test("capture réelle : kills recalés sur le tir, flick mesuré sur l'image").config(enabledIf = { TestMedia.available }, timeout = 2.seconds * 60) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = tempdir().toPath().resolve("partie.mp4")
        // Vue qui balaie de 4,55 à 4,75 s (dix largeurs d'écran par seconde) puis s'arrête ; tirs à 1,9 et 4,8 s.
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-y", "-f", "lavfi", "-i", "testsrc2=s=2560x360:r=60:d=8",
                    "-f", "lavfi", "-i", "aevalsrc='0.9*(between(t\\,1.9\\,1.915)+between(t\\,4.8\\,4.815))*sin(2*PI*2500*t)+0.003*sin(2*PI*110*t)':s=48000:d=8",
                    "-vf", "crop=640:360:'min(max((t-4.55)*6400\\,0)\\,1280)':0",
                    "-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-c:a", "aac", "-b:a", "128k", video.toString(),
                ),
                "capture de test",
            ),
        )
        val media = ffmpeg.probe(video)
        val group = KillGroup(media, listOf(2.seconds, 5.seconds), 0.5, emptyList(), emptyList())
        val inspected = KillInspector(ffmpeg).inspect(listOf(group), MontageSettings(), AudioLayout(), ProgressReporter.NONE).single()

        seconds(inspected.kills[0]) shouldBe (1.9 plusOrMinus 0.015)
        seconds(inspected.kills[1]) shouldBe (4.8 plusOrMinus 0.015)
        inspected.traitsOf(inspected.kills[0]).shift.inWholeMilliseconds.toDouble() shouldBe (-100.0 plusOrMinus 15.0)
        (inspected.traitsOf(inspected.kills[0]).flick < KillTraits.STRONG_FLICK) shouldBe true
        (inspected.traitsOf(inspected.kills[1]).flick >= KillTraits.STRONG_FLICK) shouldBe true
        (inspected.style >= MontageSettings().killStyle.flickBonus * KillTraits.STRONG_FLICK) shouldBe true

        // Recalage et mesure désactivés : le groupe ressort tel quel.
        val off = MontageSettings(shotAlign = ShotAlign(enabled = false), killStyle = KillStyle(flick = false))
        KillInspector(ffmpeg).inspect(listOf(group), off, AudioLayout(), ProgressReporter.NONE).single() shouldBe group
    }
})
