package dev.highlights.pipeline

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TrackContent
import dev.highlights.core.serialization.Durations
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Écoute rapide des pistes audio d'une capture qui en a plusieurs, pour décider de leur rôle quand leurs titres ne
 * suffisent pas ([dev.highlights.core.model.AudioTracks]) : [PASSAGES] passages de [LENGTH] répartis dans la capture,
 * toutes les pistes décodées ensemble (même instant, 8 kHz mono), énergie par tranche de 20 ms.
 *
 * - piste active : part des tranches au-dessus de [SILENCE] (le jeu sonne presque toujours, une voix bien moins) ;
 * - copie : énergies proportionnelles à une autre piste (corrélation ≥ [DUPLICATE]) — la piste « Default Mix » de
 *   FTHRClips quand le son par défaut de Windows est l'appareil « Gaming » de Sonar ;
 * - piste qui contient les autres : son énergie est la somme pondérée de la leur (les énergies de sons indépendants
 *   s'additionnent ; moindres carrés à poids positifs, part expliquée ≥ [CONTAINS]) — le mix d'Outplayed, d'OBS.
 *
 * Quelques secondes de décodage du son seul pour une partie entière : rien à côté de l'analyse.
 */
class TrackListener(private val ffmpeg: FfmpegService) {
    /** [media] dont chaque piste porte ce que l'écoute en a montré ; inchangé avec moins de deux pistes ou en cas d'échec. */
    suspend fun listen(media: MediaInfo): MediaInfo {
        val count = media.audio.size
        if (count < 2 || !media.duration.isPositive()) return media
        val energies = Array(count) { ArrayList<Double>() }
        try {
            for (start in passages(media.duration)) {
                val length = minOf(LENGTH, media.duration - start)
                if (!length.isPositive()) continue
                readPassage(media, start, length).forEachIndexed { k, e -> energies[k].addAll(e.asList()) }
            }
        } catch (e: Exception) {
            log.warn { "Écoute des pistes de ${media.path.fileName} impossible, rôles d'après les titres : ${e.message}" }
            return media
        }
        val contents = analyze(energies.map { it.toDoubleArray() })
        log.info { "Pistes de ${media.path.fileName} à l'écoute : " + contents.withIndex().joinToString { (i, c) -> "a:$i $c" } }
        return media.copy(audio = media.audio.mapIndexed { i, s -> s.copy(content = contents[i]) })
    }

    private suspend fun readPassage(media: MediaInfo, start: Duration, length: Duration): List<DoubleArray> {
        val count = media.audio.size
        val chains = (0 until count).joinToString(";") { "[0:a:$it]aresample=$RATE,aformat=sample_fmts=flt:channel_layouts=mono[m$it]" }
        val merge = (0 until count).joinToString("") { "[m$it]" } + "amerge=inputs=$count[out]"
        var samples = FloatArray(0)
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(start), "-t", Durations.ffmpegSeconds(length), "-i", media.path.toString(),
                    "-filter_complex", "$chains;$merge", "-map", "[out]", "-vn", "-f", "f32le", "-acodec", "pcm_f32le", "pipe:1",
                ),
                "écoute des pistes audio",
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                samples = FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            },
        )
        val frames = samples.size / count / FRAME
        return List(count) { k ->
            DoubleArray(frames) { f ->
                var sum = 0.0
                for (j in 0 until FRAME) {
                    val v = samples[(f * FRAME + j) * count + k].toDouble()
                    sum += v * v
                }
                sum / FRAME
            }
        }
    }

    companion object {
        const val PASSAGES = 4
        val LENGTH = 15.seconds
        const val RATE = 8000
        /** Tranche de 20 ms à 8 kHz. */
        const val FRAME = 160
        /** Énergie moyenne d'une tranche sous laquelle la piste se tait (−50 dBFS). */
        const val SILENCE = 1e-5
        /** Corrélation des niveaux au-delà de laquelle deux pistes sont le même son. */
        const val DUPLICATE = 0.97
        /** Part de l'énergie d'une piste expliquée par d'autres au-delà de laquelle elle les contient. */
        const val CONTAINS = 0.9
        /** Poids minimal d'une piste dans la somme pour la compter comme contenue (volumes d'OBS réglés plus bas). */
        const val MIN_WEIGHT = 0.2

        /** Débuts des passages, répartis sur la capture (centrés dans des parts égales). */
        fun passages(duration: Duration): List<Duration> {
            if (duration <= LENGTH * PASSAGES) return listOf(Duration.ZERO)
            return List(PASSAGES) { i -> duration * ((i + 0.5) / PASSAGES) - LENGTH / 2 }
        }

        /** Rôle de chaque piste à partir de ses énergies par tranche (toutes de même longueur, même instant). */
        fun analyze(energies: List<DoubleArray>): List<TrackContent> {
            val n = energies.size
            val active = energies.map { e -> if (e.isEmpty()) 0.0 else e.count { it > SILENCE }.toDouble() / e.size }
            val silent = active.map { it < TrackContent.SILENT_SHARE }
            val levels = energies.map { e -> DoubleArray(e.size) { sqrt(e[it]) } }

            val duplicateOf = arrayOfNulls<Int>(n)
            for (b in 0 until n) {
                if (silent[b]) continue
                for (a in 0 until b) {
                    if (silent[a] || duplicateOf[a] != null) continue
                    if (correlation(levels[a], levels[b]) >= DUPLICATE) { duplicateOf[b] = a; break }
                }
            }
            val kept = (0 until n).filter { !silent[it] && duplicateOf[it] == null }
            val fitted = kept.associateWith { a ->
                val others = kept.filter { it != a }
                if (others.isEmpty()) emptyList() else containedIn(energies[a], others.map { energies[it] }).map { others[it] }
            }
            // Deux pistes presque identiques s'expliquent l'une par l'autre (jeu ≈ mix quand personne ne parle) : seule
            // la plus forte contient l'autre, une somme n'est jamais plus faible que ses parties.
            val total = energies.map { it.sum() }
            val contains = fitted.mapValues { (a, list) -> list.filter { b -> a !in fitted[b].orEmpty() || total[a] > total[b] } }
            return List(n) { i -> TrackContent(active[i], contains[i].orEmpty(), duplicateOf[i]) }
        }

        /**
         * Pistes de [others] dont la somme pondérée explique [target] (indices dans [others]) ; vide si la part expliquée
         * reste sous [CONTAINS]. Moindres carrés à poids positifs : une piste à poids négatif sort, on recommence.
         */
        internal fun containedIn(target: DoubleArray, others: List<DoubleArray>): List<Int> {
            var set = others.indices.toList()
            while (set.isNotEmpty()) {
                val w = leastSquares(target, set.map { others[it] }) ?: return emptyList()
                val negative = w.indices.filter { w[it] <= 0.0 }
                if (negative.isNotEmpty()) { set = set.filterIndexed { k, _ -> k !in negative }; continue }
                val fit = DoubleArray(target.size) { f -> set.indices.sumOf { k -> w[k] * others[set[k]][f] } }
                if (explained(target, fit) < CONTAINS) return emptyList()
                return set.filterIndexed { k, _ -> w[k] >= MIN_WEIGHT }
            }
            return emptyList()
        }

        /** Part de la variance de [target] expliquée par [fit] (R²). */
        private fun explained(target: DoubleArray, fit: DoubleArray): Double {
            val mean = target.average()
            var res = 0.0
            var tot = 0.0
            for (f in target.indices) {
                res += (target[f] - fit[f]).let { it * it }
                tot += (target[f] - mean).let { it * it }
            }
            return if (tot <= 0.0) 0.0 else 1 - res / tot
        }

        /** Poids w minimisant |target − Σ w_k x_k|² (sans constante : le silence de toutes donne le silence), équations normales. */
        private fun leastSquares(target: DoubleArray, xs: List<DoubleArray>): DoubleArray? {
            val m = xs.size
            val a = Array(m) { i -> DoubleArray(m) { j -> dot(xs[i], xs[j]) } }
            val b = DoubleArray(m) { i -> dot(xs[i], target) }
            // Élimination de Gauss avec pivot partiel ; système minuscule (une poignée de pistes).
            for (c in 0 until m) {
                val p = (c until m).maxByOrNull { kotlin.math.abs(a[it][c]) } ?: return null
                if (kotlin.math.abs(a[p][c]) < 1e-18) return null
                a[c] = a[p].also { a[p] = a[c] }
                b[c] = b[p].also { b[p] = b[c] }
                for (r in c + 1 until m) {
                    val k = a[r][c] / a[c][c]
                    for (j in c until m) a[r][j] -= k * a[c][j]
                    b[r] -= k * b[c]
                }
            }
            val w = DoubleArray(m)
            for (c in m - 1 downTo 0) {
                var s = b[c]
                for (j in c + 1 until m) s -= a[c][j] * w[j]
                w[c] = s / a[c][c]
            }
            return w
        }

        private fun dot(x: DoubleArray, y: DoubleArray): Double {
            var s = 0.0
            for (i in x.indices) s += x[i] * y[i]
            return s
        }

        private fun correlation(x: DoubleArray, y: DoubleArray): Double {
            val mx = x.average()
            val my = y.average()
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (i in x.indices) {
                val dx = x[i] - mx
                val dy = y[i] - my
                sxy += dx * dy; sxx += dx * dx; syy += dy * dy
            }
            return if (sxx <= 0 || syy <= 0) 0.0 else sxy / sqrt(sxx * syy)
        }
    }
}
