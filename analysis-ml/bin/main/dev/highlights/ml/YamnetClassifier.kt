package dev.highlights.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.highlights.core.ConfigException
import java.nio.FloatBuffer
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/** YAMNet au format ONNX : patchs log-mel [N, 96, 64] → 521 scores (sigmoïde) par patch. */
class YamnetClassifier(modelFile: Path, threads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        if (!modelFile.isRegularFile()) throw ConfigException("Modèle YAMNet introuvable : $modelFile")
        session = env.createSession(
            modelFile.toString(),
            OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) },
        )
        inputName = session.inputNames.first()
    }

    fun classify(patches: List<FloatArray>): List<FloatArray> {
        if (patches.isEmpty()) return emptyList()
        val size = LogMelStream.PATCH_FRAMES * LogMelStream.MEL_BANDS
        val buffer = FloatBuffer.allocate(patches.size * size)
        patches.forEach { require(it.size == size) { "patch de ${it.size} valeurs, $size attendues" }; buffer.put(it) }
        buffer.flip()
        OnnxTensor.createTensor(env, buffer, longArrayOf(patches.size.toLong(), LogMelStream.PATCH_FRAMES.toLong(), LogMelStream.MEL_BANDS.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return (result[0].value as Array<FloatArray>).toList()
            }
        }
    }

    override fun close() = session.close()

    companion object {
        /** yamnet_class_map.csv : index,mid,display_name (nom éventuellement entre guillemets). */
        fun loadClassNames(file: Path): List<String> {
            if (!file.isRegularFile()) throw ConfigException("Liste des classes YAMNet introuvable : $file")
            return file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
                val rest = line.substringAfter(',').substringAfter(',')
                rest.trim().removeSurrounding("\"")
            }
        }
    }
}
