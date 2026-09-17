package dev.highlights.ml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.Path
import kotlin.math.abs

/**
 * Compare le spectrogramme Kotlin et les scores ONNX aux valeurs produites par TensorFlow (script de conversion)
 * sur le même signal : garantit que le modèle reçoit exactement les entrées pour lesquelles il a été entraîné.
 */
class YamnetReferenceTest : FunSpec({
    fun floats(name: String): FloatArray {
        val bytes = YamnetReferenceTest::class.java.getResourceAsStream("/yamnet/$name")!!.readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    val wave = floats("ref_wave.f32")
    val refLogMel = floats("ref_logmel.f32")
    val refScores = floats("ref_scores.f32")
    val model = Path("../config/models/yamnet.onnx")

    test("log-mel identique à TensorFlow, en flux par morceaux irréguliers") {
        val patches = mutableListOf<FloatArray>()
        val stream = LogMelStream { _, patch, _ -> patches += patch }
        var offset = 0
        var chunk = 777
        while (offset < wave.size) {
            val n = minOf(chunk, wave.size - offset)
            stream.push(wave.copyOfRange(offset, offset + n))
            offset += n
            chunk = if (chunk == 777) 4093 else 777
        }
        val frames = refLogMel.size / 64
        patches.size shouldBe (frames - 96) / 48 + 1
        var maxDiff = 0.0
        patches.forEachIndexed { p, patch ->
            for (f in 0 until 96) for (b in 0 until 64) {
                maxDiff = maxOf(maxDiff, abs(patch[f * 64 + b] - refLogMel[(p * 48 + f) * 64 + b]).toDouble())
            }
        }
        maxDiff shouldBeLessThan 1e-3
    }

    test("scores ONNX identiques à TensorFlow").config(enabledIf = { model.toFile().isFile }) {
        val patches = mutableListOf<FloatArray>()
        LogMelStream { _, patch, _ -> patches += patch }.push(wave)
        val scores = YamnetClassifier(model).use { it.classify(patches) }
        scores.size shouldBe refScores.size / 521
        var maxDiff = 0.0
        scores.forEachIndexed { p, s -> for (c in 0 until 521) maxDiff = maxOf(maxDiff, abs(s[c] - refScores[p * 521 + c]).toDouble()) }
        maxDiff shouldBeLessThan 1e-3
    }

    test("noms des classes AudioSet") {
        val names = YamnetClassifier.loadClassNames(Path("../config/models/yamnet_class_map.csv"))
        names.size shouldBe 521
        names[13] shouldBe "Laughter"
        names.indexOf("Chuckle, chortle") shouldBe 18
    }
})
