package dev.highlights.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import dev.highlights.export.ExportResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.writeBytes
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Rendu hors écran des écrans principaux en PNG (build/ui-snapshots) : vérifie que la composition ne plante pas
 * et permet de relire visuellement la mise en page sans lancer l'application.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiSnapshotTest : FunSpec({
    val outDir = Path("build", "ui-snapshots").createDirectories()

    fun render(name: String, state: UiState, width: Int = 1440, height: Int = 920): Path {
        val scene = ImageComposeScene(width, height, Density(1f)) { App(state, NoopActions) }
        try {
            scene.render(0)
            val image = scene.render(500.milliseconds.inWholeNanoseconds)
            val file = outDir.resolve("$name.png")
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            return file
        } finally {
            scene.close()
        }
    }

    val media = MediaInfo(
        path = Path("C:/Users/Colin/Videos/Overwolf/Outplayed/WARDOGS/WARDOGS_09-16-2026_18-4-11-925/WARDOGS_09-16-2026_19-1-23-983.mp4"),
        sizeBytes = 5_328_000_000,
        duration = 56.minutes + 59.seconds,
        video = VideoStream(0, "h264", 3440, 1440, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000, "Track1"), AudioStream(2, 1, "aac", 2, 48000, "Track2"), AudioStream(3, 2, "aac", 2, 48000, "Track3")),
    )
    val grid = WindowGrid(1.seconds, 500.milliseconds, media.duration)
    val peaks = listOf(212, 530, 861, 1204, 1490, 1905, 2410, 2980, 3150)
    val random = Random(7)
    val scores = List(grid.count) { i ->
        val t = i * 0.5
        val bumps = peaks.sumOf { p -> 0.85 * exp(-((t - p) * (t - p)) / 30.0) }
        (0.18 + 0.08 * sin(t / 40) + random.nextDouble(0.0, 0.12) + bumps).coerceIn(0.0, 1.0)
    }
    val highlights = peaks.mapIndexed { i, p ->
        Highlight(
            id = "h%03d".format(i + 1),
            source = media.path,
            range = TimeRange((p - 5).seconds, (p + 4).seconds),
            peak = p.seconds,
            score = listOf(0.92, 0.71, 0.86, 0.64, 0.99, 0.77, 0.68, 0.83, 0.61)[i],
            contributions = mapOf("game-audio" to 0.62, "mic-audio" to 0.21),
            enabled = i != 3,
            events = if (i % 3 == 0) mapOf("kill" to 1 + i / 3) else emptyMap(),
        )
    }
    val session = Session(
        createdAt = Instant.EPOCH,
        media = media,
        profileId = "wardogs",
        timeline = ScoredTimeline(
            grid, scores, mapOf("game-audio" to scores),
            events = peaks.mapIndexed { i, p -> TimelineEvent(p.seconds, if (i % 3 == 0) "kill" else "assist", 1.0, "notifications") },
            excluded = listOf(TimeRange(0.seconds, 90.seconds), TimeRange(2300.seconds, 2400.seconds)),
        ),
        highlights = highlights,
    )
    val ready = ConfigStatus.Ready(
        Path("D:/dev/editor/config/app.yaml"),
        Path("D:/dev/editor/config/profiles"),
        listOf(ProfileInfo("default", "Générique"), ProfileInfo("lol", "League of Legends"), ProfileInfo("valorant", "VALORANT"), ProfileInfo("wardogs", "Wardogs")),
    )
    val settings = SettingsState(
        formats = setOf(OutputFormat.SOURCE, OutputFormat.VERTICAL),
        durationText = "60s",
        threshold = 0.6,
        outputDir = Path("D:/Highlights"),
    )
    val source = SourceInfo(media.path, media, "wardogs")

    test("écran d'accueil") {
        render("01_accueil", UiState(config = ready, settings = settings)).fileSize() shouldBeGreaterThan 0L
    }

    test("analyses enregistrées et dossier surveillé") {
        val sessions = Path("D:/Highlights/sessions")
        val outplayed = Path("C:/Users/Colin/Videos/Overwolf/Outplayed")
        val library = listOf(
            LibraryItem(outplayed.resolve("League of Legends/LoL_09-23-2026_21-40.mp4"), sessions.resolve("LoL_09-23-2026_21-40.session.json"), "lol", Instant.parse("2026-09-23T20:25:00Z"), Instant.parse("2026-09-23T19:40:00Z"), 31.minutes, mapOf("kill" to 7, "death" to 3), true),
            LibraryItem(outplayed.resolve("League of Legends/LoL_09-23-2026_20-55.mp4"), sessions.resolve("LoL_09-23-2026_20-55.session.json"), "lol", Instant.parse("2026-09-23T19:36:00Z"), Instant.parse("2026-09-23T18:55:00Z"), 28.minutes, mapOf("kill" to 4, "assist" to 9), true),
            LibraryItem(media.path, sessions.resolve("WARDOGS.session.json"), "wardogs", Instant.parse("2026-09-16T18:10:00Z"), Instant.parse("2026-09-16T17:01:00Z"), media.duration, mapOf("kill" to 12), true),
            LibraryItem(Path("E:/OBS/valorant_old.mkv"), sessions.resolve("valorant_old.session.json"), "valorant", Instant.parse("2026-09-02T21:00:00Z"), null, 42.minutes, emptyMap(), false),
        )
        val state = UiState(
            config = ready,
            settings = settings,
            library = library,
            librarySelection = setOf(library[0].sessionFile, library[1].sessionFile),
            watch = WatchState(
                folder = outplayed,
                current = outplayed.resolve("League of Legends/LoL_09-23-2026_22-20.mp4"),
                fraction = 0.42,
                pending = listOf(outplayed.resolve("VALORANT/Valorant_09-23-2026_22-50.mp4")),
                fresh = setOf(library[0].sessionFile),
            ),
        )
        render("08_bibliotheque", state).fileSize() shouldBeGreaterThan 0L
        render("09_bibliotheque_vide", UiState(config = ready, settings = settings)).fileSize() shouldBeGreaterThan 0L
    }

    test("analyse en cours") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            job = JobState("Analyse de ${media.path.fileName}", 0.43, "Analyse > game-audio", 37.seconds, 49.seconds),
        )
        render("02_analyse", state).fileSize() shouldBeGreaterThan 0L
    }

    test("relecture des segments et export terminé") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            session = SessionState(session, Path("D:/Highlights/sessions/x.session.json"), selectedId = "h003"),
            lastExport = ExportResult(
                videos = mapOf(
                    OutputFormat.SOURCE to Path("D:/Highlights/wardogs_2026-09-16_highlights.mp4"),
                    OutputFormat.VERTICAL to Path("D:/Highlights/wardogs_2026-09-16_highlights_9x16.mp4"),
                ),
                report = Path("D:/Highlights/wardogs_2026-09-16_highlights.json"),
                duration = 58.seconds,
                encoder = "h264_amf",
            ),
            busyClips = setOf("h005"),
        )
        render("03_relecture", state).fileSize() shouldBeGreaterThan 0L
        render("04_relecture_petite_fenetre", state, 1100, 700).fileSize() shouldBeGreaterThan 0L
    }

    test("réglages du montage kills") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            session = SessionState(session, Path("D:/Highlights/sessions/x.session.json"), selectedId = "h003"),
            montage = MontageUiState(music = Path("D:/Musique/phonk_128.mp3")),
        )
        render("06_montage", state).fileSize() shouldBeGreaterThan 0L
    }

    test("publication sur YouTube : champs proposés, puis sans identifiant configuré, puis lien de la vidéo en ligne") {
        val video = Path("D:/Highlights/valorant_2026-09-27_killmontage_9x16.mp4")
        val suggested = dev.highlights.publish.YouTubeMetadata(
            "TRIPLÉ — 19 kills en 57 s | VALORANT", "19 kills, dont 13 headshots · 1 triplé, 4 doublés.\nPartie du 27/09/2026.\nMusique : R2D2\n\n#VALORANT #Shorts",
            listOf("VALORANT", "gaming", "highlights"), dev.highlights.core.config.YouTubePrivacy.PRIVATE,
        )
        val base = UiState(config = ready, sources = listOf(source), settings = settings, session = SessionState(session, Path("D:/Highlights/sessions/x.session.json")))
        render("09_publication_manuelle", base.copy(publish = PublishUiState(video).withSuggestion(suggested))).fileSize() shouldBeGreaterThan 0L
        render("09_publication_api", base.copy(publish = PublishUiState(video, api = true, connected = true).withSuggestion(suggested))).fileSize() shouldBeGreaterThan 0L
        render("10_publication_sans_identifiant", base.copy(publish = PublishUiState(video, api = true, configured = false))).fileSize() shouldBeGreaterThan 0L
        val exported = base.copy(
            lastExport = ExportResult(mapOf(OutputFormat.VERTICAL to video), Path("D:/Highlights/valorant_2026-09-27_killmontage.json"), 57.seconds, "h264_amf", Path("D:/Musique/R2D2.mp3")),
            lastUpload = dev.highlights.publish.UploadedVideo("abc123"),
        )
        render("11_publiee", exported).fileSize() shouldBeGreaterThan 0L
    }

    test("plateforme visée : format imposé, ce qu'elle change affiché") {
        val platforms = dev.highlights.core.model.PlatformProfile.DEFAULTS.map { (id, p) -> PlatformInfo.of(id, p) }
        val state = UiState(
            config = ready.copy(platforms = platforms),
            sources = listOf(source),
            settings = settings.copy(platform = "tiktok"),
            session = SessionState(session, Path("D:/Highlights/sessions/x.session.json")),
            montage = MontageUiState(music = Path("D:/Musique/phonk_128.mp3"), platform = "shorts"),
        )
        render("12_plateforme", state).fileSize() shouldBeGreaterThan 0L
        render("13_plateforme_reglages", state.copy(montage = null)).fileSize() shouldBeGreaterThan 0L
    }

    test("statistiques par soirée") {
        fun game(at: String, name: String, kills: Int, deaths: Int?, hs: Int?, best: Int?, multi: Map<Int, Int> = emptyMap(), clutches: Int = 0) =
            dev.highlights.pipeline.GameStats(
                Path("D:/Highlights/sessions/${at.take(10)}-$kills.session.json"), Path("partie.mp4"), name.lowercase(), name,
                Instant.parse(at), 40.minutes, kills, deaths, hs, multi, 0, clutches, best, best?.let { 24 },
            )
        val games = listOf(
            game("2026-09-21T19:45:00Z", "VALORANT", 10, 11, 1, 3, mapOf(2 to 1)),
            game("2026-09-22T16:45:00Z", "VALORANT", 7, 5, 2, 3, mapOf(2 to 1)),
            game("2026-09-22T16:59:00Z", "VALORANT", 6, 3, 2, 2),
            game("2026-09-25T20:24:00Z", "VALORANT", 14, 14, 4, 4, mapOf(2 to 1, 3 to 1), 1),
            game("2026-09-25T21:07:00Z", "VALORANT", 9, 17, 5, 2),
            game("2026-09-26T20:33:00Z", "VALORANT", 13, 17, 7, 3, mapOf(2 to 2)),
            game("2026-09-26T21:26:00Z", "VALORANT", 23, 15, 13, 2, mapOf(2 to 3), 2),
            game("2026-09-27T14:14:00Z", "VALORANT", 19, 16, 11, 2, mapOf(2 to 1)),
            game("2026-09-27T16:08:00Z", "VALORANT", 23, 13, null, 3, mapOf(2 to 5), 3),
            game("2026-09-27T20:26:00Z", "VALORANT", 19, 18, 13, 3, mapOf(2 to 4), 3),
            game("2026-09-23T19:55:00Z", "Wardogs", 5, null, null, null),
        )
        val state = UiState(config = ready, stats = StatsState(loading = false, games = games))
        render("14_statistiques", state).fileSize() shouldBeGreaterThan 0L
    }

    test("recherche de moments") {
        fun moment(at: String, kills: Int, hs: Int, ace: Boolean = false, clutch: Boolean = false, game: String = "VALORANT") = dev.highlights.pipeline.FoundMoment(
            Path("D:/Highlights/sessions/${at.take(10)}.session.json"), Path("D:/Videos/Valorant_${at.take(10)}.mp4"), game.lowercase(), game,
            Instant.parse(at), List(kills) { (Instant.parse(at).epochSecond % 3000 + it).seconds }, hs, ace, clutch, kills.toDouble(),
        )
        val moments = listOf(
            moment("2026-09-25T20:24:00Z", 3, 1, clutch = true), moment("2026-09-26T21:26:00Z", 2, 2, clutch = true),
            moment("2026-09-27T20:26:00Z", 2, 2, clutch = true), moment("2026-09-27T19:54:00Z", 2, 1), moment("2026-09-27T14:14:00Z", 1, 1),
            moment("2026-09-23T19:55:00Z", 1, 0, game = "Wardogs"),
        )
        val search = SearchState(loading = false, moments = moments, minKills = 2)
        search.results.size shouldBe 4
        search.copy(unpicked = setOf(SearchState.key(moments[3]))).picked.size shouldBe 3
        search.copy(clutch = true, headshots = true).results.size shouldBe 2
        render("15_recherche", UiState(config = ready, search = search)).fileSize() shouldBeGreaterThan 0L
    }

    test("plusieurs captures pour un seul montage") {
        // Trois parties de la même soirée : la deuxième est sélectionnée, sa courbe est affichée.
        val parts = listOf("18-4-11-925" to 0, "19-1-23-983" to 3, "20-12-2-114" to 6).mapIndexed { n, (stamp, from) ->
            val m = media.copy(
                path = Path("C:/Users/Colin/Videos/Overwolf/Outplayed/WARDOGS/WARDOGS_09-16-2026_$stamp.mp4"),
                creationTime = Instant.parse("2026-09-16T1${6 + n}:04:11Z"),
            )
            session.copy(media = m, highlights = highlights.subList(from, from + 3).mapIndexed { i, h -> h.copy(id = "h%03d".format(i + 1), source = m.path) })
        }
        val state = UiState(
            config = ready,
            sources = parts.map { SourceInfo(it.media.path, it.media, "wardogs") },
            settings = settings,
            session = SessionState(parts.mapIndexed { i, s -> SessionEntry(s, Path("D:/Highlights/sessions/$i.session.json")) }, selectedId = "2-h002"),
        )
        render("07_plusieurs_videos", state).fileSize() shouldBeGreaterThan 0L
    }

    test("mise à jour disponible, puis en cours de téléchargement") {
        val release = Release(
            "1.4.0", "Highlights-1.4.0.msi", java.net.URI("https://github.com/x/y/releases/download/v1.4.0/Highlights-1.4.0.msi"),
            180_000_000, null, java.net.URI("https://github.com/x/y/releases/tag/v1.4.0"),
        )
        val state = UiState(config = ready, sources = listOf(source), settings = settings, update = UpdateState.Available(release))
        render("08_mise_a_jour", state).fileSize() shouldBeGreaterThan 0L
        render("09_mise_a_jour_telechargement", state.copy(update = UpdateState.Downloading(release, 0.42))).fileSize() shouldBeGreaterThan 0L
    }

    test("erreur FFmpeg") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            error = ErrorInfo(
                "Export du montage : échec",
                "FFmpeg a échoué (rendu 9:16), code retour -22 : Invalid argument",
                listOf("[Parsed_crop_3 @ 0000] Invalid too big or non positive size", "Error reinitializing filters!", "", "Journal : C:\\Users\\Colin\\.highlights\\logs\\highlights.log"),
            ),
        )
        render("05_erreur", state).fileSize() shouldBeGreaterThan 0L
    }
})

private object NoopActions : UiActions {
    override fun chooseSource() = Unit
    override fun showLibrary() = Unit
    override fun openLibraryItem(sessionFile: Path) = Unit
    override fun toggleLibraryItem(sessionFile: Path) = Unit
    override fun openLibrarySelection() = Unit
    override fun chooseWatchFolder() = Unit
    override fun stopWatching() = Unit
    override fun addSources() = Unit
    override fun removeSource(path: Path) = Unit
    override fun chooseSession() = Unit
    override fun dropFiles(paths: List<Path>) = Unit
    override fun reloadConfig() = Unit
    override fun openProfilesFolder() = Unit
    override fun editProfile() = Unit
    override fun setProfile(id: String?) = Unit
    override fun toggleFormat(format: OutputFormat) = Unit
    override fun setPlatform(id: String?) = Unit
    override fun setStyle(style: EditStyle) = Unit
    override fun setMomentMode(mode: MomentMode) = Unit
    override fun setTargetMode(mode: TargetMode) = Unit
    override fun setDurationText(text: String) = Unit
    override fun setTopNText(text: String) = Unit
    override fun setThreshold(value: Double) = Unit
    override fun chooseOutputDir() = Unit
    override fun openOutputDir() = Unit
    override fun analyze() = Unit
    override fun export() = Unit
    override fun cancelJob() = Unit
    override fun toggleSegment(id: String) = Unit
    override fun setAllSegments(enabled: Boolean) = Unit
    override fun selectSegment(id: String?) = Unit
    override fun previewClip(id: String) = Unit
    override fun previewVertical(id: String) = Unit
    override fun regenerateVerticalPreview() = Unit
    override fun openMontage() = Unit
    override fun closeMontage() = Unit
    override fun chooseMusic() = Unit
    override fun chooseMusicLibrary() = Unit
    override fun showStats() = Unit
    override fun showSearch() = Unit
    override fun closeSearch() = Unit
    override fun updateSearch(change: (SearchState) -> SearchState) = Unit
    override fun toggleMoment(key: String) = Unit
    override fun montageFromSearch() = Unit
    override fun closeStats() = Unit
    override fun setStatsGame(game: String) = Unit
    override fun openPublish(video: java.nio.file.Path) = Unit
    override fun closePublish() = Unit
    override fun updatePublish(change: (PublishUiState) -> PublishUiState) = Unit
    override fun resetPublishText() = Unit
    override fun publishToYouTube() = Unit
    override fun youtubeLogout() = Unit
    override fun editConfig() = Unit
    override fun browse(uri: java.net.URI) = Unit
    override fun openYouTubeUpload() = Unit
    override fun copyToClipboard(text: String) = Unit
    override fun updateMontage(change: (MontageUiState) -> MontageUiState) = Unit
    override fun createMontage() = Unit
    override fun open(path: Path) = Unit
    override fun reveal(path: Path) = Unit
    override fun dismissError() = Unit
    override fun dismissImagePreview() = Unit
    override fun installUpdate() = Unit
    override fun dismissUpdate() = Unit
}
