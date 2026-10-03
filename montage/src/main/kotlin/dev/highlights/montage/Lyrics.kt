package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.LyricsEffect
import dev.highlights.core.model.LyricsSpan
import dev.highlights.core.model.TimeRange
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
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/** Paroles d'une musique : transcription, nettoyage, puis les mots que le montage affiche. Voir [LyricsEffect]. */
object Lyrics {
    /** Mots que whisper écrit seuls sur un instrumental ou un bruit, sans qu'ils soient chantés. */
    private val NOISE = setOf("music", "musique", "♪", "applause", "laughter")

    /**
     * Mots transcrits gardés : du texte lisible (lettres ou chiffres), ni bruit annoncé ([NOISE]), ni mot étiré au-delà
     * de [LyricsEffect.maxWord] (whisper colle le premier mot sur l'instrumental qui précède le chant). La ponctuation
     * de fin tombe : un mot à la fois, la virgule n'a plus de sens.
     */
    fun clean(words: List<Caption>, settings: LyricsEffect): List<Caption> = words.mapNotNull { w ->
        val text = w.text.trim().trim(',', '.', '!', '?', ';', ':', '"', '«', '»', '…', '-').trim()
        val bare = text.lowercase().filter { it.isLetterOrDigit() || it == '♪' }
        when {
            text.none { it.isLetterOrDigit() } -> null
            bare in NOISE -> null
            w.range.length > settings.maxWord -> null
            else -> Caption(w.range, text)
        }
    }.sortedBy { it.range.start }

    /**
     * Mots affichés dans [plan], en temps du montage : ceux chantés pendant le passage de la musique retenu, jusqu'à la
     * drop ([LyricsSpan.OPENING]) ou la fin. Chaque mot reste au moins [LyricsEffect.minShow] (un mot de 0,05 s ne se
     * lit pas) sans chevaucher le suivant, et s'efface au plus tard à la fin du passage.
     */
    fun shown(words: List<Caption>, plan: MontagePlan, settings: LyricsEffect): List<Caption> {
        val end = when (settings.span) {
            LyricsSpan.OPENING -> plan.dropAt ?: plan.duration
            LyricsSpan.ALL -> plan.duration
        }
        val inside = words.map { w -> Caption(TimeRange(w.range.start - plan.musicStart, w.range.end - plan.musicStart), w.text) }
            .filter { it.range.start >= Duration.ZERO && it.range.start < end }
        return inside.mapIndexed { i, w ->
            val next = inside.getOrNull(i + 1)?.range?.start ?: end
            val until = maxOf(w.range.end, w.range.start + settings.minShow).coerceAtMost(maxOf(next, w.range.start)).coerceAtMost(end)
            w.copy(range = TimeRange(w.range.start, until))
        }.filter { it.range.length.isPositive() }
    }
}

/**
 * Transcrit une musique entière, un mot par segment, avec le filtre `whisper` de FFmpeg. Le texte brut est gardé dans
 * [cacheDir] par musique (chemin, taille, date), modèle et langue : un deuxième montage sur la même musique ne la
 * retranscrit pas. Un échec n'empêche jamais le montage : il sort sans paroles.
 */
class LyricsTranscriber(private val ffmpeg: FfmpegService, private val cacheDir: Path?) {
    suspend fun words(music: Path, model: Path, settings: LyricsEffect, workDir: Path): List<Caption> {
        val raw = try {
            transcript(music, model, settings, workDir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Paroles de ${music.name} impossibles à transcrire, montage sans paroles : ${e.message}" }
            return emptyList()
        }
        return Lyrics.clean(Captions.parse(raw, Duration.ZERO), settings)
    }

    private suspend fun transcript(music: Path, model: Path, settings: LyricsEffect, workDir: Path): List<String> {
        val key = key(music, model, settings)
        val cached = cacheDir?.resolve("$key.jsonl")
        if (cached != null && cached.exists()) return cached.readLines()
        workDir.createDirectories()
        val out = workDir.resolve("paroles_$key.jsonl")
        out.deleteIfExists()
        // Un mot par segment (max_len=1) ; tranches de 20 s, comme les sous-titres : à 3 s, whisper coupe les phrases.
        val filter = Captions.transcriptionFilter(model, out, CaptionSettings(language = settings.language), maxLen = 1)
        ffmpeg.run(FfmpegCommand(listOf("-v", "error", "-i", music.toString(), "-vn", "-af", filter, "-f", "null", "-"), "paroles ${music.name}"))
        // Rien de chanté : whisper n'écrit pas de fichier.
        if (!out.exists()) out.writeText("")
        val lines = out.readLines()
        if (cached != null) {
            cached.parent.createDirectories()
            out.moveTo(cached, overwrite = true)
        }
        log.info { "Paroles de ${music.name} : ${lines.size} mot(s) transcrit(s)" }
        return lines
    }

    private fun key(music: Path, model: Path, settings: LyricsEffect): String {
        val text = listOf(
            music.toAbsolutePath().normalize(), runCatching { music.fileSize() }.getOrDefault(0L),
            runCatching { music.getLastModifiedTime().toMillis() }.getOrDefault(0L),
            model.name, runCatching { model.fileSize() }.getOrDefault(0L), settings.language,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
