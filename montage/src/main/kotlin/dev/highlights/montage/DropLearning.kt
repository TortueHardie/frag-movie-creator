package dev.highlights.montage

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Une drop posée à la main, gardée pour apprendre : où la détection la mettait ([detectedBeat]) et où l'utilisateur l'a
 * posée ([posedBeat] : temps détecté le plus proche, [residualMs] : écart à ce temps), avec les mesures de chaque temps
 * du morceau ([features], une ligne par temps, voir [DropFeatures]) pour réapprendre le choix de la drop.
 */
@Serializable
data class DropExample(
    val key: String,
    val name: String,
    val detectedBeat: Int,
    val posedBeat: Int,
    val residualMs: Long,
    val bpm: Double,
    val features: List<List<Double>> = emptyList(),
    val at: String = "",
) {
    /** Écart en temps (+ : la drop posée est après la détectée). */
    val beatOffset: Int get() = posedBeat - detectedBeat

    /** Écart total en ms. */
    val offsetMs: Long get() = (beatOffset * 60_000.0 / bpm).roundToLong() + residualMs
}

@Serializable
data class StoredDropExamples(val version: Int = 1, val examples: List<DropExample> = emptyList())

/** Exemples d'apprentissage de la drop (`<sortie>/sessions/drop-learning.json`), un par morceau, le dernier posé remplace. */
class DropExamples(val file: Path) {
    private val lock = Any()

    fun load(): List<DropExample> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            json.decodeFromString(StoredDropExamples.serializer(), file.readText()).examples
        } catch (e: Exception) {
            log.warn { "Exemples de drops illisibles ($file), ignorés : ${e.message}" }
            emptyList()
        }
    }

    fun record(example: DropExample): Unit = synchronized(lock) { save(load().filter { it.key != example.key } + example) }

    fun forget(key: String): Unit = synchronized(lock) {
        val current = load()
        if (current.any { it.key == key }) save(current.filter { it.key != key })
    }

    /** Oublie ce qui a été appris : le fichier est mis de côté (`drop-learning.reset-<instant>.json`). Les drops posées restent. */
    fun reset(): Path? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = Instant.now().toString().replace(":", "-").substringBefore('.')
        val aside = file.resolveSibling(file.name.removeSuffix(".json") + ".reset-$stamp.json")
        file.moveTo(aside)
        aside
    }

    private fun save(examples: List<DropExample>) {
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredDropExamples.serializer(), StoredDropExamples(examples = examples)))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Exemples de drops non enregistrés ($file) : ${it.message}" } }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Mesures de chaque temps d'un morceau pour choisir la drop, toutes lues dans l'analyse détectée (rien à redécoder) :
 * 0 montée du volume (8 temps après contre 8 avant, dB/10) ; 1 creux juste avant (le temps d'avant sous les 4 d'avant,
 * dB/10 : la drop suit souvent un silence, Rome Is Burning : −40 dB pendant 20 ms) ; 2 attaque du temps ; 3 saillance ;
 * 4 une section détectée commence ici ; 5 intensité de la section qui commence ici ; 6 premier temps de mesure ;
 * 7 c'est la drop détectée (la règle actuelle, que le modèle peut garder ou corriger).
 */
object DropFeatures {
    val NAMES = listOf("montee", "creux", "attaque", "saillance", "section", "intensite", "mesure", "regle")

    fun of(a: MusicAnalysis): List<DoubleArray> {
        val n = a.beats.size
        val db = DoubleArray(n) { 20 * log10(a.beatEnergy.getOrElse(it) { 0.0 } + 1e-5) }
        fun mean(from: Int, to: Int): Double {
            val r = (from.coerceAtLeast(0) until to.coerceAtMost(n))
            return if (r.isEmpty()) db.getOrElse(from.coerceIn(0, (n - 1).coerceAtLeast(0))) { -100.0 } else r.sumOf { db[it] } / (r.last - r.first + 1)
        }
        val starts = a.sections.associateBy { it.startBeat }
        return List(n) { b ->
            doubleArrayOf(
                ((mean(b, b + 8) - mean(b - 8, b)) / 10).coerceIn(-3.0, 3.0),
                (if (b > 0) (mean(b - 5, b - 1) - db[b - 1]).coerceAtLeast(0.0) / 10 else 0.0).coerceAtMost(3.0),
                a.beatAccent.getOrElse(b) { 0.0 },
                a.salience.getOrElse(b) { 0.0 },
                if (b in starts) 1.0 else 0.0,
                starts[b]?.intensity ?: 0.0,
                if (a.isDownbeat(b)) 1.0 else 0.0,
                if (b == a.dropBeat) 1.0 else 0.0,
            )
        }
    }

    /** Arrondi au millième : un exemple de 500 temps reste petit dans le fichier. */
    fun compact(rows: List<DoubleArray>): List<List<Double>> = rows.map { r -> r.map { (it * 1000).roundToInt() / 1000.0 } }
}

/**
 * Ce qui a été appris des drops posées à la main : un écart régulier de la détection ([beatOffset] temps et
 * [residual]) et, mieux, un choix de la drop réappris ([weights] sur [DropFeatures]) quand il retrouve, en validation
 * « une de côté », plus de drops posées que la règle actuelle ([modelHits] contre [ruleHits] sur [songs]).
 */
data class LearnedDrop(
    val songs: Int,
    val meanOffsetMs: Long?,
    val beatOffset: Int?,
    val beatAgreement: Double?,
    val residual: Duration?,
    val residualSpread: Duration?,
    val biasActive: Boolean,
    val weights: DoubleArray?,
    val modelHits: Int?,
    val ruleHits: Int?,
    val modelActive: Boolean,
) {
    val active: Boolean get() = biasActive || modelActive

    /**
     * Drop corrigée d'un morceau jamais posé à la main, null si rien d'appris ne s'applique (le morceau garde sa drop
     * détectée). Le choix appris passe avant l'écart régulier : il le contient déjà.
     */
    fun dropFor(a: MusicAnalysis): Duration? {
        if (a.beats.isEmpty()) return null
        if (modelActive && weights != null) {
            val beat = DropLearner.choose(DropFeatures.of(a), weights)
            if (beat != a.dropBeat) return a.beatTime(beat)
        }
        if (!biasActive) return null
        val beat = (a.dropBeat + (beatOffset ?: 0)).coerceIn(0, a.beats.lastIndex)
        val at = a.beatTime(beat) + (residual ?: Duration.ZERO)
        return at.takeIf { (it - a.beatTime(a.dropBeat)).absoluteValue >= 1.milliseconds }
    }

    companion object {
        val NONE = LearnedDrop(0, null, null, null, null, null, false, null, null, null, false)
    }
}

/**
 * Apprend des drops posées à la main. Mêmes garde-fous que les autres apprentissages : un nombre minimal de morceaux,
 * une dispersion maximale, des bornes ; et jamais sur un morceau posé à la main (le pipeline applique d'abord la drop
 * posée). Déterministe : mêmes exemples, même résultat.
 */
object DropLearner {
    /** Morceaux posés à la main avant d'appliquer un écart régulier. */
    const val MIN_SONGS = 5

    /** Part des morceaux qui doivent avoir exactement le même écart en temps. */
    const val MIN_AGREEMENT = 0.8

    /** Écart en temps borné (4 mesures). */
    const val MAX_BEATS = 16

    /** Reste en ms : dispersion maximale (écart médian absolu), minimum utile, borne. */
    val MAX_RESIDUAL_SPREAD = 30.milliseconds
    val MIN_RESIDUAL = 10.milliseconds
    val MAX_RESIDUAL = 120.milliseconds

    /** Morceaux avant de réapprendre le choix de la drop (validation « une de côté » possible et parlante). */
    const val MIN_MODEL_SONGS = 6

    /** Une drop retrouvée à ce nombre de temps près compte comme juste (l'écart restant se pose en ms). */
    const val HIT_BEATS = 1

    private const val STEPS = 400
    private const val RATE = 0.05
    private const val SHARPNESS = 4.0
    private const val REGULARIZATION = 0.02

    /** Poids de départ : la règle actuelle seule (le modèle part d'elle et ne s'en écarte que si les exemples le demandent). */
    val RULE: DoubleArray get() = DoubleArray(DropFeatures.NAMES.size).also { it[7] = 1.0 }

    fun learn(examples: List<DropExample>): LearnedDrop {
        if (examples.isEmpty()) return LearnedDrop.NONE
        val n = examples.size
        val meanOffset = examples.sumOf { it.offsetMs } / n
        val beatOffset = examples.map { it.beatOffset }.sorted()[n / 2]
        val agreement = examples.count { it.beatOffset == beatOffset }.toDouble() / n
        val residuals = examples.filter { it.beatOffset == beatOffset }.map { it.residualMs }.sorted()
        val residual = residuals[residuals.size / 2].milliseconds
        val spread = residuals.map { abs(it - residual.inWholeMilliseconds) }.sorted()[residuals.size / 2].milliseconds
        val beatsOk = abs(beatOffset) <= MAX_BEATS && agreement >= MIN_AGREEMENT
        val residualOk = spread <= MAX_RESIDUAL_SPREAD && residual.absoluteValue <= MAX_RESIDUAL
        val usedResidual = if (residualOk && residual.absoluteValue >= MIN_RESIDUAL) residual else Duration.ZERO
        val biasActive = n >= MIN_SONGS && beatsOk && residualOk && (beatOffset != 0 || usedResidual != Duration.ZERO)

        val withFeatures = examples.filter { it.features.size > it.posedBeat && it.posedBeat >= 0 }
        val rows = withFeatures.map { e -> e.features.map { it.toDoubleArray() } }
        val targets = withFeatures.map { it.posedBeat }
        val ruleHits = withFeatures.count { abs(it.detectedBeat - it.posedBeat) <= HIT_BEATS }
        val modelHits = if (withFeatures.size >= 2) leaveOneOut(rows, targets) else null
        val modelActive = withFeatures.size >= MIN_MODEL_SONGS && modelHits != null && modelHits > ruleHits
        val weights = if (withFeatures.isNotEmpty()) fit(rows, targets) else null
        log.info {
            "Drops apprises : $n morceau(x), écart ${beatOffset} temps (accord ${"%.2f".format(agreement)}) + ${residual.inWholeMilliseconds} ms " +
                "(dispersion ${spread.inWholeMilliseconds} ms) ${if (biasActive) "appliqué" else "pas encore"} ; choix appris ${modelHits ?: "-"}/${withFeatures.size} " +
                "contre règle $ruleHits/${withFeatures.size} ${if (modelActive) "actif" else "pas encore"}"
        }
        return LearnedDrop(
            n, meanOffset, beatOffset, agreement, usedResidual, spread, biasActive,
            weights, modelHits, ruleHits.takeIf { withFeatures.isNotEmpty() }, modelActive,
        )
    }

    /** Temps de plus haute note (à égalité, le premier). */
    fun choose(rows: List<DoubleArray>, w: DoubleArray): Int = rows.indices.maxBy { b -> score(rows[b], w) - 1e-9 * b }

    private fun score(f: DoubleArray, w: DoubleArray) = f.indices.sumOf { f[it] * w[it] }

    /**
     * Poids qui rendent la drop posée la plus probable parmi tous les temps de son morceau (softmax des notes ×
     * [SHARPNESS]), tirés vers la règle actuelle ([REGULARIZATION]). Descente de gradient à pas fixe : déterministe.
     */
    fun fit(songs: List<List<DoubleArray>>, targets: List<Int>): DoubleArray {
        val w = RULE.copyOf()
        val k = w.size
        repeat(STEPS) {
            val grad = DoubleArray(k)
            songs.forEachIndexed { s, rows ->
                val scores = rows.map { SHARPNESS * score(it, w) }
                val top = scores.max()
                val ex = scores.map { exp(it - top) }
                val sum = ex.sum()
                val t = targets[s]
                for (j in 0 until k) {
                    var expected = 0.0
                    for (b in rows.indices) expected += ex[b] / sum * rows[b][j]
                    grad[j] += SHARPNESS * (rows[t][j] - expected)
                }
            }
            val base = RULE
            for (j in 0 until k) w[j] += RATE * (grad[j] / songs.size - REGULARIZATION * (w[j] - base[j]))
        }
        return w
    }

    /** Drops posées retrouvées (à [HIT_BEATS] près) par un modèle appris sur tous les autres morceaux. */
    fun leaveOneOut(songs: List<List<DoubleArray>>, targets: List<Int>): Int = songs.indices.count { i ->
        val w = fit(songs.filterIndexed { j, _ -> j != i }, targets.filterIndexed { j, _ -> j != i })
        abs(choose(songs[i], w) - targets[i]) <= HIT_BEATS
    }

    /** Exemple d'une drop posée à [at] sur l'analyse détectée [detected] de la musique de clé [key]. */
    fun example(key: String, name: String, detected: MusicAnalysis, at: Duration, now: Instant = Instant.now()): DropExample {
        val posed = detected.beats.indices.minBy { (detected.beats[it] - at).absoluteValue }
        return DropExample(
            key, name, detected.dropBeat, posed, (at - detected.beats[posed]).inWholeMilliseconds, detected.bpm,
            DropFeatures.compact(DropFeatures.of(detected)), now.toString(),
        )
    }

    /** Log-vraisemblance moyenne (diagnostic des tests). */
    internal fun logLikelihood(songs: List<List<DoubleArray>>, targets: List<Int>, w: DoubleArray): Double = songs.indices.sumOf { s ->
        val scores = songs[s].map { SHARPNESS * score(it, w) }
        val top = scores.max()
        scores[targets[s]] - top - ln(scores.sumOf { exp(it - top) })
    } / songs.size
}
