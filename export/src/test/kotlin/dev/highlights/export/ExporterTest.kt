package dev.highlights.export

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.EncoderSelector
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.ffmpeg.FfmpegResult
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.session.Session
import dev.highlights.editing.DefaultEditPlanner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** FFmpeg simulé : écrit le fichier de sortie demandé (dernier argument), ou échoue sur demande. */
private class FakeFfmpeg(private val fail: Boolean = false) : FfmpegService {
    val runs = mutableListOf<List<String>>()

    override suspend fun probe(file: Path): MediaInfo = error("inutilisé")

    override suspend fun run(command: FfmpegCommand, stdout: StdoutHandler, onStderrLine: ((String) -> Unit)?): FfmpegResult {
        runs += command.args
        val output = Path.of(command.args.last())
        output.writeText("vidéo")
        if (fail) throw FfmpegException(command.description, 1, command.args.joinToString(" "), listOf("Conversion failed!"))
        return FfmpegResult(0, Duration.ZERO, emptyList())
    }

    override suspend fun runProbe(command: FfmpegCommand, stdout: StdoutHandler): FfmpegResult = error("inutilisé")
}

private val encoder = object : EncoderSelector {
    override suspend fun select() = EncoderProfile("libx264", listOf("-c:v", "libx264"), hardware = false)
}

private fun media(path: Path) = MediaInfo(
    path = path,
    sizeBytes = 1_000_000,
    duration = 10.minutes,
    creationTime = Instant.parse("2026-09-23T18:04:19Z"),
    video = VideoStream(0, "h264", 3440, 1440, 60.0),
    audio = listOf(AudioStream(1, 0, "aac", 2, 48000, "Mix")),
)

private fun session(source: Path): Session {
    val m = media(source)
    val grid = WindowGrid(1.seconds, 1.seconds, m.duration)
    fun highlight(id: String, start: Int, enabled: Boolean = true) =
        Highlight(id, source, TimeRange(start.seconds, (start + 8).seconds), (start + 4).seconds, 0.9, mapOf("game-audio" to 0.9), enabled)
    return Session(
        createdAt = Instant.parse("2026-11-17T20:00:00Z"),
        media = m,
        profileId = "wardogs",
        timeline = ScoredTimeline(grid, List(grid.count) { 0.0 }, emptyMap()),
        highlights = listOf(highlight("h1", 60), highlight("h2", 200, enabled = false), highlight("h3", 400)),
    )
}

private val reportReader = Json { ignoreUnknownKeys = true }

class ExporterTest : FunSpec({
    lateinit var dir: Path
    lateinit var source: Path
    beforeTest {
        dir = tempdir().toPath()
        source = dir.resolve("capture.mp4").also { it.writeText("source") }
    }

    fun request(out: Path = dir.resolve("sortie"), formats: List<OutputFormat> = listOf(OutputFormat.SOURCE), maxDuration: Duration? = null, platform: String? = null) =
        ExportRequest(EditSettings(formats = formats), out, dir.resolve("job"), "Wardogs", platform = platform, maxDuration = maxDuration)

    test("export : une vidéo par format, rapport JSON des moments montés et écartés, aucun .part restant") {
        runTest {
            val ffmpeg = FakeFfmpeg()
            val result = Exporter(ffmpeg, encoder, DefaultEditPlanner)
                .export(session(source), request(formats = listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)), ProgressTracker(listener = {}).root)

            result.videos.values.map { it.name } shouldContainExactly listOf("wardogs_2026-09-23_highlights.mp4", "wardogs_2026-09-23_highlights_9x16.mp4")
            result.videos.values.forEach { it.exists() shouldBe true }
            result.report.name shouldBe "wardogs_2026-09-23_highlights.json"
            dir.resolve("sortie").listDirectoryEntries("*.part.mp4").shouldBeEmpty()
            ffmpeg.runs.size shouldBe 2

            val report = reportReader.decodeFromString(ExportReport.serializer(), result.report.readText())
            report.source shouldBe source.toString()
            report.sources.shouldBeEmpty()
            report.profile shouldBe "wardogs"
            report.encoder shouldBe "libx264"
            report.outputs.map { it.format } shouldContainExactly listOf(OutputFormat.SOURCE.label, OutputFormat.VERTICAL.label)
            report.highlights.map { it.id to it.order } shouldContainExactly listOf("h1" to 1, "h3" to 2)
            report.highlights.first().startSeconds shouldBe 60.0
            report.highlights.first().durationSeconds shouldBe 8.0
            report.skippedHighlights.map { it.id to it.order } shouldContainExactly listOf("h2" to null)
            report.totalDurationSeconds shouldBe result.duration.inWholeMilliseconds / 1000.0
        }
    }

    test("jamais d'écrasement : un second export prend le suffixe _2 et laisse le premier intact") {
        runTest {
            val exporter = Exporter(FakeFfmpeg(), encoder, DefaultEditPlanner)
            val first = exporter.export(session(source), request(), ProgressTracker(listener = {}).root)
            first.videos.values.single().writeText("premier montage")
            val second = exporter.export(session(source), request(), ProgressTracker(listener = {}).root)

            second.videos.values.single().name shouldBe "wardogs_2026-09-23_highlights_2.mp4"
            second.report.name shouldBe "wardogs_2026-09-23_highlights_2.json"
            first.videos.values.single().readText() shouldBe "premier montage"
        }
    }

    test("un .part.mp4 laissé par un rendu interrompu réserve aussi le nom") {
        runTest {
            val out = dir.resolve("sortie").also { it.toFile().mkdirs() }
            out.resolve("wardogs_2026-09-23_highlights.part.mp4").writeText("rendu interrompu")
            val result = Exporter(FakeFfmpeg(), encoder, DefaultEditPlanner).export(session(source), request(out), ProgressTracker(listener = {}).root)
            result.videos.values.single().name shouldBe "wardogs_2026-09-23_highlights_2.mp4"
        }
    }

    test("refus d'écrire sur une source : rien n'est lancé si la sortie retenue est une capture du montage") {
        runTest {
            val out = dir.resolve("sortie")
            // Capture déplacée ou débranchée dont le chemin est justement le nom que le montage prendrait.
            val missing = out.resolve("WARDOGS_2026-09-23_HIGHLIGHTS.mp4")
            val ffmpeg = FakeFfmpeg()
            shouldThrow<HighlightsException> {
                Exporter(ffmpeg, encoder, DefaultEditPlanner).export(session(missing), request(out), ProgressTracker(listener = {}).root)
            }.text.key shouldBe "error.export.wouldOverwriteSource"
            ffmpeg.runs.shouldBeEmpty()
        }
    }

    test("échec du rendu : le .part est supprimé et aucun rapport n'est écrit") {
        runTest {
            val out = dir.resolve("sortie")
            shouldThrow<FfmpegException> {
                Exporter(FakeFfmpeg(fail = true), encoder, DefaultEditPlanner).export(session(source), request(out), ProgressTracker(listener = {}).root)
            }
            out.listDirectoryEntries().shouldBeEmpty()
            source.readText() shouldBe "source"
        }
    }

    test("plateforme : nommée dans le fichier, et un montage trop long refusé avant le rendu") {
        runTest {
            val ffmpeg = FakeFfmpeg()
            val exporter = Exporter(ffmpeg, encoder, DefaultEditPlanner)
            exporter.export(session(source), request(platform = "TikTok"), ProgressTracker(listener = {}).root)
                .videos.values.single().name shouldBe "wardogs_2026-09-23_highlights_tiktok.mp4"

            ffmpeg.runs.clear()
            shouldThrow<InputException> {
                exporter.export(session(source), request(platform = "tiktok", maxDuration = 5.seconds), ProgressTracker(listener = {}).root)
            }.text.key shouldBe "error.export.platformTooLong"
            ffmpeg.runs.shouldBeEmpty()

            // Sans plateforme (limite du réglage du clip) : un message qui le dit, jamais « la plateforme visée () ».
            val clip = shouldThrow<InputException> {
                exporter.export(session(source), request(maxDuration = 5.seconds), ProgressTracker(listener = {}).root)
            }
            clip.text.key shouldBe "error.export.clipTooLong"
            clip.localizedMessage.contains("()") shouldBe false
        }
    }

    test("sans format ou sans session : erreur expliquée") {
        runTest {
            val exporter = Exporter(FakeFfmpeg(), encoder, DefaultEditPlanner)
            shouldThrow<HighlightsException> { exporter.export(session(source), request(formats = emptyList()), ProgressTracker(listener = {}).root) }
                .text.key shouldBe "error.export.noFormat"
            shouldThrow<HighlightsException> { exporter.export(emptyList(), request(), ProgressTracker(listener = {}).root) }
                .text.key shouldBe "error.export.noSession"
        }
    }
})
