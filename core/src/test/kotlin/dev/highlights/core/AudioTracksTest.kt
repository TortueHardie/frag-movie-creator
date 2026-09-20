package dev.highlights.core

import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioSelection
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AudioTracksTest : FunSpec({
    fun stream(index: Int, title: String? = null, channels: Int = 2) =
        AudioStream(index + 1, index, "aac", channels, 48000, title)

    test("capture à piste unique (OBS simple, console) : tout le son, pas de micro séparé") {
        val tracks = AudioTracks.of(listOf(stream(0)))
        tracks.indexOf(AudioRole.MIX) shouldBe 0
        tracks.indexOf(AudioRole.GAME) shouldBe 0
        tracks[AudioRole.MIC].shouldBeNull()
        tracks.mixIndices() shouldBe listOf(0)
    }

    test("deux pistes (OBS jeu + micro) : le micro est la seconde, et le montage mélange les deux") {
        val tracks = AudioTracks.of(listOf(stream(0), stream(1)))
        tracks.indexOf(AudioRole.GAME) shouldBe 0
        tracks.indexOf(AudioRole.MIC) shouldBe 1
        // Aucune piste ne contient déjà tout le son : l'export doit mélanger, sans quoi la voix disparaîtrait.
        tracks.mixIndices() shouldBe listOf(0, 1)
    }

    test("trois pistes (Outplayed) : mix, jeu, micro ; le montage garde le seul mix") {
        val tracks = AudioTracks.of(listOf(stream(0), stream(1), stream(2)))
        tracks.indexOf(AudioRole.MIX) shouldBe 0
        tracks.indexOf(AudioRole.GAME) shouldBe 1
        tracks.indexOf(AudioRole.MIC) shouldBe 2
        tracks.mixIndices() shouldBe listOf(0)
    }

    test("titres des pistes : ils l'emportent sur l'ordre") {
        val tracks = AudioTracks.of(listOf(stream(0, "Mic/Aux"), stream(1, "Desktop Audio")))
        tracks.indexOf(AudioRole.MIC) shouldBe 0
        tracks.indexOf(AudioRole.GAME) shouldBe 1
        tracks.mixIndices() shouldBe listOf(0, 1)
    }

    test("un titre « mix » évite de doubler le son à l'export") {
        val tracks = AudioTracks.of(listOf(stream(0, "Mix complet"), stream(1, "Micro")))
        tracks.indexOf(AudioRole.MIX) shouldBe 0
        tracks.mixIndices() shouldBe listOf(0)
    }

    test("un seul titre reconnu n'apprend rien : l'ordre décide") {
        val tracks = AudioTracks.of(listOf(stream(0, "track1"), stream(1, "Mic"), stream(2, "track3")))
        tracks.indexOf(AudioRole.MIX) shouldBe 0
        tracks.indexOf(AudioRole.MIC) shouldBe 2
    }

    test("indices imposés par le profil : dernier mot, index inexistant ignoré") {
        val streams = listOf(stream(0), stream(1), stream(2))
        AudioTracks.of(streams, AudioLayout(game = 2, mic = 1)).let {
            it.indexOf(AudioRole.GAME) shouldBe 2
            it.indexOf(AudioRole.MIC) shouldBe 1
        }
        AudioTracks.of(streams, AudioLayout(mic = 9)).indexOf(AudioRole.MIC) shouldBe 2
    }

    test("aucune piste audio") {
        val tracks = AudioTracks.of(emptyList())
        tracks.mixIndices() shouldBe emptyList()
        tracks[AudioRole.GAME].shouldBeNull()
    }

    test("résumé court affiché par l'interface") {
        AudioTracks.of(listOf(stream(0))).shortLabel() shouldBe "1 piste audio : tout le son"
        AudioTracks.of(listOf(stream(0), stream(1))).shortLabel() shouldBe "2 pistes audio : jeu + micro séparés"
        AudioTracks.of(emptyList()).shortLabel() shouldBe "aucune piste audio"
    }

    test("choix des pistes du montage selon le réglage du profil") {
        val outplayed = AudioTracks.of(listOf(stream(0), stream(1), stream(2)))
        val obs = AudioTracks.of(listOf(stream(0), stream(1)))

        EditSettings().audioIndices(outplayed) shouldBe listOf(0)
        EditSettings().audioIndices(obs) shouldBe listOf(0, 1)
        EditSettings(audio = AudioSelection.ALL).audioIndices(outplayed) shouldBe listOf(0, 1, 2)
        EditSettings(audio = AudioSelection.GAME).audioIndices(outplayed) shouldBe listOf(1)
        EditSettings(audio = AudioSelection.MIC).audioIndices(obs) shouldBe listOf(1)
        EditSettings(audio = AudioSelection.MIC).audioIndices(AudioTracks.of(listOf(stream(0)))) shouldBe emptyList()
        // Index imposés, mais seulement ceux qui existent ; aucun ne convient = on retombe sur le rôle.
        EditSettings(audioStreams = listOf(1, 7)).audioIndices(outplayed) shouldBe listOf(1)
        EditSettings(audioStreams = listOf(7)).audioIndices(outplayed) shouldBe listOf(0)
    }
})
