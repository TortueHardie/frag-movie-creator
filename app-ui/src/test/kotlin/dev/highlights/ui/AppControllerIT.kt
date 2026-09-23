package dev.highlights.ui

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.session.SessionStore
import dev.highlights.pipeline.Pipelines
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import java.util.Collections
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Parcours complet de l'interface sans fenêtre : config → vidéo → analyse → recalcul → export → aperçus. */
class AppControllerIT : FunSpec({
    test("parcours utilisateur complet").config(enabledIf = { TestMedia.available }, timeout = 5.seconds * 60) {
        val root = tempdir().toPath()
        val video = TestMedia.generate(
            Files.createDirectories(root.resolve("WARDOGS")).resolve("partie.mp4"),
            durationSeconds = 60,
            audioTracks = listOf(
                TestMedia.AudioTrackSpec("Mix", listOf(15.0..17.0, 42.0..44.0)),
                TestMedia.AudioTrackSpec("Game", listOf(15.0..17.0, 42.0..44.0)),
                TestMedia.AudioTrackSpec("Mic", listOf(43.0..44.0), frequency = 300),
            ),
        )
        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                workDir = root.resolve("work").toString(),
            ),
            // Dossier de config réel : les modèles d'images du profil Wardogs sont résolus depuis config/.
            baseDir = Path("../config").toAbsolutePath().normalize(),
            source = null,
        )
        val platform = RecordingPlatform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppController(scope, platform, WatchPrefsStore(root.resolve("watch.json"))) {
            Backend(Pipelines.create(config, TestMedia.requireFfmpeg()), Path("app.yaml"))
        }
        suspend fun await(what: String, predicate: (UiState) -> Boolean): UiState =
            withTimeout(120.seconds) { controller.state.first { s -> s.error?.let { error("$what : ${it.title} — ${it.message}") }; predicate(s) } }

        try {
            await("config") { it.config is ConfigStatus.Ready }

            controller.dropFiles(listOf(video))
            val withSource = await("source") { it.source != null && it.job == null }
            withSource.source!!.detectedProfileId shouldBe "wardogs"
            withSource.settings.formats shouldBe setOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)

            controller.setTargetMode(TargetMode.TOP_N)
            controller.setTopNText("5")
            controller.analyze()
            val analyzed = await("analyse") { it.session != null && it.job == null }
            val session = analyzed.session.shouldNotBeNull()
            session.highlights shouldHaveSize 2
            await("vignettes") { it.session!!.thumbnails.size == 2 }

            // Seuil au maximum : recalcul instantané, plus aucun moment ; retour au seuil du profil.
            controller.setThreshold(0.95)
            await("recalcul") { it.session!!.highlights.size < 2 }
            controller.setThreshold(0.6)
            await("recalcul inverse") { it.session!!.highlights.size == 2 }

            controller.toggleSegment("h001")
            await("décoche") { it.session!!.enabledCount == 1 }

            controller.export()
            val exported = await("export") { it.lastExport != null && it.job == null }
            val result = exported.lastExport.shouldNotBeNull()
            result.videos.keys shouldBe setOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)
            result.videos.values.all { it.exists() } shouldBe true
            // La session enregistrée garde le segment décoché.
            SessionStore.load(session.entries.single().file).highlights.first { it.id == "h001" }.enabled shouldBe false

            controller.previewVertical("h002")
            val preview = await("aperçu 9:16") { it.imagePreview != null }
            preview.imagePreview!!.path.exists() shouldBe true

            controller.previewClip("h002")
            await("extrait") { "h002" !in it.busyClips && platform.opened.isNotEmpty() }
            platform.opened.single().exists() shouldBe true

            controller.state.value.error.shouldBeNull()
            video.exists() shouldBe true
        } finally {
            controller.shutdown()
            scope.cancel()
        }
    }

    test("dossier surveillé : nouvelle capture analysée en fond, retrouvée après redémarrage").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val watched = Files.createDirectories(root.resolve("Outplayed"))
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
        // Capture déjà là avant la surveillance : elle n'est pas analysée d'office.
        val old = TestMedia.generate(Files.createDirectories(watched.resolve("League of Legends")).resolve("ancienne.mp4"), durationSeconds = 20)
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(3600)))
        val prefs = WatchPrefsStore(root.resolve("watch.json"))
        val ffmpeg = TestMedia.requireFfmpeg()

        fun start(scope: CoroutineScope, platform: Platform) =
            AppController(scope, platform, prefs, watchInterval = 200.milliseconds, watchSettle = 1.seconds) {
                Backend(Pipelines.create(config, ffmpeg), Path("app.yaml"))
            }

        val platform = RecordingPlatform().apply { directory = watched }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = start(scope, platform)
        suspend fun await(what: String, predicate: (UiState) -> Boolean): UiState =
            withTimeout(120.seconds) { controller.state.first { s -> s.error?.let { error("$what : ${it.title} — ${it.message}") }; predicate(s) } }
        val fresh: Path
        try {
            await("config") { it.config is ConfigStatus.Ready }
            controller.chooseWatchFolder()
            await("surveillance") { it.watch.folder == watched.toAbsolutePath().normalize() }

            // L'enregistreur dépose une nouvelle partie (générée à côté puis déplacée, comme un fichier terminé).
            val made = TestMedia.generate(
                root.resolve("partie.mp4"),
                durationSeconds = 40,
                audioTracks = listOf(TestMedia.AudioTrackSpec("Game", bursts = listOf(10.0..12.0, 28.0..30.0))),
            )
            val arrived = Files.move(made, watched.resolve("League of Legends").resolve("partie.mp4"))
            Files.setLastModifiedTime(arrived, FileTime.from(Instant.now()))
            val analyzed = await("analyse en fond") { it.library.size == 1 && it.watch.current == null && it.watch.fresh.isNotEmpty() }
            val item = analyzed.library.single()
            item.source shouldBe arrived.toAbsolutePath().normalize()
            item.profileId shouldBe "lol"
            fresh = item.sessionFile
            (analyzed.watch.fresh == setOf(fresh)) shouldBe true
            analyzed.watch.lastError.shouldBeNull()

            // Cocher puis décocher une analyse de la liste.
            controller.toggleLibraryItem(fresh)
            await("cochée") { it.librarySelection == setOf(fresh) }
            controller.toggleLibraryItem(fresh)
            await("décochée") { it.librarySelection.isEmpty() }
            controller.toggleLibraryItem(fresh)
            controller.openLibrarySelection()
            val opened = await("réouverture") { it.session != null && it.job == null }
            opened.session!!.highlights.isNotEmpty() shouldBe true
            opened.watch.fresh.isEmpty() shouldBe true
            opened.librarySelection.isEmpty() shouldBe true
            opened.watch.pending.isEmpty() shouldBe true

            // Revenir à la liste puis rechoisir la vidéo : l'analyse est reprise, pas refaite.
            controller.showLibrary()
            await("liste") { it.session == null && it.sources.isEmpty() }
            controller.dropFiles(listOf(arrived))
            await("déjà analysée") { it.source?.alreadyAnalyzed == true && it.job == null }
            controller.analyze()
            await("reprise") { it.session != null && it.job == null }
            // La capture ancienne n'a jamais été analysée.
            controller.state.value.library.map { it.source.fileName.toString() } shouldBe listOf("partie.mp4")
        } finally {
            controller.shutdown()
            scope.cancel()
        }

        // Relance de l'application : dossier et analyses sont retrouvés.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restarted = start(scope2, RecordingPlatform())
        try {
            val back = withTimeout(60.seconds) { restarted.state.first { it.library.size == 1 && it.watch.folder != null } }
            back.watch.folder shouldBe watched.toAbsolutePath().normalize()
            back.library.single().sessionFile shouldBe fresh
            restarted.stopWatching()
            prefs.load().shouldBeNull()
        } finally {
            restarted.shutdown()
            scope2.cancel()
        }
    }
})

private class RecordingPlatform : Platform {
    val opened: MutableList<Path> = Collections.synchronizedList(mutableListOf())
    override fun chooseVideos(initialDir: Path?): List<Path> = emptyList()
    override fun chooseAudio(initialDir: Path?): Path? = null
    override fun chooseSessions(initialDir: Path?): List<Path> = emptyList()
    var directory: Path? = null
    override fun chooseDirectory(initialDir: Path?, title: String): Path? = directory
    override fun open(path: Path) { opened.add(path) }
    override fun reveal(path: Path) = Unit
    override fun edit(path: Path) = Unit
}
