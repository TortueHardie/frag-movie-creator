package dev.highlights.export

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Transcription par le programme `whisper-cli` de whisper.cpp, là où le filtre `whisper` de FFmpeg ne règle rien :
 * amorce avec le vocabulaire du jeu ([CaptionSettings.vocabulary]), recherche en faisceau ([CaptionSettings.beamSize]),
 * détection de la voix ([CaptionSettings.vadModel]) et coupe des sous-titres entre deux mots (`-sow`, le filtre coupe au
 * milieu : « il y / a un trap »). Mesuré sur une capture VALORANT de 37 s (micro + Discord) : voir
 * `output/revue/sous-titres-precis/LOG.md`. Le programme livré par whisper.cpp pour Windows tourne sur le processeur :
 * plus lent que le filtre (GPU), d'où le cache des transcriptions.
 *
 * Rend les segments dans le format du filtre (`{"start":ms,"end":ms,"text":…}` par ligne), lu par `Captions.parse`.
 */
class WhisperCli(private val ffmpeg: FfmpegService) {
    suspend fun transcribe(
        cli: Path,
        media: MediaInfo,
        range: TimeRange,
        audio: Int,
        settings: CaptionSettings,
        model: Path,
        vad: Path?,
        workDir: Path,
        key: String,
    ): List<String> {
        val wav = workDir.resolve("whisper_$key.wav")
        val base = workDir.resolve("whisper_$key")
        val json = workDir.resolve("whisper_$key.json")
        val log = workDir.resolve("whisper_$key.log")
        try {
            ffmpeg.run(
                FfmpegCommand(
                    listOf(
                        "-v", "error", "-y",
                        "-ss", Durations.ffmpegSecondsPrecise(range.start), "-t", Durations.ffmpegSeconds(range.length),
                        "-i", media.path.toString(), "-map", "0:a:$audio", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                        wav.toString(),
                    ),
                    "audio à transcrire ${media.path.fileName} $range",
                ),
            )
            json.deleteIfExists()
            val process = ProcessBuilder(arguments(cli, wav, base, settings, model, vad))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
            val code = try {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            if (code != 0 || !json.exists()) {
                val tail = runCatching { log.readText().lines().filter { it.isNotBlank() }.takeLast(5).joinToString(" | ") }.getOrDefault("")
                throw IOException("whisper-cli a échoué (code $code) : $tail")
            }
            return segments(json.readText())
        } finally {
            wav.deleteIfExists()
            json.deleteIfExists()
            log.deleteIfExists()
        }
    }

    companion object {
        /** Longueur maximale de l'amorce : whisper n'en garde que 224 jetons (~ 600 caractères de mots courts). */
        const val MAX_PROMPT = 600

        private val json = Json { ignoreUnknownKeys = true }

        fun arguments(cli: Path, wav: Path, base: Path, settings: CaptionSettings, model: Path, vad: Path?): List<String> = buildList {
            add(cli.toString())
            addAll(listOf("-m", model.toString(), "-l", settings.language, "-f", wav.toString()))
            addAll(listOf("-t", threads().toString()))
            addAll(listOf("-bs", settings.beamSize.toString(), "-bo", settings.beamSize.toString()))
            addAll(listOf("-ml", settings.maxChars.toString(), "-sow"))
            addAll(listOf("-oj", "-of", base.toString(), "-np"))
            if (!settings.useGpu) add("-ng")
            prompt(settings.vocabulary)?.let { addAll(listOf("--prompt", it)) }
            if (vad != null) addAll(listOf("--vad", "-vm", vad.toString()))
        }

        /** Le processeur entier, sans dépasser 16 fils : au-delà, whisper.cpp ne va plus plus vite. */
        private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

        /**
         * Amorce : les mots attendus, séparés par des virgules, comme une phrase déjà entendue. Whisper la prend pour le
         * début de la conversation et écrit « Cypher » quand il entend /saɪfɚ/. Tronquée à [MAX_PROMPT], entre deux mots.
         */
        fun prompt(vocabulary: List<String>): String? {
            val words = vocabulary.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (words.isEmpty()) return null
            val text = StringBuilder()
            for (w in words) {
                val next = if (text.isEmpty()) w else ", $w"
                if (text.length + next.length + 1 > MAX_PROMPT) break
                text.append(next)
            }
            return "$text."
        }

        /** Segments du JSON de `whisper-cli -oj` (`transcription[].offsets.from/to`, en ms) au format du filtre de FFmpeg. */
        fun segments(text: String): List<String> =
            json.parseToJsonElement(text).jsonObject["transcription"]?.jsonArray.orEmpty().mapNotNull { seg ->
                val obj = seg.jsonObject
                val offsets = obj["offsets"]?.jsonObject ?: return@mapNotNull null
                val from = offsets["from"]?.jsonPrimitive?.long ?: return@mapNotNull null
                val to = offsets["to"]?.jsonPrimitive?.long ?: return@mapNotNull null
                val words = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                buildJsonObject { put("start", from); put("end", to); put("text", words) }.toString()
            }
    }
}
