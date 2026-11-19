package dev.highlights.vision

import dev.highlights.core.analysis.SignalEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration.Companion.seconds

class RoundScoreTest : FunSpec({
    val decoder = ScoreDecoder(maxValue = 30, stepCost = 3.0, resetCost = 25.0, missCost = 1.0)
    fun changes(path: List<Pair<Int, Int>>) = (1 until path.size).filter { path[it] != path[it - 1] }.map { path[it] }

    test("décodage : les deux côtés ensemble, un round à la fois, une lecture fausse isolée corrigée") {
        // 3-4 tenu, un « 8 » lu une image à gauche (3 sur un ciel clair), puis 4-4.
        val ally = List(10) { 3 } + listOf(8) + List(3) { 3 } + List(10) { 4 }
        val enemy = List(24) { 4 }
        val path = decoder.decode(ally, enemy)
        changes(path) shouldBe listOf(4 to 4)
        path.first() shouldBe (3 to 4)
    }

    test("décodage : illisible quelques images (flash, bandeau de fin de round), le score reste") {
        val ally: List<Int?> = List(8) { 5 } + List(6) { null } + List(8) { 6 }
        val enemy: List<Int?> = List(8) { 5 } + List(6) { null } + List(8) { 5 }
        changes(decoder.decode(ally, enemy)) shouldBe listOf(6 to 5)
    }

    test("décodage : un saut de deux d'un coup passe par le round d'entre les deux, jamais des deux côtés à la fois") {
        val ally = List(10) { 2 } + List(10) { 3 }
        val enemy = List(10) { 2 } + List(10) { 3 }
        val path = decoder.decode(ally, enemy)
        path.last() shouldBe (3 to 3)
        changes(path).size shouldBe 2
    }

    test("décodage : retour à 0-0 tenu = nouvelle partie dans la même capture") {
        val ally = List(10) { 13 } + List(20) { 0 } + List(10) { 1 }
        val enemy = List(10) { 10 } + List(20) { 0 } + List(10) { 0 }
        changes(decoder.decode(ally, enemy)) shouldBe listOf(0 to 0, 1 to 0)
    }

    test("événements : score de départ à la première image, puis un par round, et la remise à zéro") {
        val path = listOf(2 to 1, 2 to 1, 3 to 1, 3 to 2, 0 to 0, 1 to 0)
        val times = List(path.size) { (it * 10).seconds }
        ScoreDecoder.events(path, times, "won", "lost", "reset") shouldBe listOf(
            SignalEvent(0.seconds, "won", 1.0),
            SignalEvent(0.seconds, "won", 1.0),
            SignalEvent(0.seconds, "lost", 1.0),
            SignalEvent(20.seconds, "won", 1.0),
            SignalEvent(30.seconds, "lost", 1.0),
            SignalEvent(40.seconds, "reset", 1.0),
            SignalEvent(50.seconds, "won", 1.0),
        )
    }

    test("lecture : vraies zones de score VALORANT 854x480 (un et deux chiffres, des deux côtés)") {
        val dir = Path.of("../config/templates/valorant/score")
        val crops = Path.of("src/test/resources/round-score")
        if (dir.exists() && crops.exists()) {
            val p = RoundScoreParams()
            val reader = ScoreReader.load(dir, 26, p.threshold, p.gap, p.slack, p.maxValue)
            val files = crops.listDirectoryEntries("*.png")
            files.size shouldBe 24
            for (f in files) {
                val expected = f.name.substringAfterLast('_').removeSuffix(".png").toInt()
                (f.name to reader.read(GrayImage.load(f))?.value) shouldBe (f.name to expected)
            }
        }
    }

    test("lecture : zone unie ou sans chiffre, rien de lu") {
        val dir = Path.of("../config/templates/valorant/score")
        if (dir.exists()) {
            val reader = ScoreReader.load(dir, 26, 0.6, 4, 2, 30)
            reader.read(GrayImage(26, 18, ByteArray(26 * 18) { 90 })) shouldBe null
        }
    }
})
