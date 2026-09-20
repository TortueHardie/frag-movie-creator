package dev.highlights.editing

import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Un extrait lu dans une source par le rendu, c'est-à-dire une entrée FFmpeg. */
data class SourceCut(val media: MediaInfo, val range: TimeRange)

/** Entrée FFmpeg d'un extrait : fichier à lire et instant de départ dans ce fichier. */
data class CutInput(val path: Path, val start: Duration)

/**
 * Fichiers de pré-découpe à lire à la place des sources, indexés par extrait.
 *
 * Un rendu ouvre une entrée FFmpeg par extrait. Chaque entrée réanalyse et garde en mémoire l'index du fichier lu :
 * sur une capture longue en 4K60 (2 h 40, 580 000 images) cela coûte ~70 Mo et ~0,3 s par entrée. À 190 extraits le
 * rendu réclamait 26 Go et la machine paginait. On découpe donc chaque extrait à l'avance, sans réencodage
 * (`-c copy`), et le rendu ne lit plus que de petits fichiers.
 *
 * Une découpe par copie commence forcément sur l'image clé qui précède l'extrait : [CutInput.start] replace le départ
 * exactement où le rendu le demandait, si bien que les images produites sont les mêmes qu'en lisant la source.
 */
class SourceCuts private constructor(private val inputs: Map<Pair<Path, TimeRange>, CutInput>) {
    val size: Int get() = inputs.size

    val isEmpty: Boolean get() = inputs.isEmpty()

    /** Entrée FFmpeg de cet extrait : sa pré-découpe si elle existe, sinon la source lue à l'instant demandé. */
    fun input(media: MediaInfo, range: TimeRange): CutInput =
        inputs[media.path to range] ?: CutInput(media.path, range.start)

    companion object {
        /** Aucune pré-découpe : le rendu lit directement les sources. */
        val NONE = SourceCuts(emptyMap())

        /** Associe chaque extrait à l'entrée qui le remplace. */
        fun of(inputs: Map<SourceCut, CutInput>): SourceCuts =
            if (inputs.isEmpty()) {
                NONE
            } else {
                SourceCuts(inputs.entries.associate { (cut, input) -> (cut.media.path to cut.range) to input })
            }
    }
}

/** Découpe les extraits d'un rendu sans réencodage. Voir [SourceCuts] pour le pourquoi. */
object SourceCutter {
    /** En dessous de ce nombre d'extraits, le rendu direct tient largement en mémoire : la pré-découpe ne vaut pas son I/O. */
    const val MIN_CUTS = 12

    /** Découpes simultanées : au-delà, les têtes de lecture se gênent plus qu'elles ne s'entraident. */
    private const val PARALLELISM = 4

    /**
     * Marge de fin : `-c copy` s'arrête sur une frontière de paquet, or le rendu redemande la longueur exacte.
     * (Le début, lui, remonte tout seul à l'image clé précédente.)
     */
    private val TAIL_MARGIN = 500.milliseconds

    /** Avance typique jusqu'à l'image clé précédente, comptée dans l'estimation de taille. */
    private val KEYFRAME_MARGIN = 2.seconds

    /** Marge de sécurité sur l'espace disque estimé. */
    private const val DISK_HEADROOM = 1.5

    /**
     * Pré-découpe [cuts] dans [dir] et renvoie les entrées à lire à leur place. En cas d'empêchement (trop peu
     * d'extraits, disque insuffisant, échec d'une découpe) renvoie [SourceCuts.NONE] : le rendu lit alors les
     * sources, plus lentement mais avec le même résultat.
     */
    suspend fun prepare(
        ffmpeg: FfmpegService,
        cuts: List<SourceCut>,
        dir: Path,
        progress: ProgressReporter = ProgressReporter.NONE,
    ): SourceCuts {
        val distinct = cuts.distinct()
        if (distinct.size < MIN_CUTS) {
            progress.complete()
            return SourceCuts.NONE
        }
        dir.createDirectories()

        val estimate = distinct.sumOf { estimateBytes(it) }
        val usable = runCatching { Files.getFileStore(dir).usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (estimate * DISK_HEADROOM > usable) {
            log.warn {
                "Pré-découpe abandonnée : ${mb(estimate)} estimés pour ${distinct.size} extraits, ${mb(usable)} disponibles sur $dir"
            }
            progress.complete()
            return SourceCuts.NONE
        }

        log.info { "Pré-découpe de ${distinct.size} extraits (~${mb(estimate)}) dans $dir" }
        val done = AtomicInteger()
        val inputs = try {
            coroutineScope {
                val gate = Semaphore(PARALLELISM)
                distinct.mapIndexed { i, cut ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val file = dir.resolve("cut_%04d.mp4".format(i))
                            ffmpeg.run(cutCommand(cut, file))
                            val input = CutInput(file, seekIn(ffmpeg, file, cut))
                            val n = done.incrementAndGet()
                            progress.update(n.toDouble() / distinct.size, "extrait $n/${distinct.size}")
                            cut to input
                        }
                    }
                }.awaitAll().toMap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "Pré-découpe impossible, rendu direct depuis les sources : ${e.message}" }
            clean(dir)
            progress.complete()
            return SourceCuts.NONE
        }

        val written = inputs.values.sumOf { runCatching { it.path.fileSize() }.getOrDefault(0L) }
        log.info { "Pré-découpe terminée : ${inputs.size} fichiers, ${mb(written)}" }
        progress.complete()
        return SourceCuts.of(inputs)
    }

    /**
     * `-ss` avant `-i` (seek rapide, remonte à l'image clé précédente), `-copyts` pour garder la base de temps de la
     * source, `-to` en temps source donc absolu. Toutes les pistes audio sont conservées : le rendu choisit la sienne.
     */
    private fun cutCommand(cut: SourceCut, output: Path) = FfmpegCommand(
        listOf(
            "-ss", Durations.ffmpegSeconds(cut.range.start),
            "-i", cut.media.path.toString(),
            "-to", Durations.ffmpegSeconds(minOf(cut.range.end + TAIL_MARGIN, cut.media.duration)),
            "-copyts",
            "-map", "0:v:0", "-map", "0:a?",
            "-c", "copy",
            "-y", output.toString(),
        ),
        "découpe ${cut.media.path.fileName} ${cut.range}",
    )

    /**
     * Instant de départ à demander dans la découpe. Celle-ci commence sur l'image clé qui précède l'extrait, et FFmpeg
     * ajoute à tout `-ss` le début déclaré du fichier : le décalage à retrancher est donc ce début.
     *
     * C'est bien celui du conteneur, pas celui du flux vidéo : FFmpeg prend le plus petit début parmi les flux, et
     * l'audio démarre souvent quelques millisecondes avant la première image. Retrancher le début de la vidéo
     * décalerait tout le clip d'autant — moins d'une image, donc invisible à l'œil, mais audible.
     *
     * Sans ce début, la découpe est inexploitable (un `-ss` en temps source viserait au-delà de sa fin) : on lève
     * plutôt que de deviner, et [prepare] repart alors sur les sources.
     */
    private suspend fun seekIn(ffmpeg: FfmpegService, file: Path, cut: SourceCut): Duration {
        val output = StringBuilder()
        ffmpeg.runProbe(
            FfmpegCommand(
                listOf("-v", "error", "-show_entries", "format=start_time", "-of", "csv=p=0", file.toString()),
                "début de ${file.fileName}",
            ),
            StdoutHandler.Lines { output.appendLine(it) },
        )
        val start = output.toString().trim().toDoubleOrNull()?.seconds
            ?: throw HighlightsException("Début illisible de la découpe $file : \"${output.toString().trim()}\"")
        return (cut.range.start - start).coerceAtLeast(Duration.ZERO)
    }

    /** Taille attendue de la découpe : débit moyen de la source sur la longueur demandée, marge d'image clé comprise. */
    private fun estimateBytes(cut: SourceCut): Long {
        val millis = cut.media.duration.inWholeMilliseconds
        if (millis <= 0) return 0
        val perMilli = cut.media.sizeBytes.toDouble() / millis
        return (perMilli * (cut.range.length + TAIL_MARGIN + KEYFRAME_MARGIN).inWholeMilliseconds).toLong()
    }

    private suspend fun clean(dir: Path) = withContext(Dispatchers.IO) {
        runCatching { Files.list(dir).use { s -> s.forEach { runCatching { Files.deleteIfExists(it) } } } }
    }

    private fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f Go".format(bytes / 1e9) else "%d Mo".format(bytes / 1_000_000)
}
