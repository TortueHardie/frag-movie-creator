package dev.highlights.vision

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readLines
import kotlin.io.path.writeLines

/**
 * OCR intégré à Windows (Windows.Media.Ocr), piloté par un script PowerShell : rien à installer, mais Windows seulement.
 * Les images sont traitées par lots, chacun dans son propre processus, en parallèle et en arrière-plan : l'OCR avance
 * pendant que la vidéo est encore décodée, et les images d'un lot sont supprimées dès qu'il est lu.
 */
class WindowsOcr(
    private val workDir: Path,
    private val language: String,
    parallelism: Int,
    private val batchSize: Int = 400,
) : AutoCloseable {

    @Serializable
    private data class Line(val text: String = "", val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0)

    @Serializable
    private data class Result(val file: String, val lines: List<Line> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val script: Path = workDir.createDirectories().resolve("windows-ocr.ps1").also { target ->
        val source = WindowsOcr::class.java.getResourceAsStream("windows-ocr.ps1") ?: throw IOException("script OCR introuvable")
        source.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    }
    private val executor: ExecutorService = Executors.newFixedThreadPool(parallelism) { r -> Thread(r, "ocr").apply { isDaemon = true } }
    private val pending = mutableListOf<Path>()
    private val batches = mutableListOf<Future<Map<Path, List<OcrLine>>>>()
    private var batchIndex = 0

    /** Ajoute une image ; un lot plein part aussitôt à l'OCR. */
    @Synchronized
    fun submit(image: Path) {
        pending.add(image)
        if (pending.size >= batchSize) flush()
    }

    /** Envoie le lot en cours, puis attend tous les résultats (image → lignes). */
    fun results(timeoutMinutes: Long = 30): Map<Path, List<OcrLine>> {
        synchronized(this) { flush() }
        return batches.flatMap { it.get(timeoutMinutes, TimeUnit.MINUTES).entries }.associate { it.key to it.value }
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val images = pending.toList()
        pending.clear()
        val n = batchIndex++
        batches.add(executor.submit(Callable { recognize(images, n) }))
    }

    private fun recognize(images: List<Path>, n: Int): Map<Path, List<OcrLine>> {
        val list = workDir.resolve("ocr-$n.txt").also { f -> f.writeLines(images.map { it.absolutePathString() }) }
        val out = workDir.resolve("ocr-$n.jsonl")
        try {
            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePathString(), "-List", list.absolutePathString(), "-Out", out.absolutePathString(),
                "-Language", language,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw IOException("OCR : délai dépassé sur le lot $n")
            }
            if (process.exitValue() != 0) throw IOException("OCR : échec du lot $n (code ${process.exitValue()}) : ${output.trim().take(500)}")
            val byPath = images.associateBy { it.absolutePathString() }
            return out.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { line ->
                val r = json.decodeFromString(Result.serializer(), line)
                val image = byPath[r.file] ?: return@mapNotNull null
                image to r.lines.map { OcrLine(it.text, it.x, it.y, it.w, it.h) }
            }.toMap()
        } finally {
            images.forEach { it.deleteIfExists() }
            list.deleteIfExists()
            out.deleteIfExists()
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        val supported: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}
