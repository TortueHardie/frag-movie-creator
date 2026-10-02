package dev.highlights.montage

import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.KillStyle
import dev.highlights.core.model.MatchCut
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ShotAlign
import dev.highlights.core.model.TimeRange
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

private val log = KotlinLogging.logger {}

/**
 * Inspections des kills gardées d'un montage à l'autre : le recalage sur le tir et le flick de chaque kill
 * ([KillInspector]), la pose de l'arme autour de chaque groupe ([MatchCutter]). Les mesurer décode quelques secondes de
 * source par kill, des minutes pour une soirée, et on refait souvent plusieurs montages des mêmes parties (autre musique,
 * autre durée) : ils ne paient plus que les kills jamais vus. Un fichier par capture dans [dir], oublié si la capture
 * change (taille, date) ; chaque mesure est rangée sous une clé qui contient tout ce dont elle dépend (instant, traits
 * venus de l'analyse, réglages, [VERSION]) : changer un réglage la refait, sans effacer les autres.
 */
class InspectionCache(private val dir: Path) {
    private val entries = ConcurrentHashMap<Path, Entry>()

    private class Entry(val file: Path, val stamp: Pair<Long, Long>?, val stored: StoredInspections) {
        val kills = ConcurrentHashMap(stored.kills)
        val aims = ConcurrentHashMap(stored.aims)
        @Volatile var changed = false
    }

    /** Résultat gardé de l'inspection du kill [kill] (instant recalé, traits mesurés) ; null s'il faut la faire. */
    fun kill(media: MediaInfo, kill: Duration, traits: KillTraits, align: ShotAlign, style: KillStyle, layout: AudioLayout): Pair<Duration, KillTraits>? {
        val stored = entry(media)?.kills?.get(killKey(kill, traits, align, style, layout)) ?: return null
        return stored.at.nanoseconds to traits.copy(
            shift = stored.shift.nanoseconds,
            flick = stored.flick,
            direction = stored.direction?.let { d -> FlickDirection.entries.firstOrNull { it.name == d } },
        )
    }

    fun putKill(media: MediaInfo, kill: Duration, traits: KillTraits, align: ShotAlign, style: KillStyle, layout: AudioLayout, result: Pair<Duration, KillTraits>) {
        val entry = entry(media) ?: return
        val (at, measured) = result
        entry.kills[killKey(kill, traits, align, style, layout)] =
            StoredKill(at.inWholeNanoseconds, measured.shift.inWholeNanoseconds, measured.flick, measured.direction?.name)
        entry.changed = true
    }

    /** Pose de l'arme gardée pour [group] ; null s'il faut la lire. */
    fun aim(group: KillGroup, settings: MatchCut): Aim? = entry(group.media)?.aims?.get(aimKey(group, settings))?.toAim()

    fun putAim(group: KillGroup, settings: MatchCut, aim: Aim) {
        val entry = entry(group.media) ?: return
        entry.aims[aimKey(group, settings)] = StoredAim.of(aim)
        entry.changed = true
    }

    /** Écrit les captures dont une mesure a changé. Un échec n'empêche pas le montage : on remesurera la prochaine fois. */
    fun flush() {
        for ((media, entry) in entries) {
            if (!entry.changed) continue
            entry.changed = false
            runCatching {
                dir.createDirectories()
                val tmp = entry.file.resolveSibling(entry.file.fileName.toString() + ".tmp")
                val stored = StoredInspections(VERSION, entry.stamp?.first ?: 0, entry.stamp?.second ?: 0, HashMap(entry.kills), HashMap(entry.aims))
                GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(json.encodeToString(StoredInspections.serializer(), stored)) }
                tmp.moveTo(entry.file, overwrite = true)
            }.onFailure { log.warn { "Inspections de ${media.fileName} non gardées ($dir) : ${it.message}" } }
        }
    }

    /** Mesures gardées de la capture, chargées une fois ; null si on ne peut pas dater la capture (rien n'est gardé). */
    private fun entry(media: MediaInfo): Entry? {
        val path = media.path.toAbsolutePath().normalize()
        val stamp = stamp(path) ?: return null
        return entries.computeIfAbsent(path) { load(path, stamp) }.takeIf { it.stamp == stamp }
    }

    private fun load(path: Path, stamp: Pair<Long, Long>): Entry {
        val file = dir.resolve("${digest(path.toString().lowercase())}.json.gz")
        val stored = if (!file.exists()) null else try {
            GZIPInputStream(file.inputStream()).bufferedReader().use { json.decodeFromString(StoredInspections.serializer(), it.readText()) }
                .takeIf { it.version == VERSION && it.sizeBytes == stamp.first && it.modifiedAtMillis == stamp.second }
        } catch (e: Exception) {
            log.warn { "Inspections gardées illisibles (${file.fileName}), kills remesurés : ${e.message}" }
            null
        }
        return Entry(file, stamp, stored ?: StoredInspections(VERSION, stamp.first, stamp.second))
    }

    private fun killKey(kill: Duration, traits: KillTraits, align: ShotAlign, style: KillStyle, layout: AudioLayout) =
        digest("${kill.inWholeNanoseconds}|$traits|$align|${style.flick}|${style.flickFrom}|${style.flickTo}|$layout")

    /** Le modèle d'arme en main compte par son contenu : le remplacer refait les poses. */
    private fun aimKey(group: KillGroup, settings: MatchCut): String {
        val template = settings.weapon?.template?.let { stamp(Path(it)) }
        return digest("${group.kills.joinToString(",") { it.inWholeNanoseconds.toString() }}|$settings|$template")
    }

    companion object {
        /** À incrémenter quand le calcul de [KillInspector] ou de [MatchCutter] change : les mesures gardées sont refaites. */
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        private fun stamp(file: Path): Pair<Long, Long>? = runCatching { file.fileSize() to file.getLastModifiedTime().toMillis() }.getOrNull()

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}

/** Mesures gardées d'une capture ; instants en nanosecondes, comme les mesures elles-mêmes : rien n'est arrondi. */
@Serializable
internal data class StoredInspections(
    val version: Int,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val kills: Map<String, StoredKill> = emptyMap(),
    val aims: Map<String, StoredAim> = emptyMap(),
)

@Serializable
internal data class StoredKill(val at: Long, val shift: Long, val flick: Double, val direction: String? = null)

@Serializable
internal data class StoredRange(val start: Long, val end: Long) {
    fun toRange() = TimeRange(start.nanoseconds, end.nanoseconds)

    companion object {
        fun of(r: TimeRange) = StoredRange(r.start.inWholeNanoseconds, r.end.inWholeNanoseconds)
    }
}

/** Pose de l'arme : image de référence en doubles bruts (base64), pixels comparés en bits : la même pose, au bit près. */
@Serializable
internal data class StoredPose(val mean: String, val still: String) {
    fun toPose(): Pose {
        val bytes = Base64.getDecoder().decode(mean)
        val values = DoubleArray(bytes.size / 8).also { ByteBuffer.wrap(bytes).asDoubleBuffer().get(it) }
        val bits = Base64.getDecoder().decode(still)
        return Pose(values, BooleanArray(values.size) { i -> bits[i / 8].toInt() shr (i % 8) and 1 == 1 })
    }

    companion object {
        fun of(p: Pose): StoredPose {
            val bytes = ByteBuffer.allocate(p.mean.size * 8).also { it.asDoubleBuffer().put(p.mean) }.array()
            val bits = ByteArray((p.still.size + 7) / 8)
            p.still.forEachIndexed { i, s -> if (s) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte() }
            return StoredPose(Base64.getEncoder().encodeToString(bytes), Base64.getEncoder().encodeToString(bits))
        }
    }
}

@Serializable
internal data class StoredAim(val head: StoredRange? = null, val tail: StoredRange? = null, val headPose: StoredPose? = null, val tailPose: StoredPose? = null) {
    fun toAim() = Aim(head = head?.toRange(), tail = tail?.toRange(), headPose = headPose?.toPose(), tailPose = tailPose?.toPose())

    companion object {
        fun of(a: Aim) = StoredAim(a.head?.let(StoredRange::of), a.tail?.let(StoredRange::of), a.headPose?.let(StoredPose::of), a.tailPose?.let(StoredPose::of))
    }
}
