package dev.highlights.ui

import dev.highlights.pipeline.ProblemReport
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/** Plantage à proposer de signaler : [summary] est le texte technique de l'exception (non traduit). */
data class CrashNotice(val at: Instant, val summary: String, val jvm: Boolean = false)

/**
 * Plantages remarqués pour être proposés au signalement (« Un problème est survenu — le signaler ? ») au lieu d'échouer
 * en silence :
 * - exception non gérée : [record] écrit `crash-<date>.txt` (pile complète) dans le dossier des journaux et note qu'il
 *   reste à proposer (`crash-pending`). Proposé tout de suite si l'application tient encore debout, sinon au lancement suivant ;
 * - plantage de la JVM elle-même (mémoire épuisée au démarrage, pilote) : un `hs_err_pid*.log` plus récent que le
 *   dernier passage ([acknowledge]) dans un des [crashDirs].
 *
 * Au tout premier lancement, rien d'ancien n'est proposé : la date de référence est posée à « maintenant ».
 */
class CrashLog(private val dir: Path, private val crashDirs: () -> List<Path> = { emptyList() }) {
    private val pendingFile get() = dir.resolve("crash-pending")
    private val checkedFile get() = dir.resolve("crash-checked")

    /** Note l'exception ; jamais d'exception en retour (on est déjà dans un plantage). */
    fun record(thread: String, e: Throwable): Path? = runCatching {
        dir.createDirectories()
        val now = Instant.now()
        val file = dir.resolve("crash-${STAMP.format(now)}.txt")
        file.writeText("$now [$thread]\n\n${e.stackTraceToString()}")
        pendingFile.writeText("${now.toEpochMilli()}\n${summary(e)}")
        file
    }.getOrNull()

    /** Plantage pas encore proposé, le plus récent ; null s'il n'y en a pas. */
    fun pending(): CrashNotice? {
        val checked = runCatching { checkedFile.readText().trim().toLong() }.getOrNull()
        if (checked == null) {
            // Premier lancement avec cette fonction : les vieux plantages ne concernent pas cette version.
            runCatching { dir.createDirectories(); checkedFile.writeText(System.currentTimeMillis().toString()) }
        }
        val recorded = runCatching {
            val lines = pendingFile.readText().lines()
            CrashNotice(Instant.ofEpochMilli(lines[0].trim().toLong()), lines.drop(1).joinToString("\n").trim())
        }.getOrNull()
        val jvm = if (checked == null) null else crashDirs().filter { it.isDirectory() }
            .flatMap { d -> runCatching { Files.list(d).use { s -> s.filter { it.isRegularFile() && ProblemReport.JVM_CRASH.matches(it.name) }.toList() } }.getOrDefault(emptyList()) }
            .map { it to it.getLastModifiedTime().toInstant() }
            .filter { (_, at) -> at.toEpochMilli() > checked }
            .maxByOrNull { (_, at) -> at }
            ?.let { (file, at) -> CrashNotice(at, jvmSummary(file), jvm = true) }
        return listOfNotNull(recorded, jvm).maxByOrNull { it.at }
    }

    /** Proposition vue (signalée ou non) : elle ne revient plus. */
    fun acknowledge() {
        runCatching {
            pendingFile.deleteIfExists()
            if (dir.exists()) checkedFile.writeText(System.currentTimeMillis().toString())
        }.onFailure { log.warn { "Plantage non marqué comme vu : ${it.message}" } }
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

        /** Dossier des journaux (celui de logback, `highlights.logFile` compris). */
        val DEFAULT_DIR: Path = (System.getProperty("highlights.logFile")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".highlights", "logs", ProblemReport.LOG_NAME)).parent

        fun summary(e: Throwable): String = "${e::class.qualifiedName ?: e::class.java.name}: ${e.message.orEmpty()}".trim().take(300)

        /**
         * Deux premières lignes de l'en-tête d'un `hs_err` : la cause (« There is insufficient memory… ») et son détail
         * (« Native memory allocation (mmap) failed… », « EXCEPTION_ACCESS_VIOLATION… »).
         */
        private fun jvmSummary(file: Path): String = runCatching {
            file.toFile().bufferedReader().useLines { lines ->
                lines.take(40).filter { it.startsWith("#") }.map { it.removePrefix("#").trim() }.filter { it.isNotEmpty() }
                    .take(2).joinToString(" — ").take(300).ifEmpty { null }
            }
        }.getOrNull() ?: file.name
    }
}
