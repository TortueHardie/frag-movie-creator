package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import dev.highlights.editing.CutInput
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.SourceCut
import dev.highlights.editing.SourceCuts
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Copies réduites (« proxies ») des passages de capture que lisent les aperçus de montage ([MontagePreview]).
 *
 * Un aperçu 360p décodait quand même chaque extrait en pleine taille : une capture WARDOGS fait 3440x1440 à 60 img/s,
 * et ce décodage restait l'essentiel du temps d'un aperçu (mesures dans `output/revue/apercu-rapide`). Les trois
 * variantes proposées reprennent en grande partie les mêmes kills, et on relance souvent les aperçus des mêmes parties
 * (autre musique, autre style) : chaque passage n'est donc décodé en pleine taille qu'une fois, réencodé à la taille
 * que l'aperçu montre vraiment ([height]), avec une image clé toutes les [KEYFRAME_STEP] pour que chaque extrait se
 * cherche vite, et les aperçus suivants ne lisent plus que ces petites copies.
 *
 * Par passage, pas par capture entière : une soirée WARDOGS dure 1 h 46, la réduire entière décoderait une heure trois
 * quarts de 3440x1440 pour un montage qui en montre trente secondes. Les passages sont ceux que les plans lisent
 * ([MontageRenderBuilder.sourceCuts]), élargis de [PAD] et regroupés quand ils se touchent presque ([MERGE_GAP]) :
 * une autre musique décale un peu les plans autour des mêmes kills, et retombe le plus souvent dans un passage déjà fait.
 *
 * Ce qui reste identique à l'aperçu lu sur la capture : l'horodatage de chaque image (`-copyts`, cadence d'origine
 * gardée image pour image), donc les coupes et la place des kills ; le son, copié tel quel (toutes les pistes). Le
 * cadrage aussi : les zones (recadrage 9:16, HUD recollé, centre des zooms) sont en coordonnées relatives à l'image et
 * se calculent sur la taille du proxy ([apply] donne aux plans un [MediaInfo] à cette taille).
 *
 * Rangés dans [dir], un dossier par capture et par hauteur, oubliés quand la capture change (taille, date) ou disparaît,
 * et au-delà de [maxBytes] les moins récemment servis sont effacés.
 */
class PreviewProxies(private val dir: Path, private val maxBytes: Long = MAX_BYTES) {
    private val lock = Mutex()

    /**
     * Proxies de tous les extraits de [cuts] dont [heights] donne une hauteur (null : capture lue telle quelle), faits
     * s'ils manquent. Jamais d'échec : un passage impossible à réduire est lu sur la capture, comme avant.
     */
    suspend fun prepare(
        ffmpeg: FfmpegService,
        cuts: List<SourceCut>,
        heights: (MediaInfo) -> Int?,
        progress: ProgressReporter = ProgressReporter.NONE,
    ): ProxySources = lock.withLock {
        try {
            sweep()
            val byMedia = cuts.distinct().groupBy { it.media.path.toAbsolutePath().normalize() }
            val jobs = mutableListOf<Pair<Store, TimeRange>>()
            val stores = byMedia.mapNotNull { (path, list) ->
                val media = list.first().media
                val video = media.video ?: return@mapNotNull null
                val height = heights(media) ?: return@mapNotNull null
                val stamp = stamp(path) ?: return@mapNotNull null
                val store = load(path, media, ProxySize(even(video.width.toDouble() * height / video.height), height), stamp)
                val missing = list.map { clamp(it.range, media) }.filter { r -> store.index.segments.none { it.covers(r) } }
                merge(missing.map { padded(it, media) }).forEach { jobs += store to it }
                store
            }
            build(ffmpeg, jobs, progress)
            val sources = ProxySources.of(stores.associate { s -> s.path to s.proxy() }, cuts)
            stores.forEach { s -> s.touch(sources.usedFiles(s.path)); s.save() }
            limit(sources.allUsed())
            log.info { "Proxies d'aperçu : ${sources.size}/${cuts.distinct().size} extrait(s) servis par des proxies, ${jobs.size} passage(s) faits ; ${usage()}" }
            sources
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "Proxies d'aperçu impossibles, aperçu lu sur les captures : ${e.message}" }
            ProxySources.NONE
        } finally {
            progress.complete()
        }
    }

    /**
     * Proxies des aperçus de [plans] rendus avec [request] (aperçu : format et taille de [MontagePreview]) : tous les
     * extraits de toutes les variantes en un passage, un même kill repris par deux variantes n'est réduit qu'une fois.
     */
    suspend fun prepare(ffmpeg: FfmpegService, plans: List<MontagePlan>, request: MontageExportRequest, progress: ProgressReporter = ProgressReporter.NONE): ProxySources {
        val format = MontagePreview.format(request.formats)
        val edit = MontagePreview.edit(request.edit)
        val cuts = plans.flatMap { MontageRenderBuilder.sourceCuts(MontagePreview.plan(it), edit.fps) }
        return prepare(ffmpeg, cuts, { height(format, it, edit) }, progress)
    }

    /** Place prise par les proxies, pour le dire (journal, `doctor`). */
    fun usage(): ProxyUsage {
        val indexes = indexes().map { it.second }
        return ProxyUsage(indexes.size, indexes.sumOf { it.segments.size }, indexes.sumOf { i -> i.segments.sumOf { it.bytes } }, maxBytes)
    }

    /** Efface les proxies des captures modifiées ou disparues, et ceux d'une ancienne [VERSION]. */
    fun sweep() {
        for ((folder, index) in indexes()) {
            val source = Path.of(index.source)
            val stale = index.version != VERSION || stamp(source) != (index.sizeBytes to index.modifiedAtMillis)
            if (stale) {
                log.info { "Proxies de ${source.fileName} effacés : ${if (index.version != VERSION) "ancienne version" else "capture modifiée ou disparue"}" }
                delete(folder)
            }
        }
        // Dossiers sans index lisible : restes d'un passage interrompu.
        if (dir.isDirectory()) dir.listDirectoryEntries().filter { it.isDirectory() && !it.resolve(INDEX).exists() }.forEach { delete(it) }
    }

    /** Fait les passages manquants, [PARALLELISM] à la fois (chacun décode déjà sur tous les cœurs). */
    private suspend fun build(ffmpeg: FfmpegService, jobs: List<Pair<Store, TimeRange>>, progress: ProgressReporter) {
        if (jobs.isEmpty()) return
        val total = jobs.sumOf { it.second.length.inWholeMilliseconds }.coerceAtLeast(1)
        val done = AtomicInteger()
        val doneMs = java.util.concurrent.atomic.AtomicLong()
        log.info { "Proxies d'aperçu : ${jobs.size} passage(s), ${total / 1000} s de capture à réduire" }
        coroutineScope {
            val gate = Semaphore(PARALLELISM)
            jobs.map { (store, range) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        // Un passage raté ne coûte que sa lecture sur la capture : on continue avec les autres.
                        runCatching { store.add(segment(ffmpeg, store, range)) }
                            .onFailure { if (it is CancellationException) throw it; log.warn { "Proxy de ${store.path.fileName} $range impossible : ${it.message}" } }
                        val ms = doneMs.addAndGet(range.length.inWholeMilliseconds)
                        progress.update(ms.toDouble() / total, tr("progress.proxies", "done" to done.incrementAndGet(), "total" to jobs.size))
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun segment(ffmpeg: FfmpegService, store: Store, range: TimeRange): ProxySegment {
        val name = "p_${range.start.inWholeMilliseconds}_${range.end.inWholeMilliseconds}.mp4"
        val file = store.folder.resolve(name)
        val temp = store.folder.resolve("$name.part.mp4")
        try {
            ffmpeg.run(command(store.media, store.size, range, temp))
            temp.moveTo(file, overwrite = true)
        } catch (e: Throwable) {
            temp.deleteIfExists()
            throw e
        }
        return ProxySegment(name, range.start.inWholeNanoseconds, range.end.inWholeNanoseconds, startOf(ffmpeg, file).inWholeNanoseconds, file.fileSize(), System.currentTimeMillis())
    }

    /** Début déclaré du proxy (voir [dev.highlights.editing.SourceCutter] : celui du conteneur, que FFmpeg ajoute à tout `-ss`). */
    private suspend fun startOf(ffmpeg: FfmpegService, file: Path): Duration {
        val output = StringBuilder()
        ffmpeg.runProbe(
            FfmpegCommand(listOf("-v", "error", "-show_entries", "format=start_time", "-of", "csv=p=0", file.toString()), "début de ${file.fileName}"),
            StdoutHandler.Lines { output.appendLine(it) },
        )
        return output.toString().trim().toDoubleOrNull()?.seconds ?: error("début illisible pour ${file.fileName} : $output")
    }

    /** Efface les passages les moins récemment servis au-delà de [maxBytes], jamais ceux de l'aperçu en cours ([keep]). */
    private fun limit(keep: Set<Path>) {
        val all = indexes().flatMap { (folder, index) -> index.segments.map { Triple(folder, index, it) } }
        var bytes = all.sumOf { it.third.bytes }
        if (bytes <= maxBytes) return
        for ((folder, index, seg) in all.sortedBy { it.third.usedAt }) {
            if (bytes <= maxBytes) break
            val file = folder.resolve(seg.file)
            if (file.toAbsolutePath().normalize() in keep) continue
            file.deleteIfExists()
            bytes -= seg.bytes
            val current = readIndex(folder) ?: index
            writeIndex(folder, current.copy(segments = current.segments.filter { it.file != seg.file }))
            log.info { "Proxy ${seg.file} de ${Path.of(index.source).fileName} effacé : plus de ${mb(maxBytes)} de proxies" }
        }
    }

    private fun indexes(): List<Pair<Path, ProxyIndex>> =
        if (!dir.isDirectory()) emptyList() else dir.listDirectoryEntries().filter { it.isDirectory() }.mapNotNull { f -> readIndex(f)?.let { f to it } }

    private fun load(path: Path, media: MediaInfo, size: ProxySize, stamp: Pair<Long, Long>): Store {
        val folder = dir.resolve("${digest(path.toString().lowercase())}_${size.height}")
        val index = readIndex(folder)?.takeIf { it.version == VERSION && it.sizeBytes == stamp.first && it.modifiedAtMillis == stamp.second }
            ?.let { i -> i.copy(segments = i.segments.filter { folder.resolve(it.file).exists() }) }
            ?: ProxyIndex(VERSION, path.toString(), stamp.first, stamp.second, size.width, size.height).also {
                delete(folder)
                folder.createDirectories()
            }
        return Store(path, media, folder, ProxySize(index.width, index.height), index)
    }

    private inner class Store(val path: Path, val media: MediaInfo, val folder: Path, val size: ProxySize, var index: ProxyIndex) {
        @Synchronized fun add(segment: ProxySegment) {
            index = index.copy(segments = index.segments + segment)
            save()
        }

        @Synchronized fun touch(files: Set<String>) {
            val now = System.currentTimeMillis()
            index = index.copy(segments = index.segments.map { if (it.file in files) it.copy(usedAt = now) else it })
        }

        @Synchronized fun save() = writeIndex(folder, index)

        fun proxy() = Proxy(
            media.copy(video = media.video?.copy(width = size.width, height = size.height)),
            index.segments.map { it.toLive(folder) },
        )
    }

    private fun readIndex(folder: Path): ProxyIndex? = folder.resolve(INDEX).takeIf { it.exists() }?.let { f ->
        runCatching { json.decodeFromString(ProxyIndex.serializer(), f.readText()) }
            .onFailure { log.warn { "Index de proxies illisible ($f) : ${it.message}" } }.getOrNull()
    }

    private fun writeIndex(folder: Path, index: ProxyIndex) {
        val tmp = folder.resolve("$INDEX.tmp")
        tmp.writeText(json.encodeToString(ProxyIndex.serializer(), index))
        tmp.moveTo(folder.resolve(INDEX), overwrite = true)
    }

    private fun delete(folder: Path) {
        if (!folder.isDirectory()) return
        runCatching { Files.walk(folder).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
            .onFailure { log.warn { "Proxies de $folder non effacés : ${it.message}" } }
    }

    companion object {
        /** À incrémenter quand la fabrication des proxies change : les anciens sont effacés. */
        const val VERSION = 2

        /** Place prise au plus par les proxies : quelques soirées de passages en 640p (~5 Mo par seconde de 60 img/s, son compris). */
        const val MAX_BYTES = 3_000_000_000L

        /**
         * Une image clé toutes les 0,5 s : chercher le début d'un extrait ne décode jamais plus de 30 images. Les captures
         * Outplayed en ont une par seconde, OBS souvent une toutes les deux.
         */
        val KEYFRAME_STEP = 0.5.seconds

        /**
         * Marge autour de chaque passage : une autre musique ou un autre style décale un peu les plans autour du même kill.
         * Courte : les plans d'un montage rapide durent moins d'une seconde, et 3 s de marge de chaque côté faisaient les
         * deux tiers du décodage (passages de 8,8 s pour 2,8 s lus, WARDOGS 22-55-31).
         */
        val PAD = 1.seconds

        /** Deux passages plus proches que cela n'en font qu'un (un seul démarrage de décodage pleine taille). */
        val MERGE_GAP = 2.seconds

        /** Passages faits en même temps : chacun décode sur plusieurs cœurs, mais pas sur tous. */
        private const val PARALLELISM = 3

        /** Un proxy à plus de 75 % de la hauteur de la capture ne fait presque rien gagner : on lit la capture. */
        private const val MIN_GAIN = 0.75

        private const val INDEX = "index.json"

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /**
         * Hauteur du proxy de [media] pour un aperçu au [format] et réglages [edit] ([MontagePreview.edit]) : celle qui
         * donne à l'aperçu autant de pixels de capture qu'il en affiche ([RenderCommandBuilder.pixelScale]), donc la même
         * netteté que l'aperçu lu sur la capture. WARDOGS 3440x1440 en 9:16 360x640 : 640. Null quand la capture est déjà
         * presque aussi petite (VALORANT 854x480 : 9:16 recadré sur 80 % de la hauteur, il en faudrait 805).
         */
        fun height(format: OutputFormat, media: MediaInfo, edit: EditSettings): Int? {
            val video = media.video ?: return null
            val needed = ceil(video.height * RenderCommandBuilder.pixelScale(format, media, edit)).toInt()
            val height = ((needed + 1) / 2) * 2
            return height.takeIf { it < video.height * MIN_GAIN }
        }

        /**
         * Commande d'un proxy : `-ss` rapide avant l'entrée et `-copyts` (horodatages de la capture, comme les
         * pré-découpes), cadence d'origine image pour image, image clé toutes les [KEYFRAME_STEP]. `fastdecode` : sans
         * CABAC ni filtre de boucle, le proxy se décode plus vite encore ; qualité constante haute, l'aperçu le réencode.
         * Son copié sans le toucher, toutes les pistes : le rendu y choisit la sienne comme dans la capture.
         */
        internal fun command(media: MediaInfo, size: ProxySize, range: TimeRange, output: Path): FfmpegCommand {
            val fps = media.video?.fps?.takeIf { it > 0 } ?: 60.0
            val gop = (fps * KEYFRAME_STEP.inWholeMilliseconds / 1000.0).roundToInt().coerceAtLeast(1)
            return FfmpegCommand(
                listOf(
                    // Sans filtre de boucle H.264 (voir MontagePreview.DECODE) : ses blocs disparaissent dans la réduction.
                    "-skip_loop_filter", "all",
                    "-ss", Durations.ffmpegSecondsPrecise(range.start),
                    "-i", media.path.toString(),
                    "-to", Durations.ffmpegSecondsPrecise(range.end),
                    "-copyts",
                    "-map", "0:v:0", "-map", "0:a?",
                    "-vf", "scale=${size.width}:${size.height}:flags=bicubic,setsar=1",
                    "-c:v", "libx264", "-preset", "veryfast", "-tune", "fastdecode", "-crf", "18",
                    "-g", gop.toString(), "-keyint_min", gop.toString(), "-pix_fmt", "yuv420p",
                    "-fps_mode", "passthrough", "-enc_time_base", "demux",
                    "-c:a", "copy",
                    "-y", output.toString(),
                ),
                "proxy ${media.path.fileName} $range",
            )
        }

        /** Regroupe des passages qui se chevauchent ou se suivent à moins de [MERGE_GAP]. */
        internal fun merge(ranges: List<TimeRange>): List<TimeRange> {
            val merged = mutableListOf<TimeRange>()
            for (r in ranges.sortedBy { it.start }) {
                val last = merged.lastOrNull()
                if (last != null && r.start - last.end <= MERGE_GAP) merged[merged.lastIndex] = TimeRange(last.start, maxOf(last.end, r.end))
                else merged += r
            }
            return merged
        }

        private fun clamp(range: TimeRange, media: MediaInfo) =
            TimeRange(range.start.coerceAtLeast(Duration.ZERO), range.end.coerceAtMost(media.duration).coerceAtLeast(range.start.coerceAtLeast(Duration.ZERO)))

        private fun padded(range: TimeRange, media: MediaInfo) =
            TimeRange((range.start - PAD).coerceAtLeast(Duration.ZERO), (range.end + PAD).coerceAtMost(media.duration))

        private fun stamp(file: Path): Pair<Long, Long>? = runCatching { file.fileSize() to file.getLastModifiedTime().toMillis() }.getOrNull()

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        private fun even(value: Double): Int = ((value / 2).roundToInt() * 2).coerceAtLeast(2)

        internal fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f Go".format(bytes / 1e9) else "%d Mo".format(bytes / 1_000_000)
    }
}

/** Taille d'un proxy en pixels. */
data class ProxySize(val width: Int, val height: Int)

/** Place prise par les proxies : [captures] dossiers, [segments] passages, [bytes] octets, sur [limit] au plus. */
data class ProxyUsage(val captures: Int, val segments: Int, val bytes: Long, val limit: Long) {
    override fun toString() = "$segments passage(s) de $captures capture(s), ${PreviewProxies.mb(bytes)} sur ${PreviewProxies.mb(limit)}"
}

/** Proxy d'une capture : [media] à la taille du proxy (même chemin, même durée, mêmes pistes), et ses passages faits. */
class Proxy(val media: MediaInfo, val segments: List<LiveSegment>)

/** Passage fait : [file] couvre [range] de la capture ; [fileStart] est le début déclaré du fichier. */
data class LiveSegment(val file: Path, val range: TimeRange, val fileStart: Duration) {
    fun covers(r: TimeRange) = range.start <= r.start && r.end <= range.end
}

/**
 * Extraits servis par des proxies : [apply] les branche sur un plan d'aperçu. Un extrait qu'aucun passage ne couvre
 * garde sa capture et sa géométrie d'origine : jamais une géométrie de proxy sur une image pleine taille.
 */
class ProxySources private constructor(private val proxies: Map<Path, Proxy>, private val served: Map<Pair<Path, TimeRange>, LiveSegment>) {
    /** Extraits servis. */
    val size: Int get() = served.size

    val isEmpty: Boolean get() = served.isEmpty()

    /** Passage qui sert l'extrait [range] de [media], s'il y en a un. */
    fun segment(media: MediaInfo, range: TimeRange): LiveSegment? = served[key(media.path) to range]

    /**
     * [plan] dont chaque plan servi par un proxy lit la capture à la taille du proxy, et les entrées à lire à leur
     * place. Mêmes plans, mêmes instants, même empreinte ([MontageVariants.signature] ne voit que le temps et les kills).
     */
    fun apply(plan: MontagePlan, fps: Int): Pair<MontagePlan, SourceCuts> {
        if (isEmpty) return plan to SourceCuts.NONE
        val cuts = MontageRenderBuilder.sourceCuts(plan, fps)
        val inputs = mutableMapOf<SourceCut, CutInput>()
        val clips = plan.clips.mapIndexed { i, clip ->
            val cut = cuts[i]
            val segment = segment(cut.media, cut.range) ?: return@mapIndexed clip
            val proxy = proxies.getValue(key(cut.media.path))
            inputs[SourceCut(proxy.media, cut.range)] = CutInput(segment.file, (cut.range.start - segment.fileStart).coerceAtLeast(Duration.ZERO))
            clip.copy(group = clip.group.copy(media = proxy.media))
        }
        return plan.copy(clips = clips) to SourceCuts.of(inputs)
    }

    internal fun usedFiles(path: Path): Set<String> = served.filter { it.key.first == path }.values.map { it.file.name }.toSet()

    internal fun allUsed(): Set<Path> = served.values.map { it.file.toAbsolutePath().normalize() }.toSet()

    companion object {
        val NONE = ProxySources(emptyMap(), emptyMap())

        private fun key(path: Path) = path.toAbsolutePath().normalize()

        internal fun of(proxies: Map<Path, Proxy>, cuts: List<SourceCut>): ProxySources {
            val served = cuts.distinct().mapNotNull { cut ->
                val path = key(cut.media.path)
                val segment = proxies[path]?.segments?.filter { it.covers(cut.range) }?.minByOrNull { it.range.length } ?: return@mapNotNull null
                (path to cut.range) to segment
            }.toMap()
            return ProxySources(proxies, served)
        }
    }
}

@Serializable
internal data class ProxyIndex(
    val version: Int,
    val source: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val width: Int,
    val height: Int,
    val segments: List<ProxySegment> = emptyList(),
)

/** Passage enregistré ; instants en nanosecondes de la capture. */
@Serializable
internal data class ProxySegment(val file: String, val start: Long, val end: Long, val fileStart: Long, val bytes: Long, val usedAt: Long) {
    fun covers(r: TimeRange) = start.nanoseconds <= r.start && r.end <= end.nanoseconds

    fun toLive(folder: Path) = LiveSegment(folder.resolve(file), TimeRange(start.nanoseconds, end.nanoseconds), fileStart.nanoseconds)
}
