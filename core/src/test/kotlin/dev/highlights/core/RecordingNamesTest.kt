package dev.highlights.core

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.Recorder
import dev.highlights.core.model.RecordingNames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecordingNamesTest : FunSpec({
    val utc = ZoneOffset.UTC

    test("FTHRClips : heure de la sauvegarde, donc de la fin du clip, mois en anglais ; suffixe _2 toléré") {
        val named = RecordingNames.parse("VALORANT_clip_from_22Nov2026_21-05-33.mp4", utc)!!
        named.at shouldBe Instant.parse("2026-11-22T21:05:33Z")
        named.recorder shouldBe Recorder.FTHR
        named.start(45.seconds) shouldBe Instant.parse("2026-11-22T21:04:48Z")
        RecordingNames.parse("Counter-Strike 2_clip_from_01Dec2026_00-00-07_2.mp4", utc)!!.at shouldBe Instant.parse("2026-12-01T00:00:07Z")
        RecordingNames.parse("desktop_clip_from_05Mar2027_9-15-00.mp4", utc)!!.at shouldBe Instant.parse("2027-03-05T09:15:00Z")
        // Mois en français si l'application avait changé la locale ; un mois inconnu n'est pas une date.
        RecordingNames.parse("VALORANT_clip_from_22févr.2027_21-05-33.mp4", utc)!!.at shouldBe Instant.parse("2027-02-22T21:05:33Z")
        RecordingNames.parse("VALORANT_clip_from_22Xyz2026_21-05-33.mp4", utc) shouldBe null
    }

    test("Medal et Instant Replay de ShadowPlay : des clips datés de leur sauvegarde") {
        RecordingNames.parse("MedalTVVALORANT20261122210533.mp4", utc) shouldBe
            dev.highlights.core.model.NamedTime(Instant.parse("2026-11-22T21:05:33Z"), Recorder.MEDAL)
        // Un chiffre dans le nom du jeu ne décale pas la date.
        RecordingNames.parse("MedalTVCounterStrike220261122210533.mp4", utc)!!.at shouldBe Instant.parse("2026-11-22T21:05:33Z")
        val replay = RecordingNames.parse("VALORANT 2026.11.17 - 21.08.40.02.DVR.mp4", utc)!!
        replay.recorder shouldBe Recorder.SHADOWPLAY_REPLAY
        replay.recorder.nameMarksEnd shouldBe true
        RecordingNames.parse("VALORANT 2026.11.17 - 21.08.40.02.mp4", utc)!!.recorder shouldBe Recorder.SHADOWPLAY
    }

    test("FTHR, enregistrement sans le jeu dans le nom : heure du début (relevée sur de vrais fichiers)") {
        // 18 033 s nommé 21:15:46, modifié à 02:16 le lendemain ; 24 s nommé 22:28:00, modifié à 22:28:28.
        val long = RecordingNames.parse("FTHR_recording_2026-11-20_21-15-46.mp4", utc)!!
        long.recorder shouldBe Recorder.FTHR_RECORDING
        long.start(18033.seconds) shouldBe Instant.parse("2026-11-20T21:15:46Z")
        RecordingNames.parse("FTHR_recording_2026-11-22_22-28-00.mp4", utc)!!.start(24.seconds) shouldBe Instant.parse("2026-11-22T22:28:00Z")
    }

    test("parties entières : Outplayed, OBS, ShadowPlay gardent l'heure de leur début") {
        RecordingNames.parse("WARDOGS_09-22-2026_22-55-31-983.mp4", utc)!!.recorder shouldBe Recorder.OUTPLAYED
        RecordingNames.parse("2026-11-17 21-08-40.mkv", utc)!!.recorder shouldBe Recorder.OBS
        RecordingNames.parse("2026-11-17 21-08-40.mkv", utc)!!.start(40.minutes) shouldBe Instant.parse("2026-11-17T21:08:40Z")
        RecordingNames.parse("Valorant- 44 kills.mp4", utc) shouldBe null
    }

    test("clip ou partie : nom d'un logiciel de clips, ou moins de 5 minutes") {
        MediaInfo.isClip("MedalTVVALORANT20261122210533.mp4", 8.minutes) shouldBe true
        // FTHR fait des clips et des parties de plusieurs heures : la durée seule décide.
        MediaInfo.isClip("VALORANT_clip_from_22Nov2026_21-05-33.mp4", 8.minutes) shouldBe false
        MediaInfo.isClip("FTHR_recording_2026-11-22_22-28-00.mp4", 24.seconds) shouldBe true
        MediaInfo.isClip("FTHR_recording_2026-11-20_21-15-46.mp4", 18033.seconds) shouldBe false
        MediaInfo.isClip("2026-11-17 21-08-40.mkv", 90.seconds) shouldBe true
        MediaInfo.isClip("2026-11-17 21-08-40.mkv", 38.minutes) shouldBe false
        // Un clip FTHRClips commence sa durée avant l'heure de son nom, quelles que soient ses métadonnées.
        val media = MediaInfo(Path("VALORANT_clip_from_22Nov2026_21-05-33.mp4"), 1, 60.seconds, creationTime = Instant.parse("2026-11-22T20:05:34Z"))
        media.isClip shouldBe true
        media.reliableTime shouldBe true
        media.recordedAt shouldBe java.time.LocalDateTime.of(2026, 11, 22, 21, 4, 33).atZone(java.time.ZoneId.systemDefault()).toInstant()
    }
})
