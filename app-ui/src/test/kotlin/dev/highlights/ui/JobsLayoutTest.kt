package dev.highlights.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Une longue file de traitements ne pousse pas la barre d'export hors de l'écran (QA finale : avec une dizaine de
 * traitements finis, « Exporter le montage » avait une hauteur de 0).
 */
@OptIn(ExperimentalTestApi::class)
class JobsLayoutTest : FunSpec({
    val noop = java.lang.reflect.Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { _, _, _ -> null } as UiActions
    val media = MediaInfo(Path("D:/Vidéo/partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
    val session = Session(
        createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
        timeline = ScoredTimeline(grid, List(grid.count) { 0.3 }, emptyMap()),
        highlights = (0 until 8).map { i ->
            Highlight("h%03d".format(i + 1), media.path, TimeRange((100 + i * 200).seconds, (108 + i * 200).seconds), (104 + i * 200).seconds, 0.8, emptyMap())
        },
    )
    val jobs = (1L..12L).map { QueuedJob(it, "Export : partie_$it.mp4", status = if (it % 4 == 0L) JobStatus.FAILED else JobStatus.DONE, error = "échec") } +
        QueuedJob(13, "Montage kills sur Rome Is Burning.mp3", status = JobStatus.RUNNING, fraction = 0.4) +
        (14L..15L).map { QueuedJob(it, "Clip 9:16 : h00$it de partie.mp4") }

    test("douze traitements finis et deux en attente : « Exporter le montage » reste à l'écran, à sa hauteur") {
        I18n.withLanguage("fr") {
            runDesktopComposeUiTest(width = 1440, height = 920) {
                setContent {
                    App(
                        UiState(
                            config = ConfigStatus.Ready(Path("config/app.yaml"), Path("config/profiles"), emptyList()),
                            settings = SettingsState(formats = setOf(OutputFormat.VERTICAL)),
                            session = SessionState(session, Path("x.session.json")),
                            jobs = jobs,
                        ),
                        noop,
                    )
                }
                val button = onNodeWithText(tr("ui.export.button")).fetchSemanticsNode()
                (button.size.height > 0) shouldBe true
                // Entièrement dans la fenêtre.
                (button.boundsInRoot.bottom <= 920f) shouldBe true
            }
        }
    }
})
