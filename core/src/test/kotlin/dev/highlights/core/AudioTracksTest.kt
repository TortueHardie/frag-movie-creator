package dev.highlights.core

import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioSelection
import dev.highlights.core.model.AudioSourceKind
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.TrackContent
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

    test("le résumé des pistes est traduit : il finit dans le message « aucun moment retenu »") {
        // Avant : « 1 piste : a:0 → mix/game (piste unique : tout le son) » dans toutes les langues.
        AudioTracks.of(listOf(stream(0))).describe().english() shouldBe "1 track: a:0 → full mix/game (single track: all sound)"
        AudioTracks.of(listOf(stream(0), stream(1)), AudioLayout(mic = 1)).describe().text(java.util.Locale.GERMAN) shouldBe
            "2 Spuren: a:0 → Spiel/Gesamtmix; a:1 → Mikro (zwei Spuren: Spiel, dann Mikro, beim Export gemischt, Spurnummern aus dem Profil)"
        AudioTracks.EMPTY.describe().english() shouldBe "no audio track"
    }
    test("FTHRClips, un appareil par piste (SteelSeries Sonar) : jeu, chat et micro reconnus à leur nom, tout mélangé à l'export") {
        val tracks = AudioTracks.of(listOf(
            stream(0, "Gaming"),
            stream(1, "Chat"),
            stream(2, "SteelSeries Sonar - Microphone (SteelSeries Sonar Virtual Audio Device)"),
        ))
        tracks.indexOf(AudioRole.GAME) shouldBe 0
        tracks.indexOf(AudioRole.CHAT) shouldBe 1
        tracks.indexOf(AudioRole.MIC) shouldBe 2
        tracks.mixIndices() shouldBe listOf(0, 1, 2)
        tracks.shortLabel() shouldBe "3 pistes audio : jeu, micro et chat vocal séparés"
    }

    test("FTHRClips décrit ses pistes : le jeu est sa piste d'application, pas « Default Mix » qui porte aussi Discord") {
        val streams = listOf(
            stream(0, "Default Mix").copy(source = AudioSourceKind.SYSTEM),
            stream(1, "Microphone").copy(source = AudioSourceKind.MICROPHONE),
            stream(2, "VALORANT").copy(source = AudioSourceKind.APPLICATION),
            stream(3, "Discord").copy(source = AudioSourceKind.APPLICATION),
        )
        val tracks = AudioTracks.of(streams)
        tracks.indexOf(AudioRole.GAME) shouldBe 2
        tracks.indexOf(AudioRole.MIC) shouldBe 1
        tracks.indexOf(AudioRole.CHAT) shouldBe 3
        // Les pistes d'application sont déjà dans « Default Mix » : les ajouter doublerait le jeu et Discord.
        tracks.mixIndices() shouldBe listOf(0, 1)
        tracks.hasPremix shouldBe false
    }

    test("ancien clip FTHRClips sans description : « Default Mix » n'est pas un mix complet, le micro s'y ajoute") {
        val tracks = AudioTracks.of(listOf(stream(0, "Default Mix"), stream(1, "Microphone")))
        tracks.indexOf(AudioRole.GAME) shouldBe 0
        tracks.indexOf(AudioRole.MIC) shouldBe 1
        tracks.mixIndices() shouldBe listOf(0, 1)
    }

    test("titres : un mot ne compte qu'en début de mot, chat avant micro, langues de l'application") {
        AudioTracks.roleOfTitle("Call of Duty").shouldBeNull()
        AudioTracks.roleOfTitle("Voice Chat") shouldBe AudioRole.CHAT
        AudioTracks.roleOfTitle("Discord") shouldBe AudioRole.CHAT
        AudioTracks.roleOfTitle("Mic/Aux") shouldBe AudioRole.MIC
        AudioTracks.roleOfTitle("Desktop Audio") shouldBe AudioRole.GAME
        AudioTracks.roleOfTitle("Sonar - Gaming") shouldBe AudioRole.GAME
        AudioTracks.roleOfTitle("Микрофон") shouldBe AudioRole.MIC
        AudioTracks.roleOfTitle("ゲーム音声") shouldBe AudioRole.GAME
        AudioTracks.roleOfTitle("Track 2").shouldBeNull()
    }

    test("sans titre, à l'écoute : la piste qui contient les autres est le mix, celle qui sonne en continu le jeu") {
        val tracks = AudioTracks.of(listOf(
            stream(0).copy(content = TrackContent(active = 0.97, contains = listOf(1, 2))),
            stream(1).copy(content = TrackContent(active = 0.95)),
            stream(2).copy(content = TrackContent(active = 0.25)),
        ))
        tracks.indexOf(AudioRole.MIX) shouldBe 0
        tracks.indexOf(AudioRole.GAME) shouldBe 1
        tracks.indexOf(AudioRole.MIC) shouldBe 2
        tracks.mixIndices() shouldBe listOf(0)
    }

    test("à l'écoute, sans mix : la voix vient avant le jeu dans le fichier, le jeu est quand même reconnu") {
        val tracks = AudioTracks.of(listOf(
            stream(0).copy(content = TrackContent(active = 0.3)),
            stream(1).copy(content = TrackContent(active = 0.98)),
        ))
        tracks.indexOf(AudioRole.GAME) shouldBe 1
        tracks.indexOf(AudioRole.MIC) shouldBe 0
        tracks.mixIndices() shouldBe listOf(0, 1)
    }

    test("pistes nommées : le jeu qui ressemble au mix garde son rôle (Outplayed, joueur silencieux)") {
        val tracks = AudioTracks.of(listOf(
            stream(0, "Mix").copy(content = TrackContent(active = 0.97)),
            stream(1, "Game").copy(content = TrackContent(active = 0.97, duplicateOf = 0)),
            stream(2, "Mic").copy(content = TrackContent(active = 0.0)),
        ))
        tracks.indexOf(AudioRole.MIX) shouldBe 0
        tracks.indexOf(AudioRole.GAME) shouldBe 1
        tracks.indexOf(AudioRole.MIC) shouldBe 2
        tracks.mixIndices() shouldBe listOf(0)
    }

    test("pistes nommées : une copie et une piste muette gardent leur rôle mais la copie n'est pas mélangée deux fois") {
        val tracks = AudioTracks.of(listOf(
            stream(0, "Gaming").copy(content = TrackContent(active = 0.97)),
            stream(1, "Default Mix").copy(content = TrackContent(active = 0.97, duplicateOf = 0)),
            stream(2, "Microphone").copy(content = TrackContent(active = 0.0)),
            stream(3, "Chat").copy(content = TrackContent(active = 0.2)),
        ))
        tracks.indexOf(AudioRole.GAME) shouldBe 0
        tracks.indexOf(AudioRole.MIC) shouldBe 2
        tracks.indexOf(AudioRole.CHAT) shouldBe 3
        tracks.mixIndices() shouldBe listOf(0, 2, 3)
    }
})
