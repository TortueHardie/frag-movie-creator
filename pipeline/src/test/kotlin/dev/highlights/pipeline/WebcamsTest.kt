package dev.highlights.pipeline

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WebcamChoice
import dev.highlights.core.model.WebcamSettings
import dev.highlights.core.model.WebcamSync
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WebcamsTest : FunSpec({
    test("noms de vidéos de webcam reconnus, les autres non") {
        listOf("partie_webcam", "Facecam 2026-11-17 21-08-40", "cam-1", "WEB-CAM", "caméra", "OBS cam").forEach { Webcams.isWebcamName(it) shouldBe true }
        listOf("WARDOGS_09-22-2026", "Camping trip", "scam", "Valorant- 44 kills").forEach { Webcams.isWebcamName(it) shouldBe false }
    }

    test("candidats : vidéos « webcam » du dossier de la capture, celle qui porte son nom d'abord") {
        val dir = tempdir().toPath()
        val capture = dir.resolve("partie.mp4").createFile()
        dir.resolve("autre_webcam.mp4").createFile()
        dir.resolve("partie_webcam.mkv").createFile()
        dir.resolve("partie_webcam.txt").createFile()
        dir.resolve("partie2.mp4").createFile()
        val media = MediaInfo(capture, 1, 1.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
        Webcams(FakeFfmpeg()).candidates(media).map { it.fileName.toString() } shouldBe listOf("partie_webcam.mkv", "autre_webcam.mp4")
    }

    test("enveloppe : les attaques ressortent, le niveau constant non") {
        val samples = FloatArray(Webcams.SAMPLE_RATE * 2) { i -> if (i in 8000..8100) 0.8f else 0.001f }
        val env = Webcams.envelope(samples)
        env.size shouldBe 200
        env.indices.maxBy { env[it] } shouldBe 100
        env[150] shouldBe 0.0
    }

    test("décalage au son : retrouvé à la trame près, refusé sur du bruit sans attaque commune") {
        val rnd = Random(3)
        // Attaques irrégulières (voix, claps) : 60 s à 100 trames/s.
        val capture = DoubleArray(6000) { if (rnd.nextDouble() < 0.02) 5 + rnd.nextDouble() * 10 else rnd.nextDouble() }
        val maxLag = 500
        val lag = 137
        // La webcam commence maxLag trames avant l'alignement attendu ; elle est en avance de `lag` trames de plus.
        val cam = DoubleArray(capture.size + 2 * maxLag) { j ->
            val i = j - maxLag - lag
            if (i in capture.indices) capture[i] * 0.6 + rnd.nextDouble() * 0.5 else rnd.nextDouble()
        }
        Webcams.audioLag(capture, cam, maxLag) shouldBe lag
        val unrelated = DoubleArray(cam.size) { rnd.nextDouble() }
        Webcams.audioLag(capture, unrelated, maxLag) shouldBe null
    }

    test("webcam non demandée : réglages inchangés, aucune webcam") {
        val setup = Webcams(FakeFfmpeg()).setup(emptyList(), null, null, EditSettings())
        setup.feeds.isEmpty() shouldBe true
        setup.edit shouldBe EditSettings()
        setup.notices.shouldBeEmpty()
    }

    test("webcam demandée mais introuvable : avertissement, montage sans incrustation") {
        val dir = tempdir().toPath()
        val media = MediaInfo(dir.resolve("partie.mp4").createFile(), 1, 1.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
        val setup = Webcams(FakeFfmpeg()).setup(listOf(media), WebcamChoice.File(dir.resolve("absente.mp4")), null, EditSettings())
        setup.feeds.isEmpty() shouldBe true
        setup.edit.webcam.enabled shouldBe false
        setup.notices.map { it.key } shouldBe listOf("notice.webcam.notFound")
    }

    /**
     * Vraies vidéos : capture avec piste micro (salves à 6, 13,4, 21 et 27 s), webcam bleue nommée « partie_webcam »
     * commencée 2,3 s plus tard (mêmes salves, 2,3 s plus tôt dans son temps). Recherche automatique : la webcam est
     * trouvée à côté, et calée au son (aucune heure fiable dans ces fichiers).
     */
    test("recherche automatique et calage au son sur de vraies vidéos").config(enabledIf = { TestMedia.available }, timeout = 120.seconds) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val dir = tempdir().toPath()
        val bursts = listOf(6.0, 13.4, 21.0, 27.0)
        val capture = TestMedia.generate(
            dir.resolve("partie.mp4"), 35,
            audioTracks = listOf(
                TestMedia.AudioTrackSpec("Game", emptyList(), 300),
                TestMedia.AudioTrackSpec("Mic", bursts.map { it..it + 0.15 }, 600),
            ),
        )
        val late = 2.3
        val cam = dir.resolve("partie_webcam.mp4")
        val loud = bursts.joinToString("+") { "between(t\\,${it - late}\\,${it - late + 0.15})" }
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-y", "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=30:d=33",
                    "-f", "lavfi", "-i", "aevalsrc=0.5*sin(2*PI*600*t)*($loud)+0.01*sin(2*PI*90*t):s=48000:d=33",
                    "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", cam.toString(),
                ),
                "webcam de test",
            ),
        )
        val media = ffmpeg.probe(capture)
        val setup = Webcams(ffmpeg).setup(listOf(media), WebcamChoice.Auto, null, EditSettings())
        val feed = setup.feeds[media.path]
        feed shouldNotBe null
        feed!!.file.fileName.toString() shouldBe "partie_webcam.mp4"
        feed.sync shouldBe WebcamSync.AUDIO
        feed.offset.inWholeMilliseconds shouldBeInRange (-2340L..-2260L)
        setup.edit.webcam.enabled shouldBe true
        // Décalage réglé à la main : ajouté au calage au son (le son ne l'efface pas).
        val manual = Webcams(ffmpeg).find(media, WebcamChoice.File(cam), WebcamSettings(offset = 1.seconds))!!
        manual.offset.inWholeMilliseconds shouldBeInRange (-1340L..-1260L)
        // Sans calage au son : seulement le décalage réglé (pas d'heure fiable).
        Webcams(ffmpeg).find(media, WebcamChoice.File(cam), WebcamSettings(offset = 1.seconds, audioSync = false))!!.offset shouldBe 1.seconds
    }

    test("second flux vidéo de la capture : même fichier, même horloge").config(enabledIf = { TestMedia.available }, timeout = 60.seconds) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val file = tempdir().toPath().resolve("obs.mkv")
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-y", "-f", "lavfi", "-i", "color=c=red:s=640x360:r=30:d=3", "-f", "lavfi", "-i", "color=c=blue:s=320x240:r=30:d=3",
                    "-map", "0:v", "-map", "1:v", "-c:v", "libx264", "-preset", "ultrafast", file.toString(),
                ),
                "capture à deux flux",
            ),
        )
        val media = ffmpeg.probe(file)
        val feed = Webcams(ffmpeg).find(media, WebcamChoice.Auto, WebcamSettings(offset = Duration.parse("200ms")))!!
        feed.file shouldBe media.path
        feed.stream shouldBe 1
        feed.width shouldBe 320
        feed.sync shouldBe WebcamSync.SAME_FILE
        feed.offset shouldBe Duration.parse("200ms")
    }
})

/** FFmpeg qui ne doit pas servir (tests sans vidéo). */
private class FakeFfmpeg : dev.highlights.core.ffmpeg.FfmpegService {
    override suspend fun probe(file: Path): MediaInfo = error("probe inattendu : $file")
    override suspend fun run(command: FfmpegCommand, stdout: dev.highlights.core.ffmpeg.StdoutHandler, onStderrLine: ((String) -> Unit)?) = error("run inattendu")
    override suspend fun runProbe(command: FfmpegCommand, stdout: dev.highlights.core.ffmpeg.StdoutHandler) = error("runProbe inattendu")
}
