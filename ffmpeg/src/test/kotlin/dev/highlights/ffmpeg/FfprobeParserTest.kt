package dev.highlights.ffmpeg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds

class FfprobeParserTest : FunSpec({
    // Extrait représentatif d'une capture OBS en MKV : vidéo HEVC + 2 pistes audio titrées.
    val json = """
        {
          "streams": [
            { "index": 0, "codec_name": "hevc", "codec_type": "video", "width": 2560, "height": 1440,
              "pix_fmt": "yuv420p10le", "r_frame_rate": "60/1", "avg_frame_rate": "60/1" },
            { "index": 1, "codec_name": "aac", "codec_type": "audio", "sample_rate": "48000", "channels": 2,
              "tags": { "title": "Game" } },
            { "index": 2, "codec_name": "aac", "codec_type": "audio", "sample_rate": "48000", "channels": 1,
              "tags": { "handler_name": "SoundHandler" } },
            { "index": 3, "codec_name": "mjpeg", "codec_type": "video", "disposition": { "attached_pic": 1 } }
          ],
          "format": { "format_name": "matroska,webm", "duration": "1834.567000", "size": "4294967296",
                      "tags": { "creation_time": "2026-09-16T21:04:05.000000Z" } }
        }
    """.trimIndent()

    test("parse vidéo, pistes audio et métadonnées") {
        val m = FfprobeParser.parse(Path("capture.mkv"), json)
        m.duration shouldBe 1_834_567.milliseconds
        m.sizeBytes shouldBe 4_294_967_296
        m.creationTime shouldBe Instant.parse("2026-09-16T21:04:05Z")
        val video = m.video.shouldNotBeNull()
        video.width shouldBe 2560
        video.fps shouldBe 60.0
        m.audio.map { it.audioIndex } shouldBe listOf(0, 1)
        m.audio.map { it.title } shouldBe listOf("Game", null)
        m.audio[1].channels shouldBe 1
    }

    test("fréquences d'images fractionnaires") {
        FfprobeParser.parseRate("60000/1001")!! shouldBe (59.94 plusOrMinus 0.01)
        FfprobeParser.parseRate("0/0") shouldBe null
    }
})

