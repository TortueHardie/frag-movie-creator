package dev.highlights.ui

import dev.highlights.core.profile.ProfileRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/** Détection des enregistreurs sur une fausse machine (dossiers et réglages écrits par le test, registre simulé). */
class RecordersTest : FunSpec({
    fun Path.write(text: String): Path = also { it.parent.createDirectories(); it.writeText(text) }

    test("rien d'installé : aucun enregistreur") {
        val root = tempdir().toPath()
        RecorderDetector(RecorderEnvironment.fake(root)).detect().shouldBeEmpty()
    }

    test("OBS en mode Avancé, profil de user.ini, 3 pistes : dossier lu et micro séparé") {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        val obs = env.appData.resolve("obs-studio")
        obs.resolve("user.ini").write("[Basic]\nProfile=Sans nom\nProfileDir=Sans_nom\n")
        obs.resolve("global.ini").write("[Basic]\nProfileDir=Ancien\n")
        obs.resolve("basic/profiles/Sans_nom/basic.ini").write(
            "\uFEFF[Output]\nMode=Advanced\n[SimpleOutput]\nFilePath=C:\\\\Simple\nRecTracks=1\n[AdvOut]\nRecFilePath=D:\\\\Captures\\\\OBS\nRecTracks=7\n",
        )
        val found = RecorderDetector(env).obs()!!
        found.folder shouldBe Path.of("D:\\Captures\\OBS")
        found.separateMic shouldBe true
        found.fromSettings shouldBe true
    }

    test("OBS en mode Simple, une piste ; sortie FFmpeg en Avancé : FFFilePath") {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        val obs = env.appData.resolve("obs-studio")
        obs.resolve("global.ini").write("[Basic]\nProfileDir=P\n")
        val ini = obs.resolve("basic/profiles/P/basic.ini").write("[Output]\nMode=Simple\n[SimpleOutput]\nFilePath=E:\\\\Videos\nRecTracks=1\n")
        RecorderDetector(env).obs()!!.let { it.folder shouldBe Path.of("E:\\Videos"); it.separateMic shouldBe false }
        ini.writeText("[Output]\nMode=Advanced\n[AdvOut]\nRecType=FFmpeg\nRecFilePath=X:\\\\Non\nFFFilePath=F:\\\\Ff\nRecTracks=3\n")
        RecorderDetector(env).obs()!!.folder shouldBe Path.of("F:\\Ff")
    }

    test("OBS sans profil lisible : dossier Vidéos par défaut, pistes inconnues") {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        env.appData.resolve("obs-studio").createDirectories()
        val found = RecorderDetector(env).obs()!!
        found.folder shouldBe env.home.resolve("Videos")
        found.fromSettings shouldBe false
        found.separateMic.shouldBeNull()
    }

    test("Outplayed : dossier des captures d'Overwolf + Outplayed, sinon Vidéos\\Overwolf\\Outplayed") {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        val overwolf = env.localAppData.resolve("Overwolf")
        overwolf.resolve("Extensions/${RecorderDetector.OUTPLAYED_ID}").createDirectories()
        RecorderDetector(env).outplayed()!!.folder shouldBe env.home.resolve("Videos/Overwolf/Outplayed")
        overwolf.resolve("Settings/SettingsPageGeneral.xml").write(
            """<?xml version="1.0" encoding="utf-8"?>
<Array>
  <PropertyNameValue>
    <Value Internal_Type="String">G:\Jeux &amp; vidéos</Value>
    <Name>Folders_VideoCapturesFolder</Name>
  </PropertyNameValue>
  <PropertyNameValue>
    <Value Internal_Type="String">C:\Pictures\Overwolf</Value>
    <Name>Folders_ScreenCapturesFolder</Name>
  </PropertyNameValue>
</Array>""",
        )
        val found = RecorderDetector(env).outplayed()!!
        found.folder shouldBe Path.of("G:\\Jeux & vidéos").resolve("Outplayed")
        found.fromSettings shouldBe true
    }

    test("ShadowPlay : chemin UTF-16 de DefaultPathW dans le registre") {
        val root = tempdir().toPath()
        val bytes = "D:\\NVIDIA\u0000\u0000".toByteArray(Charsets.UTF_16LE)
        val env = RecorderEnvironment.fake(root).let { e ->
            RecorderEnvironment(e.home, e.appData, e.localAppData, e.programFiles, e.systemDrive) { key, value ->
                if (key == RecorderDetector.NVIDIA_KEY && value == "DefaultPathW") RegistryValue.Binary(bytes) else null
            }
        }
        RecorderDetector(env).shadowPlay()!!.folder shouldBe Path.of("D:\\NVIDIA")
        // Application NVIDIA installée sans dossier choisi : Vidéos.
        val plain = RecorderEnvironment.fake(root)
        plain.programFiles.first().resolve("NVIDIA Corporation/NVIDIA app").createDirectories()
        RecorderDetector(plain).shadowPlay()!!.let { it.folder shouldBe plain.home.resolve("Videos"); it.fromSettings shouldBe false }
    }

    test("lecture de la sortie de reg query (binaire et texte)") {
        val out = "\r\nHKEY_CURRENT_USER\\Software\\NVIDIA Corporation\\Global\\ShadowPlay\\NVSPCAPS\r\n    DefaultPathW    REG_BINARY    44003A005C00560000000000\r\n\r\n"
        val v = WindowsRegistry.parse(out, "DefaultPathW") as RegistryValue.Binary
        String(v.bytes, Charsets.UTF_16LE).substringBefore('\u0000') shouldBe "D:\\V"
        (WindowsRegistry.parse("    Dossier    REG_SZ    C:\\Mes vidéos\r\n", "Dossier") as RegistryValue.Text).text shouldBe "C:\\Mes vidéos"
        WindowsRegistry.parse("rien", "DefaultPathW").shouldBeNull()
    }

    test("FTHR Clips : ~/.fthr/settings.json, dossier des clips et mode audio séparé") {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        val settings = env.home.resolve(".fthr/settings.json").write("""{"clips_directory": "H:\\Clips", "audio_capture_mode": "separated"}""")
        RecorderDetector(env).fthr()!!.let {
            it.folders shouldBe listOf(Path.of("H:\\Clips"))
            it.separateMic shouldBe true
        }
        settings.writeText("""{"clips_directory": "H:\\Clips", "recording_directory": "I:\\Rec"}""")
        RecorderDetector(env).fthr()!!.let {
            it.folders shouldBe listOf(Path.of("I:\\Rec"), Path.of("H:\\Clips"))
            it.separateMic shouldBe false
        }
        settings.writeText("illisible")
        RecorderDetector(env).fthr()!!.let {
            it.folder shouldBe env.home.resolve("FTHR_Clips")
            it.separateMic.shouldBeNull()
        }
    }

    test("Medal : clé de dossier existant dans store, sinon dossier commun de clips.json, sinon C:\\Medal") {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        val store = env.appData.resolve("Medal/store").createDirectories()
        RecorderDetector(env).medal()!!.let { it.folder shouldBe env.systemDrive.resolve("Medal"); it.fromSettings shouldBe false }
        val clipsDir = root.resolve("MedalClips/VALORANT").createDirectories()
        store.resolve("clips.json").write(
            """{"a":{"FilePath":"${clipsDir.resolve("a.mp4").toString().replace("\\", "\\\\")}"},"b":{"FilePath":"${clipsDir.resolve("b.mp4").toString().replace("\\", "\\\\")}"}}""",
        )
        RecorderDetector(env).medal()!!.folder shouldBe clipsDir
        val chosen = root.resolve("Choisi").createDirectories()
        store.resolve("settings.json").write("""{"recorder":{"clipFolder":"${chosen.toString().replace("\\", "\\\\")}","logPath":"Z:\\absent"}}""")
        RecorderDetector(env).medal()!!.folder shouldBe chosen
    }

    test("vidéos d'un dossier : plus récentes d'abord, dossiers de travail écartés") {
        val root = tempdir().toPath()
        val a = root.resolve("VALORANT/a.mp4").write("x")
        val b = root.resolve("VALORANT/b.mkv").write("x")
        root.resolve("temp-capture/c.mp4").write("x")
        root.resolve("notes.txt").write("x")
        Files.setLastModifiedTime(a, FileTime.from(Instant.parse("2026-11-20T20:00:00Z")))
        Files.setLastModifiedTime(b, FileTime.from(Instant.parse("2026-11-20T21:00:00Z")))
        RecordingScan.videos(root).map { it.path } shouldBe listOf(b, a).map { it.toAbsolutePath().normalize() }
        RecordingScan.videos(root.resolve("absent")).shouldBeEmpty()
    }

    test("premier montage : dernière soirée d'un jeu reconnu, 5 parties au plus, dans l'ordre joué") {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        fun v(name: String, at: String) = FoundVideo(Path.of(name), Instant.parse(at))
        val profileOf = { p: Path -> if (p.toString().startsWith("VAL")) "valorant" else ProfileRepository.DEFAULT_ID }
        val videos = listOf(
            v("autre-recente.mp4", "2026-11-21T11:00:00Z"),
            v("VAL7.mp4", "2026-11-20T23:00:00Z"),
            v("VAL6.mp4", "2026-11-20T22:30:00Z"),
            v("VAL5.mp4", "2026-11-20T22:00:00Z"),
            v("VAL4.mp4", "2026-11-20T21:30:00Z"),
            v("VAL3.mp4", "2026-11-20T21:00:00Z"),
            v("VAL2.mp4", "2026-11-20T20:30:00Z"),
            v("VAL1.mp4", "2026-11-18T20:00:00Z"),
        )
        FirstMontage.pick(videos, profileOf, now).map { it.toString() } shouldBe listOf("VAL3.mp4", "VAL4.mp4", "VAL5.mp4", "VAL6.mp4", "VAL7.mp4")
        // Écart de plus de 3 h : autre soirée.
        FirstMontage.pick(listOf(v("VAL9.mp4", "2026-11-20T23:00:00Z"), v("VAL8.mp4", "2026-11-20T18:00:00Z")), profileOf, now).map { it.toString() } shouldBe listOf("VAL9.mp4")
        // Aucun jeu reconnu : les vidéos telles quelles ; une capture qui s'écrit encore est laissée.
        FirstMontage.pick(listOf(v("x.mp4", "2026-11-21T11:59:50Z"), v("y.mp4", "2026-11-21T11:00:00Z")), profileOf, now).map { it.toString() } shouldBe listOf("y.mp4")
        FirstMontage.pick(emptyList(), profileOf, now).shouldBeEmpty()
    }

    test("contrôleur : écran rouvert depuis le menu, dossier proposé = vidéo la plus récente, Plus tard surveille et retient") {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        val obsFolder = root.resolve("obs-videos").createDirectories()
        env.appData.resolve("obs-studio/global.ini").write("[Basic]\nProfileDir=P\n")
        env.appData.resolve("obs-studio/basic/profiles/P/basic.ini").write("[Output]\nMode=Simple\n[SimpleOutput]\nFilePath=${obsFolder.toString().replace("\\", "\\\\")}\n")
        Files.setLastModifiedTime(obsFolder.resolve("2026-11-20 21-00-00.mp4").also { it.writeBytes(byteArrayOf(1)) }, FileTime.from(Instant.parse("2026-11-20T21:00:00Z")))
        env.localAppData.resolve("Overwolf/Extensions/${RecorderDetector.OUTPLAYED_ID}").createDirectories()

        val firstRun = FirstRunPrefsStore(root.resolve("prefs/first-run.json"))
        val watch = WatchPrefsStore(root.resolve("prefs/watch.json"))
        val platform = RecordingPlatform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppController(
            scope, platform, watch,
            musicPrefs = MusicPrefsStore(root.resolve("prefs/music.json")),
            languagePrefs = LanguagePrefsStore(root.resolve("prefs/language.json")),
            introPrefs = IntroPrefsStore(root.resolve("prefs/intro.json")),
            firstRunPrefs = firstRun,
            recorders = { RecorderDetector(env) },
            backendFactory = { error("pas de config") },
        )
        try {
            firstRun.done() shouldBe false
            controller.showSetup()
            val shown = withTimeout(30.seconds) { controller.state.first { it.setup?.loading == false } }.setup!!
            shown.firstRun shouldBe false
            shown.recorders.map { it.kind } shouldBe listOf(RecorderKind.OUTPLAYED, RecorderKind.OBS)
            // OBS a une vidéo, Outplayed aucune : OBS est proposé.
            shown.folder shouldBe obsFolder
            shown.chosen!!.videos.size shouldBe 1
            shown.watch shouldBe true

            controller.setSetupPage(SetupPage.TIPS)
            controller.state.value.setup!!.page shouldBe SetupPage.TIPS
            controller.setSetupPage(SetupPage.WELCOME)
            controller.openRecorderSite(RecorderKind.OBS)

            // Un autre dossier, choisi à la main.
            val other = root.resolve("mes-parties").createDirectories()
            platform.directory = other
            controller.chooseOtherSetupFolder()
            val withOther = withTimeout(30.seconds) { controller.state.first { it.setup?.loading == false && it.setup.other != null } }.setup!!
            withOther.folder shouldBe other
            withOther.canStart shouldBe false
            controller.chooseSetupFolder(obsFolder)

            controller.closeSetup()
            controller.state.value.setup.shouldBeNull()
            firstRun.done() shouldBe true
            watch.load()!!.folder shouldBe obsFolder.toAbsolutePath().normalize()
        } finally {
            controller.shutdown()
            scope.cancel()
        }
        root.resolve("prefs/first-run.json").exists() shouldBe true
    }
})
