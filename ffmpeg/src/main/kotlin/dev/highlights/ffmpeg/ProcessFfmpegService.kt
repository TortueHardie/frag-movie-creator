package dev.highlights.ffmpeg

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.ffmpeg.FfmpegResult
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MediaInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.time.TimeSource

private val log = KotlinLogging.logger {}

/** « ffmpeg version 7.1-full_build… », « ffmpeg version n6.1.1 », « ffmpeg version 6.1.1-3ubuntu5 ». */
private val VERSION = Regex("""ffmpeg version n?(\d+)\.""")

/**
 * Exécute ffmpeg/ffprobe via ProcessBuilder.
 * - stderr est drainé en continu (sinon le process se bloque quand le tampon du pipe est plein) et ses dernières lignes sont conservées.
 * - l'annulation de la coroutine tue le process.
 */
class ProcessFfmpegService(
    val ffmpegPath: Path,
    val ffprobePath: Path,
    private val stderrLines: Int = 200,
) : FfmpegService {
    private val filterOption = Mutex()
    private var cachedFilterOption: String? = null
    private var majorVersion: Int? = null

    override suspend fun probe(file: Path): MediaInfo {
        if (!file.isRegularFile()) throw InputException("Fichier introuvable : $file")
        val json = StringBuilder()
        exec(
            ffprobePath,
            listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()),
            "ffprobe ${file.name}",
            StdoutHandler.Lines { json.appendLine(it) },
        )
        return FfprobeParser.parse(file, json.toString())
    }

    override suspend fun run(command: FfmpegCommand, stdout: StdoutHandler, onStderrLine: ((String) -> Unit)?): FfmpegResult =
        exec(ffmpegPath, listOf("-hide_banner", "-nostdin", "-nostats") + command.args, command.description, stdout, onStderrLine)

    override suspend fun runProbe(command: FfmpegCommand, stdout: StdoutHandler): FfmpegResult =
        exec(ffprobePath, command.args, command.description, stdout)

    override suspend fun filterScriptOption(): String = filterOption.withLock {
        cachedFilterOption ?: detectFilterOption().also {
            cachedFilterOption = it
            log.info { "Graphe de filtres lu par « $it » (FFmpeg ${majorVersion ?: "?"})" }
        }
    }

    /** Version majeure de ffmpeg, ou null si la ligne de version n'est pas lisible (builds git « N-… »). */
    private suspend fun detectFilterOption(): String {
        var version: String? = null
        runCatching {
            run(FfmpegCommand(listOf("-version"), "version de ffmpeg"), StdoutHandler.Lines { line ->
                if (version == null) VERSION.find(line)?.let { version = it.groupValues[1] }
            })
        }.onFailure { log.warn { "Version de ffmpeg illisible (${it.message}), option moderne supposée" } }
        majorVersion = version?.toIntOrNull()
        // Version inconnue = build git récent : l'option moderne est le bon pari.
        return if ((majorVersion ?: Int.MAX_VALUE) >= 7) FfmpegService.FILTER_COMPLEX_FROM_FILE else FfmpegService.FILTER_COMPLEX_SCRIPT
    }

    private suspend fun exec(
        exe: Path,
        args: List<String>,
        description: String,
        stdout: StdoutHandler,
        onStderrLine: ((String) -> Unit)? = null,
    ): FfmpegResult {
        val commandLine = (listOf(exe.toString()) + args).joinToString(" ") { quoteForLog(it) }
        log.debug { "▶ $description : $commandLine" }
        val mark = TimeSource.Monotonic.markNow()

        val process = withContext(Dispatchers.IO) {
            try {
                ProcessBuilder(listOf(exe.toString()) + args).start().also { it.outputStream.close() }
            } catch (e: IOException) {
                throw HighlightsException("Impossible de lancer $exe : ${e.message}", e)
            }
        }
        val stderrTail = RingBuffer<String>(stderrLines)

        try {
            val exitCode = coroutineScope {
                launch(Dispatchers.IO) {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            stderrTail.add(line)
                            onStderrLine?.invoke(line)
                            log.trace { "[${exe.name}] $line" }
                        }
                    }
                }
                launch(Dispatchers.IO) {
                    when (stdout) {
                        StdoutHandler.Discard -> process.inputStream.use { it.transferTo(OutputStream.nullOutputStream()) }
                        is StdoutHandler.Lines -> process.inputStream.bufferedReader().useLines { it.forEach(stdout.onLine) }
                        is StdoutHandler.Binary -> process.inputStream.use { stdout.consume(it) }
                    }
                }
                try {
                    runInterruptible(Dispatchers.IO) { process.waitFor() }
                } catch (e: Throwable) {
                    // Annulation ou échec d'un lecteur : tuer le process ici, sinon coroutineScope attendrait
                    // indéfiniment les lecteurs de stdout/stderr bloqués sur un process toujours vivant.
                    kill(process, description)
                    throw e
                }
            }

            val result = FfmpegResult(exitCode, mark.elapsedNow(), stderrTail.toList())
            if (exitCode != 0) {
                log.error {
                    "✖ $description a échoué (code retour $exitCode)\n  commande : $commandLine\n  stderr :\n" +
                        result.stderrTail.takeLast(40).joinToString("\n") { "    $it" }
                }
                throw FfmpegException(description, exitCode, commandLine, result.stderrTail)
            }
            log.debug { "✔ $description en ${result.elapsed}" }
            return result
        } finally {
            kill(process, description)
        }
    }

    private fun kill(process: Process, description: String) {
        if (!process.isAlive) return
        log.warn { "Arrêt forcé de $description" }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    private fun quoteForLog(arg: String) = if (arg.isEmpty() || arg.any { it.isWhitespace() || it == '"' }) "\"${arg.replace("\"", "\\\"")}\"" else arg
}

internal class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(item: T) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(item)
    }

    @Synchronized
    fun toList(): List<T> = items.toList()
}
