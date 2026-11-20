package dev.highlights.montage

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drops synthétiques à biais caché (comme les goûts de groupes) : on sait où est la vraie drop, on simule la
 * détection et l'utilisateur qui la repose, et on mesure en combien de drops posées l'apprentissage converge.
 * Les tableaux vont dans `build/drop-learning.md` (repris dans le LOG de l'atelier).
 */
class DropLearningTest : FunSpec({
    /**
     * Un morceau : montée, un temps presque muet juste avant la vraie drop [truth], drop forte, puis d'autres parties.
     * [detectedAt] : où la détection met la drop (une section y commence, comme le ferait findDrop).
     */
    fun song(seed: Int, truth: Int, detectedAt: Int, n: Int = 220): MusicAnalysis {
        val r = Random(seed)
        val bpm = 100.0 + r.nextInt(50)
        val period = 60.0 / bpm
        val energy = DoubleArray(n) { b ->
            when {
                b < truth - 32 -> 0.04 + 0.01 * r.nextDouble()
                b < truth - 1 -> 0.05 + 0.15 * (b - (truth - 32)) / 31.0 + 0.02 * r.nextDouble()
                b == truth - 1 -> 0.003
                b < truth + 64 -> 0.6 + 0.05 * r.nextDouble()
                b < truth + 96 -> 0.25 + 0.03 * r.nextDouble()
                else -> 0.55 + 0.05 * r.nextDouble()
            }
        }
        // Quelques creux de remplissage ailleurs : le creux seul ne suffit pas à trouver la drop.
        repeat(4) { energy[r.nextInt(8, n - 8)] *= 0.3 }
        val starts = listOf(0, truth - 32, detectedAt, truth + 64, truth + 96).distinct().sorted().filter { it in 0 until n }
        val sections = starts.mapIndexed { i, s ->
            val e = starts.getOrElse(i + 1) { n }
            val mean = (s until e).sumOf { energy[it] } / (e - s)
            MusicSection(s, e, 20 * kotlin.math.log10(mean), (mean / 0.65).coerceIn(0.0, 1.0))
        }
        return MusicAnalysis(
            Path("song$seed.mp3"), (n * period).seconds, bpm, List(n) { (it * period).seconds }, downbeatPhase = truth % 4,
            beatEnergy = energy, beatAccent = DoubleArray(n) { 0.5 + 0.1 * r.nextDouble() }, sections = MusicAnalyzer.classify(sections, detectedAt),
            dropBeat = detectedAt,
        )
    }

    /** L'utilisateur pose la drop à l'oreille : sur la vraie drop, à quelques ms près. */
    fun posed(m: MusicAnalysis, truth: Int, r: Random) = m.beatTime(truth) + (r.nextInt(-6, 7)).milliseconds

    /** Drop retenue pour un morceau jamais posé : apprise si elle s'applique, sinon détectée. */
    fun chosen(m: MusicAnalysis, learned: LearnedDrop) = learned.dropFor(m) ?: m.beatTime(m.dropBeat)

    fun right(m: MusicAnalysis, truth: Int, at: kotlin.time.Duration) = (at - m.beatTime(truth)).absoluteValue <= 30.milliseconds

    val report = StringBuilder()

    test("écart régulier caché (détection deux temps trop tard, comme Rome Is Burning) : appliqué à partir de 5 morceaux") {
        val r = Random(7)
        val truths = List(30) { 70 + r.nextInt(40) }
        val train = (0 until 12).map { i -> song(100 + i, truths[i], truths[i] + 2) }
        val test = (12 until 30).map { i -> i to song(100 + i, truths[i], truths[i] + 2) }
        report.appendLine("### Écart régulier caché : la détection met la drop 2 temps trop tard").appendLine()
        report.appendLine("| drops posées | écart appris | appliqué | morceaux jamais posés justes (±30 ms) |")
        report.appendLine("|---|---|---|---|")
        var firstActive: Int? = null
        for (k in 1..train.size) {
            val examples = (0 until k).map { i -> DropLearner.example("k$i", "s$i", train[i], posed(train[i], truths[i], r)) }
            val learned = DropLearner.learn(examples)
            val ok = test.count { (i, m) -> right(m, truths[i], chosen(m, learned)) }
            if (learned.biasActive && firstActive == null) firstActive = k
            report.appendLine("| $k | ${learned.beatOffset} temps ${learned.residual?.inWholeMilliseconds} ms | ${if (learned.biasActive) "oui" else "non"} | $ok / ${test.size} |")
            if (k == train.size) {
                learned.biasActive shouldBe true
                learned.beatOffset shouldBe -2
                ok shouldBe test.size
            }
        }
        firstActive shouldBe DropLearner.MIN_SONGS
        report.appendLine().appendLine("Détection seule : 0 / ${test.size}. Appliqué dès ${firstActive} drops posées.").appendLine()
    }

    test("écarts sans régularité : rien d'appliqué (dispersion trop grande)") {
        val r = Random(11)
        val offsets = listOf(2, -4, 4, 0, 8, -2, 2, 4)
        val examples = offsets.mapIndexed { i, o ->
            val t = 80 + i
            val m = song(300 + i, t, t + o)
            DropLearner.example("k$i", "s$i", m, posed(m, t, r))
        }
        val learned = DropLearner.learn(examples)
        learned.biasActive shouldBe false
    }

    test("choix de la drop réappris (la vraie drop suit un silence, la détection se trompe sans régularité) : actif quand il bat la règle") {
        val r = Random(23)
        val wrong = listOf(2, 4, -4, 8, 0, 6, -8, 4, 0, 2, 12, -6)
        fun detected(i: Int) = wrong[i % wrong.size]
        val truths = List(40) { 70 + r.nextInt(40) }
        val train = (0 until 14).map { i -> song(500 + i, truths[i], truths[i] + detected(i)) }
        val test = (14 until 40).map { i -> i to song(500 + i, truths[i], truths[i] + detected(i + 5)) }
        report.appendLine("### Choix de la drop : la vraie drop suit un silence ; la détection se trompe sans régularité").appendLine()
        report.appendLine("| drops posées | une de côté : modèle / règle | actif | morceaux jamais posés justes (±30 ms) |")
        report.appendLine("|---|---|---|---|")
        val ruleOk = test.count { (i, m) -> right(m, truths[i], m.beatTime(m.dropBeat)) }
        var firstActive: Int? = null
        var last: Int? = null
        for (k in 2..train.size) {
            val examples = (0 until k).map { i -> DropLearner.example("k$i", "s$i", train[i], posed(train[i], truths[i], r)) }
            val learned = DropLearner.learn(examples)
            val ok = test.count { (i, m) -> right(m, truths[i], chosen(m, learned)) }
            if (learned.modelActive && firstActive == null) firstActive = k
            learned.biasActive shouldBe false
            report.appendLine("| $k | ${learned.modelHits} / ${learned.ruleHits} sur $k | ${if (learned.modelActive) "oui" else "non"} | $ok / ${test.size} |")
            last = ok
        }
        report.appendLine().appendLine("Règle seule sur les morceaux jamais posés : $ruleOk / ${test.size}. Actif dès ${firstActive} drops posées.").appendLine()
        firstActive shouldNotBe null
        firstActive!! shouldBe maxOf(firstActive!!, DropLearner.MIN_MODEL_SONGS)
        last!! shouldBeGreaterThan ruleOk
    }

    test("règle déjà juste : le modèle ne s'active pas (il ne fait pas mieux qu'elle)") {
        val r = Random(31)
        val examples = (0 until 8).map { i ->
            val t = 80 + 3 * i
            val m = song(700 + i, t, t)
            DropLearner.example("k$i", "s$i", m, posed(m, t, r))
        }
        val learned = DropLearner.learn(examples)
        learned.modelActive shouldBe false
        learned.biasActive shouldBe false
        learned.ruleHits shouldBe 8
    }

    test("exemples : un par morceau (le dernier posé remplace), oubliés un à un, mis de côté au réinitialiser") {
        val dir = tempdir().toPath()
        val store = DropExamples(dir.resolve("sessions/drop-learning.json"))
        val m = song(1, 80, 82)
        store.record(DropLearner.example("a", "A", m, m.beatTime(80)))
        store.record(DropLearner.example("a", "A", m, m.beatTime(79)))
        store.record(DropLearner.example("b", "B", m, m.beatTime(80)))
        store.load().size shouldBe 2
        store.load().single { it.key == "a" }.beatOffset shouldBe -3
        store.forget("b")
        store.load().map { it.key } shouldBe listOf("a")
        val aside = store.reset()
        aside!!.exists() shouldBe true
        store.load() shouldBe emptyList()
    }

    test("drop apprise : appliquée par MusicDrops sans passer pour une drop posée") {
        val m = song(2, 80, 82)
        val d = MusicDrops.apply(m, m.beatTime(80), byHand = false)
        d.dropByHand shouldBe null
        d.dropLearned shouldBe m.beatTime(80)
        d.dropBeat shouldBe 80
        abs(d.sectionAt(80).startBeat - 80) shouldBe 0
    }

    afterSpec {
        val out = Path("build").createDirectories().resolve("drop-learning.md")
        out.writeText(report.toString())
    }
})
