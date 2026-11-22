package dev.highlights.pipeline

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class TrackListenerIT : FunSpec({
    test("énergies seules : copie, piste muette et mix reconnus") {
        val game = DoubleArray(400) { 0.01 + 0.01 * (it % 7) }
        val voice = DoubleArray(400) { if (it % 10 < 3) 0.02 else 0.0 }
        val mix = DoubleArray(400) { game[it] + voice[it] }
        val copy = DoubleArray(400) { game[it] * 0.5 }
        val mute = DoubleArray(400)
        val c = TrackListener.analyze(listOf(mix, game, voice, copy, mute))
        c[0].contains shouldBe listOf(1, 2)
        c[1].contains shouldBe emptyList()
        c[3].duplicateOf shouldBe 1
        c[4].silent shouldBe true
        c[2].active shouldBeLessThan 0.5
    }

    test("vraie capture sans titres : mix, jeu, micro et une copie du jeu retrouvés à l'écoute").config(enabledIf = { TestMedia.available }) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val file = tempdir().toPath().resolve("pistes.mp4")
        // Jeu : bruit dont le niveau varie sans cesse ; micro : une note qui parle 30 % du temps ; mix = jeu + micro ;
        // a:3 = le jeu une seconde fois (la « Default Mix » de FTHRClips quand le son par défaut est l'appareil du jeu).
        ffmpeg.run(FfmpegCommand(listOf(
            "-y", "-f", "lavfi", "-i", "color=black:s=320x180:r=30:d=70",
            "-f", "lavfi", "-i", "anoisesrc=d=70:c=pink:a=0.3:r=48000",
            "-f", "lavfi", "-i", "sine=f=300:d=70:r=48000",
            "-filter_complex",
            "[1:a]volume='0.2+0.8*abs(sin(t*1.3))':eval=frame,asplit=3[g1][g2][g3];" +
                "[2:a]volume='if(lt(mod(t\\,4)\\,1.2)\\,0.8\\,0)':eval=frame,asplit=2[m1][m2];" +
                "[g2][m1]amix=inputs=2:normalize=0[mix]",
            "-map", "0:v", "-map", "[mix]", "-map", "[g1]", "-map", "[m2]", "-map", "[g3]",
            "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-b:a", "128k", file.toString(),
        ), "pistes de test"))

        val media = TrackListener(ffmpeg).listen(ffmpeg.probe(file))
        val c = media.audio.map { it.content!! }
        c[0].contains shouldBe listOf(1, 2)
        c[3].duplicateOf shouldBe 1
        c[1].active shouldBeGreaterThan 0.9
        c[2].active shouldBeLessThan 0.5

        val tracks = AudioTracks.of(media.audio)
        tracks.indexOf(AudioRole.MIX) shouldBe 0
        tracks.indexOf(AudioRole.GAME) shouldBe 1
        tracks.indexOf(AudioRole.MIC) shouldBe 2
        tracks[AudioRole.CHAT].shouldBeNull()
        tracks.mixIndices() shouldBe listOf(0)
    }
})
