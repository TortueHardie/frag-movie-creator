package dev.highlights.montage

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class MusicAnalyzerTest : FunSpec({
    /**
     * Boucle synthétique : grosse caisse sur chaque temps (plus forte sur le 1er de la mesure), charleston entre les temps,
     * et une « drop » deux fois plus forte à partir de la mesure 9.
     */
    fun beatLoop(bpm: Double, seconds: Int, firstBeat: Double = 0.25): FloatArray {
        val sr = MusicAnalyzer.SAMPLE_RATE
        val out = FloatArray(sr * seconds)
        val period = 60.0 / bpm
        val random = Random(3)
        var beat = 0
        var t0 = firstBeat
        while (t0 < seconds) {
            val downbeat = beat % 4 == 0
            val loud = if (beat >= 32) 2.0 else 1.0
            val start = (t0 * sr).toInt()
            for (i in 0 until (0.15 * sr).toInt()) {
                if (start + i >= out.size) break
                val t = i.toDouble() / sr
                val kick = sin(2 * PI * (60 + 90 * exp(-t * 30)) * t) * exp(-t * 18) * (if (downbeat) 0.9 else 0.55)
                out[start + i] += (kick * 0.5 * loud).toFloat()
            }
            val hat = ((t0 + period / 2) * sr).toInt()
            for (i in 0 until (0.03 * sr).toInt()) {
                if (hat + i >= out.size) break
                out[hat + i] += ((random.nextDouble() * 2 - 1) * 0.08 * exp(-i / (0.01 * sr)) * loud).toFloat()
            }
            beat++
            t0 += period
        }
        return out
    }

    test("tempo, temps et partie intense d'une boucle à 128 BPM") {
        val analysis = MusicAnalyzer.analyzeSamples(beatLoop(128.0, 40))
        analysis.bpm shouldBe (128.0 plusOrMinus 1.0)
        analysis.beats.size shouldBeGreaterThan 70
        // Les temps détectés tombent sur les coups de grosse caisse (à une trame d'analyse près).
        val period = 60.0 / 128
        analysis.beats.drop(2).dropLast(2).forEach { b ->
            val s = b.inWholeMicroseconds / 1e6
            val phase = ((s - 0.25) / period) - Math.round((s - 0.25) / period)
            (phase * period) shouldBe (0.0 plusOrMinus 0.035)
        }
        val firstLoudBeat = analysis.beats.indexOfFirst { it.inWholeMicroseconds / 1e6 >= 0.25 + 32 * period - 0.05 }
        analysis.dropBeat shouldBeInRange (firstLoudBeat - 4)..(firstLoudBeat + 4)
        // Structure : une section calme puis la partie intense, frontière sur un premier temps de mesure.
        val drop = analysis.sectionAt(analysis.dropBeat)
        drop.startBeat shouldBe analysis.dropBeat
        analysis.isDownbeat(drop.startBeat) shouldBe true
        drop.level shouldBe Intensity.HIGH
        analysis.sections.first().level shouldBe Intensity.LOW
        analysis.sections.sumOf { it.beats } shouldBe analysis.beats.size
        // Les accents sont plus forts sur les temps de la partie intense.
        val calm = (0 until analysis.dropBeat).map { analysis.beatAccent[it] }.average()
        val loud = (analysis.dropBeat until analysis.beats.size).map { analysis.beatAccent[it] }.average()
        (loud > calm) shouldBe true
    }

    test("morceau uniforme : une seule section, drop au début") {
        val samples = beatLoop(120.0, 30)
        // Sans partie plus forte : même volume partout.
        val analysis = MusicAnalyzer.analyzeSamples(FloatArray(samples.size) { i -> if (i >= 32 * 0.5 * MusicAnalyzer.SAMPLE_RATE) samples[i] / 2 else samples[i] })
        analysis.sections shouldHaveSize 1
        analysis.dropBeat shouldBe 0
    }

    test("drop : plus gros saut d'intensité vers une section intense, première à égalité") {
        val sections = listOf(
            MusicSection(0, 32, -20.0, 0.1),
            MusicSection(32, 64, -14.0, 0.5),
            MusicSection(64, 128, -8.0, 1.0),
            MusicSection(128, 160, -16.0, 0.3),
            MusicSection(160, 224, -8.0, 1.0),
        )
        MusicAnalyzer.findDrop(sections) shouldBe 160
        MusicAnalyzer.findDrop(sections.take(3)) shouldBe 64
        MusicAnalyzer.findDrop(listOf(MusicSection(0, 64, -10.0, 1.0))) shouldBe 0
    }

    test("tempos variés") {
        listOf(90.0, 100.0, 120.0, 140.0, 150.0, 170.0).forEach { bpm ->
            withClue("$bpm BPM") { MusicAnalyzer.analyzeSamples(beatLoop(bpm, 40)).bpm shouldBe (bpm plusOrMinus 1.5) }
        }
    }

    test("rôles des sections : intro, montée vers la drop, creux, outro") {
        val sections = listOf(
            MusicSection(0, 32, -20.0, 0.10, rise = 1.0),
            // Mène à la drop : c'est une montée, même si son volume baisse (un riser perd ses basses).
            MusicSection(32, 64, -14.0, 0.50, rise = -3.0),
            MusicSection(64, 128, -6.0, 0.90),
            // Nettement en dessous de ses deux voisines : une respiration.
            MusicSection(128, 160, -16.0, 0.30),
            MusicSection(160, 192, -7.0, 0.85),
            MusicSection(192, 224, -22.0, 0.05),
        )
        MusicAnalyzer.classify(sections, dropBeat = 64).map { it.kind } shouldBe listOf(
            SectionKind.INTRO, SectionKind.BUILD_UP, SectionKind.DROP,
            SectionKind.BREAKDOWN, SectionKind.BODY, SectionKind.OUTRO,
        )
    }

    test("montée loin de la drop : il faut l'entendre monter") {
        fun kinds(rise: Double) = MusicAnalyzer.classify(
            listOf(
                MusicSection(0, 32, -8.0, 0.60),
                MusicSection(32, 64, -6.0, 0.90),
                MusicSection(64, 96, -14.0, 0.30, rise = rise),
                MusicSection(96, 128, -7.0, 0.85),
            ),
            dropBeat = 32,
        ).map { it.kind }

        // Une section calme qui grimpe de 4 dB vers plus intense est une montée ; sans pente, c'est un creux.
        kinds(4.0)[2] shouldBe SectionKind.BUILD_UP
        kinds(0.0)[2] shouldBe SectionKind.BREAKDOWN
    }

    test("premier temps de mesure repéré par l'accent") {
        val analysis = MusicAnalyzer.analyzeSamples(beatLoop(120.0, 30))
        analysis.bpm shouldBe (120.0 plusOrMinus 1.0)
        val period = 0.5
        val downbeats = analysis.beats.indices.filter { it % 4 == analysis.downbeatPhase }.map { analysis.beats[it].inWholeMicroseconds / 1e6 }
        downbeats.drop(1).take(6).forEach { t ->
            val beatIndex = Math.round((t - 0.25) / period).toInt()
            (beatIndex % 4) shouldBe 0
        }
    }

    test("contretemps : une caisse claire entre les temps devient une frappe, pas le charleston") {
        val sr = MusicAnalyzer.SAMPLE_RATE
        val plain = beatLoop(120.0, 30)
        val snare = plain.copyOf()
        val random = Random(5)
        var t = 0.25 + 0.25
        while (t < 30) {
            val start = (t * sr).toInt()
            for (i in 0 until (0.06 * sr).toInt()) {
                if (start + i >= snare.size) break
                snare[start + i] += ((random.nextDouble() * 2 - 1) * 0.3 * exp(-i / (0.02 * sr))).toFloat()
            }
            // Un contretemps sur deux : le tempo reste celui de la grosse caisse.
            t += 1.0
        }
        val withSnare = MusicAnalyzer.analyzeSamples(snare)
        val withHats = MusicAnalyzer.analyzeSamples(plain)
        withSnare.bpm shouldBe (120.0 plusOrMinus 1.0)
        fun strongHalves(a: MusicAnalysis) = a.beats.indices.count(a::isHalfHit)
        // Le charleston est une attaque nette, mais bien moins marquée que les temps : pas une frappe.
        (strongHalves(withHats) <= 2) shouldBe true
        // Un temps sur deux porte une caisse claire en son milieu.
        (strongHalves(withSnare) >= withSnare.beats.size / 3) shouldBe true
        val hits = withSnare.hits(8, 24)
        hits.count { !it.onBeat } shouldBeGreaterThan 4
        hits.zipWithNext().forEach { (a, b) -> (a.at < b.at) shouldBe true }
    }
})
