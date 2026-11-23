package dev.highlights.export

import dev.highlights.core.model.CaptionSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlin.io.path.Path

class WhisperCliTest : FunSpec({
    test("amorce : mots du vocabulaire séparés par des virgules, sans doublon, tronquée entre deux mots") {
        WhisperCli.prompt(listOf("Cypher", " Sova ", "", "Cypher")) shouldBe "Cypher, Sova."
        WhisperCli.prompt(emptyList()) shouldBe null
        val long = WhisperCli.prompt(List(400) { "mot$it" })!!
        long.length shouldBeLessThanOrEqual WhisperCli.MAX_PROMPT
        long shouldStartWith "mot0, mot1"
        long shouldEndWith "."
    }

    test("segments du JSON de whisper-cli rendus au format du filtre de FFmpeg") {
        val json = """
            {"systeminfo":"x","transcription":[
              {"timestamps":{"from":"00:00:00,000","to":"00:00:01,200"},"offsets":{"from":0,"to":1200},"text":" Y'a Cypher,"},
              {"timestamps":{"from":"00:00:01,200","to":"00:00:02,000"},"offsets":{"from":1200,"to":2000},"text":" là."}
            ]}
        """.trimIndent()
        WhisperCli.segments(json) shouldBe listOf(
            """{"start":0,"end":1200,"text":"Y'a Cypher,"}""",
            """{"start":1200,"end":2000,"text":"là."}""",
        )
    }

    test("arguments : faisceau, amorce, coupe entre deux mots, VAD seulement avec son modèle") {
        val settings = CaptionSettings(beamSize = 5, vocabulary = listOf("Cypher"), maxChars = 18, language = "fr")
        val args = WhisperCli.arguments(Path("whisper-cli.exe"), Path("a.wav"), Path("sortie"), settings, Path("m.bin"), Path("vad.bin"))
        args shouldContainInOrder listOf("-bs", "5")
        args shouldContainInOrder listOf("-ml", "18", "-sow")
        args shouldContainInOrder listOf("--prompt", "Cypher.")
        args shouldContainInOrder listOf("--vad", "-vm", Path("vad.bin").toString())
        args shouldContainInOrder listOf("-l", "fr")
        val plain = WhisperCli.arguments(Path("whisper-cli.exe"), Path("a.wav"), Path("sortie"), CaptionSettings(), Path("m.bin"), null)
        plain shouldNotContain "--vad"
        plain shouldNotContain "--prompt"
        plain shouldContain "-oj"
    }
})
