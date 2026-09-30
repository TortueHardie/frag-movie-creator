package dev.highlights.analysis.audio

import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.profile.ProfileRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.Path
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

private const val RATE = 48_000

/** Fond sonore faible, puis sons ajoutés à leur instant. */
private fun track(seconds: Double, seed: Int = 1): FloatArray {
    val random = Random(seed)
    return FloatArray((seconds * RATE).toInt()) { (random.nextFloat() - 0.5f) * 0.002f }
}

/** Détonation : bruit large bande qui s'éteint en 40 ms. */
private fun FloatArray.shot(at: Double, amplitude: Float, seed: Int = 7) {
    val random = Random(seed + (at * 1000).toInt())
    val start = (at * RATE).toInt()
    for (i in 0 until RATE / 5) {
        if (start + i >= size) break
        this[start + i] += (random.nextFloat() - 0.5f) * 2 * amplitude * exp(-i / (0.04 * RATE)).toFloat()
    }
}

/** Son tonal (deux partiels) qui s'éteint en [decay] secondes. */
private fun FloatArray.tone(at: Double, amplitude: Float, frequencies: List<Double>, decay: Double = 0.06, length: Double = 0.2) {
    val start = (at * RATE).toInt()
    for (i in 0 until (length * RATE).toInt()) {
        if (start + i >= size) break
        val t = i.toDouble() / RATE
        this[start + i] += (amplitude * exp(-t / decay) * frequencies.sumOf { sin(2 * PI * it * t) } / frequencies.size).toFloat()
    }
}

private val DINK = listOf(3200.0, 5100.0)

class GameSoundsTest : FunSpec({

    test("tirs du joueur : les attaques fortes, pas les tirs lointains") {
        val samples = track(4.0)
        val mine = listOf(0.5, 0.9, 1.3, 2.0, 2.6, 3.5)
        mine.forEach { samples.shot(it, 0.5f) }
        listOf(1.6, 2.3, 3.0).forEach { samples.shot(it, 0.03f) }
        val onsets = OnsetStream(RATE).apply { push(samples, samples.size) }.onsets(9.0, 0.04)
        onsets shouldHaveSize 9
        val kept = ShotFilter.keep(onsets, 0.9, 10.0)
        kept shouldHaveSize mine.size
        kept.zip(mine).forEach { (o, t) -> o.seconds shouldBe (t plusOrMinus 0.01) }
    }

    test("tirs en rafale : chaque balle compte, l'écho non") {
        val samples = track(1.5)
        // Cadence d'un fusil d'assaut : une balle toutes les 100 ms.
        (0 until 5).forEach { samples.shot(0.3 + it * 0.1, 0.5f) }
        val onsets = OnsetStream(RATE).apply { push(samples, samples.size) }.onsets(9.0, 0.04)
        onsets shouldHaveSize 5
    }

    test("flux par petits morceaux : mêmes niveaux qu'en une fois") {
        val samples = track(1.0).also { it.shot(0.4, 0.5f) }
        val whole = OnsetStream(RATE).apply { push(samples, samples.size) }
        val pieces = OnsetStream(RATE)
        samples.toList().chunked(333).forEach { c -> pieces.push(c.toFloatArray(), c.size) }
        pieces.size shouldBe whole.size
        (0 until whole.size).all { kotlin.math.abs(whole.levels[it] - pieces.levels[it]) < 1e-6 } shouldBe true
    }

    test("gabarit : le son reconnu là où il est, même couvert par un tir, et pas un autre son") {
        val template = FloatArray((0.2 * RATE).toInt()).also { it.tone(0.0, 0.5f, DINK) }
        val fingerprint = TemplateMatcher.fingerprint(template, RATE, 24, 1000.0, 12000.0)
        val samples = track(4.0)
        samples.shot(1.0, 0.1f)
        samples.tone(1.0, 0.3f, DINK)
        samples.tone(2.0, 0.4f, listOf(1500.0, 2200.0))
        samples.shot(3.0, 0.5f)
        samples.tone(3.5, 0.1f, DINK)

        val matcher = TemplateMatcher(fingerprint)
        val scores = mutableListOf<Float>()
        val spectrum = BandSpectrum(RATE, 24, 1000.0, 12000.0) { scores += matcher.push(it).toFloat() }
        spectrum.push(samples, samples.size)
        val peaks = TemplateMatcher.peaks(scores.toFloatArray(), scores.size, 0.75, 30)
        val times = peaks.map { spectrum.time(it - (matcher.length - 1)) }
        times shouldHaveSize 2
        times[0] shouldBe (1.0 plusOrMinus 0.02)
        times[1] shouldBe (3.5 plusOrMinus 0.02)

        fun around(t: Double) = scores.indices.filter { kotlin.math.abs(spectrum.time(it - (matcher.length - 1)) - t) < 0.1 }
            .maxOf { scores[it].takeUnless { v -> v.isNaN() } ?: -1f }.toDouble()
        around(1.0) shouldBeGreaterThan 0.75
        around(2.0) shouldBeLessThan 0.75
        around(3.0) shouldBeLessThan 0.75
    }

    test("gabarit : les silences autour de l'extrait sont retirés") {
        val sound = FloatArray((0.2 * RATE).toInt()).also { it.tone(0.0, 0.5f, DINK) }
        // Extrait coupé large : 0,3 s de silence de part et d'autre, soit une centaine de trames de plus.
        val padding = FloatArray(256 * 56)
        val loose = padding + sound + padding
        val tight = TemplateMatcher.fingerprint(sound, RATE, 24, 1000.0, 12000.0).size
        val trimmed = TemplateMatcher.fingerprint(loose, RATE, 24, 1000.0, 12000.0).size
        // Restent les trames à cheval sur le début et la fin du son : quelques-unes, pas une centaine.
        (trimmed - tight in 0..6) shouldBe true
    }

    test("lecture des flottants : aucun échantillon perdu entre deux blocs") {
        val values = FloatArray(40_001) { it.toFloat() }
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> values.forEach { b.putFloat(it) } }.array()
        val read = mutableListOf<Float>()
        GameSoundsDetector.readFloats(ByteArrayInputStream(bytes)) { chunk, n -> for (i in 0 until n) read += chunk[i] }
        read.size shouldBe values.size
        read.last() shouldBe 40_000f
    }

    test("profil VALORANT : tirs toujours cherchés, son du tir à la tête en secours d'Outplayed") {
        val profile = ProfileRepository.loadDirectory(Path("../config/profiles")).byId("valorant")
        val configs = profile.detectors.filter { it.type == "game-sounds" }.associateBy { it.id }
        val shots = configs.getValue("game-shots").detectorParams().decode(GameSoundsParams.serializer()) { GameSoundsParams() }
        shots.shots shouldBe ShotSounds(minRiseDb = 9.0, percentile = 0.9, belowDb = 10.0)
        shots.sounds shouldBe emptyMap()
        val headshot = configs.getValue("headshot-sound")
        headshot.fallbackFor shouldBe "game-events"
        val params = headshot.detectorParams().decode(GameSoundsParams.serializer()) { GameSoundsParams() }
        params.shots shouldBe null
        params.sounds.keys shouldBe setOf("headshot")
        DetectorRegistry.fromServiceLoader().create(headshot.type, headshot.id, headshot.detectorParams()).id shouldBe "headshot-sound"
    }

    test("découvert par ServiceLoader") {
        DetectorRegistry.fromServiceLoader().types shouldContainAll setOf("game-sounds")
    }
})
