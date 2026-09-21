package dev.highlights.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
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
    override fun addSources() = Unit
    override fun removeSource(path: Path) = Unit
    override fun chooseSession() = Unit
    override fun dropFiles(paths: List<Path>) = Unit
    override fun reloadConfig() = Unit
    override fun openProfilesFolder() = Unit
    override fun editProfile() = Unit
    override fun setProfile(id: String?) = Unit
    override fun toggleFormat(format: OutputFormat) = Unit
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
    override fun updateMontage(change: (MontageUiState) -> MontageUiState) = Unit
    override fun createMontage() = Unit
    override fun open(path: Path) = Unit
    override fun reveal(path: Path) = Unit
    override fun dismissError() = Unit
    override fun dismissImagePreview() = Unit
}
