package dev.highlights.ui.player

import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainInOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sortie son simulée qui « joue » en temps réel : [write] bloque quand 200 ms d'avance sont écrites, comme une vraie
 * carte son ; [playedFrames] avance avec l'horloge (figée en pause). [heard] : octets reçus.
 */
class RealTimeSink(private val now: () -> Long = System::nanoTime) : AudioSink {
    override val rate = AudioSink.RATE
    @Volatile private var written = 0L
    @Volatile private var closed = false
    @Volatile private var playing = true
    @Volatile private var playedNanos = 0L
    @Volatile private var mark = now()
    @Volatile var heard = 0L
        private set

    /** Comme une vraie sortie, rien ne joue avant les premiers octets. */
    @Volatile private var started = false

    private fun elapsed(): Long = if (!started) 0 else playedNanos + if (playing) now() - mark else 0

    override val playedFrames: Long get() = minOf(written, elapsed() * rate / 1_000_000_000L)

    override fun write(bytes: ByteArray, length: Int) {
        if (!started) {
            mark = now()
            started = true
        }
        written += length / AudioSink.BYTES_PER_FRAME
        heard += length
        while (!closed && written - playedFrames > rate / 5) Thread.sleep(2)
    }

    override fun pause() {
        if (!playing) return
        playedNanos = elapsed()
        playing = false
    }

    override fun resume() {
        if (playing) return
        mark = now()
        playing = true
    }

    override fun drain() {
        while (!closed && playedFrames < written) Thread.sleep(2)
    }

    override fun close() {
        closed = true
    }
}

class VideoPlayerTest : FunSpec({
    test("taille de décodage : réduite dans 1280×720, paire, jamais agrandie") {
        DecodeSize.fit(1080, 1920, 1280, 720) shouldBe DecodeSize(404, 720)
        DecodeSize.fit(3440, 1440, 1280, 720) shouldBe DecodeSize(1280, 534)
        DecodeSize.fit(1920, 1080, 1280, 720) shouldBe DecodeSize(1280, 720)
        DecodeSize.fit(640, 360, 1280, 720) shouldBe DecodeSize(640, 360)
    }

    test("cadence : 60 img/s pour un montage 9:16, 30 pour une capture 21:9 réduite (budget de pixels)") {
        VideoPlayer.playbackFps(60.0, DecodeSize(404, 720)) shouldBe 60.0
        VideoPlayer.playbackFps(60.0, DecodeSize(1280, 534)) shouldBe 30.0
        VideoPlayer.playbackFps(30.0, DecodeSize(640, 360)) shouldBe 30.0
        VideoPlayer.playbackFps(0.0, DecodeSize(640, 360)) shouldBe VideoPlayer.DEFAULT_FPS
        VideoPlayer.playbackFps(240.0, DecodeSize(404, 720)) shouldBe 60.0
    }

    test("commande vidéo : recherche avant l'entrée, cadence fixe puis réduction, image seule sur demande") {
        val args = VideoPlayer.videoCommand(Path.of("a.mp4"), 12.5.seconds, DecodeSize(404, 720), 60.0, "auto", single = true).args
        args.shouldContainInOrder("-hwaccel", "auto", "-ss", "12.500", "-i", "a.mp4", "-vf", "fps=60.000,scale=404:720:flags=bilinear", "-frames:v", "1", "-pix_fmt", "rgba")
        VideoPlayer.videoCommand(Path.of("a.mp4"), Duration.ZERO, DecodeSize(2, 2), 30.0, null, single = false).args.contains("-hwaccel") shouldBe false
        VideoPlayer.audioCommand(Path.of("a.mp4"), 1.seconds).args.shouldContainInOrder("-ss", "1.000", "-map", "0:a:0", "-ar", "48000", "-f", "s16le")
    }

    test("horloge : la sortie son mène, se fige en pause, puis l'horloge murale prend le relais") {
        var t = 0L
        val sink = object : AudioSink {
            override val rate = 1000
            var frames = 0L
            override val playedFrames get() = frames
            override fun write(bytes: ByteArray, length: Int) {}
            override fun pause() {}
            override fun resume() {}
            override fun drain() {}
            override fun close() {}
        }
        val clock = PlaybackClock(10.seconds, sink) { t }
        sink.frames = 500
        clock.position() shouldBe 10.5.seconds
        clock.audioFinished()
        t += 2_000_000_000L
        clock.position() shouldBe 12.5.seconds
        clock.pause()
        t += 5_000_000_000L
        clock.position() shouldBe 12.5.seconds
        clock.resume()
        t += 1_000_000_000L
        clock.position() shouldBe 13.5.seconds
    }

    test("mesure de synchro : moyenne, 95e centile, maximum, images sautées") {
        val meter = SyncMeter()
        repeat(95) { meter.record(2.milliseconds) }
        repeat(5) { meter.record(30.milliseconds) }
        meter.drop()
        val s = meter.snapshot()
        s.frames shouldBe 100
        s.dropped shouldBe 1
        s.meanMs shouldBe (95 * 2 + 5 * 30) / 100.0
        s.p95Ms shouldBe 2.5
        s.maxMs shouldBe 30.0
    }

    context("avec FFmpeg").config(enabledIf = { TestMedia.available }) {
        val dir = tempdir().toPath()
        val video = TestMedia.generate(dir.resolve("lecture.mp4"), durationSeconds = 6, audioTracks = listOf(TestMedia.AudioTrackSpec("Game", listOf(2.0..3.0))))

        test("lecture complète : toutes les images à l'heure du son, tout le son joué") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sink = RealTimeSink()
            val player = VideoPlayer(TestMedia.requireFfmpeg(), scope, sinks = { sink }, hwaccel = null)
            try {
                player.open(video)
                val end = withTimeout(30.seconds) { player.view.first { it.ended || it.error != null } }
                end.error shouldBe null
                (end.position >= 5.9.seconds) shouldBe true
                // 6 s à 30 img/s : quelques images sautées au démarrage au plus.
                (end.sync.frames + end.sync.dropped) shouldBeGreaterThan 170L
                end.sync.p95Ms shouldBeLessThan 1000.0 / 30
                end.sync.meanMs shouldBeLessThan 10.0
                // Tout le son : 6 s × 48 kHz × 4 octets, à une trame AAC près.
                abs(sink.heard - 6L * 48_000 * 4).toDouble() shouldBeLessThan 48_000 * 4 * 0.05
                player.frame.value!!.image.width shouldBe 640
            } finally {
                player.dispose()
                scope.cancel()
            }
        }

        test("pause : la position ne bouge plus, la lecture reprend où elle était") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = VideoPlayer(TestMedia.requireFfmpeg(), scope, sinks = { RealTimeSink() }, hwaccel = null)
            try {
                player.open(video)
                withTimeout(10.seconds) { player.view.first { it.position >= 1.seconds } }
                player.pause()
                val paused = withTimeout(5.seconds) { player.view.first { !it.playing } }.position
                kotlinx.coroutines.delay(600)
                val pts = player.frame.value!!.pts
                (pts - paused).absoluteValue shouldBeLessThanDuration 150.milliseconds
                player.view.value.position shouldBe paused
                player.play()
                withTimeout(10.seconds) { player.view.first { it.position >= paused + 1.seconds } }
            } finally {
                player.dispose()
                scope.cancel()
            }
        }

        test("glissement : seule la dernière position compte, son image est montrée, rien ne joue") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = VideoPlayer(TestMedia.requireFfmpeg(), scope, sinks = { RealTimeSink() }, hwaccel = null)
            try {
                player.open(video, at = 1.seconds, play = false)
                withTimeout(10.seconds) { player.frame.first { it != null } }!!.pts shouldBe 1.seconds
                listOf(2.0, 2.5, 3.0, 4.2).forEach { player.scrub(it.seconds) }
                withTimeout(10.seconds) { player.frame.first { it?.pts == 4.2.seconds } }
                player.endScrub()
                kotlinx.coroutines.delay(300)
                player.view.value.playing shouldBe false
                player.view.value.position shouldBe 4.2.seconds
            } finally {
                player.dispose()
                scope.cancel()
            }
        }

        test("frise : relâcher le glissement lance la lecture à l'endroit relâché") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = VideoPlayer(TestMedia.requireFfmpeg(), scope, sinks = { RealTimeSink() }, hwaccel = null)
            try {
                player.open(video, at = 1.seconds, play = false)
                withTimeout(10.seconds) { player.frame.first { it != null } }
                listOf(2.0, 3.0).forEach { player.scrub(it.seconds) }
                withTimeout(10.seconds) { player.frame.first { it?.pts == 3.seconds } }
                player.endScrub(play = true)
                withTimeout(5.seconds) { player.view.first { it.playing } }
                // Lecture repartie de 3 s, pas du début ni de la position d'avant le glissement.
                val later = withTimeout(10.seconds) { player.view.first { it.position >= 3.5.seconds } }
                later.position shouldBeLessThanDuration 5.seconds
                player.frame.value!!.pts shouldBeLessThanDuration 5.seconds
                (player.frame.value!!.pts >= 3.seconds) shouldBe true
            } finally {
                player.dispose()
                scope.cancel()
            }
        }

        test("sans sortie son : l'horloge murale cadence la lecture jusqu'au bout") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val player = VideoPlayer(TestMedia.requireFfmpeg(), scope, sinks = { null }, hwaccel = null)
            try {
                player.open(video, at = 4.seconds)
                val started = System.nanoTime()
                val end = withTimeout(20.seconds) { player.view.first { it.ended || it.error != null } }
                end.error shouldBe null
                // 2 s de vidéo jouées en temps réel, pas d'un trait.
                ((System.nanoTime() - started) / 1e9) shouldBeGreaterThan 1.7
            } finally {
                player.dispose()
                scope.cancel()
            }
        }
    }
})

private infix fun Duration.shouldBeLessThanDuration(max: Duration) {
    (this < max) shouldBe true
}
