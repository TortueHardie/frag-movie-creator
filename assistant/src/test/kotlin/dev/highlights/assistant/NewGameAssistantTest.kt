package dev.highlights.assistant

import dev.highlights.core.InputException
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.ffmpeg.ProcessFfmpegService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

class NewGameAssistantTest : FunSpec({
    val w = 300
    val h = 120

    /** Image unie [bg], avec une ligne CS2 (contour rouge, côtés d'un pixel) si [line]. */
    fun frame(bg: Triple<Int, Int, Int>, line: Boolean, filled: Boolean = false): RgbFrame {
        val px = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            var c = bg
            val inLine = line && x in 150..289 && y in 33..48
            if (inLine && (filled || y - 33 < 2 || 48 - y < 2 || x == 150 || x == 289)) c = Triple(163, 92, 100)
            px[i] = c.first.toByte()
            px[i + 1] = c.second.toByte()
            px[i + 2] = c.third.toByte()
        }
        return RgbFrame(w, h, px)
    }

    test("l'identifiant du profil est tiré du nom du jeu") {
        NewGameAssistant.slug("Counter-Strike 2") shouldBe "counter-strike-2"
        NewGameAssistant.slug("Apex Légendes !") shouldBe "apex-legendes"
        NewGameAssistant.slug("   ") shouldBe "jeu"
    }

    test("la couleur du joueur est celle qui apparaît dans le cadre au kill, pas le sable du décor") {
        val sand = Triple(180, 135, 97)
        val pairs = listOf(frame(sand, false) to frame(sand, true))
        val color = LineColor.find(pairs) { PixelBox(140, 28, 160, 26) }.shouldNotBeNull()
        color.c1 shouldBe (71.0 plusOrMinus 4.0)
        color.c2 shouldBe (-8.0 plusOrMinus 4.0)
        color.matches(180, 135, 97) shouldBe false
        color.matches(96, 23, 21) shouldBe true
        // Rien de nouveau dans le cadre : pas de couleur.
        LineColor.find(listOf(frame(sand, false) to frame(sand, false))) { PixelBox(140, 28, 160, 26) }.shouldBeNull()
    }

    test("la hauteur de la ligne est mesurée sur ses traits, même dans un cadre tracé large") {
        val color = LineColor(71.0, -8.0, 0.1)
        val line = measureLine(frame(Triple(200, 200, 200), true), PixelBox(140, 20, 160, 40), color).shouldNotBeNull()
        line.top shouldBe 33
        line.height shouldBe 16
        line.right shouldBe 289
    }

    test("le profil écrit se relit et chaque détecteur candidat se construit") {
        val dir = Files.createTempDirectory("nouveau-jeu")
        val profiles = dir.resolve("profiles").createDirectories()
        Path("../config/profiles/default.yaml").copyTo(profiles.resolve("default.yaml"))
        val config = LoadedConfig(AppConfig(profilesDir = profiles.toString(), outputDir = dir.resolve("sortie").toString()), dir, null)
        val assistant = NewGameAssistant(config, ProcessFfmpegService(Path("ffmpeg"), Path("ffprobe")))
        val media = MediaInfo(Path("partie.mp4"), 1, 600.seconds, video = VideoStream(0, "h264", 854, 480, 30.0))
        val request = NewGameRequest(Path("partie.mp4"), "Jeu \"Test\"", listOf(KillMark(10.seconds, CropRegion(0.8, 0.05, 0.1, 0.04))))
        val registry = DetectorRegistry.fromServiceLoader()
        val yamlOf = mapOf(
            CueMethod.COUNTER to "  - id: c-counter\n    type: hud-change\n    params:\n      region: { x: 0.8, y: 0.02, width: 0.04, height: 0.03 }\n      referenceWidth: 854\n      referenceHeight: 480\n",
            CueMethod.TEMPLATE to "  - id: c-template\n    type: hud-template\n    params:\n      templates:\n        - name: repere\n          file: templates/x/repere.png\n          region: { x: 0.4, y: 0.6, width: 0.2, height: 0.1 }\n          threshold: @SEUIL@\n",
        )
        for ((method, yaml) in yamlOf) {
            val candidate = Candidate(method, yaml.lines()[0].substringAfter("id: "), yaml, minScore = 0.72)
            val result = CandidateResult(candidate, listOf(Hit(10.seconds, "kill")), 1, emptyList(), null)
            val text = assistant.profile(NewGameScan(request, media, listOf(result), emptyMap()), Choice(result, 1.0, 1.0))
            val file = dir.resolve("p.yaml")
            Files.writeString(file, text)
            val tmp = Files.createTempDirectory("p").also { Files.copy(file, it.resolve("jeu-test.yaml")) }
            val profile = ProfileRepository.loadDirectory(tmp).byId("jeu-test")
            profile.displayName shouldBe "Jeu Test"
            val events = profile.detectors.first { it.id == "game-events" }
            events.type shouldBe method.type
            registry.create(events.type, events.id, DetectorParams(events.params))
            if (method == CueMethod.TEMPLATE) text shouldContain "threshold: 0.72"
        }
    }

    test("un profil existant n'est jamais remplacé") {
        val dir = Files.createTempDirectory("nouveau-jeu")
        val profiles = dir.resolve("profiles").createDirectories()
        Files.writeString(profiles.resolve("jeu.yaml"), "id: jeu\n")
        val config = LoadedConfig(AppConfig(profilesDir = profiles.toString()), dir, null)
        val assistant = NewGameAssistant(config, ProcessFfmpegService(Path("ffmpeg"), Path("ffprobe")))
        val request = NewGameRequest(Path("p.mp4"), "Jeu", listOf(KillMark(1.seconds, CropRegion(0.1, 0.1, 0.1, 0.1))))
        val result = CandidateResult(Candidate(CueMethod.COUNTER, "c", "  - id: c\n"), listOf(Hit(1.seconds, "kill")), 1, emptyList(), null)
        val scan = NewGameScan(request, MediaInfo(Path("p.mp4"), 1, 10.seconds), listOf(result), emptyMap())
        shouldThrow<InputException> { assistant.write(scan, Choice(result, 1.0, 1.0)) }
        profiles.resolve("jeu.yaml").readText() shouldBe "id: jeu\n"
    }

    test("la méthode retenue garde le plus de vrais kills moins de faux, et doit retrouver un kill marqué") {
        val config = LoadedConfig(AppConfig(), Path("."), null)
        val assistant = NewGameAssistant(config, ProcessFfmpegService(Path("ffmpeg"), Path("ffprobe")))
        fun result(method: CueMethod, kills: Int, marks: Int) =
            CandidateResult(Candidate(method, "c", ""), List(kills) { Hit((it * 10).seconds, "kill") }, marks, emptyList(), null)
        val request = NewGameRequest(Path("p.mp4"), "Jeu", listOf(KillMark(1.seconds, CropRegion(0.1, 0.1, 0.1, 0.1))))
        val scan = NewGameScan(
            request, MediaInfo(Path("p.mp4"), 1, 10.seconds),
            listOf(result(CueMethod.COUNTER, 124, 3), result(CueMethod.COLOR_LINE, 40, 3), result(CueMethod.TEMPLATE, 1, 0)),
            emptyMap(),
        )
        val verdicts = mapOf(
            CueMethod.COUNTER to List(12) { it == 0 },
            CueMethod.COLOR_LINE to List(12) { it < 10 },
            CueMethod.TEMPLATE to listOf(true),
        )
        assistant.choose(scan, verdicts).shouldNotBeNull().result.candidate.method shouldBe CueMethod.COLOR_LINE
    }
})
