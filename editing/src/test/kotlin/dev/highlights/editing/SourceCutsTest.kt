package dev.highlights.editing

import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegResult
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Service qui note les commandes reçues, crée les fichiers demandés et répond aux `ffprobe` de début de fichier.
 * [keyframeLead] simule l'avance jusqu'à l'image clé précédente : la découpe commence d'autant avant l'extrait.
 */
private class RecordingFfmpeg(val failOn: Int? = null, val keyframeLead: Duration = Duration.ZERO) : FfmpegService {
    val commands: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    override suspend fun probe(file: Path): MediaInfo = error("inutilisé")

    override suspend fun run(command: FfmpegCommand, stdout: StdoutHandler, onStderrLine: ((String) -> Unit)?): FfmpegResult {
        val index = synchronized(commands) { commands.add(command.args); commands.size - 1 }
        if (index == failOn) throw HighlightsException("découpe impossible")
        val requested = command.args[command.args.indexOf("-ss") + 1].toDouble().seconds
        starts[Path(command.args.last())] = (requested - keyframeLead).coerceAtLeast(Duration.ZERO)
        Path(command.args.last()).createFile()
        return FfmpegResult(0, Duration.ZERO, emptyList())
    }

    override suspend fun runProbe(command: FfmpegCommand, stdout: StdoutHandler): FfmpegResult {
        val start = starts.getValue(Path(command.args.last()))
        (stdout as StdoutHandler.Lines).onLine("%.6f".format(Locale.ROOT, start.inWholeMicroseconds / 1e6))
        return FfmpegResult(0, Duration.ZERO, emptyList())
    }

    private val starts: MutableMap<Path, Duration> = Collections.synchronizedMap(mutableMapOf())
}

class SourceCutsTest : FunSpec({
    val media = MediaInfo(
        path = Path("D:/captures/wardogs/partie.mp4"),
        sizeBytes = 1_000_000_000,
        duration = 60.minutes,
        video = VideoStream(0, "h264", 3840, 1606, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000, "Mix")),
    )

    fun cuts(count: Int) = (0 until count).map { SourceCut(media, TimeRange((it * 60).seconds, (it * 60 + 10).seconds)) }

    test("en dessous du seuil, aucune pré-découpe : le rendu lit les sources") {
        runTest {
            val ffmpeg = RecordingFfmpeg()
            val result = SourceCutter.prepare(ffmpeg, cuts(SourceCutter.MIN_CUTS - 1), tempdir().toPath())

            result.isEmpty shouldBe true
            ffmpeg.commands shouldBe emptyList()
            result.input(media, TimeRange(Duration.ZERO, 10.seconds)) shouldBe CutInput(media.path, Duration.ZERO)
        }
    }

    test("au-dessus du seuil, un fichier par extrait, lu à la place de la source") {
        runTest {
            val ffmpeg = RecordingFfmpeg()
            val dir = tempdir().toPath()
            val wanted = cuts(SourceCutter.MIN_CUTS)
            val result = SourceCutter.prepare(ffmpeg, wanted, dir)

            result.size shouldBe SourceCutter.MIN_CUTS
            dir.listDirectoryEntries().size shouldBe SourceCutter.MIN_CUTS
            wanted.forEach { cut ->
                val input = result.input(cut.media, cut.range)
                input.path shouldNotBe media.path
                input.path.exists() shouldBe true
            }
        }
    }

    test("la découpe garde la base de temps de la source et ne réencode pas") {
        runTest {
            val ffmpeg = RecordingFfmpeg()
            SourceCutter.prepare(ffmpeg, cuts(SourceCutter.MIN_CUTS), tempdir().toPath())

            // Premier extrait : [0s, 10s] plus la marge de fin de 500 ms.
            val first = ffmpeg.commands.single { it.contains("-copyts") && it.contains("0.000") }
            first shouldContainInOrder listOf("-ss", "0.000", "-i", media.path.toString(), "-to", "10.500")
            first shouldContainInOrder listOf("-copyts", "-map", "0:v:0", "-map", "0:a?", "-c", "copy")
        }
    }

    test("la marge de fin ne dépasse pas la fin de la source") {
        runTest {
            val ffmpeg = RecordingFfmpeg()
            val last = SourceCut(media, TimeRange(media.duration - 5.seconds, media.duration))
            SourceCutter.prepare(ffmpeg, cuts(SourceCutter.MIN_CUTS) + last, tempdir().toPath())

            ffmpeg.commands.single { it.contains("-copyts") && it.contains("3595.000") } shouldContainInOrder listOf("-to", "3600.000")
        }
    }

    test("extraits identiques découpés une seule fois") {
        runTest {
            val ffmpeg = RecordingFfmpeg()
            val duplicated = cuts(SourceCutter.MIN_CUTS) + cuts(SourceCutter.MIN_CUTS)
            val result = SourceCutter.prepare(ffmpeg, duplicated, tempdir().toPath())

            ffmpeg.commands.count { it.contains("-copyts") } shouldBe SourceCutter.MIN_CUTS
            result.size shouldBe SourceCutter.MIN_CUTS
        }
    }

    test("le départ est recalé sur le début réel de la découpe") {
        runTest {
            val ffmpeg = RecordingFfmpeg(keyframeLead = 1200.milliseconds)
            val wanted = cuts(SourceCutter.MIN_CUTS)
            val result = SourceCutter.prepare(ffmpeg, wanted, tempdir().toPath())

            // La découpe de [60s, 70s] commence à 58,8 s : le rendu doit y demander 1,2 s, pas 60 s.
            result.input(media, wanted[1].range).start shouldBe 1200.milliseconds
            // Premier extrait : la découpe commence à 0 s faute de source avant, donc aucun décalage.
            result.input(media, wanted[0].range).start shouldBe Duration.ZERO
        }
    }

    test("un début de découpe illisible fait retomber sur la lecture directe des sources") {
        runTest {
            val ffmpeg = object : FfmpegService by RecordingFfmpeg() {
                override suspend fun runProbe(command: FfmpegCommand, stdout: StdoutHandler): FfmpegResult {
                    (stdout as StdoutHandler.Lines).onLine("N/A")
                    return FfmpegResult(0, Duration.ZERO, emptyList())
                }
            }
            val dir = tempdir().toPath()
            val result = SourceCutter.prepare(ffmpeg, cuts(SourceCutter.MIN_CUTS), dir)

            result.isEmpty shouldBe true
            dir.listDirectoryEntries() shouldBe emptyList()
        }
    }

    test("une découpe qui échoue fait retomber sur la lecture directe des sources") {
        runTest {
            val ffmpeg = RecordingFfmpeg(failOn = 3)
            val dir = tempdir().toPath()
            val result = SourceCutter.prepare(ffmpeg, cuts(SourceCutter.MIN_CUTS), dir)

            result.isEmpty shouldBe true
            result.input(media, TimeRange(Duration.ZERO, 10.seconds)) shouldBe CutInput(media.path, Duration.ZERO)
            dir.listDirectoryEntries() shouldBe emptyList()
        }
    }
})
