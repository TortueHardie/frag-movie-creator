package dev.highlights.ffmpeg

import dev.highlights.core.model.AudioSourceKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

class RecorderSidecarTest : FunSpec({
    // Forme écrite par FTHRClips (FTHR_UI/core/audio_manifest.py, version 1), raccourcie aux champs lus.
    val manifest = """
        {"manifest_version": 1, "transaction_id": "0d7c2f0e-6a0e-4c1b-9d55-2f0f3c1b7a10", "media_file": "VALORANT_clip_from_x.mp4",
         "media_sha256": "${"0".repeat(64)}",
         "sources": [
           {"source_uuid": "a", "stream_index": 1, "source_type": "system", "display_name": "Default Mix", "sample_rate": 48000, "channels": 2},
           {"source_uuid": "b", "stream_index": 2, "source_type": "microphone", "display_name": "Microphone", "sample_rate": 48000, "channels": 1},
           {"source_uuid": "c", "stream_index": 3, "source_type": "application", "display_name": "Discord", "sample_rate": 48000, "channels": 2}
         ]}
    """.trimIndent()

    test("description FTHRClips : nature et nom de chaque piste, par index dans le conteneur") {
        val declared = RecorderSidecar.parse(manifest, "VALORANT_clip_from_x.mp4")
        declared[1] shouldBe RecorderSidecar.Declared(AudioSourceKind.SYSTEM, "Default Mix")
        declared[2] shouldBe RecorderSidecar.Declared(AudioSourceKind.MICROPHONE, "Microphone")
        declared[3] shouldBe RecorderSidecar.Declared(AudioSourceKind.APPLICATION, "Discord")
    }

    test("handler_name de FTHRClips (vrais enregistrements du 22/11) : nom de la piste, type « system » laissé au nom") {
        fun h(type: String, name: String) = "FTHR3|B53668FB-68C4-47BC-A048-A0BB2F3AADD1|$type|100|0|1046:1147296|" + name.padEnd(120)
        RecorderSidecar.fromHandler(h("system", "SteelSeries Sonar - Microphone (SteelSer")) shouldBe
            RecorderSidecar.Declared(null, "SteelSeries Sonar - Microphone (SteelSer")
        RecorderSidecar.fromHandler(h("system", "Chat")) shouldBe RecorderSidecar.Declared(null, "Chat")
        RecorderSidecar.fromHandler(h("microphone", "Microphone")) shouldBe RecorderSidecar.Declared(AudioSourceKind.MICROPHONE, "Microphone")
        RecorderSidecar.fromHandler("SoundHandler") shouldBe null
        // Lu par la sonde : titre = nom de la piste ; rôles d'après ces noms, dans l'ordre où FTHRClips les écrit.
        val probe = """
            {"format": {"duration": "91.2", "format_name": "mov,mp4"},
             "streams": [
               {"index": 0, "codec_type": "video", "codec_name": "h264", "width": 3440, "height": 1440, "avg_frame_rate": "60/1"},
               {"index": 1, "codec_type": "audio", "codec_name": "pcm_f32le", "channels": 2, "sample_rate": "48000", "tags": {"handler_name": "${h("system", "SteelSeries Sonar - Microphone (SteelSer")}"}},
               {"index": 2, "codec_type": "audio", "codec_name": "pcm_f32le", "channels": 2, "sample_rate": "48000", "tags": {"handler_name": "${h("system", "Gaming")}"}},
               {"index": 3, "codec_type": "audio", "codec_name": "pcm_f32le", "channels": 2, "sample_rate": "48000", "tags": {"handler_name": "${h("system", "Chat")}"}}
             ]}
        """.trimIndent()
        val media = FfprobeParser.parse(java.nio.file.Path.of("FTHR_recording_2026-11-22_22-28-33.mp4"), probe)
        media.audio.map { it.title } shouldBe listOf("SteelSeries Sonar - Microphone (SteelSer", "Gaming", "Chat")
        val tracks = dev.highlights.core.model.AudioTracks.of(media.audio)
        tracks.indexOf(dev.highlights.core.model.AudioRole.MIC) shouldBe 0
        tracks.indexOf(dev.highlights.core.model.AudioRole.GAME) shouldBe 1
        tracks.indexOf(dev.highlights.core.model.AudioRole.CHAT) shouldBe 2
        tracks.mixIndices() shouldBe listOf(0, 1, 2)
    }

    test("description d'un autre fichier ou d'une autre version : ignorée") {
        RecorderSidecar.parse(manifest, "autre.mp4").shouldBeEmpty()
        RecorderSidecar.parse(manifest.replace("\"manifest_version\": 1", "\"manifest_version\": 2"), "VALORANT_clip_from_x.mp4").shouldBeEmpty()
    }
})
