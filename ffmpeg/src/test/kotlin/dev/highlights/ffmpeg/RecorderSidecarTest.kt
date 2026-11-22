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

    test("description d'un autre fichier ou d'une autre version : ignorée") {
        RecorderSidecar.parse(manifest, "autre.mp4").shouldBeEmpty()
        RecorderSidecar.parse(manifest.replace("\"manifest_version\": 1", "\"manifest_version\": 2"), "VALORANT_clip_from_x.mp4").shouldBeEmpty()
    }
})
