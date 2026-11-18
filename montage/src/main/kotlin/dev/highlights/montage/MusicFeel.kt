package dev.highlights.montage

import dev.highlights.core.model.MusicFeel
import dev.highlights.core.serialization.roundTo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

/**
 * Réponse de l'utilisateur après un montage, sur le rythme rapide de sa musique : [GOOD] (ça va), [TOO_FAST] (trop
 * rapide), [NOT_FAST_ENOUGH] (pas assez rapide).
 */
@Serializable
enum class FeelVote {
    @SerialName("good") GOOD,
    @SerialName("tooFast") TOO_FAST,
    @SerialName("notFastEnough") NOT_FAST_ENOUGH;

    /**
     * Ressenti qu'on en tire, d'après ce que le montage a fait ([hadFast] : des plans au rythme rapide). « Ça va » confirme
     * ce qu'il a fait : rapide s'il y avait du rapide, calme sinon. « Trop rapide » : calme. « Pas assez » : rapide.
     */
    fun feel(hadFast: Boolean): MusicFeel = when (this) {
        GOOD -> if (hadFast) MusicFeel.FAST else MusicFeel.CALM
        TOO_FAST -> MusicFeel.CALM
        NOT_FAST_ENOUGH -> MusicFeel.FAST
    }
}

/**
 * Ressenti d'une musique, une entrée par musique : [key] (nom du fichier + taille, voir [MusicFeelStore.keyOf]) ;
 * [feel] : AUTO efface le ressenti ; [vote] : la réponse d'où il vient (null : dit en ligne de commande) ; [features] :
 * les mesures de la musique au moment de la réponse, pour apprendre sans réanalyser.
 */
@Serializable
data class FeelEntry(
    val key: String,
    val name: String,
    val sizeBytes: Long,
    val feel: MusicFeel,
    val at: String,
    val vote: FeelVote? = null,
    val features: MusicFeatures? = null,
)

/** Fichier `music-feel.json` : version du format et une entrée par musique. */
@Serializable
data class StoredFeels(val version: Int = 1, val songs: List<FeelEntry> = emptyList())

/**
 * Ressentis des musiques (`outputDir/sessions/music-feel.json`, comme [TasteHistory]) : alimenté par la question posée
 * après un montage kills et par `app musique-ressenti`, lu à chaque montage ([MusicFeels]).
 */
class MusicFeelStore(val file: Path) {
    private val lock = Any()

    fun load(): StoredFeels = synchronized(lock) {
        if (!file.exists()) return StoredFeels()
        try {
            json.decodeFromString(StoredFeels.serializer(), file.readText())
        } catch (e: Exception) {
            log.warn { "Ressentis des musiques illisibles ($file), ignorés : ${e.message}" }
            StoredFeels()
        }
    }

    /** Ressenti dit pour [music] (sans prédiction), AUTO s'il n'y en a pas. */
    fun feelOf(music: Path): MusicFeel = keyOf(music)?.let { key -> load().songs.firstOrNull { it.key == key }?.feel } ?: MusicFeel.AUTO

    /** Retient [feel] pour [music] ; AUTO retire la musique. Rend l'entrée gardée, null si retirée ou fichier illisible. */
    fun record(music: Path, feel: MusicFeel, vote: FeelVote? = null, features: MusicFeatures? = null, at: Instant = Instant.now()): FeelEntry? {
        val key = keyOf(music) ?: return null
        val entry = FeelEntry(key, music.name, music.fileSize(), feel, at.toString(), vote, features)
        update { stored ->
            val others = stored.songs.filter { it.key != key }
            stored.copy(songs = if (feel == MusicFeel.AUTO) others else others + entry)
        }
        return entry.takeIf { feel != MusicFeel.AUTO }
    }

    private fun update(change: (StoredFeels) -> StoredFeels) = synchronized(lock) {
        val next = change(load())
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredFeels.serializer(), next))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Ressenti de la musique non enregistré ($file) : ${it.message}" } }
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        /**
         * Identité d'une musique : nom du fichier (casse ignorée) et taille. Une musique déplacée ou copiée garde son
         * ressenti ; une autre version (taille différente) en a un à elle. Null si le fichier est illisible.
         */
        fun keyOf(music: Path): String? = runCatching { "${music.name.lowercase()}|${music.fileSize()}" }.getOrNull()
    }
}

/** D'où vient le ressenti appliqué à une musique : dit par l'utilisateur, prédit par [MusicFeelLearner], ou la règle par défaut. */
enum class FeelSource { MARKED, LEARNED, DEFAULT }

/** Ressenti appliqué à une musique et sa source ; [probability] : probabilité « rapide » prédite (LEARNED). */
data class ResolvedFeel(val feel: MusicFeel, val source: FeelSource, val probability: Double? = null)

/**
 * Ressenti à appliquer à chaque musique d'un montage : celui que l'utilisateur a dit, sinon celui que [MusicFeelLearner]
 * prédit de ses mesures quand le modèle est fiable ([LearnedFeel.active]) et la prédiction nette, sinon AUTO.
 */
class MusicFeels(private val store: MusicFeelStore, private val learnEnabled: Boolean = true) {
    private val stored by lazy { store.load() }
    private val learned by lazy { MusicFeelLearner.learn(stored.songs) }

    fun resolve(music: MusicAnalysis): ResolvedFeel {
        val key = MusicFeelStore.keyOf(music.file)
        stored.songs.firstOrNull { it.key == key }?.let { return ResolvedFeel(it.feel, FeelSource.MARKED) }
        val features = music.features ?: return ResolvedFeel(MusicFeel.AUTO, FeelSource.DEFAULT)
        if (!learnEnabled || !learned.active) return ResolvedFeel(MusicFeel.AUTO, FeelSource.DEFAULT)
        val p = learned.probability(features)
        return when {
            p >= MusicFeelLearner.CONFIDENT -> ResolvedFeel(MusicFeel.FAST, FeelSource.LEARNED, p)
            p <= 1 - MusicFeelLearner.CONFIDENT -> ResolvedFeel(MusicFeel.CALM, FeelSource.LEARNED, p)
            else -> ResolvedFeel(MusicFeel.AUTO, FeelSource.DEFAULT, p)
        }
    }

    /** Modèle appris des ressentis gardés (pour `app musique-ressenti`). */
    fun learned(): LearnedFeel = learned
}

/**
 * Modèle appris : [weights] (une par mesure de [MusicFeatures.NAMES], sur valeurs centrées réduites par [means] et
 * [scales]) et [bias] ; [songs] musiques utilisées ([fast] ressenties rapides) ; [accuracy] : part des musiques bien
 * classées en validation croisée « une de côté » (chaque musique prédite par un modèle appris sans elle), null si trop
 * peu de musiques. [active] : assez de musiques des deux sortes et [accuracy] au moins [MusicFeelLearner.MIN_ACCURACY].
 */
data class LearnedFeel(
    val weights: List<Double>,
    val bias: Double,
    val means: List<Double>,
    val scales: List<Double>,
    val songs: Int,
    val fast: Int,
    val accuracy: Double?,
    val active: Boolean,
) {
    /** Probabilité que [features] soit ressentie rapide. */
    fun probability(features: MusicFeatures): Double {
        val x = features.values()
        val z = bias + weights.indices.sumOf { weights[it] * (x[it] - means[it]) / scales[it] }
        return 1.0 / (1.0 + exp(-z))
    }
}

/**
 * Apprend le ressenti des mesures d'une musique ([MusicFeatures]) : régression logistique (mesures centrées réduites,
 * rappel L2 [REGULARIZATION] vers zéro, descente de gradient de [STEPS] pas, toujours depuis zéro : déterministe), comme
 * [TasteLearner] simple et explicable. Un poids positif : plus la mesure est haute, plus la musique est ressentie rapide.
 * Le modèle ne sert que s'il a de quoi apprendre ([MIN_SONGS] musiques, [MIN_PER_KIND] de chaque sorte) et s'il
 * prédit bien les musiques qu'il n'a pas vues ([MIN_ACCURACY] en validation croisée « une de côté »). Le 18/11, aucune
 * mesure seule ne séparait les musiques dites rapides de celles dites calmes : on ne sait pas encore si une combinaison
 * le fera, d'où la validation avant tout usage.
 */
object MusicFeelLearner {
    const val MIN_SONGS = 8
    const val MIN_PER_KIND = 3

    /** Part de musiques bien classées, en validation croisée, à partir de laquelle les prédictions servent. */
    const val MIN_ACCURACY = 0.8

    /** Probabilité à partir de laquelle une prédiction est assez nette pour servir (rapide), ou en dessous de 1 − elle (calme). */
    const val CONFIDENT = 0.7

    const val REGULARIZATION = 0.1
    private const val STEPS = 3000
    private const val RATE = 0.1

    fun learn(songs: List<FeelEntry>): LearnedFeel {
        val usable = songs.filter { it.features != null && it.feel != MusicFeel.AUTO }
        val xs = usable.map { it.features!!.values() }
        val ys = usable.map { if (it.feel == MusicFeel.FAST) 1.0 else 0.0 }
        val fast = ys.count { it > 0.5 }
        val enough = usable.size >= MIN_SONGS && fast >= MIN_PER_KIND && usable.size - fast >= MIN_PER_KIND
        val model = fit(xs, ys, usable.size, fast)
        val accuracy = if (usable.size >= 2 && fast in 1 until usable.size) leaveOneOut(xs, ys) else null
        val active = enough && accuracy != null && accuracy >= MIN_ACCURACY
        if (usable.isNotEmpty()) {
            log.info { "Ressenti des musiques : ${usable.size} musique(s) dont $fast rapide(s), validation ${accuracy?.let { "%.2f".format(it) } ?: "impossible"}, ${if (active) "prédictions utilisées" else "règle par défaut"}" }
        }
        return model.copy(accuracy = accuracy, active = active)
    }

    /** Part des musiques bien classées par un modèle appris sur toutes les autres. */
    fun leaveOneOut(xs: List<DoubleArray>, ys: List<Double>): Double {
        var right = 0
        for (i in xs.indices) {
            val trainX = xs.filterIndexed { j, _ -> j != i }
            val trainY = ys.filterIndexed { j, _ -> j != i }
            val m = fit(trainX, trainY, trainX.size, trainY.count { it > 0.5 })
            val p = m.probabilityOf(xs[i])
            if ((p >= 0.5) == (ys[i] > 0.5)) right++
        }
        return right.toDouble() / xs.size
    }

    private fun LearnedFeel.probabilityOf(x: DoubleArray): Double {
        val z = bias + weights.indices.sumOf { weights[it] * (x[it] - means[it]) / scales[it] }
        return 1.0 / (1.0 + exp(-z))
    }

    private fun fit(xs: List<DoubleArray>, ys: List<Double>, songs: Int, fast: Int): LearnedFeel {
        val d = MusicFeatures.NAMES.size
        if (xs.isEmpty()) return LearnedFeel(List(d) { 0.0 }, 0.0, List(d) { 0.0 }, List(d) { 1.0 }, 0, 0, null, false)
        val means = List(d) { k -> xs.sumOf { it[k] } / xs.size }
        val scales = List(d) { k -> sqrt(xs.sumOf { (it[k] - means[k]).let { v -> v * v } } / xs.size).takeIf { it > 1e-9 } ?: 1.0 }
        val z = xs.map { x -> DoubleArray(d) { k -> (x[k] - means[k]) / scales[k] } }
        val w = DoubleArray(d)
        var b = 0.0
        repeat(STEPS) {
            val gw = DoubleArray(d)
            var gb = 0.0
            for (i in z.indices) {
                val p = 1.0 / (1.0 + exp(-(b + (0 until d).sumOf { w[it] * z[i][it] })))
                val e = p - ys[i]
                for (k in 0 until d) gw[k] += e * z[i][k]
                gb += e
            }
            for (k in 0 until d) w[k] -= RATE * (gw[k] / z.size + REGULARIZATION * w[k])
            b -= RATE * gb / z.size
        }
        return LearnedFeel(w.map { it.roundTo(4) }, b.roundTo(4), means, scales, songs, fast, null, false)
    }

    /** Perte logistique moyenne de [model] sur [songs] (pour les tests). */
    fun loss(model: LearnedFeel, songs: List<FeelEntry>): Double {
        val usable = songs.filter { it.features != null && it.feel != MusicFeel.AUTO }
        if (usable.isEmpty()) return 0.0
        return usable.sumOf { s ->
            val p = model.probability(s.features!!).coerceIn(1e-9, 1 - 1e-9)
            if (s.feel == MusicFeel.FAST) -ln(p) else -ln(1 - p)
        } / usable.size
    }
}
