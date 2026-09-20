package dev.highlights.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.core.ConfigException
import dev.highlights.core.HighlightsException
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.progress.ProgressSnapshot
import dev.highlights.core.serialization.toShortText
import dev.highlights.pipeline.ConfigLocator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

class HighlightsCli : CliktCommand(name = "app") {
    private val config by option("-c", "--config", help = "Fichier app.yaml (défaut : ./config/app.yaml puis celui de l'installation)").path()
    private val verbose by option("-v", "--verbose", help = "Logs détaillés (commandes FFmpeg, stderr)").flag()

    override fun help(context: Context) = "Transforme des captures de gameplay en montages des meilleurs moments."

    override fun run() {
        if (verbose) (LoggerFactory.getLogger("dev.highlights") as Logger).level = Level.TRACE
        currentContext.findOrSetObject { CliEnv(config) }
    }
}

class CliEnv(private val explicitConfig: Path?) {
    val config: LoadedConfig by lazy { ConfigYaml.load(ConfigLocator.locate(explicitConfig)) }
}

abstract class PipelineCommand(name: String) : CliktCommand(name = name) {
    protected val env by requireObject<CliEnv>()

    /** Exécute un traitement suspendu, annulable par Ctrl+C (les process FFmpeg en cours sont tués). */
    protected fun <T> execute(block: suspend CoroutineScope.() -> T): T {
        var hook: Thread? = null
        // Les erreurs sont traitées hors de runBlocking : l'échec du job enfant annule le parent,
        // une exception levée dans le runBlocking serait masquée par l'exception d'origine.
        try {
            return runBlocking {
                val job = async(Dispatchers.Default) { block() }
                hook = Thread {
                    job.cancel(CancellationException("Interrompu par l'utilisateur"))
                    runBlocking { withTimeoutOrNull(5.seconds) { job.join() } }
                }.also { Runtime.getRuntime().addShutdownHook(it) }
                job.await()
            }
        } catch (e: FfmpegException) {
            System.err.println()
            System.err.println("Erreur : ${e.message}")
            System.err.println("  commande : ${e.commandLine}")
            e.stderrTail.takeLast(15).forEach { System.err.println("  | $it") }
            System.err.println("Détails complets dans le journal : ${LogFiles.current()}")
            throw ProgramResult(2)
        } catch (e: HighlightsException) {
            System.err.println()
            System.err.println("Erreur : ${e.message}")
            log.debug(e) { "Détail de l'erreur" }
            throw ProgramResult(1)
        } catch (e: CancellationException) {
            System.err.println()
            System.err.println("Interrompu.")
            throw ProgramResult(130)
        } catch (e: Exception) {
            log.error(e) { "Erreur inattendue" }
            System.err.println()
            System.err.println("Erreur inattendue : $e (voir ${LogFiles.current()})")
            throw ProgramResult(3)
        } finally {
            hook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        }
    }
}

object LogFiles {
    fun current(): String = System.getProperty("highlights.logFile")
        ?: Path(System.getProperty("user.home"), ".highlights", "logs", "highlights.log").toString()
}

/** Barre de progression sur une seule ligne (stderr), rafraîchie au plus toutes les 200 ms. */
class ConsoleProgress(private val out: PrintStream = System.err) : (ProgressSnapshot) -> Unit {
    private val lock = Any()
    private var lastRender = 0L
    private var lastLength = 0
    private var lastPercentLine = -1
    private val interactive = System.console() != null

    override fun invoke(s: ProgressSnapshot): Unit = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now - lastRender < 200 && s.fraction < 1.0) return
        lastRender = now

        val percent = s.fraction * 100
        val eta = s.eta?.toShortText() ?: "?"
        if (!interactive) {
            val bucket = (percent / 10).toInt()
            if (bucket != lastPercentLine) {
                lastPercentLine = bucket
                out.println("%5.1f%%  %s  (reste %s)".format(percent, s.stage, eta))
            }
            return
        }
        val width = 30
        val filled = (s.fraction * width).toInt().coerceIn(0, width)
        val line = "[%s%s] %5.1f%%  %-28s écoulé %s  reste %s".format(
            "#".repeat(filled), "-".repeat(width - filled), percent, s.stage.take(28), s.elapsed.toShortText(), eta,
        )
        out.print("\r" + line + " ".repeat((lastLength - line.length).coerceAtLeast(0)))
        out.flush()
        lastLength = line.length
    }

    fun finish() = synchronized(lock) {
        if (interactive && lastLength > 0) out.println()
        lastLength = 0
    }
}
