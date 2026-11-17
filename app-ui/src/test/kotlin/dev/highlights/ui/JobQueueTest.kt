package dev.highlights.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.io.path.Path

class JobQueueTest : FunSpec({
    fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Attend que [condition] soit vraie (la file tourne sur d'autres fils). */
    fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    fun JobQueue.status(id: Long) = snapshot.single { it.id == id }.status

    test("un seul traitement à la fois, dans l'ordre d'arrivée ; le résultat de chacun est gardé") {
        val scope = scope()
        val queue = JobQueue(scope, onChange = {})
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val a = queue.submit("A") { order += "A"; gate.await(); Path("a.mp4") }
        val b = queue.submit("B") { order += "B"; Path("b.mp4") }
        val c = queue.submit("C") { order += "C"; null }
        waitFor { queue.status(a) == JobStatus.RUNNING }
        queue.status(b) shouldBe JobStatus.WAITING
        queue.status(c) shouldBe JobStatus.WAITING
        order shouldBe listOf("A")
        gate.complete(Unit)
        waitFor { queue.status(c) == JobStatus.DONE }
        order shouldBe listOf("A", "B", "C")
        queue.snapshot.map { it.result } shouldBe listOf(Path("a.mp4"), Path("b.mp4"), null)
        queue.activeCount shouldBe 0
        scope.cancel()
    }

    test("annuler : un traitement en attente ne part jamais, celui en cours s'arrête et la file continue") {
        val scope = scope()
        val queue = JobQueue(scope, onChange = {})
        val ran = Collections.synchronizedList(mutableListOf<String>())
        val a = queue.submit("A") { ran += "A"; awaitCancellation() }
        val b = queue.submit("B") { ran += "B"; null }
        val c = queue.submit("C") { ran += "C"; null }
        waitFor { queue.status(a) == JobStatus.RUNNING }
        queue.cancel(b)
        queue.status(b) shouldBe JobStatus.CANCELLED
        queue.cancel(a)
        waitFor { queue.status(c) == JobStatus.DONE }
        queue.status(a) shouldBe JobStatus.CANCELLED
        ran shouldBe listOf("A", "C")
        scope.cancel()
    }

    test("un échec reste affiché, lisible, sans bloquer les suivants") {
        val scope = scope()
        val queue = JobQueue(scope, onChange = {}, describe = { "lisible : ${it.message}" to listOf("détail") })
        val a = queue.submit("A") { error("FFmpeg a échoué") }
        val b = queue.submit("B") { Path("b.mp4") }
        waitFor { queue.status(b) == JobStatus.DONE }
        val failed = queue.snapshot.single { it.id == a }
        failed.status shouldBe JobStatus.FAILED
        failed.error shouldBe "lisible : FFmpeg a échoué"
        failed.details shouldBe listOf("détail")
        scope.cancel()
    }

    test("ordre modifiable : un traitement en attente avance ou recule, celui en cours ne bouge pas") {
        val scope = scope()
        val queue = JobQueue(scope, onChange = {})
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = CompletableDeferred<Unit>()
        val a = queue.submit("A") { gate.await(); order += "A"; null }
        queue.submit("B") { order += "B"; null }
        val c = queue.submit("C") { order += "C"; null }
        val d = queue.submit("D") { order += "D"; null }
        waitFor { queue.status(a) == JobStatus.RUNNING }
        queue.move(c, -1)
        queue.move(d, -5)
        queue.snapshot.map { it.title } shouldBe listOf("A", "D", "C", "B")
        gate.complete(Unit)
        waitFor { queue.activeCount == 0 }
        order shouldBe listOf("A", "D", "C", "B")
        // Effacer les finis vide la carte.
        queue.clearFinished()
        queue.snapshot shouldBe emptyList()
        scope.cancel()
    }

    test("tout annuler à la fermeture : rien ne reste en cours ni en attente") {
        val scope = scope()
        val queue = JobQueue(scope, onChange = {})
        val a = queue.submit("A") { awaitCancellation() }
        queue.submit("B") { null }
        waitFor { queue.status(a) == JobStatus.RUNNING }
        queue.activeCount shouldBe 2
        queue.cancelAll()
        waitFor { queue.activeCount == 0 }
        queue.snapshot.map { it.status } shouldBe listOf(JobStatus.CANCELLED, JobStatus.CANCELLED)
        scope.cancel()
    }
})
