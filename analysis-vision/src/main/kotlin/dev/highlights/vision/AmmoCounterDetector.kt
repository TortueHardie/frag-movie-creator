package dev.highlights.vision

import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.RegionAnchor
import dev.highlights.core.model.ScreenGeometry
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.video.FrameSpec
import dev.highlights.core.video.FrameZone
import dev.highlights.core.video.ZoneFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Compteur de munitions du chargeur dans le HUD (VALORANT) : chaque balle tirée change ses chiffres. [region] : mesurée
 * en 16:9, accrochée au centre comme le HUD, assez large pour trois chiffres (alignés à droite contre l'icône du
 * chargeur). Un pixel est un chiffre au-dessus de [brightness] (blanc franc) ; une image dont plus de [minChange] des
 * pixels blancs diffèrent de la précédente compte une balle.
 *
 * Ne relit que [before] avant et [after] après chaque kill des détecteurs dont il dépend ([kill]) : décoder toute la
 * partie à [fps] images/s coûterait plusieurs minutes. Les instants d'Outplayed arrivent jusqu'à 0,6 s après la balle
 * qui tue (ligne du killfeed déjà affichée), d'où une fenêtre large avant le kill.
 *
 * Combat à mort : le chargeur se remplit à chaque kill, et le compteur l'annonce par une animation (chiffres agrandis,
 * puis turquoise qui repasse au blanc en 0,3 s). Sans précaution, recharge et animation comptaient trois balles de plus
 * (5 pour 2 vraies sur une partie du 01/10), et aucun one tap n'était jamais trouvé dans ce mode. La zone est donc
 * aussi relue en turquoise ([TEAL]) : les changements pendant une recharge ([refills]) ne comptent pas. Jamais un pixel
 * turquoise autour des kills de deux parties classées.
 */
@Serializable
data class AmmoCounterParams(
    val region: CropRegion,
    val kill: String = "kill",
    /** Événement émis à chaque balle vue sur le compteur. */
    val kind: String = "hud-shot",
    val brightness: Int = 200,
    val minChange: Double = 0.15,
    val before: SerialDuration = 1800.milliseconds,
    val after: SerialDuration = 450.milliseconds,
    /** Assez pour séparer les balles du Vandal (0,1 s) ; un changement de chiffre s'étale sur une ou deux images. */
    val fps: Double = 60.0,
    val hwaccel: String? = Hwaccel.AUTO,
) {
    init {
        require(brightness in 1..254) { "ammo-counter : brightness doit être entre 1 et 254" }
        require(minChange > 0 && minChange < 1) { "ammo-counter : minChange doit être entre 0 et 1" }
        require(before.isPositive() && !after.isNegative()) { "ammo-counter : before positif, after positif ou nul" }
        require(fps in 20.0..120.0) { "ammo-counter : fps entre 20 et 120" }
    }
}

class AmmoCounterDetector(override val id: String, private val params: AmmoCounterParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val raw = DoubleArray(ctx.grid.count) { Double.NaN }
        val video = ctx.media.video ?: return SignalTrack.missing(id, ctx.grid.count, "pas de vidéo")
        val kills = ctx.events.filter { it.kind == params.kill }.map { it.at }
        if (kills.isEmpty()) return SignalTrack(id, raw, note = "aucun kill à relire")
        val zone = zone(video.width, video.height)
        val tealZone = zone.copy(color = TEAL)
        val windows = windows(kills, params.before, params.after, ctx.media.duration)
        val done = AtomicInteger()
        val semaphore = Semaphore(PARALLEL_READS)
        val shots = coroutineScope {
            windows.map { w ->
                async {
                    semaphore.withPermit {
                        val frames = mutableListOf<ByteArray>()
                        val teal = mutableListOf<ByteArray>()
                        val times = ctx.frames.extractRange(
                            spec = FrameSpec.fps(params.fps, params.hwaccel),
                            zones = listOf(zone, tealZone),
                            from = w.first,
                            length = w.second - w.first,
                            label = id,
                            progress = ProgressReporter.NONE,
                            onReset = { frames.clear(); teal.clear() },
                            onFrame = { _, zones: List<ZoneFrame> ->
                                frames += zones[0].pixels.copyOf()
                                teal += zones[1].pixels.copyOf()
                            },
                        )
                        ctx.progress.update(done.incrementAndGet().toDouble() / windows.size, "${done.get()}/${windows.size} kills")
                        val masked = refills(frames, teal, params.brightness, margin(params.fps))
                        changes(frames, params.brightness, params.minChange, sameShot(params.fps), masked).mapNotNull { times.getOrNull(it) }
                    }
                }
            }.awaitAll().flatten().distinct().sorted()
        }
        log.info { "$id : ${shots.size} balle(s) lue(s) sur le compteur autour de ${kills.size} kill(s)" }
        return SignalTrack(id, raw, shots.map { SignalEvent(it, params.kind, 1.0) }, note = "compteur immobile autour des kills".takeIf { shots.isEmpty() })
    }

    /** Zone des chiffres dans la capture, réduite à [WIDTH] x [HEIGHT]. */
    private fun zone(width: Int, height: Int): FrameZone {
        val region = ScreenGeometry.rescale(params.region, REFERENCE_ASPECT, width.toDouble() / height, RegionAnchor.CENTER)
        val cx = (region.x * width).roundToInt().coerceIn(0, width - 1)
        val cy = (region.y * height).roundToInt().coerceIn(0, height - 1)
        val cw = (region.width * width).roundToInt().coerceIn(1, width - cx)
        val ch = (region.height * height).roundToInt().coerceIn(1, height - cy)
        if (cw < 8 || ch < 8) throw ConfigException("$id : zone du compteur trop petite (${cw}x$ch)")
        return FrameZone(cx, cy, cw, ch, WIDTH, HEIGHT)
    }

    companion object {
        /** Zone réduite : trois chiffres de 13 pixels de large environ, lisibles sans le détail de la capture. */
        const val WIDTH = 96
        const val HEIGHT = 48

        private const val PARALLEL_READS = 3

        /** Format sur lequel la zone par défaut a été mesurée. */
        private const val REFERENCE_ASPECT = 16.0 / 9

        /** Changements plus proches que 50 ms : le même chiffre qui s'affiche sur deux images. */
        private fun sameShot(fps: Double) = (0.05 * fps).roundToInt()

        /**
         * Turquoise des chiffres pendant l'animation de recharge : vert et bleu nettement au-dessus du rouge (mesuré
         * vers 100, 220, 200). Le blanc des chiffres et le décor gris ou chaud n'y passent pas.
         */
        const val TEAL = "if(gt(g(X,Y),140)*gt(g(X,Y)-r(X,Y),45)*gt(b(X,Y)-r(X,Y),30),255,0)"

        /**
         * Recharge vue 50 ms avant le turquoise (chiffres agrandis, deux kills mesurés à l'image), fondu fini après
         * lui : 70 ms de chaque côté. La balle qui tue tombe 0,1 s avant le turquoise : elle reste comptée.
         */
        private fun margin(fps: Double) = (0.07 * fps).roundToInt().coerceAtLeast(1)

        /**
         * Images d'une recharge animée : celles où le turquoise l'emporte sur le blanc des chiffres et où ce blanc
         * tombe de plus de moitié en quelques images (ils s'effacent à son profit, de 1700 pixels blancs à 17 mesurés),
         * prolongées tant que le turquoise vaut plus du dixième du
         * blanc (le fondu), puis de [margin] images de chaque côté. Un décor turquoise derrière des chiffres blancs
         * restés visibles ne compte pas.
         */
        internal fun refills(frames: List<ByteArray>, teal: List<ByteArray>, brightness: Int, margin: Int): Set<Int> {
            val n = minOf(frames.size, teal.size)
            val lit = IntArray(n) { i -> frames[i].count { (it.toInt() and 0xFF) > brightness } }
            val green = IntArray(n) { i -> teal[i].count { (it.toInt() and 0xFF) > 127 } }
            val masked = mutableSetOf<Int>()
            var i = 0
            while (i < n) {
                val before = (i - margin).coerceAtLeast(0) until i
                val collapsed = before.any { lit[i] * 2 < lit[it] }
                if (green[i] < MIN_TEAL || green[i] <= lit[i] || !collapsed) { i++; continue }
                var end = i
                while (end + 1 < n && green[end + 1] * 10 > lit[end + 1]) end++
                for (j in (i - margin).coerceAtLeast(0)..(end + margin).coerceAtMost(n - 1)) masked += j
                i = end + 1
            }
            return masked
        }

        /** Pixels turquoise (zone réduite de 96x48) en deçà desquels ce n'est pas l'animation : un reflet, un liseré. */
        private const val MIN_TEAL = 40

        /** Fenêtres de relecture autour des kills, fusionnées quand elles se chevauchent. */
        internal fun windows(kills: List<Duration>, before: Duration, after: Duration, end: Duration): List<Pair<Duration, Duration>> {
            val merged = mutableListOf<Pair<Duration, Duration>>()
            for (k in kills.sorted()) {
                val from = (k - before).coerceAtLeast(Duration.ZERO)
                val to = (k + after).coerceAtMost(end)
                if (to <= from) continue
                val last = merged.lastOrNull()
                if (last != null && from <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, to)
                else merged += from to to
            }
            return merged
        }

        /**
         * Images (indices) où le compteur change : plus de [minChange] des pixels blancs (les chiffres) diffèrent de
         * l'image précédente. Deux changements à moins de [gap] images n'en font qu'un. Les images de [masked] (une
         * recharge, voir [refills]) ne comptent pas.
         */
        internal fun changes(frames: List<ByteArray>, brightness: Int, minChange: Double, gap: Int, masked: Set<Int> = emptySet()): List<Int> {
            val masks = frames.map { f -> BooleanArray(f.size) { (f[it].toInt() and 0xFF) > brightness } }
            val result = mutableListOf<Int>()
            for (i in 1 until masks.size) {
                if (i in masked) continue
                val a = masks[i - 1]
                val b = masks[i]
                val lit = maxOf(a.count { it }, b.count { it })
                if (lit == 0) continue
                val differ = a.indices.count { a[it] != b[it] }
                if (differ.toDouble() / lit > minChange && (result.isEmpty() || i - result.last() > gap)) result += i
            }
            return result
        }
    }
}

class AmmoCounterDetectorFactory : SignalDetectorFactory {
    override val type = "ammo-counter"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        AmmoCounterDetector(id, params.decode(AmmoCounterParams.serializer()) { throw ConfigException("$id : paramètre region requis") })
}

