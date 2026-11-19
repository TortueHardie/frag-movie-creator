package dev.highlights.cli

import dev.highlights.core.ConfigException
import dev.highlights.core.model.TimelineEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GroundTruthTest : FunSpec({
    fun ev(at: Double, kind: String = "kill", detector: String = "game-events") = TimelineEvent((at * 1000).toLong().milliseconds, kind, 1.0, detector)

    test("lecture du YAML : capture, temps en minutes:secondes, seuils") {
        val file = tempdir().toPath().resolve("partie.yaml")
        file.writeText(
            """
            capture: { name: WARDOGS_x.mp4, sizeBytes: 123, duration: 45m08.992s }
            profile: wardogs
            tolerance: 2s
            validated: utilisateur, 2026-11-18
            kinds: [kill, death]
            events:
              - { at: "07:56", kind: kill }
              - { at: "1:00:02.5", kind: death, note: grenade }
            thresholds:
              game-events: { kill: { precision: 0.9, recall: 0.5 } }
            """.trimIndent(),
        )
        val truth = GroundTruth.load(file)
        truth.capture.duration shouldBe 45.minutes + 8992.milliseconds
        truth.events.map { it.at } shouldContainExactly listOf(476.seconds, 3602500.milliseconds)
        truth.thresholds.getValue("game-events").getValue("kill") shouldBe Threshold(0.9, 0.5)
    }

    test("YAML strict : une sorte d'événement hors de kinds est refusée") {
        val file = tempdir().toPath().resolve("partie.yaml")
        file.writeText(
            """
            capture: { name: a.mp4, sizeBytes: 1, duration: 10s }
            profile: wardogs
            kinds: [kill]
            events: [{ at: 5s, kind: assist }]
            """.trimIndent(),
        )
        shouldThrow<ConfigException> { GroundTruth.load(file) }
    }

    test("paires dans la tolérance, la plus proche d'abord ; le reste en faux et manqués") {
        val s = TruthScoring.match("d", "kill", listOf(10.seconds, 11.seconds, 100.seconds), listOf(10.8.seconds, 50.seconds), 2.seconds)
        // 10,8 s est plus proche de 11 que de 10 : 11 est retrouvé, 10 manqué (une détection ne compte qu'une fois).
        s.hits shouldContainExactly listOf(11.seconds to 10.8.seconds)
        s.missed shouldContainExactly listOf(10.seconds, 100.seconds)
        s.falseAlarms shouldContainExactly listOf(50.seconds)
        s.precision shouldBe 0.5
        s.recall shouldBe 1.0 / 3
    }

    test("avance permise (Apex) : la détection peut précéder le relevé, jamais le suivre au-delà de la tolérance") {
        // Mise à terre à 100 s, crédit du compteur à l'élimination, 20 s plus tard.
        val s = TruthScoring.match("d", "kill", listOf(120.seconds, 200.seconds), listOf(100.seconds, 210.seconds), 3.seconds, lead = 30.seconds)
        s.hits shouldContainExactly listOf(120.seconds to 100.seconds)
        // 210 s suit le relevé de 200 s de 10 s : au-delà de la tolérance, pas retrouvé.
        s.falseAlarms shouldContainExactly listOf(210.seconds)
        TruthScoring.match("d", "kill", listOf(120.seconds), listOf(100.seconds), 3.seconds).hits.shouldBeEmpty()
    }

    test("rien à trouver et rien trouvé : ni précision ni rappel, et pas d'échec") {
        val s = TruthScoring.match("d", "death", emptyList(), emptyList(), 2.seconds)
        s.precision shouldBe null
        s.recall shouldBe null
        s.failures(Threshold(1.0, 1.0)).shouldBeEmpty()
    }

    test("score : par détecteur et par sorte relevée ; les autres sortes ignorées ; détecteur des seuils mesuré même muet") {
        val truth = GroundTruth(
            capture = TruthCapture("a.mp4", 1, 10.minutes),
            profile = "wardogs",
            kinds = listOf("kill", "death"),
            events = listOf(TruthEvent(60.seconds, "kill"), TruthEvent(120.seconds, "kill"), TruthEvent(200.seconds, "death")),
            thresholds = mapOf("killfeed" to mapOf("kill" to Threshold(recall = 0.5))),
        )
        val scores = TruthScoring.score(truth, listOf(ev(61.0), ev(300.0), ev(130.0, "revive"), ev(5.0, "laughter", "reactions")))
        scores.map { "${it.detector}/${it.kind}" } shouldContainExactly listOf("game-events/kill", "game-events/death", "killfeed/kill", "killfeed/death")
        val kills = scores.first()
        kills.hits.size shouldBe 1
        kills.falseAlarms shouldContainExactly listOf(300.seconds)
        kills.missed shouldContainExactly listOf(120.seconds)
        scores[1].missed shouldContainExactly listOf(200.seconds)
        scores[2].recall shouldBe 0.0
        scores[2].failures(truth.thresholds["killfeed"]?.get("kill")) shouldContainExactly listOf("rappel 0,00 < 0,50")
    }

    test("les fichiers de vérité du dépôt se lisent, triés, dans la durée de leur capture") {
        val dir = Path.of("..").toAbsolutePath().normalize().resolve("verite")
        val files = if (Files.isDirectory(dir)) Files.walk(dir).use { w -> w.filter { it.extension == "yaml" }.toList() } else emptyList()
        files.forEach { file ->
            val truth = GroundTruth.load(file)
            // Non validée (validated: null) : relevé provisoire tiré des détections, signalé par `app verite`.
            (truth.validated != null || truth.note != null) shouldBe true
            truth.events.zipWithNext().forEach { (a, b) -> (a.at <= b.at) shouldBe true }
            truth.events.forEach { (it.at <= truth.capture.duration) shouldBe true }
        }
    }
})
