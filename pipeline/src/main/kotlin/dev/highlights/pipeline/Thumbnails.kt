package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.i18n.Message
import dev.highlights.export.ExportReport
import dev.highlights.export.OutputNamer
import dev.highlights.montage.MontageReport
import dev.highlights.montage.ThumbnailFrames
import dev.highlights.montage.ThumbnailPicker
import dev.highlights.montage.ThumbnailRenderer
import dev.highlights.montage.ThumbnailStyle
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * Miniatures prêtes pour YouTube : 1280x720 pour une vidéo en largeur (16:9 ou format de la capture), 1080x1920 pour
 * une vidéo verticale. Le moment vient du rapport écrit avec la vidéo ([ThumbnailPicker]), l'image de la capture
 * d'origine, en pleine résolution, à l'instant le plus net autour du kill ([ThumbnailFrames]). Écrite à côté de la
 * vidéo, `<nom>_miniature.png`, sans jamais écraser ([OutputNamer.thumbnailFor]).
 */
class Thumbnails(private val ffmpeg: FfmpegService) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun make(video: Path, style: ThumbnailStyle = ThumbnailStyle.DEFAULT): Path {
        if (!video.isRegularFile()) throw InputException(Message("error.input.videoNotFound", "file" to video))
        val report = reportOf(video) ?: throw InputException(Message("error.thumbnail.noReport", "file" to video))
        val (moments, total) = when {
            "clips" in report -> json.decodeFromJsonElement(MontageReport.serializer(), report).let { r ->
                ThumbnailPicker.fromMontage(r) to r.clips.sumOf { it.kills.size }
            }
            else -> json.decodeFromJsonElement(ExportReport.serializer(), report).let { r ->
                ThumbnailPicker.fromHighlights(r).let { m -> m to m.sumOf { it.kills } }
            }
        }
        // Une capture déplacée depuis l'export ne compte plus : on prend les meilleurs moments encore lisibles.
        val contenders = ThumbnailPicker.contenders(moments.filter { it.source.isRegularFile() })
        if (contenders.isEmpty()) throw InputException(Message("error.thumbnail.noSource", "file" to video))
        val target = ffmpeg.probe(video).video ?: throw InputException(Message("error.input.noVideo", "file" to video))
        val (outWidth, outHeight) = if (target.height > target.width) 1080 to 1920 else 1280 to 720
        // Entre moments de même force, l'image la plus lisible ; le plus fort garde l'instant du kill si tout est sombre.
        val measured = contenders.mapNotNull { m ->
            val source = ffmpeg.probe(m.source).video ?: return@mapNotNull null
            val best = ThumbnailFrames.best(ThumbnailFrames.measure(ffmpeg, m.source, m.kill, source.width, source.height), m.kill)
            Triple(m, source, best)
        }
        val (moment, source, frame) = measured.maxByOrNull { (m, _, f) -> f?.let { ThumbnailFrames.readability(it, m.kill) } ?: -1.0 }
            ?: throw InputException(Message("error.input.noVideo", "file" to contenders.first().source))
        val at = frame?.at ?: moment.kill
        val output = OutputNamer.thumbnailFor(video)
        ThumbnailRenderer.render(ffmpeg, moment.source, at, source.width, source.height, outWidth, outHeight, style, ThumbnailPicker.label(moment, total), output)
        log.info { "Miniature ${style.id} de ${video.fileName} : ${moment.source.fileName} à $at (kill à ${moment.kill}) → $output" }
        return output
    }

    /** Une miniature par vidéo ; un échec n'empêche pas les autres ni l'export (la vidéo est déjà là). */
    suspend fun makeAll(videos: Collection<Path>, style: ThumbnailStyle): List<Path> = videos.mapNotNull { video ->
        runCatching { make(video, style) }.onFailure { log.warn(it) { "Miniature impossible pour $video" } }.getOrNull()
    }
}

/** Rapport JSON qui liste [video] parmi ses sorties, dans le même dossier (highlights ou montage kills). */
internal fun reportOf(video: Path): JsonObject? {
    val target = video.toAbsolutePath().normalize().toString().lowercase()
    val candidates = runCatching { video.toAbsolutePath().parent.listDirectoryEntries("*.json") }.getOrDefault(emptyList())
    return candidates.firstNotNullOfOrNull { file ->
        val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
        val outputs = runCatching { root["outputs"]!!.jsonArray }.getOrNull() ?: return@firstNotNullOfOrNull null
        val paths = outputs.mapNotNull { o ->
            runCatching { (if (o is JsonObject) o["path"]!! else o).jsonPrimitive.content }.getOrNull()
        }
        root.takeIf { paths.any { Path(it).toAbsolutePath().normalize().toString().lowercase() == target } }
    }
}
