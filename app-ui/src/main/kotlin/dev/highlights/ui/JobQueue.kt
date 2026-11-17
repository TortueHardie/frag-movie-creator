package dev.highlights.ui

import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.progress.ProgressSnapshot
import dev.highlights.core.progress.ProgressTracker
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

enum class JobStatus { WAITING, RUNNING, DONE, FAILED, CANCELLED }

/**
 * Un traitement de la file : [blocking] s'il change ce que montre l'écran (analyse, réanalyse : l'interface attend
 * avec lui) ; [result] : fichier à ouvrir une fois fini (vidéo exportée) ; [error]/[details] : l'échec, lisible.
 */
data class QueuedJob(
    val id: Long,
    val title: String,
    val blocking: Boolean = false,
    val status: JobStatus = JobStatus.WAITING,
    val fraction: Double = 0.0,
    val stage: String = "",
    val elapsed: Duration = Duration.ZERO,
    val eta: Duration? = null,
    val result: Path? = null,
    val error: String? = null,
    val details: List<String> = emptyList(),
) {
    val active: Boolean get() = status == JobStatus.WAITING || status == JobStatus.RUNNING
}

/**
 * File des traitements lourds (analyse, export, montage, aperçus, montage de soirée) : un seul à la fois, FFmpeg et la
 * mémoire ne supportent pas mieux (un export 9:16 seul prend déjà 1,5 Go, voir [MEMORY_EXPORT]) ; les suivants
 * attendent dans l'ordre, que l'utilisateur peut changer. Un échec ne bloque pas la suite : il reste affiché, lisible.
 * Sans rien de Compose : testée seule ([onChange] reçoit la liste à chaque changement).
 */
class JobQueue(
    private val scope: CoroutineScope,
    private val onChange: (List<QueuedJob>) -> Unit,
    /** Message lisible et détails d'un échec (voir AppController.describeFailure). */
    private val describe: (Throwable) -> Pair<String, List<String>> = { (it.localizedMessage ?: it.toString()) to emptyList() },
    private val context: CoroutineContext = Dispatchers.Default,
    /** Traitements finis gardés à l'écran : de quoi rouvrir les derniers résultats sans allonger la carte sans fin. */
    private val keepFinished: Int = 8,
) {
    private class Entry(val block: suspend (ProgressReporter) -> Path?, val onProgress: ((ProgressSnapshot) -> Unit)?)

    private val lock = Any()
    private var jobs: List<QueuedJob> = emptyList()
    private val entries = mutableMapOf<Long, Entry>()
    private var running: Job? = null
    private var runningId: Long? = null
    private var nextId = 1L

    val snapshot: List<QueuedJob> get() = synchronized(lock) { jobs }

    /** Traitements en cours ou en attente (ce que la fermeture de l'application annulerait). */
    val activeCount: Int get() = synchronized(lock) { jobs.count { it.active } }

    /**
     * Ajoute [block] à la file (lancé aussitôt si rien ne tourne) ; il rend le fichier produit, ou null. [onProgress] :
     * pour qui suit aussi l'avancement ailleurs (carte du dossier surveillé).
     */
    fun submit(title: String, blocking: Boolean = false, onProgress: ((ProgressSnapshot) -> Unit)? = null, block: suspend (ProgressReporter) -> Path?): Long {
        val id = synchronized(lock) {
            val id = nextId++
            entries[id] = Entry(block, onProgress)
            jobs = jobs + QueuedJob(id, title, blocking)
            id
        }
        publish()
        pump()
        return id
    }

    /** Annule un traitement : retiré de l'attente, ou arrêté s'il tourne (ses process FFmpeg sont tués). */
    fun cancel(id: Long) {
        val job = synchronized(lock) {
            val current = jobs.find { it.id == id } ?: return
            when (current.status) {
                JobStatus.WAITING -> {
                    entries.remove(id)
                    update(id) { it.copy(status = JobStatus.CANCELLED) }
                    null
                }
                JobStatus.RUNNING -> running
                else -> return
            }
        }
        if (job != null) job.cancel(CancellationException("Annulé par l'utilisateur")) else publish()
    }

    /** Avance ([delta] < 0) ou recule un traitement en attente parmi ceux qui attendent. */
    fun move(id: Long, delta: Int) {
        synchronized(lock) {
            val waiting = jobs.filter { it.status == JobStatus.WAITING }
            val from = waiting.indexOfFirst { it.id == id }
            if (from < 0) return
            val to = (from + delta).coerceIn(0, waiting.lastIndex)
            if (to == from) return
            val reordered = waiting.toMutableList().apply { add(to, removeAt(from)) }
            // Les autres (en cours, finis) gardent leur place ; les attentes prennent le nouvel ordre.
            val it = reordered.iterator()
            jobs = jobs.map { j -> if (j.status == JobStatus.WAITING) it.next() else j }
        }
        publish()
    }

    /** Oublie les traitements finis (réussis, en échec ou annulés). */
    fun clearFinished() {
        synchronized(lock) { jobs = jobs.filter { it.active } }
        publish()
    }

    /** Arrête tout (fermeture de l'application). */
    fun cancelAll() {
        synchronized(lock) {
            jobs.filter { it.status == JobStatus.WAITING }.forEach { j -> entries.remove(j.id); update(j.id) { it.copy(status = JobStatus.CANCELLED) } }
            running
        }?.cancel(CancellationException("Fermeture de l'application"))
        publish()
    }

    /** Lance le premier traitement en attente si rien ne tourne. */
    private fun pump() {
        val (job, entry) = synchronized(lock) {
            if (running != null) return
            val next = jobs.firstOrNull { it.status == JobStatus.WAITING } ?: return
            val entry = entries.remove(next.id) ?: return
            runningId = next.id
            update(next.id) { it.copy(status = JobStatus.RUNNING) }
            next to entry
        }
        publish()
        val lastUpdate = AtomicLong(0)
        val tracker = ProgressTracker { snap ->
            entry.onProgress?.invoke(snap)
            val now = System.currentTimeMillis()
            if (snap.fraction >= 1.0 || now - lastUpdate.getAndUpdate { prev -> if (now - prev >= 100) now else prev } >= 100) {
                synchronized(lock) { update(job.id) { it.copy(fraction = snap.fraction, stage = snap.stage, elapsed = snap.elapsed, eta = snap.eta) } }
                publish()
            }
        }
        val launched = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val outcome: QueuedJob = try {
                val result = withContext(context) { entry.block(tracker.root) }
                job.copy(status = JobStatus.DONE, fraction = 1.0, result = result)
            } catch (e: CancellationException) {
                log.info { "${job.title} annulé" }
                job.copy(status = JobStatus.CANCELLED)
            } catch (e: Throwable) {
                if (e !is Exception && e !is OutOfMemoryError) throw e
                log.error(e) { "${job.title} : échec" }
                val (message, details) = describe(e)
                job.copy(status = JobStatus.FAILED, error = message, details = details)
            }
            synchronized(lock) {
                // L'avancement affiché reste celui du dernier instant, le statut change.
                update(job.id) { current -> current.copy(status = outcome.status, result = outcome.result, error = outcome.error, details = outcome.details, fraction = if (outcome.status == JobStatus.DONE) 1.0 else current.fraction) }
                running = null
                runningId = null
                trimFinished()
            }
            publish()
            pump()
        }
        synchronized(lock) { running = launched }
        launched.start()
    }

    private fun update(id: Long, change: (QueuedJob) -> QueuedJob) {
        jobs = jobs.map { if (it.id == id) change(it) else it }
    }

    private fun trimFinished() {
        val finished = jobs.filter { !it.active }
        if (finished.size <= keepFinished) return
        val dropped = finished.take(finished.size - keepFinished).map { it.id }.toSet()
        jobs = jobs.filterNot { it.id in dropped }
    }

    private fun publish() = onChange(snapshot)
}
