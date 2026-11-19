package dev.highlights.editing

import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.WebcamCorner
import dev.highlights.core.model.WebcamFeed
import dev.highlights.core.serialization.Durations
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Webcam en incrustation dans les graphes FFmpeg (réglages : [dev.highlights.core.model.WebcamSettings]).
 *
 * Une entrée FFmpeg de plus par plan : la webcam lue sur le même extrait que la capture, décalé de [WebcamFeed.offset].
 * [RenderCommandBuilder.videoChain] la ramène à la taille de son emplacement ([target]) et :
 * - rendu simple (pas d'effet qui bouge l'image) : la colle directement à sa place ;
 * - story et montage kills (`pinHud`) : l'empile sous l'image, comme le HUD recollé ([HudPin]). Ralentis, gels et coupes
 *   du plan s'appliquent à toute la trame : la webcam suit exactement la chronologie du jeu (au ralenti avec lui).
 *   [detach] l'en sort avant les effets, [finish] lui donne la même longueur en images que le plan, et [overlay] la
 *   pose sur la vidéo concaténée. Zoom, secousse, flous, whip pan, flash et couleurs boostées restent ainsi sur le jeu ;
 *   une webcam collée dans l'image zoomait avec elle, et un flash blanc effaçait le visage du joueur.
 *
 * En 9:16 le jeu est recadré plus large (sur la hauteur laissée par le bandeau) et posé sous le bandeau
 * ([RenderCommandBuilder.geometry]) ; le HUD recollé et le point visé par les zooms suivent ce nouveau cadre.
 */
object WebcamLayout {
    /** En deçà, un extrait ne voit presque rien de la webcam : pas d'entrée (une entrée vide finirait le plan). */
    private val MIN_OVERLAP = 100.milliseconds

    /**
     * Entrée FFmpeg [index] d'une webcam pour un plan ; [lead] : début du plan sans webcam (elle a commencé après) ;
     * [box] : emplacement imposé, le même pour tous les plans d'un rendu (sinon calculé sur la capture du plan).
     */
    data class Input(val index: Int, val feed: WebcamFeed, val lead: Duration, val box: RenderCommandBuilder.CropBox? = null)

    /**
     * Ajoute à [args] l'entrée de la webcam pour l'extrait [start] + [length] de la capture [media] (temps de la
     * capture d'origine, pas de sa pré-découpe). null : webcam éteinte, pas de webcam pour cette capture, ou aucune
     * image de webcam sur l'extrait.
     */
    fun input(
        args: MutableList<String>,
        feeds: Map<Path, WebcamFeed>,
        media: MediaInfo,
        start: Duration,
        length: Duration,
        settings: EditSettings,
        box: RenderCommandBuilder.CropBox? = null,
    ): Input? {
        if (!settings.webcam.enabled) return null
        val feed = feeds[media.path] ?: return null
        val from = start + feed.offset
        if (from >= feed.duration - MIN_OVERLAP || from + length <= MIN_OVERLAP) return null
        val lead = if (from.isNegative()) -from else Duration.ZERO
        // Rang de l'entrée = nombre d'entrées déjà posées : toutes passent par « -i ».
        val index = args.count { it == "-i" }
        args += listOf(
            "-ss", Durations.ffmpegSecondsPrecise(from.coerceAtLeast(Duration.ZERO)),
            "-t", Durations.ffmpegSeconds(length - lead),
            "-i", feed.file.toString(),
        )
        return Input(index, feed, lead, box)
    }

    /**
     * Emplacement de la webcam dans une image [width] × [height] du [format] ; null si elle est éteinte. Le coin est pris
     * dans l'image du jeu de [media] : une capture 21:9 en 16:9 a des bandes noires en haut et en bas, et une webcam
     * calée sur le cadre chevauchait la bande du bas (vu sur WARDOGS, 19/11 : à cheval sur la mini-carte et le noir).
     */
    fun target(format: OutputFormat, width: Int, height: Int, settings: EditSettings, media: MediaInfo? = null): RenderCommandBuilder.CropBox? {
        val cam = settings.webcam
        if (!cam.enabled) return null
        val band = cam.bandHeight(format, height)
        if (band > 0) return RenderCommandBuilder.CropBox(0, 0, width, band)
        val area = gameArea(format, width, height, media)
        val w = even(cam.size * area.w).coerceIn(2, area.w)
        val h = even(w / cam.aspect).coerceIn(2, area.h)
        val m = (cam.margin * area.w).roundToInt()
        val left = cam.corner == WebcamCorner.TOP_LEFT || cam.corner == WebcamCorner.BOTTOM_LEFT
        val top = cam.corner == WebcamCorner.TOP_LEFT || cam.corner == WebcamCorner.TOP_RIGHT
        val x = (if (left) area.x + m else area.x + area.w - w - m).coerceIn(0, width - w)
        val y = (if (top) area.y + m else area.y + area.h - h - m).coerceIn(0, height - h)
        return RenderCommandBuilder.CropBox(x, y, w, h)
    }

    /** Partie de l'image occupée par le jeu : en 16:9, la capture ramenée dedans sans la déformer (bandes noires autour). */
    private fun gameArea(format: OutputFormat, width: Int, height: Int, media: MediaInfo?): RenderCommandBuilder.CropBox {
        val video = media?.video
        if (format != OutputFormat.LANDSCAPE || video == null || video.width <= 0 || video.height <= 0) {
            return RenderCommandBuilder.CropBox(0, 0, width, height)
        }
        val scale = minOf(width.toDouble() / video.width, height.toDouble() / video.height)
        val w = even(video.width * scale).coerceIn(2, width)
        val h = even(video.height * scale).coerceIn(2, height)
        return RenderCommandBuilder.CropBox((width - w) / 2, (height - h) / 2, w, h)
    }

    /**
     * Filtres de l'entrée de la webcam jusqu'à une image de la taille de [box] : même cadence que le jeu, recadrée au
     * rapport de l'emplacement (sur [dev.highlights.core.model.WebcamSettings.crop] si donnée), liseré, noir avant son
     * début, puis dernière image répétée sans fin (la trame s'arrête avec le jeu, jamais avant lui).
     */
    fun piece(input: Input, box: RenderCommandBuilder.CropBox, format: OutputFormat, settings: EditSettings): String {
        val cam = settings.webcam
        val feed = input.feed
        val band = format == OutputFormat.VERTICAL
        val b = cam.border
        // Bandeau : liseré sous lui seulement (il sépare la webcam du jeu) ; coin : tout autour.
        val innerW = if (band || b == 0) box.w else (box.w - 2 * b).coerceAtLeast(2)
        val innerH = (if (b == 0) box.h else if (band) box.h - b else box.h - 2 * b).coerceAtLeast(2)
        val crop = RenderCommandBuilder.cropBox(feed.width, feed.height, innerW.toDouble() / innerH, cam.crop)
        val filters = mutableListOf(
            "setpts=PTS-STARTPTS",
            "fps=${settings.fps}",
            "crop=${crop.w}:${crop.h}:${crop.x}:${crop.y}",
            "scale=${even(innerW.toDouble())}:${even(innerH.toDouble())}:flags=lanczos",
            "setsar=1",
        )
        if (b > 0) {
            val color = cam.borderColor.replace(":", "\\:")
            filters += if (band) "pad=${box.w}:${box.h}:0:0:color=$color" else "pad=${box.w}:${box.h}:$b:$b:color=$color"
        }
        if (input.lead.isPositive()) filters += "tpad=start_duration=${Durations.ffmpegSeconds(input.lead)}:color=black"
        filters += "tpad=stop_mode=clone:stop=-1"
        return "[${input.index}:v:${feed.stream}]" + filters.joinToString(",")
    }

    /**
     * Sort la webcam de la trame empilée [base] (image [width] × [height], puis [hudRows] lignes de HUD, puis la webcam
     * calée à gauche). Renvoie la trame sans la webcam (prête pour [HudPin], ou l'image seule sans HUD) et la webcam ;
     * [base] tel quel et null si le plan n'a pas de webcam empilée.
     */
    fun detach(
        graph: MutableList<String>,
        base: String,
        input: Input?,
        box: RenderCommandBuilder.CropBox?,
        hudRows: Int,
        width: Int,
        height: Int,
        prefix: String,
    ): Pair<String, String?> {
        if (input == null || box == null) return base to null
        graph += "[$base]split=2[${prefix}m][${prefix}w]"
        graph += "[${prefix}m]crop=$width:${height + hudRows}:0:0[${prefix}g]"
        graph += "[${prefix}w]crop=${box.w}:${box.h}:0:${height + hudRows}[${prefix}c]"
        return "${prefix}g" to "${prefix}c"
    }

    /**
     * Webcam d'un plan à sa longueur finale : [pre] (gel du début, comme le jeu), puis [frames] images posées sur la
     * grille de sortie, comme le plan lui-même ; sans webcam ([cam] null), une image transparente de la même longueur.
     */
    fun finish(
        graph: MutableList<String>,
        cam: String?,
        box: RenderCommandBuilder.CropBox,
        pre: List<String>,
        frames: Int,
        fps: Int,
        out: String,
    ) {
        val tail = "trim=end_frame=$frames,setpts=N/($fps*TB),setsar=1,format=yuva420p,settb=AVTB[$out]"
        graph += if (cam == null) {
            "color=c=black@0.0:s=${box.w}x${box.h}:r=$fps,format=yuva420p,$tail"
        } else {
            "[$cam]" + (pre + "tpad=stop_mode=clone:stop=-1" + "setpts=PTS-STARTPTS").joinToString(",") + ",$tail"
        }
    }

    /**
     * Pose les webcams des plans ([pieces], une par plan, dans l'ordre) sur la vidéo concaténée [video]. Renvoie le
     * label à utiliser à la place de [video] ([video] lui-même s'il n'y a rien à poser).
     */
    fun overlay(graph: MutableList<String>, video: String, pieces: List<String>, box: RenderCommandBuilder.CropBox?): String {
        if (box == null || pieces.isEmpty()) return video
        graph += pieces.joinToString("") { "[$it]" } + "concat=n=${pieces.size}:v=1:a=0[wcat]"
        graph += "[$video][wcat]overlay=x=${box.x}:y=${box.y}:shortest=1[wcam]"
        return "wcam"
    }

    /**
     * Réglages d'un rendu au [format] : en 9:16 avec bandeau, le haut de la zone sûre descend sous le bandeau, pour
     * que textes, sous-titres et compteur de kills restent sur le jeu au lieu de passer sous la webcam.
     */
    fun settingsFor(settings: EditSettings, format: OutputFormat): EditSettings {
        val cam = settings.webcam
        if (!cam.enabled || format != OutputFormat.VERTICAL) return settings
        val top = minOf(cam.band + 0.01, 0.45)
        if (settings.safeArea.top >= top) return settings
        return settings.copy(safeArea = settings.safeArea.copy(top = top))
    }

    private fun even(v: Double) = (v.toInt() / 2) * 2
}
