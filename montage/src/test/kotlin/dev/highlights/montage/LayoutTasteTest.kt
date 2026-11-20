package dev.highlights.montage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Apprendre d'où l'utilisateur pose ses kills à l'étape 1 de « Montage sur la musique » (demande du 20/11) :
 * contexte musical, règles prudentes, et une démo à goût caché qui dit combien de montages il faut.
 */
class LayoutTasteTest : FunSpec({
    val n = 240
    fun music(seed: Int) = Random(seed).let { r ->
        MusicAnalysis(
            Path("musique$seed.mp3"), 120.seconds, 120.0, List(n) { (it * 0.5).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { 0.2 + 0.8 * r.nextDouble() },
            sections = listOf(MusicSection(0, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)), dropBeat = 64,
        )
    }

    /**
     * L'utilisateur au goût caché : chaque kill proposé va, avec la probabilité [follow], sur le temps le plus fort à
     * ±2 temps (sinon sur un temps voisin au hasard), 25 ms après (± [noise]) ; il ajoute 2 kills un temps après d'autres.
     */
    fun hidden(noise: Int = 6, follow: Double = 1.0) = { m: MusicAnalysis, auto: List<Duration>, r: Random ->
        fun late() = (25 + r.nextInt(-noise, noise + 1)).milliseconds
        val beats = auto.map { a ->
            val b = (a / m.beatPeriod).toInt()
            if (r.nextDouble() < follow) (b - 2..b + 2).filter { it in 1 until n - 1 }.maxBy { m.strength(it) } else b + r.nextInt(-2, 3)
        }.distinct()
        (beats.map { m.beats[it] + late() } + List(2) { k -> m.beats[beats[k * 3] + 1] + late() }).sorted()
    }

    /** Un montage simulé : proposition de 10 kills sur des temps au hasard, placement final de [user]. */
    fun montage(i: Int, user: (MusicAnalysis, List<Duration>, Random) -> List<Duration>): LayoutExample {
        val r = Random(1000 + i)
        val m = music(i)
        val auto = (0 until 10).map { k -> m.beats[8 + k * 20 + r.nextInt(0, 6)] }
        val final = user(m, auto, r)
        val chosen = final.filter { f -> auto.none { (it - f).absoluteValue <= 30.milliseconds } }.toSet()
        return LayoutExample(
            "t$i", "wardogs", "musique$i", LayoutTasteLearner.contexts(m, auto), LayoutTasteLearner.contexts(m, final, chosen),
            moved = chosen.size, added = (final.size - auto.size).coerceAtLeast(0), removed = 0,
        )
    }

    test("contexte d'un kill : écart au temps, force, section, après la drop, kills par mesure") {
        val m = music(1)
        val c = LayoutTasteLearner.contexts(m, listOf(m.beats[70] + 20.milliseconds, m.beats[71], m.beats[10]), chosen = setOf(m.beats[71]))
        c.map { it.offsetMs } shouldBe listOf(0L, 20L, 0L)
        c[1].afterDrop.shouldBeTrue()
        c[0].afterDrop.shouldBeFalse()
        c[1].barKills shouldBe 2
        c[2].chosen.shouldBeTrue()
        c[1].strength shouldBe m.strength(70)
    }

    test("démo à goût caché : appris après quelques montages, rien sans goût ni au hasard") {
        /** Table de convergence d'un goût : ligne par nombre de montages, et le premier montage où chaque goût s'applique. */
        fun converge(user: (MusicAnalysis, List<Duration>, Random) -> List<Duration>): Pair<List<String>, List<Int?>> {
            val rows = mutableListOf<String>()
            val at = arrayOfNulls<Int>(3)
            val examples = mutableListOf<LayoutExample>()
            for (i in 1..12) {
                examples += montage(i, user)
                val l = LayoutTasteLearner.learn(examples)
                listOf(l.offsetActive, l.accentActive, l.densityActive).forEachIndexed { k, on -> if (on && at[k] == null) at[k] = i }
                rows += "| $i | ${l.chosen} | ${l.offset?.inWholeMilliseconds} ms ${if (l.offsetActive) "oui" else "non"} | " +
                    "${l.accentGain?.let { "%+.2f".format(it) }} ${if (l.accentActive) "oui" else "non"} | ${l.density?.let { "%.2f".format(it) }} ${if (l.densityActive) "oui" else "non"} |"
            }
            return rows to at.toList()
        }
        val (rows, firsts) = converge(hidden())
        val (vague, vagueFirsts) = converge(hidden(noise = 15, follow = 0.6))
        val (offsetAt, accentAt, densityAt) = firsts
        val learned = LayoutTasteLearner.learn((1..12).map { montage(it, hidden()) })
        learned.offset!!.inWholeMilliseconds shouldBeInRange 20L..30L
        learned.offsetActive.shouldBeTrue()
        learned.accentActive.shouldBeTrue()
        learned.densityActive.shouldBeTrue()
        (learned.perClipFactor < 1.0).shouldBeTrue()
        learned.killShift shouldNotBe null
        // Prudence : jamais avant MIN_MONTAGES.
        listOfNotNull(offsetAt, accentAt, densityAt).all { it >= LayoutTasteLearner.MIN_MONTAGES }.shouldBeTrue()

        // Sans goût (proposition gardée telle quelle) : rien d'appris d'appliqué.
        val none = LayoutTasteLearner.learn((1..10).map { montage(it) { _, auto, _ -> auto } })
        none.offsetActive.shouldBeFalse()
        none.accentActive.shouldBeFalse()
        none.densityActive.shouldBeFalse()
        none.perClipFactor shouldBe 1.0
        // Au hasard (déplacements sans régularité, ±100 ms autour d'un temps voisin) : ni écart ni accents.
        val noisy = LayoutTasteLearner.learn(
            (1..10).map { i ->
                montage(i) { m, auto, r -> auto.map { a -> a + m.beatPeriod * r.nextInt(-1, 2) + r.nextInt(-100, 101).milliseconds + 31.milliseconds } }
            },
        )
        noisy.offsetActive.shouldBeFalse()
        noisy.accentActive.shouldBeFalse()

        val head = "| montages | kills choisis | écart (appliqué) | accents (appliqués) | densité (appliquée) |\n|---|---|---|---|---|\n"
        val out = Path("build/layout-taste").createDirectories().resolve("demo.md")
        out.writeText(
            "Goût net : kill sur le temps le plus fort à ±2 temps, 25 ms ± 6 après, 2 kills ajoutés par montage (10 proposés).\n\n" +
                head + rows.joinToString("\n") + "\n\nÉcart appliqué au montage $offsetAt, accents au montage $accentAt, densité au montage $densityAt.\n\n" +
                "Goût flou : le temps le plus fort 6 fois sur 10 seulement, 25 ms ± 15 après.\n\n" +
                head + vague.joinToString("\n") + "\n\nÉcart appliqué au montage ${vagueFirsts[0]}, accents au montage ${vagueFirsts[1]}, densité au montage ${vagueFirsts[2]}.\n\n" +
                "Sans goût (proposition gardée) : rien d'appliqué ; au hasard : écart ${noisy.offsetActive}, accents ${noisy.accentActive}.\n",
        )
        println(Files.readString(out))
    }

    test("réinitialiser met l'historique de côté") {
        val dir = Files.createTempDirectory("layout")
        val h = LayoutHistory(dir.resolve("kill-layout.json"))
        h.reset() shouldBe null
        h.record(montage(1, hidden()))
        h.load().size shouldBe 1
        val aside = h.reset()!!
        aside.exists().shouldBeTrue()
        h.load().size shouldBe 0
        LayoutTasteLearner.learn(h.load()) shouldBe LearnedLayout.NONE
    }
})
