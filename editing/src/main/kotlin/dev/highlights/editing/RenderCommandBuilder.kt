package dev.highlights.editing

import dev.highlights.core.i18n.Message
import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.GradeSettings
import dev.highlights.core.model.HudOverlay
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScreenGeometry
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
    /** Indices de pistes imposés par le profil ; vide = rôles déduits de la capture. */
    val audioLayout: AudioLayout = AudioLayout(),
    /** Option de lecture du graphe depuis un fichier, selon la version de FFmpeg (voir [FfmpegService.filterScriptOption]). */
    val filterScriptOption: String = FfmpegService.FILTER_COMPLEX_FROM_FILE,
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

        // Taille commune, prise sur le premier clip : les captures d'une autre taille ou d'un autre ratio (1440p, ultrawide)
        // y sont ramenées avec des bandes noires, sinon la concaténation échouerait.
        val (w, h) = outputSize(request.format, clips.first().media, settings)
        val graph = mutableListOf<String>()
        clips.forEachIndexed { i, clip ->
            if (outputSize(request.format, clip.media, settings) == w to h) {
                graph += videoChain(i, clip.media, request.format, settings, "v$i")
            } else {
                graph += videoChain(i, clip.media, request.format, settings, "g$i")
                graph += "[g$i]scale=$w:$h:force_original_aspect_ratio=decrease:flags=lanczos,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1[v$i]"
            }
            graph += audioChain(i, clip, settings, request.audioLayout)
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
        // Format imposé juste avant l'encodeur : xfade et concat peuvent sinon négocier du 4:4:4 (selon la version de
        // FFmpeg), que le profil « high » de x264 refuse.
        graph += "[$videoOut]format=yuv420p[vout]"

        args += listOf(request.filterScriptOption, request.filterScript.toString())
        args += listOf("-map", "[vout]", "-map", "[aout]")
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
    fun buildPreview(
        media: MediaInfo,
        at: Duration,
        format: OutputFormat,
        settings: EditSettings,
        filterScript: Path,
        output: Path,
        filterScriptOption: String = FfmpegService.FILTER_COMPLEX_FROM_FILE,
    ): RenderCommand {
        val args = inputArgs(null, at, 1.seconds, media.path).toMutableList()
        val graph = videoChain(0, media, format, settings, "vout")
        args += listOf(filterScriptOption, filterScript.toString(), "-map", "[vout]", "-frames:v", "1", "-update", "1", "-y", output.toString())
        return RenderCommand(FfmpegCommand(args, "aperçu ${format.label} à ${Durations.format(at)}"), graph.joinToString(";\n") + "\n", Duration.ZERO)
    }

    private fun inputArgs(hwaccel: String?, start: Duration, length: Duration, source: Path): List<String> =
        (hwaccel?.let { listOf("-hwaccel", it) } ?: emptyList()) + listOf(
            "-ss", Durations.ffmpegSecondsPrecise(start),
            "-t", Durations.ffmpegSeconds(length),
            "-i", source.toString(),
        )

    /**
     * Chaîne vidéo d'une entrée jusqu'au label [out] : géométrie du format, cadence, HUD éventuel, étalonnage.
     *
     * [pinHud] : le HUD recollé n'est pas collé dans l'image mais empilé dessous, dans la même trame (image 9:16 puis
     * chaque morceau du HUD, calé à gauche, l'un sous l'autre : voir [HudPin]). Il suit ainsi exactement les mêmes
     * ralentis et gels que l'image, et [HudPin] le recolle fixe après le zoom. Collé avant, le zoom l'agrandissait :
     * le bloc du bas de VALORANT apparaissait en double sous sa copie fixe.
     */
    fun videoChain(input: Int, media: MediaInfo, format: OutputFormat, settings: EditSettings, out: String, pinHud: Boolean = false): List<String> {
        val video = media.video ?: throw HighlightsException(Message("error.input.noVideo", "file" to media.path))
        val head = "[$input:v:0]setpts=PTS-STARTPTS,fps=${settings.fps}"
        // L'étalonnage vient après le recadrage et le HUD : il s'applique à l'image finale, avant les effets du montage.
        val tail = "${grade(settings.grade)}format=yuv420p,settb=AVTB[$out]"

        val overlays = if (format == OutputFormat.VERTICAL) settings.vertical.hud.filter { it.enabled } else emptyList()
        if (overlays.isEmpty()) {
            return listOf("$head,${geometry(format, video.width, video.height, settings)},$tail")
        }

        val p = "c$input"
        val statements = mutableListOf<String>()
        statements += "$head,split=${overlays.size + 1}[${p}base]" + overlays.indices.joinToString("") { "[${p}hud$it]" }
        statements += "[${p}base]${geometry(format, video.width, video.height, settings)}[${p}l0]"
        val boxes = hudBoxes(video.width, video.height, settings, overlays)
        val width = settings.vertical.size.width
        boxes.forEachIndexed { k, (src, dst) ->
            val piece = "[${p}hud$k]crop=${src.w}:${src.h}:${src.x}:${src.y},scale=${dst.w}:${dst.h}:flags=lanczos,setsar=1"
            if (pinHud) {
                statements += "$piece,pad=$width:${dst.h}:0:0[${p}o$k]"
            } else {
                statements += "$piece[${p}o$k]"
                statements += "[${p}l$k][${p}o$k]overlay=x=${dst.x}:y=${dst.y}:shortest=1[${p}l${k + 1}]"
            }
        }
        statements += if (pinHud) {
            "[${p}l0]" + boxes.indices.joinToString("") { "[${p}o$it]" } + "vstack=inputs=${boxes.size + 1}:shortest=1,$tail"
        } else {
            "[${p}l${overlays.size}]$tail"
        }
        return statements
    }

    /**
     * Emplacements du HUD recollé (`vertical.hud`) dans l'image de sortie du [format] : là où [videoChain] les colle.
     * Vide hors 9:16 ou sans HUD. Sert à les recoller par-dessus un zoom ([HudPin]) : ils restent fixes.
     */
    fun hudTargets(format: OutputFormat, media: MediaInfo, settings: EditSettings): List<CropBox> {
        val video = media.video ?: return emptyList()
        if (format != OutputFormat.VERTICAL) return emptyList()
        return hudBoxes(video.width, video.height, settings, settings.vertical.hud.filter { it.enabled }).map { it.second }
    }

    /** Pour chaque élément du HUD : zone lue dans la capture, et zone où il est collé dans l'image 9:16. */
    private fun hudBoxes(srcWidth: Int, srcHeight: Int, settings: EditSettings, overlays: List<HudOverlay>): List<Pair<CropBox, CropBox>> {
        val size = settings.vertical.size
        return overlays.map { hud ->
            val region = ScreenGeometry.forVideo(hud.source, settings.vertical.reference, srcWidth, srcHeight, hud.anchor)
            val src = pixelBox(srcWidth, srcHeight, region)
            val w = even(hud.target.width * size.width)
            val h = even(w.toDouble() * src.h / src.w).coerceAtLeast(2)
            val x = (hud.target.x * size.width).roundToInt().coerceIn(0, size.width - w)
            val y = (hud.target.y * size.height).roundToInt().coerceIn(0, (size.height - h).coerceAtLeast(0))
            src to CropBox(x, y, w, h)
        }
    }

    private fun audioChain(i: Int, clip: PlannedClip, settings: EditSettings, layout: AudioLayout): String {
        val length = Durations.ffmpegSeconds(clip.range.length)
        val streams = settings.audioIndices(AudioTracks.of(clip.media.audio, layout))
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
            val region = settings.vertical.cropRegion
                ?.let { ScreenGeometry.forVideo(it, settings.vertical.reference, srcWidth, srcHeight) }
            val crop = cropBox(srcWidth, srcHeight, size.width.toDouble() / size.height, region)
            "crop=${crop.w}:${crop.h}:${crop.x}:${crop.y},scale=${size.width}:${size.height}:flags=lanczos,setsar=1"
        }
    }

    /**
     * Pixels de l'image produite par pixel de la capture : ce qui se déplace de 10 pixels dans la capture se déplace de
     * 10 × ce facteur dans la sortie (recadrage 9:16 compris, qui grossit l'image).
     */
    fun pixelScale(format: OutputFormat, media: MediaInfo, settings: EditSettings): Double {
        val video = media.video ?: throw HighlightsException(Message("error.input.noVideo", "file" to media.path))
        return when (format) {
            OutputFormat.SOURCE -> settings.sourceHeight.toDouble() / video.height
            OutputFormat.LANDSCAPE -> minOf(settings.landscape.width.toDouble() / video.width, settings.landscape.height.toDouble() / video.height)
            OutputFormat.VERTICAL -> {
                val size = settings.vertical.size
                val region = settings.vertical.cropRegion
                    ?.let { ScreenGeometry.forVideo(it, settings.vertical.reference, video.width, video.height) }
                size.height.toDouble() / cropBox(video.width, video.height, size.width.toDouble() / size.height, region).h
            }
        }
    }

    /** Dimensions de l'image produite pour un format donné. */
    fun outputSize(format: OutputFormat, media: MediaInfo, settings: EditSettings): Pair<Int, Int> {
        val video = media.video ?: throw HighlightsException(Message("error.input.noVideo", "file" to media.path))
        return when (format) {
            OutputFormat.SOURCE -> even(settings.sourceHeight.toDouble() * video.width / video.height) to settings.sourceHeight
            OutputFormat.LANDSCAPE -> settings.landscape.width to settings.landscape.height
            OutputFormat.VERTICAL -> settings.vertical.size.width to settings.vertical.size.height
        }
    }

    data class CropBox(val x: Int, val y: Int, val w: Int, val h: Int)

    /** Plus grand rectangle au ratio [aspect] inscrit dans la zone (ou l'image entière), centré sur elle, borné à l'image. */
    /**
     * Réticule (centre de la capture) dans l'image de sortie du [format], en fractions de sa largeur et de sa hauteur :
     * le point que visent les zooms. Au centre sauf en 9:16 quand le recadrage n'est pas centré sur lui (zone
     * `vertical.cropRegion` décalée, par exemple sous le bandeau du haut).
     */
    fun crosshair(format: OutputFormat, media: MediaInfo, settings: EditSettings): Pair<Double, Double> = sourcePoint(format, media, settings, 0.5)

    /**
     * Point que visent les zooms, en fractions du cadre de sortie : le réticule ([crosshair]), ou en 9:16 la hauteur
     * `vertical.zoomFocus` de la capture si le profil en donne une.
     */
    fun zoomFocus(format: OutputFormat, media: MediaInfo, settings: EditSettings): Pair<Double, Double> =
        sourcePoint(format, media, settings, if (format == OutputFormat.VERTICAL) settings.vertical.zoomFocus ?: 0.5 else 0.5)

    /** Point de la capture (centre en largeur, hauteur [sourceY]) reporté dans le cadre de sortie du [format]. */
    private fun sourcePoint(format: OutputFormat, media: MediaInfo, settings: EditSettings, sourceY: Double): Pair<Double, Double> {
        val video = media.video ?: return 0.5 to 0.5
        if (format != OutputFormat.VERTICAL) return 0.5 to 0.5
        val size = settings.vertical.size
        val region = settings.vertical.cropRegion?.let { ScreenGeometry.forVideo(it, settings.vertical.reference, video.width, video.height) }
        val box = cropBox(video.width, video.height, size.width.toDouble() / size.height, region)
        val x = ((video.width / 2.0 - box.x) / box.w).coerceIn(0.0, 1.0)
        val y = ((video.height * sourceY - box.y) / box.h).coerceIn(0.0, 1.0)
        return x to y
    }

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

    /**
     * Filtres d'étalonnage, terminés par une virgule (chaîne vide si l'image reste telle quelle) : table de
     * correspondance, puis saturation et contraste, puis assombrissement des bords.
     */
    fun grade(settings: GradeSettings): String {
        if (settings.isNeutral) return ""
        val filters = mutableListOf<String>()
        settings.lut?.let { filters += "lut3d=file='${it.replace("\\", "/").replace(":", "\\:")}'" }
        // Vibrance d'abord : elle juge ce qui est terne sur les couleurs d'origine, avant que eq ne les pousse toutes.
        if (settings.vibrance != 0.0) filters += "vibrance=intensity=${fmt2(settings.vibrance)}"
        if (settings.saturation != 1.0 || settings.contrast != 1.0 || settings.brightness != 0.0) {
            val brightness = if (settings.brightness != 0.0) ":brightness=${fmt2(settings.brightness)}" else ""
            filters += "eq=saturation=${fmt2(settings.saturation)}:contrast=${fmt2(settings.contrast)}$brightness"
        }
        // L'angle de l'objectif pilote la force du vignettage : plus il est ouvert, plus les bords tombent.
        if (settings.vignette > 0) filters += "vignette=a=${fmt2(settings.vignette * Math.PI / 4)}"
        return filters.joinToString(",") + ","
    }

    private fun even(v: Double) = (v.toInt() / 2) * 2

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun fmt2(v: Double) = String.format(Locale.ROOT, "%.3f", v)
}
