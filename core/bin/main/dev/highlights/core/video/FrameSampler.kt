package dev.highlights.core.video

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Zone rectangulaire de la source (pixels), réduite à [width]x[height] avant d'être remontée. */
data class FrameZone(
    val cropX: Int,
    val cropY: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val width: Int,
    val height: Int,
)

/**
 * Cadence d'une passe de décodage : deux détecteurs qui demandent la même cadence partagent la même passe.
 * [interval] est l'écart entre deux images, mesuré pour les images clés et 1/[fps] sinon.
 */
@ConsistentCopyVisibility
data class FrameSpec private constructor(
    val keyframes: Boolean,
    val interval: Duration,
    val hwaccel: String?,
    val fps: Double,
) {
    companion object {
        /**
         * Images clés uniquement, espacées d'environ [interval]. Toujours décodées en logiciel : elles sont trop
         * peu nombreuses pour amortir le transfert depuis le GPU, et les pixels sont ceux de la référence.
         */
        fun keyframes(interval: Duration) = FrameSpec(true, interval, hwaccel = null, fps = 0.0)

        /** Échantillonnage régulier : toutes les images sont décodées, d'où l'intérêt du décodage matériel. */
        fun fps(fps: Double, hwaccel: String?) = FrameSpec(false, (1.0 / fps).seconds, hwaccel, fps)
    }
}

/** Une zone d'une image, en niveaux de gris ligne par ligne. Le tampon est réutilisé d'une image à l'autre. */
class ZoneFrame(val width: Int, val height: Int, val pixels: ByteArray)

/**
 * Décodage partagé de la vidéo : chaque détecteur déclare ses zones et sa cadence, et FFmpeg ne lit la capture
 * qu'une fois pour tout le monde. Deux zones identiques ne sont découpées et transmises qu'une fois.
 *
 * Toujours sans réencodage : FFmpeg décode les images voulues, découpe les zones, les met à l'échelle et les
 * envoie en niveaux de gris sur stdout.
 */
class FrameSampler(private val ffmpeg: FfmpegService, private val media: MediaInfo) {

    /** Abonnement d'un détecteur : ses zones, dans l'ordre où il les recevra. */
    class Subscription internal constructor(
        internal val spec: FrameSpec,
        internal val zones: List<FrameZone>,
        internal val label: String,
        internal val progress: ProgressReporter,
        /** Appelé avant de rejouer la passe (repli sur le décodage logiciel) : les scores accumulés sont à jeter. */
        internal val onReset: () -> Unit,
        /** Reçoit chaque image, zones dans l'ordre déclaré. Les tampons ne doivent pas être conservés. */
        internal val onFrame: (index: Int, zones: List<ZoneFrame>) -> Unit,
        private val sampler: FrameSampler,
    ) {
        /** Instants des images une fois la passe terminée (et la déclenche si personne ne l'a fait). */
        suspend fun await(): List<Duration> = sampler.await(spec)
    }

    private val mutex = Mutex()
    private val subscriptions = mutableListOf<Subscription>()
    private val passes = mutableMapOf<FrameSpec, CompletableDeferred<List<Duration>>>()
    private var keyframeMeasured = false
    private var measuredInterval: Duration? = null

    fun subscribe(
        spec: FrameSpec,
        zones: List<FrameZone>,
        label: String,
        progress: ProgressReporter,
        onReset: () -> Unit,
        onFrame: (Int, List<ZoneFrame>) -> Unit,
    ): Subscription {
        require(zones.isNotEmpty()) { "$label : au moins une zone attendue" }
        return Subscription(spec, zones, label, progress, onReset, onFrame, this).also {
            synchronized(subscriptions) { subscriptions += it }
        }
    }

    /** Lance les passes déclarées, en parallèle. Ne lève rien : chaque échec est rendu à ses abonnés. */
    suspend fun runAll() {
        val specs = synchronized(subscriptions) { subscriptions.map { it.spec }.distinct() }
        coroutineScope {
            specs.forEach { spec -> launch { runCatching { await(spec) } } }
        }
    }

    /** Exécute la passe une seule fois : le premier arrivé la lance, les autres attendent son résultat. */
    private suspend fun await(spec: FrameSpec): List<Duration> {
        var mine = false
        val deferred = mutex.withLock {
            passes.getOrPut(spec) {
                mine = true
                CompletableDeferred()
            }
        }
        if (mine) {
            try {
                deferred.complete(extract(spec))
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            }
        }
        return deferred.await()
    }

    /**
     * Intervalle médian entre images clés, mesuré sur 30 s au milieu de la vidéo. Mémoïsé : une seule mesure
     * quel que soit le nombre de détecteurs.
     */
    suspend fun keyframeInterval(label: String): Duration? = mutex.withLock {
        if (keyframeMeasured) return measuredInterval
        keyframeMeasured = true
        val keyTimes = mutableListOf<Double>()
        val start = (media.duration / 2 - 15.seconds).coerceAtLeast(Duration.ZERO)
        runCatching {
            ffmpeg.runProbe(
                FfmpegCommand(
                    listOf(
                        "-v", "error", "-select_streams", "v:0",
                        "-read_intervals", "${Durations.ffmpegSeconds(start)}%+30",
                        "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", media.path.toString(),
                    ),
                    "$label : intervalle des images clés",
                ),
                StdoutHandler.Lines { line ->
                    val parts = line.split(',')
                    if (parts.size >= 2 && parts[1].startsWith("K")) parts[0].toDoubleOrNull()?.let { keyTimes += it }
                },
            )
        }.onFailure { log.warn { "$label : mesure des images clés impossible : ${it.message}" } }
        if (keyTimes.size < 3) return null
        val gaps = keyTimes.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        measuredInterval = gaps.getOrNull(gaps.size / 2)?.seconds
        return measuredInterval
    }

    // ------------------------------------------------------------------ passe de décodage

    private class Layout(val zones: List<FrameZone>, val offsets: IntArray, val width: Int, val height: Int) {
        val bytes: Int get() = width * height
    }

    private fun layout(subs: List<Subscription>): Layout {
        // Deux détecteurs peuvent viser la même zone (et un même détecteur y chercher plusieurs modèles) :
        // elle n'est alors découpée, mise à l'échelle et transmise qu'une fois.
        val unique = LinkedHashSet<FrameZone>()
        subs.forEach { unique += it.zones }
        val zones = unique.toList()
        val offsets = IntArray(zones.size)
        var y = 0
        zones.forEachIndexed { i, z ->
            offsets[i] = y
            y += z.height
        }
        return Layout(zones, offsets, zones.maxOf { it.width }, y)
    }

    private fun filterGraph(layout: Layout, spec: FrameSpec): String {
        val head = "[0:v:0]" + (if (spec.keyframes) "" else "fps=${spec.fps},") + "setsar=1"
        fun chain(z: FrameZone, first: Boolean) = buildString {
            append("crop=${z.cropWidth}:${z.cropHeight}:${z.cropX}:${z.cropY},scale=${z.width}:${z.height}:flags=area,format=gray")
            if (first) append(",showinfo")
            if (z.width < layout.width) append(",pad=${layout.width}:${z.height}:0:0")
        }
        val zones = layout.zones
        if (zones.size == 1) return "$head,${chain(zones[0], true)}[out]"
        return buildString {
            append("$head,split=${zones.size}")
            zones.indices.forEach { append("[s$it]") }
            zones.forEachIndexed { k, z -> append(";[s$k]${chain(z, k == 0)}[r$k]") }
            append(";")
            zones.indices.forEach { append("[r$it]") }
            append("vstack=inputs=${zones.size}[out]")
        }
    }

    private suspend fun extract(spec: FrameSpec): List<Duration> {
        val subs = synchronized(subscriptions) { subscriptions.filter { it.spec == spec } }
        val layout = layout(subs)
        val label = subs.joinToString("+") { it.label }
        val times = ConcurrentHashMap<Int, Duration>()
        val showinfo = Regex("""\bn:\s*(\d+)\b.*\bpts_time:\s*(-?[\d.]+)""")
        val expected = (media.duration / spec.interval).coerceAtLeast(1.0)
        var count = 0

        // Un tampon par zone unique, réutilisé à chaque image et redistribué à chaque abonné.
        val buffers = layout.zones.map { ZoneFrame(it.width, it.height, ByteArray(it.width * it.height)) }
        val perSub = subs.map { sub -> sub.zones.map { buffers[layout.zones.indexOf(it)] } }

        suspend fun run(hwaccel: String?) = ffmpeg.run(
            FfmpegCommand(
                buildList {
                    hwaccel?.let { addAll(listOf("-hwaccel", it)) }
                    // Images clés : les autres paquets sont écartés dès le démultiplexage (le décodeur ne les voit
                    // même pas), deux fois plus rapide que -skip_frame pour des images identiques au pixel près.
                    if (spec.keyframes) addAll(listOf("-discard", "nokey"))
                    addAll(listOf("-i", media.path.toString(), "-an", "-sn", "-dn"))
                    addAll(listOf("-filter_complex", filterGraph(layout, spec)))
                    addAll(listOf("-map", "[out]", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "pipe:1"))
                },
                "$label : zones vidéo",
            ),
            StdoutHandler.Binary { input ->
                val data = DataInputStream(input.buffered(layout.bytes * 4))
                val frame = ByteArray(layout.bytes)
                var index = 0
                while (true) {
                    try {
                        data.readFully(frame)
                    } catch (_: EOFException) {
                        break
                    }
                    layout.zones.forEachIndexed { k, zone ->
                        val target = buffers[k].pixels
                        for (row in 0 until zone.height) {
                            System.arraycopy(frame, (layout.offsets[k] + row) * layout.width, target, row * zone.width, zone.width)
                        }
                    }
                    subs.forEachIndexed { s, sub -> sub.onFrame(index, perSub[s]) }
                    index++
                    if (index % 20 == 0) {
                        val fraction = (index / expected).coerceAtMost(0.99)
                        subs.forEach { it.progress.update(fraction, "$index images") }
                    }
                }
                count = index
            },
            onStderrLine = { line ->
                if (line.contains("showinfo")) {
                    showinfo.find(line)?.let { m -> times[m.groupValues[1].toInt()] = m.groupValues[2].toDouble().seconds }
                }
            },
        )

        val hwaccel = spec.hwaccel
        try {
            run(hwaccel)
        } catch (e: FfmpegException) {
            if (hwaccel == null) throw e
            log.warn { "$label : échec avec -hwaccel $hwaccel (${e.message}), nouvel essai en décodage logiciel" }
            times.clear()
            count = 0
            subs.forEach { it.onReset() }
            run(null)
        }
        subs.forEach { it.progress.complete() }
        if (times.size < count) log.warn { "$label : ${count - times.size} horodatage(s) manquant(s), estimation par l'intervalle" }
        return List(count) { i -> times[i] ?: (spec.interval * i) }
    }
}
