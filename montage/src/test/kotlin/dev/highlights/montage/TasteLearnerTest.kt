package dev.highlights.montage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import kotlin.random.Random

class TasteLearnerTest : FunSpec({
    val base = MontageScorer.WEIGHTS

    /** Goût caché : l'utilisateur tient bien plus que le moteur à l'action et à l'ouverture, beaucoup moins à la sobriété. */
    val hidden = base + mapOf("action" to 0.40, "opening" to 0.30, "restraint" to 0.05)

    /**
     * [n] choix entre 3 variantes aux critères tirés au hasard autour de valeurs réalistes (0,6 à 1), parfois non mesurés ;
     * l'utilisateur rend celle que son goût caché préfère, sauf [noise] fois sur 1 où il en prend une au hasard.
     */
    fun choices(n: Int, seed: Int, noise: Double = 0.1): List<TasteChoice> {
        val random = Random(seed)
        return List(n) {
            val variants = List(3) {
                base.keys.associateWith { k -> if (k in setOf("variety", "pacing") && random.nextDouble() < 0.2) null else 0.6 + 0.4 * random.nextDouble() }
            }
            val chosen = if (random.nextDouble() < noise) random.nextInt(3) else variants.indices.maxBy { MontageScorer.total(variants[it], hidden) }
            TasteChoice("2026-11-18T00:00:00Z", "wardogs", "musique.mp3", chosen, variants)
        }
    }

    test("goût caché retrouvé : les poids des critères préférés montent, celui négligé baisse") {
        val learned = TasteLearner.learn(choices(40, seed = 1))
        learned.active shouldBe true
        (learned.weights.getValue("action") > base.getValue("action")) shouldBe true
        (learned.weights.getValue("opening") > base.getValue("opening")) shouldBe true
        (learned.weights.getValue("restraint") < base.getValue("restraint")) shouldBe true
        (learned.agreementAfter > learned.agreementBefore) shouldBe true
    }

    test("les poids appris prédisent mieux les choix suivants que ceux d'origine") {
        val learned = TasteLearner.learn(choices(40, seed = 2))
        val later = choices(200, seed = 3, noise = 0.0)
        (TasteLearner.agreement(later, learned.weights) > TasteLearner.agreement(later, base) + 0.05) shouldBe true
        (TasteLearner.loss(later, learned.weights) < TasteLearner.loss(later, base)) shouldBe true
    }

    test("poids bornés et même somme qu'à l'origine") {
        val learned = TasteLearner.learn(choices(60, seed = 4, noise = 0.0))
        learned.weights.forEach { (k, w) ->
            (w >= base.getValue(k) * TasteLearner.MIN_FACTOR - 1e-3 && w <= base.getValue(k) * TasteLearner.MAX_FACTOR + 1e-3) shouldBe true
        }
        learned.weights.values.sum() shouldBe (base.values.sum() plusOrMinus 1e-2)
    }

    test("trop peu de choix : poids d'origine, inactif") {
        val learned = TasteLearner.learn(choices(TasteLearner.MIN_CHOICES - 1, seed = 5))
        learned.active shouldBe false
        learned.weights shouldBe base
    }

    test("choix sans préférence (au hasard) : les poids restent près de l'origine") {
        val learned = TasteLearner.learn(choices(40, seed = 6, noise = 1.0))
        learned.weights.forEach { (k, w) -> (kotlin.math.abs(w - base.getValue(k)) < base.getValue(k)) shouldBe true }
    }

    test("historique : choix et corrections relus, remise à zéro réversible") {
        val dir = Files.createTempDirectory("gouts")
        val history = TasteHistory(dir.resolve("taste-history.json"))
        val one = choices(1, seed = 7).single()
        history.record(one)
        history.record(TasteEdit("2026-11-18T00:00:00Z", "wardogs", "musique.mp3", excluded = listOf("partie.mp4@3:12.4")))
        history.load().choices shouldBe listOf(one)
        history.load().edits.single().excluded shouldBe listOf("partie.mp4@3:12.4")
        val aside = history.reset()
        aside shouldNotBe null
        history.load().choices shouldBe emptyList()
        TasteHistory(aside!!).load().choices shouldBe listOf(one)
    }

    test("la note d'un montage suit les poids donnés par les réglages") {
        val criteria = base.keys.associateWith<String, Double?> { 0.5 } + mapOf("action" to 1.0)
        val heavier = base + mapOf("action" to 0.40)
        (MontageScorer.total(criteria, heavier) > MontageScorer.total(criteria, base)) shouldBe true
    }
})
