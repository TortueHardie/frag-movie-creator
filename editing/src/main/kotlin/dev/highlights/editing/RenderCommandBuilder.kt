package dev.highlights.editing

import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.serialization.Durations
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class RenderCommand(
    val command: FfmpegCommand,
    /** Contenu à écrire dans le fichier de script de filtres avant de lancer la commande. */
    val filterGraph: String,
    val expectedDuration: Duration,
)

data class RenderRequest(
    val plan: EditPlan,
    val format: OutputFormat,
    val encoder: EncoderProfile,
    val output: Path,
    /** Fichier où le graphe est écrit (évite la limite de longueur de ligne de commande Windows). */
    val filterScript: Path,
    val audioBitrate: String = "192k",
    /** Décodage matériel des sources, ex. "d3d11va". null = logiciel. */
    val hwaccel: String? = null,
    /** Pré-découpes des extraits, lues à la place des sources. Voir [SourceCuts]. */
    val cuts: SourceCuts = SourceCuts.NONE,
)

/**
 * Une entrée FFmpeg par clip avec seek rapide (-ss/-t avant -i) : on ne décode que les segments utiles,
 * au lieu de `trim` dans le graphe qui décoderait la vidéo depuis le début.
 */
object RenderCommandBuilder {

    fun build(request: RenderRequest): RenderCommand {
        val plan = request.plan
        val settings = plan.settings
        val clips = plan.clips
        require(clips.isNotEmpty()) { "plan vide" }

        val args = mutableListOf<String>()
        clips.forEach { clip ->
            val input = request.cuts.input(clip.media, clip.range)
            args += inputArgs(request.hwaccel, input.start, clip.range.length, input.path)
        }

        val graph = mutableListOf<String>()
        clips.forEachIndexed { i, clip ->
            graph += videoChain(i, clip.media, request.format, settings, "v$i")
            graph += audioChain(i, clip, settings.audioStreams)
        }

        val (videoOut, audioOut) = if (clips.size == 1) {
            "v0" to "a0"
        } else if (plan.fade.isPositive()) {
            val d = Durations.ffmpegSeconds(plan.fade)
            var offset = Duration.ZERO
            var v = "v0"
            var a = "a0"
            for (i in 1 until clips.size) {
                offset += clips[i - 1].range.length - plan.fade
                graph += "[$v][v$i]xfade=transition=fade:duration=$d:offset=${Durations.ffmpegSeconds(offset)}[vx$i]"
                graph += "[$a][a$i]acrossfade=d=$d:c1=tri:c2=tri[ax$i]"
                v = "vx$i"
                a = "ax$i"
            }
            v to a
        } else {
            graph += clips.indices.joinToString("") { "[v$it][a$it]" } + "concat=n=${clips.size}:v=1:a=1[vcat][acat]"
            "vcat" to "acat"
        }

        val loudness = settings.loudnessLufs?.let { "loudnorm=I=${fmt(it)}:TP=-1.5:LRA=11," } ?: ""
        graph += "[$audioOut]${loudness}aresample=48000[aout]"

        args += listOf("-/filter_complex", request.filterScript.toString())
        args += listOf("-map", "[$videoOut]", "-map", "[aout]")
        args += request.encoder.videoArgs
        args += listOf(
            "-c:a", "aac", "-b:a", request.audioBitrate, "-ar", "48000",
            "-movflags", "+faststart",
            "-progress", "pipe:1",
            "-y", request.output.toString(),
        )

        return RenderCommand(
            command = FfmpegCommand(args, "rendu ${request.format.label} (${clips.size} clips, ${request.encoder.name})"),
            filterGraph = graph.joinToString(";\n") + "\n",
            expectedDuration = plan.outputDuration,
        )
    }

    /** Extraits lus par le rendu de [plan] : ce que [SourceCutter] doit pré-découper. */
    fun sourceCuts(plan: EditPlan): List<SourceCut> = plan.clips.map { SourceCut(it.media, it.range) }

    /** Une image PNG du format demandé à l'instant [at] : sert à régler recadrage et HUD sans rendu complet. */
    fun buildPreview(media: MediaInfo, at: Duration, format: OutputFormat, settings: EditSettings, filterScript: Path, output: Path): RenderCommand {
        val args = inputArgs(null, at, 1.seconds, media.path).toMutableList()
        val graph = videoChain(0, media, format, settings, "vout")
        args += listOf("-/filter_complex", filterScript.toString(), "-map", "[vout]", "-frames:v", "1", "-update", "1", "-y", output.toString())
        return RenderCommand(FfmpegCommand(args, "aperçu ${format.label} à ${Durations.format(at)}"), graph.joinToString(";\n") + "\n", Duration.ZERO)
    }

    private fun inputArgs(hwaccel: String?, start: Duration, length: Duration, source: Path): List<String> =
        (hwaccel?.let { listOf("-hwaccel", it) } ?: emptyList()) + listOf(
            "-ss", Durations.ffmpegSecondsPrecise(start),
            "-t", Durations.ffmpegSeconds(length),
            "-i", source.toString(),
        )

    /** Chaîne vidéo d'une entrée jusqu'au label [out] : géométrie du format, cadence, HUD éventuel. */
    fun videoChain(input: Int, media: MediaInfo, format: OutputFormat, settings: EditSettings, out: String): List<String> {
        val video = media.video ?: throw HighlightsException("${media.path} ne contient pas de flux vidéo")
        val head = "[$input:v:0]setpts=PTS-STARTPTS,fps=${settings.fps}"
        val tail = "format=yuv420p,settb=AVTB[$out]"

        val overlays = if (format == OutputFormat.VERTICAL) settings.vertical.hud.filter { it.enabled } else emptyList()
        if (overlays.isEmpty()) {
            return listOf("$head,${geometry(format, video.width, video.height, settings)},$tail")
        }

        val size = settings.vertical.size
        val p = "c$input"
        val statements = mutableListOf<String>()
        statements += "$head,split=${overlays.size + 1}[${p}base]" + overlays.indices.joinToString("") { "[${p}hud$it]" }
        statements += "[${p}base]${geometry(format, video.width, video.height, settings)}[${p}l0]"
        overlays.forEachIndexed { k, hud ->
            val src = pixelBox(video.width, video.height, hud.source)
            val w = even(hud.target.width * size.width)
            val h = even(w.toDouble() * src.h / src.w).coerceAtLeast(2)
            val x = (hud.target.x * size.width).roundToInt().coerceIn(0, size.width - w)
            val y = (hud.target.y * size.height).roundToInt().coerceIn(0, (size.height - h).coerceAtLeast(0))
            statements += "[${p}hud$k]crop=${src.w}:${src.h}:${src.x}:${src.y},scale=$w:$h:flags=lanczos,setsar=1[${p}o$k]"
            statements += "[${p}l$k][${p}o$k]overlay=x=$x:y=$y:shortest=1[${p}l${k + 1}]"
        }
        statements += "[${p}l${overlays.size}]$tail"
        return statements
    }

    private fun audioChain(i: Int, clip: PlannedClip, selection: List<Int>?): String {
        val length = Durations.ffmpegSeconds(clip.range.length)
        val available = clip.media.audio.map { it.audioIndex }
        val streams = selection?.filter { it in available }?.ifEmpty { null } ?: available
        // Longueur audio forcée à celle du clip : sinon une piste plus courte décalerait la synchro des clips suivants.
        val tail = "aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo,apad,atrim=duration=$length,asetpts=PTS-STARTPTS[a$i]"
        return when (streams.size) {
            0 -> "anullsrc=r=48000:cl=stereo,$tail"
            1 -> "[$i:a:${streams[0]}]asetpts=PTS-STARTPTS,$tail"
            else -> streams.joinToString("") { "[$i:a:$it]" } + "amix=inputs=${streams.size}:normalize=0:duration=longest,$tail"
        }
    }

    fun geometry(format: OutputFormat, srcWidth: Int, srcHeight: Int, settings: EditSettings): String = when (format) {
        OutputFormat.SOURCE -> {
            val h = settings.sourceHeight
            val w = even(h.toDouble() * srcWidth / srcHeight)
            "scale=$w:$h:flags=lanczos,setsar=1"
        }

        OutputFormat.LANDSCAPE -> {
            val size = settings.landscape
            "scale=${size.width}:${size.height}:force_original_aspect_ratio=decrease:flags=lanczos," +
                "pad=${size.width}:${size.height}:(ow-iw)/2:(oh-ih)/2,setsar=1"
        }

        OutputFormat.VERTICAL -> {
            val size = settings.vertical.size
            val crop = cropBox(srcWidth, srcHeight, size.width.toDouble() / size.height, settings.vertical.cropRegion)
            "crop=${crop.w}:${crop.h}:${crop.x}:${crop.y},scale=${size.width}:${size.height}:flags=lanczos,setsar=1"
        }
    }

    /** Dimensions de l'image produite pour un format donné. */
    fun outputSize(format: OutputFormat, media: MediaInfo, settings: EditSettings): Pair<Int, Int> {
        val video = media.video ?: throw HighlightsException("${media.path} ne contient pas de flux vidéo")
        return when (format) {
            OutputFormat.SOURCE -> even(settings.sourceHeight.toDouble() * video.width / video.height) to settings.sourceHeight
            OutputFormat.LANDSCAPE -> settings.landscape.width to settings.landscape.height
            OutputFormat.VERTICAL -> settings.vertical.size.width to settings.vertical.size.height
        }
    }

    internal data class CropBox(val x: Int, val y: Int, val w: Int, val h: Int)

    /** Plus grand rectangle au ratio [aspect] inscrit dans la zone (ou l'image entière), centré sur elle, borné à l'image. */
    internal fun cropBox(srcWidth: Int, srcHeight: Int, aspect: Double, region: CropRegion?): CropBox {
        val rx = (region?.x ?: 0.0) * srcWidth
        val ry = (region?.y ?: 0.0) * srcHeight
        val rw = (region?.width ?: 1.0) * srcWidth
        val rh = (region?.height ?: 1.0) * srcHeight
        var h = rh
        var w = h * aspect
        if (w > rw) {
            w = rw
            h = w / aspect
        }
        val wi = even(w).coerceIn(2, even(srcWidth.toDouble()))
        val hi = even(h).coerceIn(2, even(srcHeight.toDouble()))
        val x = (rx + rw / 2 - wi / 2.0).roundToInt().coerceIn(0, srcWidth - wi)
        val y = (ry + rh / 2 - hi / 2.0).roundToInt().coerceIn(0, srcHeight - hi)
        return CropBox(x, y, wi, hi)
    }

    /** Zone normalisée → pixels pairs, bornés à l'image. */
    internal fun pixelBox(srcWidth: Int, srcHeight: Int, region: CropRegion): CropBox {
        val w = even(region.width * srcWidth).coerceIn(2, even(srcWidth.toDouble()))
        val h = even(region.height * srcHeight).coerceIn(2, even(srcHeight.toDouble()))
        val x = (region.x * srcWidth).roundToInt().coerceIn(0, srcWidth - w)
        val y = (region.y * srcHeight).roundToInt().coerceIn(0, srcHeight - h)
        return CropBox(x, y, w, h)
    }

    private fun even(v: Double) = (v.toInt() / 2) * 2

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
