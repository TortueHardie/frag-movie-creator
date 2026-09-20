package dev.highlights.core.ffmpeg

import dev.highlights.core.HighlightsException
import dev.highlights.core.model.MediaInfo
import java.io.InputStream
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** Arguments passés à ffmpeg (sans l'exécutable ni les options globales -hide_banner/-nostdin/-nostats). */
data class FfmpegCommand(
    val args: List<String>,
    /** Libellé court pour les logs et les messages d'erreur. */
    val description: String,
)

/** Traitement de stdout. stderr est toujours lu en continu et conservé dans un buffer circulaire. */
sealed interface StdoutHandler {
    data object Discard : StdoutHandler

    class Lines(val onLine: (String) -> Unit) : StdoutHandler

    class Binary(val consume: suspend (InputStream) -> Unit) : StdoutHandler
}

data class FfmpegResult(val exitCode: Int, val elapsed: Duration, val stderrTail: List<String>)

class FfmpegException(
    val description: String,
    val exitCode: Int,
    val commandLine: String,
    val stderrTail: List<String>,
    cause: Throwable? = null,
) : HighlightsException(
    buildString {
        append("FFmpeg a échoué ($description), code retour $exitCode")
        stderrTail.lastOrNull { it.isNotBlank() }?.let { append(" : ").append(it.trim()) }
    },
    cause,
)

interface FfmpegService {
    suspend fun probe(file: Path): MediaInfo

    /**
     * Lance ffmpeg et attend sa fin. Lève [FfmpegException] si le code retour est non nul. Annulable.
     * [onStderrLine] reçoit chaque ligne de stderr (ex. sortie du filtre showinfo), en plus du buffer circulaire.
     */
    suspend fun run(
        command: FfmpegCommand,
        stdout: StdoutHandler = StdoutHandler.Discard,
        onStderrLine: ((String) -> Unit)? = null,
    ): FfmpegResult

    /** Lance ffprobe avec des arguments libres (sans -hide_banner). */
    suspend fun runProbe(command: FfmpegCommand, stdout: StdoutHandler): FfmpegResult
}

data class EncoderProfile(
    val name: String,
    /** Arguments de sortie vidéo, codec inclus (ex. -c:v h264_amf …). */
    val videoArgs: List<String>,
    val hardware: Boolean,
)

interface EncoderSelector {
    /** Premier encodeur de la liste de préférence réellement utilisable sur cette machine. */
    suspend fun select(): EncoderProfile
}

/** Lecture de la sortie `-progress pipe:1`. */
object FfmpegProgressParser {
    fun parseOutTime(line: String): Duration? {
        if (!line.startsWith("out_time_us=")) return null
        return line.substringAfter('=').trim().toLongOrNull()?.takeIf { it >= 0 }?.microseconds
    }

    fun isEnd(line: String): Boolean = line.trim() == "progress=end"
}
