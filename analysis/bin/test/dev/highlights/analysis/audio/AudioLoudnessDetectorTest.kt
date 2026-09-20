package dev.highlights.analysis.audio

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.testing.TestMedia
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AudioLoudnessDetectorTest : FunSpec({

    test("parser ebur128 : associe chaque mesure à son pts_time") {
        val samples = mutableListOf<Pair<Duration, Double>>()
        val parser = Ebur128MetadataParser { t, v -> samples += t to v }
        listOf(
            "frame:0    pts:0       pts_time:0",
            "lavfi.r128.M=-120.691",
            "frame:1    pts:4800    pts_time:0.1",
            "lavfi.r128.M=-23.5",
            "bruit inattendu",
        ).forEach { parser.feed(it) }
        samples shouldBe listOf(Duration.ZERO to -120.691, 0.1.seconds to -23.5)
    }

    test("contraste : un pic isolé ressort, un niveau constant reste à 0") {
        val grid = WindowGrid(1.seconds, 1.seconds, 60.seconds)
        val flat = DoubleArray(60) { -30.0 }
        LoudnessContrast.compute(flat, grid, 10.seconds, 0.7).all { it == 0.0 } shouldBe true

        val peak = flat.copyOf().also { it[30] = -10.0 }
        val result = LoudnessContrast.compute(peak, grid, 10.seconds, 0.7)
        result[30] shouldBe (20.0 plusOrMinus 1e-9)
        result[10] shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("découvert par ServiceLoader") {
        DetectorRegistry.fromServiceLoader().types shouldContainAll setOf("audio-loudness")
    }

    test("vidéo synthétique : les salves de la piste jeu sont les fenêtres les plus fortes").config(enabledIf = { TestMedia.available }) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val file = TestMedia.generate(
            tempdir().toPath().resolve("bursts.mp4"),
            durationSeconds = 60,
            audioTracks = listOf(
                TestMedia.AudioTrackSpec("Game", listOf(15.0..17.0, 42.0..44.0)),
                TestMedia.AudioTrackSpec("Mic", listOf(30.0..31.0), frequency = 300),
            ),
        )
        val media = ffmpeg.probe(file)
        val grid = WindowGrid(2.seconds, 1.seconds, media.duration)
        fun ctx() = AnalysisContext(media, grid, ffmpeg, tempdir().toPath(), ProgressReporter.NONE)

        val game = AudioLoudnessDetector("game", AudioLoudnessParams(stream = 0)).analyze(ctx())
        val top = game.raw.withIndex().filterNot { it.value.isNaN() }.sortedByDescending { it.value }.take(6).map { it.index }
        withClue("top fenêtres $top, valeurs ${game.raw.joinToString { "%.1f".format(it) }}") {
            top.all { it in 13..17 || it in 40..44 } shouldBe true
        }
        game.raw[5] shouldBeLessThan 1.0

        val mic = AudioLoudnessDetector("mic", AudioLoudnessParams(titleContains = "mic")).analyze(ctx())
        (mic.raw.withIndex().filterNot { it.value.isNaN() }.maxBy { it.value }.index in 28..31) shouldBe true
        mic.raw[30] shouldBeGreaterThan 20.0

        val absent = AudioLoudnessDetector("mic2", AudioLoudnessParams(stream = 5, optional = true)).analyze(ctx())
        absent.isMissing shouldBe true
        absent.note.shouldNotBeNull()

        val fallback = AudioLoudnessDetector("game2", AudioLoudnessParams(stream = 5, fallbackStream = 0)).analyze(ctx())
        fallback.isMissing shouldBe false
    }
})

