package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.GradeSettings
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.toTimecode
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.story.TextMeasure
import dev.highlights.editing.story.VideoFonts
import dev.highlights.export.ExportReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Habillage de la miniature : trois au choix, [VIVID] par défaut ([DEFAULT], choisi par le joueur sur la planche des trois).
 * [zoom] : punch-in sur le centre de l'écran (le réticule) ; [grade] : couleurs ; texte : hauteur relative
 * [textSize] (bornée par la largeur), couleur, hauteur de sa base [textY] (fraction de l'image).
 */
@Serializable
enum class ThumbnailStyle(val zoom: Double, val grade: GradeSettings, val textSize: Double, val textColor: String, val textY: Double) {
    /** Image presque telle quelle, petit texte blanc en bas. */
    @SerialName("sober")
    SOBER(1.15, GradeSettings(saturation = 1.08, contrast = 1.05), 0.11, "white", 0.90),

    /** Couleurs boostées comme les kill montages de TikTok (réglages de `ColorBoost`), bords assombris, texte jaune. */
    @SerialName("vivid")
    VIVID(1.3, GradeSettings(vibrance = 0.5, saturation = 1.2, contrast = 1.12, brightness = -0.02, vignette = 0.35), 0.14, "0xFFD400", 0.88),

    /** Couleurs boostées, texte aussi large que l'image en haut : lisible en vignette de 168 px de large. */
    @SerialName("big")
    BIG_TEXT(1.3, GradeSettings(vibrance = 0.5, saturation = 1.2, contrast = 1.12, brightness = -0.02, vignette = 0.2), 0.30, "white", 0.34),
    ;

    val id: String get() = name.lowercase().let { if (this == BIG_TEXT) "big" else it }

    companion object {
        /** Habillage par défaut, partout (application, ligne de commande, exports) : le vif, choisi par le joueur le 18/11. */
        val DEFAULT: ThumbnailStyle get() = VIVID

        fun parse(text: String): ThumbnailStyle? = entries.firstOrNull { it.id == text.trim().lowercase() }
    }
}

/** Moment candidat à la miniature : une capture, l'instant du kill (dans la capture) et ce qui en fait la force. */
data class ThumbnailMoment(
    val source: Path,
    val kill: Duration,
    val kills: Int,
    val ace: Boolean = false,
    val clutch: Boolean = false,
    val headshots: Int = 0,
    /** Kill posé sur la drop de la musique : celui que le montage met déjà en avant. */
    val onDrop: Boolean = false,
    /** Texte imposé (montage des morts : celui de la mort, OUPS, RIP…) ; null : choisi par [ThumbnailPicker.label]. */
    val text: String? = null,
)

/**
 * Le moment de la miniature : celui qui ferait cliquer, avec les mêmes critères que l'accroche du titre YouTube
 * (`YouTubeText.hook`). Force = kills du groupe + 3 pour un ace + 2 pour un clutch + 0,3 par tir à la tête + 0,5 pour
 * le kill posé sur la drop ; à égalité, le premier de la vidéo (l'accroche, ou le premier vu).
 */
object ThumbnailPicker {
    fun strength(m: ThumbnailMoment): Double =
        m.kills + (if (m.ace) ACE else 0.0) + (if (m.clutch) CLUTCH else 0.0) + HEADSHOT * m.headshots + (if (m.onDrop) DROP else 0.0)

    fun best(moments: List<ThumbnailMoment>): ThumbnailMoment? = contenders(moments).firstOrNull()

    /**
     * Les plus forts, à [TIE] près de la force du meilleur, dans l'ordre (le plus fort d'abord, puis le premier vu), au
     * plus [MAX_CONTENDERS] : entre eux, c'est l'image qui décide ([ThumbnailFrames.readability]). Sur une soirée de
     * Wardogs, tous les kills sont seuls : sans ça, le premier kill retenu tombait dans un couloir presque noir.
     */
    fun contenders(moments: List<ThumbnailMoment>): List<ThumbnailMoment> {
        val ranked = moments.withIndex().sortedWith(compareByDescending<IndexedValue<ThumbnailMoment>> { strength(it.value) }.thenBy { it.index }).map { it.value }
        val top = ranked.firstOrNull()?.let(::strength) ?: return emptyList()
        return ranked.filter { strength(it) >= top - TIE }.take(MAX_CONTENDERS)
    }

    /** Plans d'un montage kills (hors ouverture vitrine) ; l'instant retenu est le dernier kill, celui qui clôt la série. */
    fun fromMontage(report: MontageReport): List<ThumbnailMoment> = report.clips.filter { !it.showcase && it.kills.isNotEmpty() }.mapNotNull { c ->
        val last = c.kills.mapNotNull { Durations.parseOrNull(it) }.maxOrNull() ?: return@mapNotNull null
        ThumbnailMoment(Path(c.source), last, c.kills.size, c.ace, c.clutch, c.headshots, c.onDrop, text = c.failText)
    }

    /** Moments montés d'un export highlights, à leur pic de score ; leurs kills comptent s'ils sont connus. */
    fun fromHighlights(report: ExportReport): List<ThumbnailMoment> = report.highlights.filter { it.order != null }.sortedBy { it.order }.map { h ->
        ThumbnailMoment(Path(h.source ?: report.source), (h.peakSeconds ?: (h.startSeconds + h.endSeconds) / 2).let { (it * 1000).roundToInt().milliseconds }, h.events["kill"] ?: 0)
    }

    /**
     * Texte de la miniature, dans la langue de l'interface (libellés incrustés dans les montages, `video.*`) : CLUTCH,
     * sinon la série du moment (DOUBLÉ, TRIPLÉ…), sinon le nombre de kills de la vidéo ; rien sous deux kills. Un ace
     * prend le nombre de kills de la vidéo : le jeu affiche déjà « ACE » en grand à cet instant (VALORANT, miniature
     * de la QA du 18/11 : « ACE » deux fois).
     */
    fun label(m: ThumbnailMoment, totalKills: Int): String = when {
        // Montage des morts : le texte de la mort choisie ; « 8 KILLS » y comptait des morts.
        m.text != null -> m.text
        m.ace -> if (totalKills >= 2) I18n.text("video.kills", mapOf("count" to totalKills)) else ""
        m.clutch -> I18n.text("video.clutch")
        m.kills >= 2 -> I18n.text("video.streak.${m.kills.coerceAtMost(5)}")
        totalKills >= 2 -> I18n.text("video.kills", mapOf("count" to totalKills))
        else -> ""
    }

    private const val ACE = 3.0
    private const val CLUTCH = 2.0
    private const val HEADSHOT = 0.3
    private const val DROP = 0.5
    const val TIE = 0.5
    const val MAX_CONTENDERS = 6
}

/** Image mesurée autour du kill : [sharpness] (variance du laplacien) et [luma] moyenne, au centre de l'écran. */
data class FrameSample(val at: Duration, val sharpness: Double, val luma: Double)

/**
 * L'instant le plus lisible autour du kill. On décode 10 images par seconde de [BEFORE] avant à [AFTER] après le kill,
 * au centre de l'écran (la moitié de la largeur, 60 % de la hauteur : la cible et la notification de kill), en gris.
 *
 * - Netteté = variance du laplacien (4 voisins) : une image filée par un flick (balayage de plusieurs largeurs d'écran
 *   par seconde) perd ses contours et tombe, une image arrêtée sur la cible monte.
 * - Après le kill (jusqu'à [AFTER]), la notification (tête de mort, « +$1500 ») est à l'écran : ces images valent
 *   [NOTICE_BONUS] fois leur netteté. Au-delà, le joueur passe à autre chose, ou meurt.
 * - Écran noir ou presque (luminosité moyenne sous [DARK], chargement, fondu, écran de mort sombre) : écarté.
 * - Lisibilité ([readability]) = netteté × bonus de notification × luminosité ramenée à [BRIGHT] (une scène en intérieur
 *   à 40 de luminosité moyenne compte pour moitié d'une scène à 80) : elle départage aussi les moments de même force.
 */
object ThumbnailFrames {
    val BEFORE = 400.milliseconds
    val AFTER = 1200.milliseconds
    const val FPS = 10
    const val NOTICE_BONUS = 1.2
    const val DARK = 28.0
    const val BRIGHT = 80.0
    private const val WIDTH = 480

    fun choose(samples: List<FrameSample>, kill: Duration): Duration? = best(samples, kill)?.at

    /** Image la plus lisible autour de [kill] ; null si toutes sont trop sombres. */
    fun best(samples: List<FrameSample>, kill: Duration): FrameSample? =
        samples.filter { it.luma >= DARK }.maxByOrNull { readability(it, kill) }

    fun readability(sample: FrameSample, kill: Duration): Double =
        sample.sharpness * (if (sample.at >= kill) NOTICE_BONUS else 1.0) * (sample.luma / BRIGHT).coerceAtMost(1.0)

    /** Variance du laplacien d'une image grise (un octet par pixel). */
    fun sharpness(pixels: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        fun p(x: Int, y: Int) = pixels[y * width + x].toInt() and 0xFF
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val l = (4 * p(x, y) - p(x - 1, y) - p(x + 1, y) - p(x, y - 1) - p(x, y + 1)).toDouble()
            sum += l
            sum2 += l * l
            n++
        }
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    fun luma(pixels: ByteArray): Double = if (pixels.isEmpty()) 0.0 else pixels.sumOf { (it.toInt() and 0xFF).toLong() }.toDouble() / pixels.size

    /** Mesure les images autour de [kill] dans [source] (de dimensions [srcWidth]x[srcHeight]). */
    suspend fun measure(ffmpeg: FfmpegService, source: Path, kill: Duration, srcWidth: Int, srcHeight: Int): List<FrameSample> {
        val start = (kill - BEFORE).coerceAtLeast(Duration.ZERO)
        val cw = srcWidth / 2
        val ch = srcHeight * 6 / 10
        val height = (WIDTH.toDouble() * ch / cw).roundToInt() / 2 * 2
        val frame = WIDTH * height
        val samples = mutableListOf<FrameSample>()
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSeconds(start), "-i", source.toString(), "-t", Durations.ffmpegSeconds(BEFORE + AFTER),
                    "-an", "-vf", "fps=$FPS,crop=$cw:$ch:${(srcWidth - cw) / 2}:${(srcHeight - ch) / 2},scale=$WIDTH:$height,format=gray",
                    "-f", "rawvideo", "pipe:1",
                ),
                "netteté autour de ${kill.toTimecode()}",
            ),
            StdoutHandler.Binary { input ->
                val buffer = ByteArray(frame)
                var i = 0
                while (input.readNBytes(buffer, 0, frame) == frame) {
                    samples += FrameSample(start + (i * 1000 / FPS).milliseconds, sharpness(buffer, WIDTH, height), luma(buffer))
                    i++
                }
            },
        )
        return samples
    }
}

/** Rendu d'une miniature : une image de la capture, recadrée au format voulu, punch-in, couleurs, texte. */
object ThumbnailRenderer {
    /** Police des textes, comme les libellés du montage ; un repli qui a tous les caractères si besoin. */
    const val FONT = "C:/Windows/Fonts/impact.ttf"

    /** Largeur utile du texte : il ne touche pas les bords. */
    private const val TEXT_WIDTH = 0.86

    /**
     * Graphe de filtres : recadrage au format [outWidth]x[outHeight] centré sur l'écran, resserré de [ThumbnailStyle.zoom]
     * (le réticule est au centre), mise à l'échelle, étalonnage, texte.
     */
    fun filter(style: ThumbnailStyle, srcWidth: Int, srcHeight: Int, outWidth: Int, outHeight: Int, label: String, locale: Locale = I18n.locale): String {
        val aspect = outWidth.toDouble() / outHeight
        var ch = srcHeight / style.zoom
        var cw = ch * aspect
        if (cw > srcWidth / style.zoom) {
            cw = srcWidth / style.zoom
            ch = cw / aspect
        }
        val w = even(cw)
        val h = even(ch)
        val parts = mutableListOf(
            "crop=$w:$h:${(srcWidth - w) / 2}:${(srcHeight - h) / 2}",
            "scale=$outWidth:$outHeight:flags=lanczos",
        )
        RenderCommandBuilder.grade(style.grade).trimEnd(',').takeIf { it.isNotEmpty() }?.let { parts += it }
        drawText(style, label, outWidth, outHeight, locale)?.let { parts += it }
        return parts.joinToString(",")
    }

    /** Texte centré, aussi grand que le style le veut sans dépasser [TEXT_WIDTH] de la largeur (mesuré, `TextMeasure`). */
    internal fun drawText(style: ThumbnailStyle, label: String, width: Int, height: Int, locale: Locale): String? {
        if (label.isBlank()) return null
        val font = VideoFonts.forText(FONT, label, locale)
        var size = style.textSize * height
        TextMeasure.width(font, size, label)?.let { measured ->
            if (measured > width * TEXT_WIDTH) size *= width * TEXT_WIDTH / measured
        }
        val px = size.roundToInt().coerceAtLeast(8)
        val border = (px * 0.07).roundToInt().coerceAtLeast(2)
        return "drawtext=fontfile='${VideoFonts.drawtextPath(font)}':text='${escape(label)}':fontsize=$px:fontcolor=${style.textColor}" +
            ":borderw=$border:bordercolor=black:shadowx=${border / 2}:shadowy=${border / 2}:shadowcolor=black@0.6" +
            ":x=(w-text_w)/2:y=h*${String.format(Locale.ROOT, "%.3f", style.textY)}-text_h"
    }

    suspend fun render(
        ffmpeg: FfmpegService, source: Path, at: Duration, srcWidth: Int, srcHeight: Int,
        outWidth: Int, outHeight: Int, style: ThumbnailStyle, label: String, output: Path,
    ): Path {
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-ss", Durations.ffmpegSeconds(at), "-i", source.toString(), "-frames:v", "1", "-an",
                    "-vf", filter(style, srcWidth, srcHeight, outWidth, outHeight, label), "-update", "1", "-y", output.toString(),
                ),
                "miniature ${at.toTimecode()}",
            ),
        )
        return output
    }

    private fun even(v: Double) = (v.toInt() / 2) * 2

    /** Texte de `drawtext` entre apostrophes : l'apostrophe devient typographique, deux-points et barres échappés. */
    private fun escape(text: String) = text.replace("\\", "\\\\\\\\").replace("'", "\u2019").replace(":", "\\:").replace("%", "\\%")
}
