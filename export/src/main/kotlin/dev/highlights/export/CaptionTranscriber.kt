package dev.highlights.export

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import dev.highlights.editing.story.Caption
import dev.highlights.editing.story.Captions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Transcrit la voix des moments montés avec le filtre `whisper` de FFmpeg (whisper.cpp, en local). Seuls les extraits
 * montés sont lus, pas toute la capture ; la transcription brute est gardée en cache, si bien qu'un nouvel export des
 * mêmes moments ne la refait pas. Une transcription en échec n'empêche jamais le montage : il sort sans sous-titres.
 */
class CaptionTranscriber(private val ffmpeg: FfmpegService) {
    /** Types de segments qui signalent une voix : ce qui n'en recouvre aucun est une hallucination. */
    private val voiceKinds = setOf("speech", "laughter", "shout")

    /** Audio lu en plus de chaque côté du moment : une phrase commencée juste avant n'est pas coupée, et whisper a du contexte. */
    private val context = 2.seconds

    suspend fun transcribe(
        media: MediaInfo,
        range: TimeRange,
        timeline: ScoredTimeline?,
        settings: CaptionSettings,
        model: Path,
        layout: AudioLayout,
        workDir: Path,
        cacheDir: Path?,
    ): List<Caption> = transcribe(
        media, range,
        timeline?.segments.orEmpty().filter { it.kind in voiceKinds }.map { it.range },
        timeline?.segments.orEmpty().filter { it.kind == "shout" }.map { it.range },
        settings, model, layout, workDir, cacheDir,
    )

    /**
     * Même transcription, à partir des prises de parole ([voice]) et des cris ([shouts]) déjà connus : le montage des
     * réactions y ajoute les éclats de voix trouvés sur le micro, que l'analyse ne connaît pas.
     */
    suspend fun transcribe(
        media: MediaInfo,
        range: TimeRange,
        voice: List<TimeRange>,
        shouts: List<TimeRange>,
        settings: CaptionSettings,
        model: Path,
        layout: AudioLayout,
        workDir: Path,
        cacheDir: Path?,
    ): List<Caption> {
        val audio = trackFor(media, settings, layout, voice, range) ?: return emptyList()
        val segments = voice
        // L'analyse a cherché la voix et n'en a pas trouvé ici : rien à transcrire (et rien à inventer pour whisper).
        if (segments.isNotEmpty() && segments.none { it.isWithin(range) }) return emptyList()
        // Sur le son complet passent aussi les répliques du jeu ; sur le micro, le joueur a le droit de dire « spike ».
        val ignore = if (withoutMic(media, settings, layout)) settings.ignore else emptyList()

        val padded = TimeRange((range.start - context).coerceAtLeast(Duration.ZERO), (range.end + context).coerceAtMost(media.duration))
        val raw = try {
            rawTranscript(media, padded, audio, settings, model, workDir, cacheDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Transcription impossible de ${media.path.name} $range, montage sans sous-titres : ${e.message}" }
            return emptyList()
        }
        // Une phrase criée (elle recouvre un cri détecté) s'affiche plus grosse, en couleur.
        return Captions.readable(Captions.clean(Captions.parse(raw, padded.start), segments, ignore), segments).map { c ->
            if (shouts.any { it.isWithin(c.range) }) c.copy(loud = true) else c
        }
    }

    /** Vrai si la capture n'a pas de piste du rôle transcrit ([CaptionSettings.role]) : seul le son complet est là. */
    fun withoutMic(media: MediaInfo, settings: CaptionSettings, layout: AudioLayout): Boolean =
        AudioTracks.of(media.audio, layout).indexOf(settings.role) == null

    /**
     * Piste à transcrire pour l'extrait [range], ou null pour ne rien sous-titrer. La piste du joueur si elle existe.
     * Sinon le son complet, selon [CaptionSettings.withoutMic] : jamais (false), toujours (true), ou, par défaut,
     * seulement si l'analyse a trouvé la voix du joueur dans l'extrait ([voice]) : sur une capture à piste unique sans
     * détecteur qui isole cette voix, whisper ne transcrirait que l'annonceur et les personnages du jeu.
     */
    private fun trackFor(media: MediaInfo, settings: CaptionSettings, layout: AudioLayout, voice: List<TimeRange>, range: TimeRange): Int? {
        val tracks = AudioTracks.of(media.audio, layout)
        tracks.indexOf(settings.role)?.let { return it }
        val transcribe = when (settings.withoutMic) {
            true -> true
            false -> false
            null -> voice.any { it.isWithin(range) }
        }
        if (!transcribe) {
            log.info { "Pas de piste ${settings.role} séparée dans ${media.path.name} ni de voix du joueur détectée en $range : pas de sous-titres (story.captions.withoutMic: true pour transcrire le son complet)" }
            return null
        }
        return tracks.indexOf(AudioRole.MIX) ?: tracks.mixIndices().firstOrNull()
    }

    private suspend fun rawTranscript(
        media: MediaInfo,
        range: TimeRange,
        audio: Int,
        settings: CaptionSettings,
        model: Path,
        workDir: Path,
        cacheDir: Path?,
    ): List<String> {
        val key = cacheKey(media, range, audio, settings, model)
        val cached = cacheDir?.resolve("$key.jsonl")
        if (cached != null && cached.exists()) return cached.readLines()

        workDir.createDirectories()
        val out = workDir.resolve("whisper_$key.jsonl")
        out.deleteIfExists()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-v", "error",
                    "-ss", Durations.ffmpegSecondsPrecise(range.start),
                    "-t", Durations.ffmpegSeconds(range.length),
                    "-i", media.path.toString(),
                    "-map", "0:a:$audio",
                    "-af", Captions.transcriptionFilter(model, out, settings),
                    "-f", "null", "-",
                ),
                "transcription ${media.path.name} $range",
            ),
        )
        // Rien de dit : whisper n'écrit pas de fichier.
        if (!out.exists()) out.writeText("")
        val lines = out.readLines()
        if (cached != null) {
            cached.parent.createDirectories()
            out.moveTo(cached, overwrite = true)
        }
        return lines
    }

    /** Même capture, même extrait, même piste, mêmes réglages de transcription : même texte. */
    private fun cacheKey(media: MediaInfo, range: TimeRange, audio: Int, settings: CaptionSettings, model: Path): String {
        val modelSize = runCatching { model.fileSize() }.getOrDefault(0L)
        val text = listOf(
            media.path.toAbsolutePath(), media.sizeBytes, range.start.inWholeMilliseconds, range.end.inWholeMilliseconds,
            audio, model.name, modelSize, settings.language, settings.maxChars, settings.queue.inWholeMilliseconds,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
