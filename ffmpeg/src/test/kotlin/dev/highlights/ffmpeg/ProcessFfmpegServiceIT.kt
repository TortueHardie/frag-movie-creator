package dev.highlights.ffmpeg

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.testing.TestMedia
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessFfmpegServiceIT : FunSpec({
    val enabled: (io.kotest.core.test.TestCase) -> Boolean = { TestMedia.available }

    test("probe d'une vidéo synthétique à deux pistes").config(enabledIf = enabled) {
        val file = TestMedia.generate(
            tempdir().toPath().resolve("two tracks.mp4"),
            durationSeconds = 10,
            audioTracks = listOf(TestMedia.AudioTrackSpec("Game", listOf(2.0..3.0)), TestMedia.AudioTrackSpec("Mic", emptyList())),
        )
        val media = TestMedia.requireFfmpeg().probe(file)
        media.duration.inWholeMilliseconds.toDouble() shouldBe (10_000.0 plusOrMinus 100.0)
        media.video?.width shouldBe 640
        media.audio.map { it.title } shouldBe listOf("Game", "Mic")
    }

    test("échec FFmpeg : code retour et stderr remontés").config(enabledIf = enabled) {
        val e = shouldThrow<FfmpegException> {
            TestMedia.requireFfmpeg().run(FfmpegCommand(listOf("-i", "Z:/n-existe/pas.mp4", "-f", "null", "-"), "test échec"))
        }
        e.exitCode shouldNotBe 0
        e.stderrTail.shouldNotBeEmpty()
        e.commandLine.contains("pas.mp4") shouldBe true
    }

    test("l'annulation tue le process ffmpeg").config(enabledIf = enabled) {
        withTimeout(10.seconds) {
            val job = launch {
                TestMedia.requireFfmpeg().run(
                    FfmpegCommand(listOf("-re", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=30", "-f", "null", "-"), "process infini"),
                )
            }
            delay(800.milliseconds)
            job.cancel()
            job.join()
        }
        ProcessHandle.allProcesses()
            .filter { it.info().command().orElse("").endsWith("ffmpeg.exe") && it.info().arguments().orElse(emptyArray()).contains("-re") }
            .count() shouldBe 0
    }

    test("encodeur utilisable détecté").config(enabledIf = enabled) {
        val selector = FfmpegEncoderSelector(TestMedia.requireFfmpeg(), dev.highlights.core.config.EncoderSettings())
        val checks = selector.checkAll()
        checks.any { it.usable } shouldBe true
        println("Encodeurs : $checks")
    }
})
