package dev.highlights.editing

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.CardAnimation
import dev.highlights.core.model.CardBackground
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.IntroOutroSettings
import dev.highlights.core.model.SafeArea
import dev.highlights.core.serialization.Durations
import dev.highlights.editing.story.Captions
import dev.highlights.editing.story.VideoFonts
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/** Image d'une capture qui sert de fond flouté à une carte. */
data class CardStill(val file: Path, val at: Duration)

/**
 * Musique jouée sous l'ouverture d'un montage : ce qui précède [start] dans [file] (son amorce), au volume [volume]
 * qu'elle a dans le montage avant normalisation. Elle continue ainsi sans couture sur le premier plan.
 */
data class MusicLeadIn(val file: Path, val start: Duration, val volume: Double)

/** Les cartes d'un rendu : réglages résolus (chemins absolus) et ce que le rendu sait de l'image et des moments. */
data class CardRequest(
    val settings: IntroOutroSettings,
    val width: Int,
    val height: Int,
    val fps: Int,
    val safe: SafeArea = SafeArea.NONE,
    val introStill: CardStill? = null,
    val outroStill: CardStill? = null,
    val leadIn: MusicLeadIn? = null,
    /** Durée de l'ouverture imposée par le rendu (montage : un nombre entier de temps) ; null : celle des réglages. */
    val introLength: Duration? = null,
) {
    companion object {
        /** Cartes demandées par [edit] (null : aucune, ou rien à montrer). */
        fun of(
            edit: EditSettings, width: Int, height: Int,
            introStill: CardStill? = null, outroStill: CardStill? = null, leadIn: MusicLeadIn? = null, introLength: Duration? = null,
        ): CardRequest? {
            val settings = edit.intro?.takeIf { it.enabled && it.hasContent && (it.intro || it.outro) } ?: return null
            return CardRequest(settings, width, height, edit.fps, edit.safeArea, introStill, outroStill, leadIn, introLength)
        }
    }
}

/**
 * Cartes d'ouverture et de fin : un fond (couleur, image, ou image de la vidéo floutée et assombrie), le logo et le
 * pseudo dessous, animés selon [IntroOutroSettings.animation] (par défaut le logo apparaît en montant légèrement et le
 * pseudo « pop » un instant après ; sinon flash blanc aux coupes, zoom lent du fond, ou rien). Elles sont générées dans le même
 * graphe que le montage et concaténées autour de lui, avant la normalisation du son : pas de second encodage, et le
 * volume est jugé sur la vidéo entière.
 *
 * L'ouverture d'un montage kills joue sur l'amorce de la musique ([MusicLeadIn]) et dure un nombre entier de temps
 * ([beatLength]) : l'image et le son du montage reculent ensemble d'autant, la musique continue sans couture sur le
 * premier plan, et chaque kill reste sur son temps. La musique continue de même sous la carte de fin et s'y éteint
 * (le rendu passe sa suite : `outroMusic` d'[append]). Les cartes sont comptées en images, comme les plans du montage.
 *
 * Les textes et le logo restent dans la zone sûre de la plateforme ; le pseudo prend une police qui a tous ses
 * caractères ([VideoFonts.forText]).
 */
object IntroOutroCards {
    /** Labels du graphe après les cartes, et durée ajoutée. */
    data class Appended(val video: String, val audio: String, val added: Duration)

    private const val AUDIO_FORMAT = "aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"
    private val LOGO_FADE = 350.milliseconds
    private val NAME_DELAY = 150.milliseconds
    private val NAME_POP = 300.milliseconds
    private val FADE_FROM_BLACK = 250.milliseconds
    private val OUTRO_FADE_OUT = 500.milliseconds
    private val FLASH_OUT = 120.milliseconds
    private val FLASH_IN = 250.milliseconds

    /** Le fond de l'animation « zoom » avance de 8 % sur la durée de la carte : lent, mais visible sur 1 s. */
    private const val ZOOM_AMOUNT = 0.08

    /**
     * Durée d'une ouverture de [wanted] sur une musique dont un temps dure [period] : le nombre entier de temps le plus
     * proche, un au moins. La carte s'ouvre ainsi sur un temps et se ferme sur le premier temps du montage.
     */
    fun beatLength(wanted: Duration, period: Duration): Duration {
        if (!period.isPositive()) return wanted
        val beats = (wanted / period).roundToInt().coerceAtLeast(1)
        return period * beats
    }

    /** Fonds floutés des highlights : le moment le mieux noté en ouverture, le dernier en fin, pris à leur pic. */
    fun stills(clips: List<PlannedClip>): Pair<CardStill?, CardStill?> {
        val best = clips.maxByOrNull { it.highlight.score }
        val last = clips.lastOrNull()
        return best?.let { CardStill(it.media.path, it.highlight.peak) } to last?.let { CardStill(it.media.path, it.highlight.peak) }
    }

    /** Images d'une carte de [length] à [fps]. */
    fun frames(length: Duration, fps: Int): Int = (length.inWholeMicroseconds * fps / 1e6).roundToInt().coerceAtLeast(1)

    /** Durée exacte de [frames] images. */
    private fun duration(frames: Int, fps: Int): Duration = (frames * 1_000_000L / fps).microseconds

    /** Durée que les cartes de [request] ajoutent (en images entières). */
    fun added(request: CardRequest?): Duration {
        if (request == null) return Duration.ZERO
        val s = request.settings
        val intro = if (s.intro) duration(frames(request.introLength ?: s.introLength, request.fps), request.fps) else Duration.ZERO
        val outro = if (s.outro) duration(frames(s.outroLength, request.fps), request.fps) else Duration.ZERO
        return intro + outro
    }

    /**
     * Durée de la carte de fin de [request] (zéro sans elle) : la longueur de musique que le rendu prolonge au-delà de
     * la vidéo pour la passer en `outroMusic` à [append].
     */
    fun outroLength(request: CardRequest?): Duration {
        if (request == null || !request.settings.outro) return Duration.ZERO
        return duration(frames(request.settings.outroLength, request.fps), request.fps)
    }

    /**
     * Ajoute les entrées ([args]) et les filtres ([graph]) des cartes autour de la vidéo [video] et du son [audio]
     * (son pas encore normalisé). Sans carte, rend les labels tels quels. Les entrées sont ajoutées à la suite de
     * celles déjà dans [args].
     *
     * [outroMusic] : label d'un son qui prolonge la musique au-delà de la vidéo (déjà à son volume, au moins
     * [outroLength] de long), joué sous la carte de fin et éteint en fondu (choix du joueur, 20/11/2026). Sans lui
     * (highlights sans musique), la fin est muette.
     */
    fun append(
        args: MutableList<String>, graph: MutableList<String>, video: String, audio: String, request: CardRequest?,
        outroMusic: String? = null,
    ): Appended {
        if (request == null) return Appended(video, audio, Duration.ZERO)
        val s = request.settings
        val parts = mutableListOf<String>()
        if (s.intro) {
            val frames = frames(request.introLength ?: s.introLength, request.fps)
            val v = card(args, graph, "ioi", true, frames, request, request.introStill)
            val a = cardAudio(args, graph, "ioi", duration(frames, request.fps), request.leadIn)
            parts += "[$v][$a]"
        }
        // Horodatage refait échantillon par échantillon : le son mixé d'un montage (amix de plans décalés) a des
        // horodatages qui reculent parfois d'un rien, et concat refuse alors un segment sur deux (« Invalid data »).
        graph += "[$audio]$AUDIO_FORMAT,asetpts=N/SR/TB[iomaina]"
        // Flash : la vidéo sort du blanc où l'ouverture s'est fondue.
        val main = if (s.intro && s.animation == CardAnimation.FLASH) {
            graph += "[$video]fade=t=in:st=0:d=${sec(FLASH_OUT)}:color=white[iomainv]"
            "iomainv"
        } else {
            video
        }
        parts += "[$main][iomaina]"
        if (s.outro) {
            val frames = frames(s.outroLength, request.fps)
            val length = duration(frames, request.fps)
            val v = card(args, graph, "ioo", false, frames, request, request.outroStill)
            val a = outroMusic?.let { outroAudio(graph, it, length) } ?: cardAudio(args, graph, "ioo", length, null)
            parts += "[$v][$a]"
        }
        graph += parts.joinToString("") + "concat=n=${parts.size}:v=1:a=1[iov][ioa]"
        return Appended("iov", "ioa", added(request))
    }

    /**
     * Image PNG d'une carte (l'ouverture si [intro]), prise une fois le logo et le pseudo apparus : l'aperçu des
     * réglages dans l'application.
     */
    fun preview(request: CardRequest, intro: Boolean, filterScript: Path, output: Path, filterScriptOption: String = FfmpegService.FILTER_COMPLEX_FROM_FILE): RenderCommand {
        val s = request.settings
        val frames = frames(if (intro) request.introLength ?: s.introLength else s.outroLength, request.fps)
        val args = mutableListOf<String>()
        val graph = mutableListOf<String>()
        val v = card(args, graph, "iop", intro, frames, request, if (intro) request.introStill else request.outroStill)
        // Après l'apparition du pseudo, avant le fondu de fin.
        val shown = frames(NAME_DELAY + NAME_POP + 100.milliseconds, request.fps).coerceAtMost(frames - 1)
        graph += "[$v]select='eq(n\\,$shown)'[iopng]"
        args += listOf(filterScriptOption, filterScript.toString(), "-map", "[iopng]", "-frames:v", "1", "-update", "1", "-y", output.toString())
        return RenderCommand(FfmpegCommand(args, "aperçu de la carte ${if (intro) "d'ouverture" else "de fin"}"), graph.joinToString(";\n") + "\n", Duration.ZERO)
    }

    /** Une carte de [frames] images ; rend le label de sa vidéo. */
    private fun card(args: MutableList<String>, graph: MutableList<String>, p: String, intro: Boolean, frames: Int, r: CardRequest, still: CardStill?): String {
        val s = r.settings
        val w = r.width
        val h = r.height
        val fps = r.fps
        val length = duration(frames, fps)
        val cover = "scale=$w:$h:force_original_aspect_ratio=increase:flags=lanczos,crop=$w:$h,setsar=1"
        val image = s.image?.let(Path::of)?.takeIf { Files.isRegularFile(it) }
        // Image choisie : assombrie pour que le logo et le pseudo se lisent dessus.
        val dim = "eq=brightness=-0.12:saturation=0.8,vignette=a=PI/4"
        // Image de la vidéo : ramenée à une plage fixe (noir → gris moyen) quelle que soit la scène. Assombrie comme une
        // image choisie, une scène d'intérieur de WARDOGS (luminance moyenne 41) devenait un fond noir uni.
        val level = "normalize=blackpt=black:whitept=0x8C8C8C:smoothing=0:strength=0.85,eq=saturation=0.85,vignette=a=PI/6"
        val background = when {
            s.background == CardBackground.IMAGE && image != null -> {
                val i = input(args, listOf("-loop", "1", "-framerate", "$fps", "-t", sec(length), "-i", image.toString()))
                "[$i:v]$cover,$dim,fps=$fps"
            }

            s.background == CardBackground.BLUR && still != null -> {
                val i = input(args, listOf("-ss", Durations.ffmpegSecondsPrecise(still.at), "-t", "1", "-i", still.file.toString()))
                val sigma = (minOf(w, h) * 0.025).roundToInt().coerceAtLeast(4)
                "[$i:v]trim=end_frame=1,$cover,gblur=sigma=$sigma,$level,loop=loop=${frames - 1}:size=1"
            }

            else -> "color=c=0x${s.color.drop(1)}:s=${w}x$h:r=$fps"
        }
        val animation = s.animation
        // Zoom lent : le fond seul avance (point fixe au centre, voir ZoomCrop), le logo et le pseudo restent posés.
        val zoom = if (animation == CardAnimation.ZOOM) {
            "," + ZoomCrop.filters(w, h, "(1+$ZOOM_AMOUNT*n/$frames)", "bicubic").joinToString(",")
        } else {
            ""
        }
        graph += "$background,trim=end_frame=$frames,setpts=N/($fps*TB)$zoom,format=yuv420p,setsar=1[${p}bg]"

        val animated = animation == CardAnimation.RISE
        val layout = layout(s, r)
        var current = "${p}bg"
        layout.logo?.let { logo ->
            val i = input(args, listOf("-loop", "1", "-framerate", "$fps", "-t", sec(length), "-i", logo.file.toString()))
            val fade = sec(LOGO_FADE)
            val appear = if (animated) ",fade=t=in:st=0:d=$fade:alpha=1" else ""
            graph += "[$i:v]format=rgba,scale=${logo.width}:${logo.height}:flags=lanczos,setsar=1$appear[${p}logo]"
            // Le logo monte de 3 % de la hauteur en apparaissant.
            val rise = (h * 0.03).roundToInt()
            val y = if (animated) "'${logo.y}+$rise*(1-min(t/$fade\\,1))'" else "${logo.y}"
            graph += "[$current][${p}logo]overlay=x=${logo.x}:y=$y:shortest=1:format=auto[${p}l]"
            current = "${p}l"
        }

        val filters = mutableListOf<String>()
        layout.name?.let { name -> filters += drawName(name, s.textColor, h, animated) }
        filters += fades(animation, intro, length)
        graph += "[$current]" + (filters + "trim=end_frame=$frames").joinToString(",") + ",format=yuv420p,setsar=1[${p}v]"
        return "${p}v"
    }

    /**
     * Fondus d'une carte de [length] selon l'animation : sortie du noir (et retour au noir en fin) pour « monte » et
     * « zoom » ; flash blanc à la coupe avec la vidéo pour « flash » ; rien pour « aucune ».
     */
    private fun fades(animation: CardAnimation, intro: Boolean, length: Duration): List<String> {
        val fromBlack = "fade=t=in:st=0:d=${sec(FADE_FROM_BLACK)}"
        val toBlack = "fade=t=out:st=${sec((length - OUTRO_FADE_OUT).coerceAtLeast(Duration.ZERO))}:d=${sec(minOf(OUTRO_FADE_OUT, length))}"
        return when (animation) {
            CardAnimation.NONE -> emptyList()
            CardAnimation.FLASH -> if (intro) {
                listOf(fromBlack, "fade=t=out:st=${sec((length - FLASH_OUT).coerceAtLeast(Duration.ZERO))}:d=${sec(minOf(FLASH_OUT, length))}:color=white")
            } else {
                listOf("fade=t=in:st=0:d=${sec(FLASH_IN)}:color=white", toBlack)
            }
            CardAnimation.RISE, CardAnimation.ZOOM -> if (intro) listOf(fromBlack) else listOf(fromBlack, toBlack)
        }
    }

    /**
     * Son de la carte de fin : la suite de la musique ([music]), tenue un tiers de la carte puis éteinte en fondu
     * jusqu'à la dernière image.
     */
    private fun outroAudio(graph: MutableList<String>, music: String, length: Duration): String {
        val total = sec(length)
        val hold = length / 3
        graph += "[$music]asetpts=PTS-STARTPTS,$AUDIO_FORMAT,apad=whole_dur=$total,atrim=duration=$total," +
            "afade=t=out:st=${sec(hold)}:d=${sec(length - hold)}[iooa]"
        return "iooa"
    }

    /** Son d'une carte : l'amorce de la musique (ouverture d'un montage) ou du silence. */
    private fun cardAudio(args: MutableList<String>, graph: MutableList<String>, p: String, length: Duration, leadIn: MusicLeadIn?): String {
        val total = sec(length)
        if (leadIn == null || !leadIn.start.isPositive()) {
            graph += "anullsrc=r=48000:cl=stereo,$AUDIO_FORMAT,atrim=duration=$total[${p}a]"
            return "${p}a"
        }
        // La musique avant son départ dans le montage ; si elle commence trop tôt, du silence devant.
        val from = (leadIn.start - length).coerceAtLeast(Duration.ZERO)
        val silence = length - (leadIn.start - from)
        val i = input(args, listOf("-ss", Durations.ffmpegSecondsPrecise(from), "-t", Durations.ffmpegSecondsPrecise(leadIn.start - from), "-i", leadIn.file.toString()))
        val delay = silence.inWholeMilliseconds
        // Montée sur la première moitié : la musique arrive, puis tient son niveau jusqu'au premier plan.
        val fadeIn = sec((length - silence) / 2)
        graph += "[$i:a]asetpts=PTS-STARTPTS,$AUDIO_FORMAT," + (if (delay > 0) "adelay=$delay:all=1," else "") +
            "volume=${num(leadIn.volume)},afade=t=in:st=${sec(silence)}:d=$fadeIn,apad=whole_dur=$total,atrim=duration=$total[${p}a]"
        return "${p}a"
    }

    /**
     * Pseudo centré dans la zone sûre ; [animated] : il « pop » (0,8 → 1) en apparaissant un instant après le logo,
     * sinon il est posé d'emblée.
     */
    private fun drawName(name: NameBox, color: String, height: Int, animated: Boolean): String {
        val t0 = sec(NAME_DELAY)
        val pop = sec(NAME_POP)
        val size = if (animated) "'${name.size}*(0.8+0.2*min(max((t-$t0)/$pop\\,0)\\,1))'" else "${name.size}"
        val alpha = if (animated) ":alpha='min(max((t-$t0)/$pop\\,0)\\,1)'" else ""
        val border = (height * 0.005).roundToInt().coerceAtLeast(2)
        return "drawtext=fontfile='${VideoFonts.drawtextPath(name.font)}':text='${name.text}':expansion=none:fontsize=$size" +
            ":fontcolor=0x${color.drop(1)}:borderw=$border:bordercolor=black@0.7:shadowx=0:shadowy=${border + 1}:shadowcolor=black@0.5" +
            ":x='${name.left}+(${name.room}-text_w)/2':y='${name.centerY}-th/2'$alpha"
    }

    internal data class LogoBox(val file: Path, val width: Int, val height: Int, val x: Int, val y: Int)

    internal data class NameBox(val text: String, val font: String, val size: Int, val left: Int, val room: Int, val centerY: Int)

    internal data class Layout(val logo: LogoBox?, val name: NameBox?)

    /**
     * Place du logo et du pseudo : le bloc (logo, écart, pseudo) centré dans la zone sûre. Le logo tient dans 70 % de
     * la largeur utile et 34 % du petit côté de l'image (42 % sans pseudo) ; le pseudo fait 10 % du petit côté, réduit
     * s'il est trop large.
     */
    internal fun layout(s: IntroOutroSettings, r: CardRequest): Layout {
        val w = r.width
        val h = r.height
        val safe = r.safe
        val short = minOf(w, h)
        val left = (safe.left * w).roundToInt()
        val room = (safe.width * w).roundToInt()
        val text = s.name?.let { Captions.sanitize(it, uppercase = false) }?.takeIf { it.isNotBlank() }
        val logoFile = s.logo?.let(Path::of)?.takeIf { Files.isRegularFile(it) }
        val font = text?.let { VideoFonts.forText(s.font, it) }
        val size = text?.let { floor(Captions.fit(font!!, short * 0.10, it, room)).toInt().coerceAtLeast(12) }
        val gap = if (text != null && logoFile != null) (short * 0.04).roundToInt() else 0
        val logoSize = logoFile?.let { file ->
            val (iw, ih) = imageSize(file) ?: (1 to 1)
            val maxW = room * 0.7
            val maxH = short * (if (text != null) 0.34 else 0.42)
            val k = minOf(maxW / iw, maxH / ih)
            even(iw * k) to even(ih * k)
        }
        val textHeight = size?.let { (it * 1.2).roundToInt() } ?: 0
        val block = (logoSize?.second ?: 0) + gap + textHeight
        val center = ((safe.top + 1.0 - safe.bottom) / 2 * h).roundToInt()
        val top = (center - block / 2).coerceAtLeast((safe.top * h).roundToInt())
        val logo = logoSize?.let { (lw, lh) -> LogoBox(logoFile, lw, lh, left + (room - lw) / 2, top) }
        val name = text?.let { NameBox(it, font!!, size!!, left, room, top + (logoSize?.second ?: 0) + gap + textHeight / 2) }
        return Layout(logo, name)
    }

    /** Largeur et hauteur d'une image, lues sans la décoder entièrement ; null si Java ne sait pas la lire. */
    private fun imageSize(file: Path): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(file.toFile()).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return null
            try {
                reader.input = stream
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    /** Ajoute une entrée à [args] et rend son numéro. */
    private fun input(args: MutableList<String>, input: List<String>): Int {
        val index = args.count { it == "-i" }
        args += input
        return index
    }

    private fun even(v: Double) = ((v.toInt() / 2) * 2).coerceAtLeast(2)

    private fun sec(d: Duration) = Durations.ffmpegSeconds(d)

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}
