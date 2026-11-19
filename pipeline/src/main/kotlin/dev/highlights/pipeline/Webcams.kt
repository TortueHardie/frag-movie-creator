package dev.highlights.pipeline

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.WebcamChoice
import dev.highlights.core.model.WebcamFeed
import dev.highlights.core.model.WebcamSettings
import dev.highlights.core.model.WebcamSync
import dev.highlights.core.serialization.Durations
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Webcams trouvées pour un rendu : réglages à utiliser (webcam allumée si trouvée), vidéos par capture, avertissements. */
data class WebcamSetup(val edit: EditSettings, val feeds: Map<Path, WebcamFeed>, val notices: List<Message> = emptyList())

/**
 * Recherche et calage de la webcam de chaque capture.
 *
 * Recherche (« auto ») : second flux vidéo de la capture elle-même (OBS peut enregistrer jeu et webcam dans un même
 * fichier), sinon une vidéo du même dossier dont le nom parle de webcam (« webcam », « facecam », « cam »…) et dont
 * l'enregistrement recouvre le plus la partie ; à défaut d'heures fiables, celle qui porte le nom de la capture.
 *
 * Calage : l'heure de début des deux enregistrements ([MediaInfo.recordedAt]), plus le décalage réglé, puis affiné par
 * le son ([audioLag]) : les attaques du son de la webcam (son micro) sont comparées à celles du micro de la capture
 * (ou de son mélange) sur une minute. Le décalage n'est retenu que si la ressemblance est nette.
 *
 * Non vérifié sur une vraie webcam au 19/11/2026 : construit et testé sur des vidéos synthétiques (claps générés).
 */
class Webcams(private val ffmpeg: FfmpegService) {

    /**
     * Webcams des [medias] selon le [choice] de l'utilisateur ([offset] : décalage réglé à la main, remplace celui du
     * profil). null : pas de webcam demandée, [edit] inchangé.
     */
    suspend fun setup(medias: List<MediaInfo>, choice: WebcamChoice?, offset: Duration?, edit: EditSettings, layout: AudioLayout = AudioLayout()): WebcamSetup {
        if (choice == null) return WebcamSetup(edit, emptyMap())
        val settings = edit.webcam.copy(offset = offset ?: edit.webcam.offset)
        val feeds = medias.distinctBy { it.path }.mapNotNull { media ->
            runCatching { find(media, choice, settings, layout) }
                .onFailure { log.warn(it) { "Webcam de ${media.path.fileName} illisible" } }
                .getOrNull()?.let { media.path to it }
        }.toMap()
        if (feeds.isEmpty()) {
            log.warn { "Aucune webcam trouvée (${describe(choice)}) : montage sans incrustation" }
            return WebcamSetup(edit, emptyMap(), listOf(Message("notice.webcam.notFound")))
        }
        feeds.forEach { (capture, feed) ->
            log.info { "Webcam de ${capture.fileName} : ${feed.file.fileName} (flux ${feed.stream}), décalage ${feed.offset} (${feed.sync})" }
        }
        val missing = medias.count { it.path !in feeds }
        val notices = if (missing > 0) listOf(Message("notice.webcam.partial", "count" to missing)) else emptyList()
        return WebcamSetup(edit.copy(webcam = settings.copy(enabled = true)), feeds, notices)
    }

    /** Webcam d'une capture, calée ; null si rien ne convient. */
    suspend fun find(media: MediaInfo, choice: WebcamChoice, settings: WebcamSettings, layout: AudioLayout = AudioLayout()): WebcamFeed? {
        val feed = when (choice) {
            is WebcamChoice.Resolved -> return choice.feed
            is WebcamChoice.File -> fromFile(media, choice.path, settings)
            WebcamChoice.Auto -> secondStream(media, settings) ?: candidates(media).let { pick(media, it, settings) }
        } ?: return null
        if (feed.sync == WebcamSync.SAME_FILE) return feed
        // Le décalage réglé à la main s'ajoute après le calage au son : sinon le son le corrigeait et il ne servait à rien.
        val synced = if (settings.audioSync) refine(media, feed, layout) else feed
        return synced.copy(offset = synced.offset + settings.offset)
    }

    /** Second flux vidéo de la capture (hors pochettes) : même horloge, seul le décalage réglé s'applique. */
    internal suspend fun secondStream(media: MediaInfo, settings: WebcamSettings): WebcamFeed? {
        val streams = videoStreams(media.path)
        val cam = streams.getOrNull(1) ?: return null
        return WebcamFeed(media.path, cam.rank, settings.offset, media.duration, cam.width, cam.height, WebcamSync.SAME_FILE)
    }

    private suspend fun fromFile(media: MediaInfo, file: Path, settings: WebcamSettings): WebcamFeed? {
        if (!file.isRegularFile()) return null
        if (file.toAbsolutePath().normalize() == media.path.toAbsolutePath().normalize()) return secondStream(media, settings)
        val cam = ffmpeg.probe(file)
        val video = cam.video ?: return null
        val (offset, sync) = timeOffset(media, cam)
        return WebcamFeed(cam.path, 0, offset, cam.duration, video.width, video.height, sync)
    }

    /** Vidéos rangées avec la capture dont le nom parle de webcam, de la plus proche par le nom à la plus lointaine. */
    internal fun candidates(media: MediaInfo): List<Path> {
        val dir = media.path.parent ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        val own = media.path.toAbsolutePath().normalize()
        val stem = media.path.nameWithoutExtension.lowercase()
        return Files.list(dir).use { files ->
            files.filter { it.isRegularFile() && it.extension.lowercase() in VIDEO_EXTENSIONS && it.toAbsolutePath().normalize() != own }
                .filter { isWebcamName(it.nameWithoutExtension) }
                .toList()
        }.sortedWith(compareByDescending<Path> { it.nameWithoutExtension.lowercase().startsWith(stem) }.thenBy { it.fileName.toString() })
    }

    /** Parmi les [files], celui dont l'enregistrement recouvre le plus la capture ; le premier si les heures manquent. */
    private suspend fun pick(media: MediaInfo, files: List<Path>, settings: WebcamSettings): WebcamFeed? {
        val probed = files.mapNotNull { f -> runCatching { ffmpeg.probe(f) }.getOrNull()?.takeIf { it.video != null } }
        if (probed.isEmpty()) return null
        val start = media.recordedAt
        val best = if (start != null && media.reliableTime && probed.any { it.reliableTime }) {
            probed.filter { it.reliableTime }.maxByOrNull { overlap(media, it) }?.takeIf { overlap(media, it) > Duration.ZERO }
        } else {
            null
        } ?: probed.firstOrNull { it.path.nameWithoutExtension.lowercase().startsWith(media.path.nameWithoutExtension.lowercase()) }
            ?: probed.singleOrNull()
            ?: return null
        val video = best.video ?: return null
        val (offset, sync) = timeOffset(media, best)
        return WebcamFeed(best.path, 0, offset, best.duration, video.width, video.height, sync)
    }

    /** Recouvrement des deux enregistrements dans le temps (heures de début et durées). */
    private fun overlap(capture: MediaInfo, cam: MediaInfo): Duration {
        val a = capture.recordedAt ?: return Duration.ZERO
        val b = cam.recordedAt ?: return Duration.ZERO
        val aEnd = a.toEpochMilli() + capture.duration.inWholeMilliseconds
        val bEnd = b.toEpochMilli() + cam.duration.inWholeMilliseconds
        return (minOf(aEnd, bEnd) - maxOf(a.toEpochMilli(), b.toEpochMilli())).milliseconds
    }

    /** Instant de la webcam pour l'instant 0 de la capture, d'après les heures de début ; 0 si l'une n'est pas fiable. */
    private fun timeOffset(capture: MediaInfo, cam: MediaInfo): Pair<Duration, WebcamSync> {
        val a = capture.recordedAt
        val b = cam.recordedAt
        if (a == null || b == null || !capture.reliableTime || !cam.reliableTime) return Duration.ZERO to WebcamSync.NONE
        return (a.toEpochMilli() - b.toEpochMilli()).milliseconds to WebcamSync.TIME
    }

    /**
     * Affine le calage de [feed] par le son : une minute du micro de la capture (sinon de son mélange) contre la même
     * minute de la webcam, élargie de [SEARCH] de chaque côté. [feed] tel quel si l'un n'a pas de son ou si rien ne
     * ressort nettement.
     */
    private suspend fun refine(media: MediaInfo, feed: WebcamFeed, layout: AudioLayout): WebcamFeed {
        val camMedia = runCatching { ffmpeg.probe(feed.file) }.getOrNull() ?: return feed
        if (camMedia.audio.isEmpty() || media.audio.isEmpty()) return feed
        val tracks = AudioTracks.of(media.audio, layout)
        val track = tracks.indexOf(AudioRole.MIC) ?: tracks.mixIndices().firstOrNull() ?: 0
        // Début de la partie commune, un peu après (les premières secondes d'un enregistrement sont souvent vides).
        val from = (if (feed.offset.isNegative()) -feed.offset else Duration.ZERO) + LEAD_IN
        val length = minOf(WINDOW, media.duration - from, feed.duration - (from + feed.offset))
        if (length < MIN_WINDOW) return feed
        val capture = envelope(decode(media.path, track, from, length))
        val camFrom = from + feed.offset - SEARCH
        val camStart = camFrom.coerceAtLeast(Duration.ZERO)
        // Ce qui manque avant le début de la webcam compte comme du silence, pour garder les lags alignés.
        val missing = ((camStart - camFrom).inWholeMilliseconds / FRAME_MS).toInt()
        val cam = DoubleArray(missing) + envelope(decode(feed.file, 0, camStart, length + SEARCH * 2 - (camStart - camFrom)))
        val lag = audioLag(capture, cam, (SEARCH.inWholeMilliseconds / FRAME_MS).toInt()) ?: run {
            log.info { "Calage au son de ${feed.file.fileName} : rien de net, calage par l'heure gardé" }
            return feed
        }
        val shift = (lag * FRAME_MS).milliseconds
        log.info { "Calage au son de ${feed.file.fileName} : ${shift} de plus que par l'heure" }
        return feed.copy(offset = feed.offset + shift, sync = WebcamSync.AUDIO)
    }

    /** Son mono à [SAMPLE_RATE] Hz, en flottants, d'une portion d'une piste. */
    private suspend fun decode(file: Path, audioIndex: Int, from: Duration, length: Duration): FloatArray {
        val samples = ArrayList<Float>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSecondsPrecise(from), "-t", Durations.ffmpegSeconds(length), "-i", file.toString(),
                    "-map", "0:a:$audioIndex", "-ac", "1", "-ar", "$SAMPLE_RATE", "-f", "f32le", "pipe:1",
                ),
                "son pour le calage de la webcam",
            ),
            StdoutHandler.Binary { input ->
                val data = DataInputStream(input.buffered())
                val bytes = ByteArray(4)
                try {
                    while (true) {
                        data.readFully(bytes)
                        samples += ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
                    }
                } catch (_: EOFException) {
                }
            },
        )
        return samples.toFloatArray()
    }

    private data class VideoTrack(val rank: Int, val width: Int, val height: Int)

    /** Flux vidéo de la capture, hors pochettes (attached_pic), avec leur rang parmi tous les flux vidéo. */
    private suspend fun videoStreams(file: Path): List<VideoTrack> {
        val out = StringBuilder()
        ffmpeg.runProbe(
            FfmpegCommand(
                listOf("-v", "error", "-select_streams", "v", "-show_entries", "stream=width,height:stream_disposition=attached_pic", "-of", "json", file.toString()),
                "flux vidéo de ${file.fileName}",
            ),
            StdoutHandler.Lines { out.appendLine(it) },
        )
        val streams = runCatching { Json.parseToJsonElement(out.toString()).jsonObject["streams"]?.jsonArray }.getOrNull() ?: return emptyList()
        return streams.mapIndexedNotNull { rank, s ->
            val o = s.jsonObject
            val pic = o["disposition"]?.jsonObject?.get("attached_pic")?.jsonPrimitive?.intOrNull ?: 0
            val w = o["width"]?.jsonPrimitive?.int ?: return@mapIndexedNotNull null
            val h = o["height"]?.jsonPrimitive?.int ?: return@mapIndexedNotNull null
            if (pic == 1) null else VideoTrack(rank, w, h)
        }
    }

    private fun describe(choice: WebcamChoice) = when (choice) {
        WebcamChoice.Auto -> "recherche automatique"
        is WebcamChoice.File -> choice.path.toString()
        is WebcamChoice.Resolved -> choice.feed.file.toString()
    }

    companion object {
        const val SAMPLE_RATE = 8000

        /** Trame de l'enveloppe : 10 ms, une précision de moins d'une image à 60 img/s. */
        const val FRAME_MS = 10L
        private const val FRAME = (SAMPLE_RATE * FRAME_MS / 1000).toInt()

        private val WINDOW = 60.seconds
        private val MIN_WINDOW = 8.seconds
        private val LEAD_IN = 2.seconds

        /** Écart cherché autour du calage par l'heure : l'heure d'un fichier se trompe de quelques secondes. */
        val SEARCH = 5.seconds

        /** Ressemblance minimale retenue (corrélation normalisée des attaques). */
        const val MIN_CORRELATION = 0.25

        /** Le meilleur décalage doit dépasser de ce facteur le meilleur des autres (hors voisinage) : sinon, ambigu. */
        const val MIN_MARGIN = 1.3

        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "avi", "flv", "ts")

        private val WEBCAM_WORDS = Regex("""(web[-_ ]?cam|face[-_ ]?cam|cam[eé]ra|(^|[^a-z])cam([^a-z]|$))""", RegexOption.IGNORE_CASE)

        /** Le nom d'un fichier parle-t-il de webcam ? (« partie_webcam », « Facecam 2026-11-17 », « cam-1 »…) */
        fun isWebcamName(name: String): Boolean = WEBCAM_WORDS.containsMatchIn(name)

        /**
         * Enveloppe des attaques du son : énergie par trame de [FRAME_MS] ms en échelle log, puis seulement ses montées
         * (un clap, une syllabe). Les montées se retrouvent dans deux micros différents ; le niveau, lui, dépend du micro.
         */
        fun envelope(samples: FloatArray): DoubleArray {
            val n = samples.size / FRAME
            if (n < 2) return DoubleArray(0)
            val level = DoubleArray(n) { f ->
                var e = 0.0
                for (k in f * FRAME until (f + 1) * FRAME) e += samples[k].toDouble() * samples[k]
                10 * log10(e / FRAME + 1e-10)
            }
            return DoubleArray(n) { f -> if (f == 0) 0.0 else maxOf(0.0, level[f] - level[f - 1]) }
        }

        /**
         * Décalage (en trames) qui aligne le mieux [capture] sur [cam] : [cam] commence [maxLag] trames avant
         * l'alignement attendu, et l'alignement cherché va de -[maxLag] à +[maxLag]. Corrélation normalisée des
         * enveloppes ; null si elle est faible, ou si un autre décalage éloigné fait presque aussi bien (bruit régulier).
         */
        fun audioLag(capture: DoubleArray, cam: DoubleArray, maxLag: Int): Int? {
            if (capture.isEmpty() || cam.size < capture.size) return null
            val a = normalized(capture) ?: return null
            val scores = DoubleArray(2 * maxLag + 1) { j ->
                val start = j // lag = j - maxLag
                if (start + a.size > cam.size) return@DoubleArray Double.NaN
                val b = normalized(cam.copyOfRange(start, start + a.size)) ?: return@DoubleArray Double.NaN
                var s = 0.0
                for (i in a.indices) s += a[i] * b[i]
                s / a.size
            }
            val best = scores.indices.filter { !scores[it].isNaN() }.maxByOrNull { scores[it] } ?: return null
            if (scores[best] < MIN_CORRELATION) return null
            // Hors du voisinage du pic (± 20 trames = 0,2 s) : un rythme régulier donnerait plusieurs pics semblables.
            val other = scores.indices.filter { !scores[it].isNaN() && abs(it - best) > 20 }.maxOfOrNull { scores[it] } ?: 0.0
            if (other > 0 && scores[best] < other * MIN_MARGIN) return null
            return best - maxLag
        }

        private fun normalized(x: DoubleArray): DoubleArray? {
            val mean = x.average()
            var v = 0.0
            for (e in x) v += (e - mean) * (e - mean)
            val sd = sqrt(v / x.size)
            if (sd < 1e-9) return null
            return DoubleArray(x.size) { (x[it] - mean) / sd }
        }
    }
}
