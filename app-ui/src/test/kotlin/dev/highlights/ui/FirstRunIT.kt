package dev.highlights.ui

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.pipeline.Pipelines
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Premier lancement de bout en bout : fausse machine avec OBS qui enregistre dans un dossier WARDOGS → l'écran s'ouvre
 * de lui-même, trouve OBS, le jeu et la partie → « Faire mon premier montage » analyse puis ouvre le parcours du montage.
 * Relancée, l'application ne le montre plus.
 */
class FirstRunIT : FunSpec({
    test("du premier lancement au parcours du montage").config(enabledIf = { TestMedia.available }, timeout = 5.seconds * 60) {
        val root = tempdir().toPath()
        val env = RecorderEnvironment.fake(root)
        val videos = root.resolve("Captures OBS").createDirectories()
        val video = TestMedia.generate(
            videos.resolve("WARDOGS").createDirectories().resolve("2026-11-20 21-03-11.mp4"),
            durationSeconds = 60,
            audioTracks = listOf(
                TestMedia.AudioTrackSpec("Mix", listOf(15.0..17.0, 42.0..44.0)),
                TestMedia.AudioTrackSpec("Game", listOf(15.0..17.0, 42.0..44.0)),
                TestMedia.AudioTrackSpec("Mic", listOf(43.0..44.0), frequency = 300),
            ),
        )
        Files.setLastModifiedTime(video, FileTime.from(Instant.now().minusSeconds(600)))
        env.appData.resolve("obs-studio/basic/profiles/P").createDirectories()
        env.appData.resolve("obs-studio/user.ini").writeText("[Basic]\nProfileDir=P\n")
        env.appData.resolve("obs-studio/basic/profiles/P/basic.ini")
            .writeText("[Output]\nMode=Advanced\n[AdvOut]\nRecFilePath=${videos.toString().replace("\\", "\\\\")}\nRecTracks=7\n")

        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                workDir = root.resolve("work").toString(),
            ),
            baseDir = Path("../config").toAbsolutePath().normalize(),
            source = null,
        )
        val ffmpeg = TestMedia.requireFfmpeg()
        val prefs = root.resolve("prefs")
        fun start(scope: CoroutineScope) = AppController(
            scope, RecordingPlatform(), WatchPrefsStore(prefs.resolve("watch.json")),
            musicPrefs = MusicPrefsStore(prefs.resolve("music.json")),
            languagePrefs = LanguagePrefsStore(prefs.resolve("language.json")),
            introPrefs = IntroPrefsStore(prefs.resolve("intro.json")),
            firstRunPrefs = FirstRunPrefsStore(prefs.resolve("first-run.json")),
            recorders = { RecorderDetector(env) },
        ) { Backend(Pipelines.create(config, ffmpeg), Path("app.yaml")) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = start(scope)
        suspend fun await(what: String, predicate: (UiState) -> Boolean): UiState =
            withTimeout(180.seconds) { controller.state.first { s -> s.error?.let { error("$what : ${it.title} — ${it.message}") }; predicate(s) } }
        try {
            val setup = await("écran de premier lancement") { it.setup?.loading == false }.setup!!
            setup.firstRun shouldBe true
            setup.recorders.single().kind shouldBe RecorderKind.OBS
            setup.recorders.single().separateMic shouldBe true
            setup.folder shouldBe videos
            setup.chosen!!.games.map { it.profileId } shouldBe listOf("wardogs")
            setup.firstPick shouldBe listOf(video.toAbsolutePath().normalize())

            controller.startFirstMontage()
            controller.state.value.setup.shouldBeNull()
            val opened = await("parcours du montage") { it.montage != null && it.job == null }
            opened.session.shouldNotBeNull().sessions.single().profileId shouldBe "wardogs"
            opened.montage!!.gameName shouldBe "Wardogs"
            await("surveillance") { it.watch.folder == videos.toAbsolutePath().normalize() }
        } finally {
            controller.shutdown()
            scope.cancel()
        }

        // Relance : ni dossier à proposer ni écran, la bibliothèque a déjà une analyse.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val again = start(scope2)
        try {
            withTimeout(60.seconds) { again.state.first { it.config is ConfigStatus.Ready && it.library.isNotEmpty() } }
            delay(500)
            again.state.value.setup.shouldBeNull()
        } finally {
            again.shutdown()
            scope2.cancel()
        }
    }
})
