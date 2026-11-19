package dev.highlights.vision

import dev.highlights.core.i18n.tr
import dev.highlights.core.i18n.Message
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
 * chargeur). Un pixel est un chiffre au-dessus de [brightness] (blanc franc). Le compteur est lu en paliers : des images
 * qui se ressemblent (moins de [minChange] de pixels blancs différents d'une image à la suivante) pendant au moins
 * [minPlateau] ; passer d'un palier au suivant quand ils diffèrent de plus de [minChange] compte une balle. Un palier
 * presque sans blanc ([minDigits]) est un compteur absent ou effacé : il ne compte pas, et la lecture repart du palier
 * lisible suivant.
 *
 * Paliers mesurés sur une partie en 854x480 (16:9, 30 img/s) : en comparant chaque image à la précédente, le décor
 * derrière les chiffres (flash rouge des dégâts, mur clair, compteur caché 1,2 s) comptait 28 fausses balles autour de
 * 5 kills pour 33 vraies ; en paliers, 4 fausses, les 33 vraies gardées. Un chiffre reste affiché au moins 0,064 s
 * (Odin, l'arme la plus rapide) : le palier de 0,05 s ne perd pas de balle.
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
    /** Durée minimale d'un palier : en deçà, le changement est du décor qui scintille, pas un chiffre affiché. */
    val minPlateau: SerialDuration = 50.milliseconds,
    /**
     * Part de la zone en blanc en deçà de laquelle le compteur est absent (caché, effacé par un flash) : 80 pixels de la
     * zone réduite à 96x48. Un vrai chiffre en allume 102 au moins (mesuré, sous un flash rouge), un « 1 » seul vers 120.
     */
    val minDigits: Double = 0.017,
) {
    init {
        require(brightness in 1..254) { "ammo-counter : brightness doit être entre 1 et 254" }
        require(minChange > 0 && minChange < 1) { "ammo-counter : minChange doit être entre 0 et 1" }
        require(before.isPositive() && !after.isNegative()) { "ammo-counter : before positif, after positif ou nul" }
        require(fps in 20.0..120.0) { "ammo-counter : fps entre 20 et 120" }
        require(!minPlateau.isNegative() && minDigits in 0.0..1.0) { "ammo-counter : minPlateau positif, minDigits entre 0 et 1" }
    }
}

class AmmoCounterDetector(override val id: String, private val params: AmmoCounterParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val raw = DoubleArray(ctx.grid.count) { Double.NaN }
        val video = ctx.media.video ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        val kills = ctx.events.filter { it.kind == params.kill }.map { it.at }
        if (kills.isEmpty()) return SignalTrack(id, raw, note = Message("notice.ammo.noKills"))
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
                        ctx.progress.update(done.incrementAndGet().toDouble() / windows.size, tr("progress.kill", "done" to done.get(), "total" to windows.size))
                        val masked = refills(frames, teal, params.brightness, margin(params.fps))
                        changes(
                            frames, params.brightness, params.minChange, sameShot(params.fps), masked,
                            plateau = (params.minPlateau.inWholeMilliseconds / 1000.0 * params.fps).roundToInt().coerceAtLeast(1),
                            minDigits = params.minDigits,
                        ).mapNotNull { times.getOrNull(it) }
                    }
                }
            }.awaitAll().flatten().distinct().sorted()
        }
        log.info { "$id : ${shots.size} balle(s) lue(s) sur le compteur autour de ${kills.size} kill(s)" }
        return SignalTrack(id, raw, shots.map { SignalEvent(it, params.kind, 1.0) }, note = Message("notice.ammo.still").takeIf { shots.isEmpty() })
    }

    /** Zone des chiffres dans la capture, réduite à [WIDTH] x [HEIGHT]. */
    private fun zone(width: Int, height: Int): FrameZone {
        val region = ScreenGeometry.rescale(params.region, REFERENCE_ASPECT, width.toDouble() / height, RegionAnchor.CENTER)
        val cx = (region.x * width).roundToInt().coerceIn(0, width - 1)
        val cy = (region.y * height).roundToInt().coerceIn(0, height - 1)
        val cw = (region.width * width).roundToInt().coerceIn(1, width - cx)
        val ch = (region.height * height).roundToInt().coerceIn(1, height - cy)
        if (cw < 8 || ch < 8) throw ConfigException(Message("error.detector.zoneTooSmall", "id" to id, "size" to "${cw}x$ch"))
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
         * Images (indices) où le compteur change : début d'un palier (images à moins de [minChange] de pixels blancs
         * différents l'une de l'autre, au moins [plateau] de suite) qui diffère de plus de [minChange] du palier lisible
         * d'avant, chacun pris à la majorité de ses images. Un palier avec moins de [minDigits] de la zone en blanc
         * (compteur absent ou effacé) ne compte pas et oublie le palier d'avant. Deux changements à moins de [gap]
         * images n'en font qu'un. Un palier qui commence dans [masked] (une recharge, voir [refills]) ne compte pas mais
         * devient la référence.
         */
        internal fun changes(
            frames: List<ByteArray>,
            brightness: Int,
            minChange: Double,
            gap: Int,
            masked: Set<Int> = emptySet(),
            plateau: Int = 1,
            minDigits: Double = 0.0,
        ): List<Int> {
            if (frames.isEmpty()) return emptyList()
            val masks = frames.map { f -> BooleanArray(f.size) { (f[it].toInt() and 0xFF) > brightness } }
            fun differ(a: BooleanArray, b: BooleanArray): Boolean {
                val lit = maxOf(a.count { it }, b.count { it })
                return lit > 0 && a.indices.count { a[it] != b[it] }.toDouble() / lit > minChange
            }
            // Paliers : suites d'images voisines semblables.
            val runs = mutableListOf<IntRange>()
            var start = 0
            for (i in 1..masks.size) {
                if (i == masks.size || differ(masks[i - 1], masks[i])) {
                    runs += start until i
                    start = i
                }
            }
            val result = mutableListOf<Int>()
            var previous: BooleanArray? = null
            for (run in runs) {
                if (run.last - run.first + 1 < plateau) continue
                // Le palier à la majorité de ses images : le bruit d'une image isolée s'efface.
                val size = masks[run.first].size
                val votes = IntArray(size)
                for (i in run) for (p in 0 until size) if (masks[i][p]) votes[p]++
                val count = run.last - run.first + 1
                val current = BooleanArray(size) { votes[it] * 2 >= count }
                if (current.count { it } < minDigits * size) {
                    previous = null
                    continue
                }
                val before = previous
                if (before != null && run.first !in masked && differ(before, current) && (result.isEmpty() || run.first - result.last() > gap)) {
                    result += run.first
                }
                previous = current
            }
            return result
        }
    }
}

class AmmoCounterDetectorFactory : SignalDetectorFactory {
    override val type = "ammo-counter"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        AmmoCounterDetector(id, params.decode(AmmoCounterParams.serializer()) { throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "region")) })
}

