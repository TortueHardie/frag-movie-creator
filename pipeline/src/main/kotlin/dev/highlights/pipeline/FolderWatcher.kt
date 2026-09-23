package dev.highlights.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = KotlinLogging.logger {}

/**
 * Repère les nouvelles captures d'un dossier (et de ses sous-dossiers : Outplayed range chaque jeu à part), à appeler
 * régulièrement avec [poll]. Une capture n'est proposée qu'une fois terminée : taille et date inchangées depuis
 * [settle], l'enregistreur (OBS, Outplayed…) écrivant le fichier au fil de la partie.
 *
 * Seules les captures modifiées après [since] comptent : choisir un dossier qui contient déjà des centaines de
 * parties ne les fait pas toutes analyser, alors que celles arrivées pendant que l'application était fermée le sont.
 */
class FolderWatcher(
    val folder: Path,
    private val since: Instant,
    private val settle: Duration = 15.seconds,
    private val clock: () -> Instant = Instant::now,
    private val maxDepth: Int = 4,
) {
    private data class Observation(val size: Long, val modified: Long, val stableSince: Instant)

    private val observed = mutableMapOf<Path, Observation>()

    /** Captures déjà proposées, avec leur taille et date d'alors : réécrite, une capture est reproposée. */
    private val proposed = mutableMapOf<Path, Pair<Long, Long>>()

    /** Captures prêtes, nouvelles depuis [since], ni déjà proposées ni connues de [isKnown] (déjà analysées). */
    @Synchronized
    fun poll(isKnown: (Path) -> Boolean): List<Path> {
        if (!folder.isDirectory()) return emptyList()
        val now = clock()
        val files = try {
            Files.walk(folder, maxDepth).use { stream ->
                stream.asSequence()
                    .filter { it.extension.lowercase() in HighlightPipeline.SUPPORTED_EXTENSIONS && it.isRegularFile() }
                    .map { it.toAbsolutePath().normalize() }
                    .toList()
            }
        } catch (e: Exception) {
            log.warn { "Lecture du dossier surveillé $folder impossible : ${e.message}" }
            return emptyList()
        }
        observed.keys.retainAll(files.toSet())
        val ready = mutableListOf<Path>()
        for (file in files) {
            val stamp = AnalysisLibrary.stamp(file) ?: continue
            if (proposed[file] == stamp) continue
            val (size, modified) = stamp
            if (modified <= since.toEpochMilli()) continue
            val previous = observed[file]
            val current = if (previous != null && previous.size == size && previous.modified == modified) previous else Observation(size, modified, now)
            observed[file] = current
            val settled = !current.stableSince.plus(settle.toJavaDuration()).isAfter(now)
            if (size > 0 && settled && !isKnown(file)) {
                proposed[file] = stamp
                ready.add(file)
            }
        }
        return ready.sortedBy { proposed[it]?.second }
    }
}
