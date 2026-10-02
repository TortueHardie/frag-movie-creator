package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import java.nio.file.Path

/** Zone lue sur la source : [filters] (recadrage, réduction) mènent à une image grise de [width] x [height] pixels. */
data class FrameZoneSpec(val filters: String, val width: Int, val height: Int)

/**
 * Plusieurs zones d'une même portion de la source, lues d'un seul décodage : l'image complète est décodée une fois,
 * chaque zone en est recadrée et réduite, et les zones sont empilées dans une seule image grise qu'on sépare ensuite.
 * Décoder une capture 3440x1440 coûte bien plus que tout le reste (mesuré : 0,47 s pour 2 s de source, 0,61 s pour
 * 4 s, le démarrage et l'image clé précédente pèsent autant que les images elles-mêmes) : lire la visée et le repère
 * d'arme d'un même décodage, et une seule fois la fenêtre qui couvre avant et après le kill, divise ce temps.
 */
object SourceFrames {
    /**
     * Décodages de courts extraits lancés ensemble. Sur un seul fichier déjà en cache, 8 à la fois décodent 30 % plus
     * vite que 4 ; sur une vraie soirée (15 captures de plusieurs Go, lues de place en place), c'est l'inverse : 245
     * kills en 48 s à 4, 73 s à 8. Le disque compte autant que les cœurs.
     */
    const val PARALLELISM = 4

    /**
     * Images de chaque zone de [zones] sur [range] de [file], à [fps] images/s : une liste d'images par zone, dans
     * l'ordre de [zones], toutes de même longueur. Les filtres sont ceux qu'aurait une lecture zone par zone
     * (`fps`, filtres de la zone, `format=gray`) : mêmes images.
     */
    suspend fun read(ffmpeg: FfmpegService, file: Path, range: TimeRange, fps: Int, zones: List<FrameZoneSpec>, label: String): List<List<ByteArray>> {
        require(zones.isNotEmpty()) { "aucune zone à lire" }
        val width = zones.maxOf { it.width }
        val height = zones.sumOf { it.height }
        val filters = if (zones.size == 1) {
            listOf("-vf", "fps=$fps,${zones.single().filters},format=gray")
        } else {
            val branches = zones.indices.joinToString("") { "[s$it]" }
            val graph = buildString {
                append("[0:v]fps=$fps,split=${zones.size}$branches;")
                zones.forEachIndexed { i, z -> append("[s$i]${z.filters},format=gray,pad=$width:${z.height}:0:0[z$i];") }
                append(zones.indices.joinToString("") { "[z$it]" })
                append("vstack=inputs=${zones.size}[out]")
            }
            listOf("-filter_complex", graph, "-map", "[out]")
        }
        val frames = zones.map { mutableListOf<ByteArray>() }
        ffmpeg.run(
            FfmpegCommand(
                listOf("-ss", Durations.ffmpegSecondsPrecise(range.start), "-t", Durations.ffmpegSeconds(range.length), "-i", file.toString(), "-an") +
                    filters + listOf("-f", "rawvideo", "pipe:1"),
                label,
            ),
            StdoutHandler.Binary { input ->
                val bytes = input.readAllBytes()
                val size = width * height
                for (f in 0 until bytes.size / size) {
                    var row = f * size
                    zones.forEachIndexed { i, z ->
                        val image = ByteArray(z.width * z.height)
                        for (y in 0 until z.height) System.arraycopy(bytes, row + y * width, image, y * z.width, z.width)
                        frames[i] += image
                        row += z.height * width
                    }
                }
            },
        )
        return frames
    }
}
