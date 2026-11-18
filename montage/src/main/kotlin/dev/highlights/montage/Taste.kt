package dev.highlights.montage

import dev.highlights.core.serialization.roundTo
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
import kotlin.math.exp
import kotlin.math.ln

private val log = KotlinLogging.logger {}

/**
 * Un choix de l'utilisateur après les aperçus : les critères de la note ([MontageScore.criteria]) de chaque variante
 * proposée, dans l'ordre des aperçus, et l'index (0..n−1) de celle qu'il a rendue.
 */
@Serializable
data class TasteChoice(
    val at: String,
    val profile: String,
    val music: String,
    val chosen: Int,
    val variants: List<Map<String, Double?>>,
    /** Rapport du rendu final, pour retrouver le montage d'où vient ce choix. */
    val report: String? = null,
) {
    init {
        require(chosen in variants.indices) { "variante choisie hors de la liste" }
    }
}

/** Corrections de l'éditeur de plan sur un montage rendu ; gardées pour plus tard, pas encore apprises. */
@Serializable
data class TasteEdit(
    val at: String,
    val profile: String,
    val music: String,
    /** Kills écartés, forcés, épinglés (« capture@instant ») ; épinglés : vers « drop » ou l'instant de la musique. */
    val excluded: List<String> = emptyList(),
    val forced: List<String> = emptyList(),
    val pins: Map<String, String> = emptyMap(),
    val report: String? = null,
)

/** Fichier `taste-history.json` : version du format, choix et corrections, du plus ancien au plus récent. */
@Serializable
data class StoredTaste(
    val version: Int = 1,
    val choices: List<TasteChoice> = emptyList(),
    val edits: List<TasteEdit> = emptyList(),
)

/**
 * Historique des goûts de l'utilisateur (`outputDir/sessions/taste-history.json`, comme [MusicHistory]) : alimenté à
 * chaque rendu d'une variante choisie et à chaque montage rendu avec des corrections de l'éditeur. Lu par
 * [TasteLearner] quand `montage.learnTaste` est vrai, et par `app gouts`.
 */
class TasteHistory(val file: Path) {
    private val lock = Any()

    fun load(): StoredTaste = synchronized(lock) {
        if (!file.exists()) return StoredTaste()
        try {
            json.decodeFromString(StoredTaste.serializer(), file.readText())
        } catch (e: Exception) {
            log.warn { "Historique des goûts illisible ($file), ignoré : ${e.message}" }
            StoredTaste()
        }
    }

    fun record(choice: TasteChoice) = update { it.copy(choices = (it.choices + choice).takeLast(KEEP)) }

    fun record(edit: TasteEdit) = update { it.copy(edits = (it.edits + edit).takeLast(KEEP)) }

    /**
     * Oublie tout ce qui a été appris : l'historique est mis de côté (`taste-history.reset-<instant>.json`), pas
     * effacé ; le renommer en `taste-history.json` le rétablit. Rend le fichier mis de côté, null s'il n'y avait rien.
     */
    fun reset(): Path? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = Instant.now().toString().replace(":", "-").substringBefore('.')
        val aside = file.resolveSibling(file.name.removeSuffix(".json") + ".reset-$stamp.json")
        file.moveTo(aside)
        aside
    }

    private fun update(change: (StoredTaste) -> StoredTaste) = synchronized(lock) {
        val next = change(load())
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredTaste.serializer(), next))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Historique des goûts non enregistré ($file) : ${it.message}" } }
    }

    companion object {
        /** Les 500 derniers choix : des années de montages, et un fichier qui reste petit. */
        const val KEEP = 500
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}

/**
 * Poids appris : [weights] à donner au scoreur, [base] ceux d'origine ; [choices] choix utilisés. [agreementBefore] /
 * [agreementAfter] : part des choix où la variante rendue est la mieux notée, avec les poids d'origine puis appris.
 * [active] : assez de choix pour que les poids appris servent ([TasteLearner.MIN_CHOICES]).
 */
data class LearnedTaste(
    val weights: Map<String, Double>,
    val base: Map<String, Double>,
    val choices: Int,
    val agreementBefore: Double,
    val agreementAfter: Double,
    val active: Boolean,
)

/**
 * Apprend les poids des critères de la note à partir des choix de variantes. Préférence par paires (modèle de
 * Bradley-Terry) : pour chaque choix, la variante rendue doit battre chacune des autres, avec une probabilité
 * `1 / (1 + exp(−[SHARPNESS] × (note choisie − note autre)))`. On minimise la perte logistique de ces paires plus un
 * rappel vers les poids d'origine ([REGULARIZATION], en écart relatif), par descente de gradient projetée : chaque poids
 * reste entre [MIN_FACTOR] et [MAX_FACTOR] fois sa valeur d'origine, et leur somme reste celle d'origine (la note garde
 * son échelle). Simple et explicable : un poids qui monte est un critère que l'utilisateur préfère plus que le moteur ne
 * le pensait.
 */
object TasteLearner {
    /** En dessous, les poids appris ne servent pas : quelques choix au hasard ne disent pas un goût. */
    const val MIN_CHOICES = 10

    /**
     * Pente de la préférence : les notes de variantes proposées diffèrent de quelques centièmes (0,857 à 0,954 sur
     * « Rome Is Burning ») ; à 30, un écart de 0,05 vaut une préférence de 82 %.
     */
    const val SHARPNESS = 30.0

    /** Rappel vers les poids d'origine : sans lui, dix choix suffiraient à faire basculer toute la note. */
    const val REGULARIZATION = 0.05

    /** Bornes d'un poids appris, en multiple de son poids d'origine : aucun critère ne disparaît ni n'écrase les autres. */
    const val MIN_FACTOR = 0.25
    const val MAX_FACTOR = 4.0

    private const val STEPS = 2000
    private const val RATE = 0.02

    fun learn(choices: List<TasteChoice>, base: Map<String, Double> = MontageScorer.WEIGHTS, minChoices: Int = MIN_CHOICES): LearnedTaste {
        val usable = choices.filter { it.variants.size >= 2 }
        val before = agreement(usable, base)
        if (usable.size < minChoices) return LearnedTaste(base, base, usable.size, before, before, active = false)
        val keys = base.keys.toList()
        val budget = base.values.sum()
        var w = base.toMutableMap()
        repeat(STEPS) {
            val grad = keys.associateWith { 0.0 }.toMutableMap()
            for (c in usable) {
                val chosen = c.variants[c.chosen]
                for ((i, other) in c.variants.withIndex()) {
                    if (i == c.chosen) continue
                    val diff = MontageScorer.total(chosen, w) - MontageScorer.total(other, w)
                    // d(perte)/d(diff) pour log(1 + exp(−s·diff)).
                    val dLoss = -SHARPNESS / (1.0 + exp(SHARPNESS * diff))
                    for (k in keys) grad[k] = grad.getValue(k) + dLoss * (partial(chosen, w, k) - partial(other, w, k))
                }
            }
            val pairs = usable.sumOf { it.variants.size - 1 }.toDouble()
            w = keys.associateWith { k ->
                val b = base.getValue(k)
                val g = grad.getValue(k) / pairs + 2 * REGULARIZATION * (w.getValue(k) - b) / (b * b)
                (w.getValue(k) - RATE * g).coerceIn(b * MIN_FACTOR, b * MAX_FACTOR)
            }.toMutableMap()
            // Même somme qu'à l'origine, puis de nouveau dans les bornes (la remise à l'échelle peut en faire sortir).
            val scale = budget / w.values.sum()
            w = keys.associateWith { k -> (w.getValue(k) * scale).coerceIn(base.getValue(k) * MIN_FACTOR, base.getValue(k) * MAX_FACTOR) }.toMutableMap()
        }
        val learned = w.mapValues { it.value.roundTo(4) }
        val after = agreement(usable, learned)
        log.info { "Goûts appris sur ${usable.size} choix : accord ${"%.2f".format(before)} → ${"%.2f".format(after)}" }
        return LearnedTaste(learned, base, usable.size, before, after, active = true)
    }

    /** Dérivée de la note d'ensemble par rapport au poids [k] : (critère − note) / somme des poids des critères mesurés. */
    private fun partial(criteria: Map<String, Double?>, w: Map<String, Double>, k: String): Double {
        val value = criteria[k] ?: return 0.0
        val weight = criteria.filter { (key, v) -> v != null && key in w }.keys.sumOf { w.getValue(it) }
        if (weight <= 0.0) return 0.0
        return (value - MontageScorer.total(criteria, w)) / weight
    }

    /** Part des choix où la variante rendue est (seule) la mieux notée avec [weights]. */
    fun agreement(choices: List<TasteChoice>, weights: Map<String, Double>): Double {
        if (choices.isEmpty()) return 0.0
        return choices.count { c ->
            val totals = c.variants.map { MontageScorer.total(it, weights) }
            totals.indices.filter { it != c.chosen }.all { totals[c.chosen] > totals[it] }
        }.toDouble() / choices.size
    }

    /** Perte moyenne par paire (log-vraisemblance négative), pour comparer deux jeux de poids. */
    fun loss(choices: List<TasteChoice>, weights: Map<String, Double>): Double {
        var total = 0.0
        var n = 0
        for (c in choices) for ((i, other) in c.variants.withIndex()) {
            if (i == c.chosen) continue
            total += ln(1.0 + exp(-SHARPNESS * (MontageScorer.total(c.variants[c.chosen], weights) - MontageScorer.total(other, weights))))
            n++
        }
        return if (n == 0) 0.0 else total / n
    }
}
