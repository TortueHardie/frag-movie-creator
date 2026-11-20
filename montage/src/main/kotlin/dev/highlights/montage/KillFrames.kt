package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.serialization.Durations
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** Une image de la capture autour d'un kill : son instant exact dans la capture et le fichier JPEG réduit. */
data class KillFrame(val at: Duration, val file: Path)

/**
 * Images d'une capture autour d'un kill, toutes celles de la source (pas un échantillon) : le sélecteur d'image de la
 * frise y choisit celle où le kill a vraiment lieu. L'instant de chaque image est celui que FFmpeg lui donne dans la
 * capture (`-copyts` + filtre `showinfo`), pas un compte d'images : une capture à cadence variable (Outplayed) garde
 * des instants justes, et c'est cet instant qui devient le calage du kill. Mises en cache par capture et instant
 * (`<cacheDir>/<capture>_<taille>/<ms>/`), une seule lecture de la source par kill.
 */
class KillFrames(private val ffmpeg: FfmpegService, private val cacheDir: Path) {
    @Serializable
    private data class Index(val version: Int, val frames: List<Long>)

    /**
     * Les images de [before] avant [center] à [after] après, hauteur [height] px. Une demi-seconde de chaque côté : les
     * recalages déplacent un kill de 0,6 s au plus, et 60 images à 60 img/s se parcourent encore à la flèche.
     */
    suspend fun around(
        media: MediaInfo,
        center: Duration,
        before: Duration = 0.5.seconds,
        after: Duration = 0.5.seconds,
        height: Int = HEIGHT,
    ): List<KillFrame> {
        val start = (center - before).coerceAtLeast(media.bounds.start)
        val end = (center + after).coerceAtMost(media.duration)
        val dir = cacheDir.resolve("${media.path.nameWithoutExtension}_${media.sizeBytes}").resolve("${start.inWholeMilliseconds}_${end.inWholeMilliseconds}_$height")
        val index = dir.resolve("index.json")
        cached(index)?.let { stored -> return stored.mapIndexed { i, ns -> KillFrame(ns.nanoseconds, dir.resolve(name(i))) } }
        dir.createDirectories()
        dir.listDirectoryEntries("f_*.jpg").forEach { it.deleteIfExists() }
        val fps = media.video?.fps?.takeIf { it > 0 } ?: 60.0
        // Une image de plus que la durée n'en contient : la dernière est coupée par le compte, jamais par la durée.
        val count = ((end - start).inWholeMicroseconds / 1e6 * fps).toInt() + 1
        val times = mutableListOf<Duration>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-copyts", "-ss", Durations.ffmpegSeconds(start), "-i", media.path.toString(),
                    "-map", "0:v:0", "-an", "-frames:v", count.toString(),
                    "-vf", "scale=-2:$height,showinfo", "-fps_mode", "passthrough", "-q:v", "4",
                    dir.resolve("f_%03d.jpg").toString(),
                ),
                "images du kill ${Durations.format(center)}",
            ),
            StdoutHandler.Discard,
            onStderrLine = { line -> ptsTime(line)?.let { times += it } },
        )
        val files = dir.listDirectoryEntries("f_*.jpg").sortedBy { it.name }
        // Images avant le début demandé (recherche à l'image clé avec -copyts) : écartées, l'instant reste juste.
        val frames = files.zip(times).filter { (_, t) -> t >= start - 1.milliseconds && t <= end }
        val kept = frames.mapIndexed { i, (file, t) ->
            val target = dir.resolve(name(i))
            if (file != target) java.nio.file.Files.move(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            KillFrame(t, target)
        }
        dir.listDirectoryEntries("f_*.jpg").filter { f -> kept.none { it.file == f } }.forEach { it.deleteIfExists() }
        index.writeText(json.encodeToString(Index.serializer(), Index(VERSION, kept.map { it.at.inWholeNanoseconds })))
        return kept
    }

    private fun cached(index: Path): List<Long>? = runCatching {
        if (!index.exists()) return null
        json.decodeFromString(Index.serializer(), index.readText()).takeIf { it.version == VERSION }?.frames
            ?.takeIf { it.indices.all { i -> index.resolveSibling(name(i)).exists() } }
    }.getOrNull()

    private fun name(i: Int) = "k_%03d.jpg".format(i)

    companion object {
        const val VERSION = 1

        /**
         * Hauteur des images lues : 360 px, l'image entière affichée sur 640 dp de large (270 px au départ, avec un zoom
         * du réticule à part que l'utilisateur a retiré le 20/11). 60 images de 360 px pèsent ~2 Mo.
         */
        const val HEIGHT = 360

        private val json = Json { ignoreUnknownKeys = true }
        private val PTS = Regex("""pts_time:\s*(-?[0-9.]+)""")

        /** Instant d'une ligne de `showinfo`, null pour toute autre ligne. */
        internal fun ptsTime(line: String): Duration? {
            if ("Parsed_showinfo" !in line || "n:" !in line) return null
            val value = PTS.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            return (value * 1e6).roundToLong().microseconds
        }
    }
}
