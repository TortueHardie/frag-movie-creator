package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.absoluteValue
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/** Drop d'un morceau posée à la main : [key] = [MusicFeelStore.keyOf], [name] pour l'affichage, [atMs] l'instant dans le morceau. */
@Serializable
data class StoredDrop(val key: String, val name: String, val atMs: Long, val setAt: String = "") {
    val at: Duration get() = atMs.milliseconds
}

@Serializable
data class StoredDrops(val version: Int = 1, val drops: List<StoredDrop> = emptyList())

/**
 * Drops posées à la main, une par morceau (`<sortie>/sessions/music-drops.json`), réutilisées à chaque montage sur ce
 * morceau. Le détecteur de drop se trompe sur certains morceaux (Rome Is Burning : la basse entre deux temps avant la
 * section qu'il retient) ; l'utilisateur la pose une fois en l'écoutant, et ne devine plus.
 */
class MusicDropStore(val file: Path) {
    private val lock = Any()

    fun load(): StoredDrops = synchronized(lock) {
        if (!file.exists()) return StoredDrops()
        try {
            json.decodeFromString(StoredDrops.serializer(), file.readText())
        } catch (e: Exception) {
            log.warn { "Drops posées à la main illisibles ($file), ignorées : ${e.message}" }
            StoredDrops()
        }
    }

    /** Drop posée pour [music], null si aucune (ou fichier illisible). */
    fun dropOf(music: Path): Duration? {
        val key = MusicFeelStore.keyOf(music) ?: return null
        return load().drops.firstOrNull { it.key == key }?.at
    }

    /** Pose la drop de [music] à [at] ; null : l'oublie (la détection reprend la main). */
    fun set(music: Path, at: Duration?, now: Instant = Instant.now()): Unit = synchronized(lock) {
        val key = MusicFeelStore.keyOf(music) ?: return
        val others = load().drops.filter { it.key != key }
        val next = if (at == null) others else others + StoredDrop(key, music.name, at.inWholeMilliseconds, now.toString())
        save(StoredDrops(drops = next.sortedBy { it.name.lowercase() }))
    }

    /** Oublie la drop d'un morceau par sa clé (vue des goûts, qui n'a pas forcément le fichier sous la main). */
    fun forget(key: String): Unit = synchronized(lock) {
        val current = load()
        if (current.drops.none { it.key == key }) return
        save(current.copy(drops = current.drops.filter { it.key != key }))
    }

    /** Oublie toutes les drops : le fichier est mis de côté (`music-drops.reset-<instant>.json`), comme [TimingHistory.reset]. */
    fun reset(): Path? = synchronized(lock) {
        if (!file.exists()) return null
        val stamp = Instant.now().toString().replace(":", "-").substringBefore('.')
        val aside = file.resolveSibling(file.name.removeSuffix(".json") + ".reset-$stamp.json")
        file.moveTo(aside)
        aside
    }

    private fun save(drops: StoredDrops) {
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(json.encodeToString(StoredDrops.serializer(), drops))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Drops posées à la main non enregistrées ($file) : ${it.message}" } }
    }

    private companion object {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}

/**
 * Applique une drop posée à la main à l'analyse d'un morceau.
 *
 * L'instant choisi devient un temps : le temps détecté le plus proche y est amené, et ses voisins suivent en
 * s'atténuant sur [REALIGN_BEATS] temps de chaque côté (la grille est recalée localement, pas déplacée en entier).
 * Pourquoi ne pas garder l'instant seulement pour l'ancrage du kill : tout le montage repose sur les temps (le kill de
 * la drop atterrit sur un temps, les coupes autour aussi, le plan de la drop commence sur un temps). Un instant hors
 * de la grille aurait mis le kill sur la drop entendue et les coupes voisines sur la grille d'avant, à côté du son.
 * Recaler la grille autour garde kill et coupes ensemble ; l'écart d'un temps à l'autre change au plus de
 * (demi-temps / [REALIGN_BEATS]), inaudible (30 ms à 126 BPM). Une section commence ensuite sur ce temps (la frontière
 * la plus proche y est déplacée à moins de [MOVE_BEATS], sinon la section est coupée en deux), et les sections sont
 * reclassées autour de cette drop.
 */
object MusicDrops {
    /** Temps de chaque côté de la drop sur lesquels le recalage s'atténue jusqu'à zéro. */
    const val REALIGN_BEATS = 8

    /** Frontière de section déplacée sur la drop si elle en est à moins de ce nombre de temps ; sinon, section coupée. */
    const val MOVE_BEATS = 16

    fun apply(analysis: MusicAnalysis, at: Duration, byHand: Boolean = true): MusicAnalysis {
        val beats = analysis.beats
        if (beats.isEmpty()) return analysis
        val target = at.coerceIn(Duration.ZERO, analysis.duration)
        val drop = beats.indices.minBy { (beats[it] - target).absoluteValue }
        val offset = target - beats[drop]
        val moved = beats.mapIndexed { i, t ->
            val distance = (i - drop).absoluteValue
            if (distance >= REALIGN_BEATS) t else t + offset * (1.0 - distance.toDouble() / REALIGN_BEATS)
        }
        val sections = MusicAnalyzer.classify(boundaryAt(analysis.sections, drop), drop)
        log.info { "Drop ${if (byHand) "posée à la main" else "apprise"} sur ${analysis.file.name} : ${target} (temps $drop, recalé de ${offset.inWholeMilliseconds} ms ; détectée au temps ${analysis.dropBeat})" }
        return if (byHand) analysis.copy(beats = moved, sections = sections, dropBeat = drop, dropByHand = target)
        else analysis.copy(beats = moved, sections = sections, dropBeat = drop, dropLearned = target)
    }

    /** Sections dont l'une commence au temps [beat] : frontière proche déplacée, sinon section coupée. */
    internal fun boundaryAt(sections: List<MusicSection>, beat: Int): List<MusicSection> {
        if (sections.isEmpty() || beat <= sections.first().startBeat) return sections
        val index = sections.indexOfLast { beat >= it.startBeat }
        val s = sections[index]
        if (s.startBeat == beat) return sections
        val next = sections.getOrNull(index + 1)
        val toStart = beat - s.startBeat
        val toNext = next?.let { it.startBeat - beat }
        return when {
            // La section suivante commence peu après : elle commence sur la drop (la section courante raccourcit).
            next != null && toNext!! <= MOVE_BEATS && toNext <= toStart && s.beats - toNext >= 1 ->
                sections.toMutableList().also { it[index] = s.copy(endBeat = beat); it[index + 1] = next.copy(startBeat = beat) }
            // La section courante a commencé peu avant, et une autre la précède : elle commence sur la drop.
            index > 0 && toStart <= MOVE_BEATS && s.endBeat - beat >= 1 ->
                sections.toMutableList().also { it[index - 1] = sections[index - 1].copy(endBeat = beat); it[index] = s.copy(startBeat = beat) }
            else -> sections.toMutableList().also {
                it[index] = s.copy(endBeat = beat)
                it.add(index + 1, s.copy(startBeat = beat))
            }
        }
    }
}

/**
 * Son d'un morceau en mémoire pour l'outil de la drop : PCM mono 16 bits à [rate] Hz (forme d'onde et écoute).
 * 4 min à 48 kHz : 23 Mo, gardés tant que l'outil est ouvert.
 */
class MusicPcm(val rate: Int, val samples: ShortArray) {
    val duration: Duration get() = (samples.size * 1000L / rate).milliseconds

    fun indexOf(t: Duration): Int = (t.inWholeMicroseconds * rate / 1_000_000).toInt().coerceIn(0, samples.size)

    /**
     * Crêtes de la forme d'onde : [buckets] valeurs 0..1, chacune le maximum absolu de sa part du morceau, puis
     * ramenées au maximum du morceau (une musique mixée bas reste lisible).
     */
    fun peaks(buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        if (samples.isEmpty() || buckets <= 0) return out
        for (b in 0 until buckets) {
            val from = (samples.size.toLong() * b / buckets).toInt()
            val to = (samples.size.toLong() * (b + 1) / buckets).toInt().coerceAtLeast(from + 1).coerceAtMost(samples.size)
            var max = 0
            for (i in from until to) max = maxOf(max, (samples[i].toInt()).absoluteValue)
            out[b] = max / 32768f
        }
        val top = out.max().takeIf { it > 0f } ?: return out
        for (i in out.indices) out[i] /= top
        return out
    }

    companion object {
        /**
         * Décode [file] en mono 16 bits à [rate] Hz par FFmpeg (mêmes instants que [MusicAnalyzer], qui décode aussi par
         * FFmpeg). [from] et [length] : un passage seulement (l'échantillon 0 est à [from]) ; première piste son, celle
         * que joue le lecteur intégré.
         */
        suspend fun decode(ffmpeg: FfmpegService, file: Path, rate: Int = 48_000, from: Duration? = null, length: Duration? = null): MusicPcm {
            val bytes = ByteArrayOutputStream()
            val seek = from?.let { listOf("-ss", dev.highlights.core.serialization.Durations.ffmpegSeconds(it)) }.orEmpty()
            val span = length?.let { listOf("-t", dev.highlights.core.serialization.Durations.ffmpegSeconds(it)) }.orEmpty()
            ffmpeg.run(
                FfmpegCommand(seek + listOf("-i", file.toString()) + span + listOf("-map", "0:a:0", "-vn", "-ac", "1", "-ar", rate.toString(), "-f", "s16le", "-"), "son de ${file.name}"),
                StdoutHandler.Binary { input -> input.copyTo(bytes) },
            )
            val buffer = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return MusicPcm(rate, ShortArray(buffer.remaining()).also(buffer::get))
        }
    }
}

/** Instant affiché et saisi en m:ss.mmm (« 0:30.523 ») ; [parse] accepte aussi les secondes seules (« 30.523 »). */
object DropTime {
    fun format(t: Duration): String {
        val ms = t.inWholeMilliseconds.coerceAtLeast(0)
        return "%d:%02d.%03d".format(ms / 60_000, ms / 1000 % 60, ms % 1000)
    }

    fun parse(text: String): Duration? {
        val clean = text.trim().replace(',', '.')
        val match = Regex("""^(?:(\d+):)?(\d+(?:\.\d{1,3})?)$""").matchEntire(clean) ?: return null
        val minutes = match.groupValues[1].ifEmpty { "0" }.toLong()
        val seconds = match.groupValues[2].toDouble()
        if (match.groupValues[1].isNotEmpty() && seconds >= 60) return null
        return (minutes * 60_000 + (seconds * 1000).roundToLong()).milliseconds
    }
}
