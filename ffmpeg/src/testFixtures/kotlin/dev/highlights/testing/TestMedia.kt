package dev.highlights.testing

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.ffmpeg.FfmpegLocator
import dev.highlights.ffmpeg.ProcessFfmpegService
import java.nio.file.Path

/** Génération de vidéos synthétiques pour les tests d'intégration (aucune vraie capture dans le dépôt). */
object TestMedia {
    val ffmpeg: ProcessFfmpegService? by lazy {
        runCatching {
            ProcessFfmpegService(FfmpegLocator.locate("ffmpeg", null), FfmpegLocator.locate("ffprobe", null))
        }.getOrNull()
    }

    val available: Boolean get() = ffmpeg != null

    fun requireFfmpeg(): ProcessFfmpegService = ffmpeg ?: error("ffmpeg introuvable : test d'intégration impossible")

    /**
     * Vidéo 640x360 à 30 fps. Chaque piste audio est un bruit de fond faible avec des salves fortes aux intervalles donnés (secondes).
     */
    suspend fun generate(
        output: Path,
        durationSeconds: Int = 60,
        audioTracks: List<AudioTrackSpec> = listOf(AudioTrackSpec("Game", bursts = listOf(15.0..17.0, 42.0..44.0))),
    ): Path {
        val args = mutableListOf("-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30:d=$durationSeconds")
        audioTracks.forEach { track ->
            val loud = track.bursts.joinToString("+") { "between(t\\,${it.start}\\,${it.endInclusive})" }.ifEmpty { "0" }
            val expr = "0.8*sin(2*PI*${track.frequency}*t)*($loud)+0.01*sin(2*PI*120*t)"
            args += listOf("-f", "lavfi", "-i", "aevalsrc=$expr:s=48000:d=$durationSeconds")
        }
        args += listOf("-map", "0:v")
        audioTracks.indices.forEach { i -> args += listOf("-map", "${i + 1}:a") }
        audioTracks.forEachIndexed { i, t -> args += listOf("-metadata:s:a:$i", "title=${t.title}", "-metadata:s:a:$i", "handler_name=${t.title}") }
        args += listOf("-c:v", "libx264", "-preset", "ultrafast", "-g", "60", "-c:a", "aac", "-b:a", "96k", output.toString())
        requireFfmpeg().run(FfmpegCommand(args, "génération vidéo de test"))
        return output
    }

    data class AudioTrackSpec(val title: String, val bursts: List<ClosedFloatingPointRange<Double>>, val frequency: Int = 440)
}
