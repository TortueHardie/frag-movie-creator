package dev.highlights.montage

import dev.highlights.core.i18n.tr
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.KillCounterMode
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SafeArea
import dev.highlights.core.serialization.Durations
import dev.highlights.editing.story.VideoFonts
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Compteur de kills à l'écran : « 1 », « 2 », « 3 »… à chaque kill, puis ACE sous le dernier kill d'un groupe qui
 * finit un ace ([RoundOutcome.ace]). [KillCounterMode.CLIP] : compté dans chaque plan (un multi-kill fait 1, 2, 3) ;
 * [KillCounterMode.MONTAGE] : cumul sur tout le montage, le total restant affiché d'un plan à l'autre.
 *
 * Placé en 9:16 en haut à droite, à côté du kill feed recollé (en haut à gauche, jusqu'à 62 % de la largeur dans les
 * profils livrés) et au-dessus des libellés DOUBLÉ/TRIPLÉ (25 % de la hauteur) : il ne cache ni l'un ni l'autre ; dans
 * les autres formats, centré sous la boussole (11 % de la hauteur). Toujours tenu dans la zone sûre de la plateforme
 * (boutons et onglets de TikTok, des Shorts…).
 */
object KillCounter {
    /** Un nombre affiché de [from] à [to] (temps du plan), et ACE dessous si [ace]. */
    data class Mark(val count: Int, val from: Duration, val to: Duration, val ace: Boolean)

    /** Hauteur du nombre, en part de la hauteur de l'image : lisible sur un téléphone sans écraser l'action. */
    private const val SIZE = 0.065

    /** Apparition du nombre : assez vite pour tomber avec le kill, assez pour qu'on la voie. */
    private val POP = 80.milliseconds

    /**
     * Ce que le plan [index] affiche : chaque kill visible ([outKills], temps du plan) fait monter le compteur, tenu
     * jusqu'au suivant ou jusqu'à la fin du plan ([length]). En cumul, le total d'avant reste affiché depuis le début
     * du plan jusqu'à son premier kill (rien avant le tout premier kill du montage).
     */
    fun marks(clips: List<MontageClip>, index: Int, outKills: List<Duration>, length: Duration, mode: KillCounterMode): List<Mark> {
        if (mode == KillCounterMode.OFF) return emptyList()
        val clip = clips[index]
        val before = if (mode == KillCounterMode.MONTAGE) clips.take(index).sumOf { it.kills.size } else 0
        val visible = clip.kills
        // Ace : le groupe finit un ace, et son dernier kill est à l'écran dans ce plan.
        val aceAt = if (clip.group.outcome.ace && visible.lastOrNull() == clip.group.kills.last()) outKills.lastIndex else -1
        val marks = mutableListOf<Mark>()
        if (before > 0 && outKills.firstOrNull()?.isPositive() == true) marks += Mark(before, Duration.ZERO, outKills.first(), ace = false)
        outKills.forEachIndexed { j, at ->
            val end = outKills.getOrNull(j + 1) ?: length
            if (end > at) marks += Mark(before + j + 1, at, end, ace = j == aceAt)
        }
        return marks
    }

    /**
     * Le coin haut droit du 9:16 (au-delà de 66 % de la largeur : là où commence un nombre de 3 chiffres collé à droite,
     * au-dessus de 25 % de la hauteur) est-il pris par un
     * élément du HUD recollé ([vertical]) ? Sa hauteur vient de la forme de la zone découpée dans la capture de
     * référence et de la taille de sortie.
     */
    internal fun rightCornerTaken(vertical: VerticalSettings): Boolean {
        val ref = vertical.reference ?: FrameSize(1920, 1080)
        val out = vertical.size
        return vertical.hud.filter { it.enabled }.any { o ->
            val height = o.target.width * out.width * (o.source.height * ref.height) / (o.source.width * ref.width) / out.height
            o.target.x + o.target.width > 0.66 && o.target.y < 0.25 && o.target.y + height > 0.08
        }
    }

    /**
     * Filtres drawtext des [marks] d'un plan de [h] pixels de haut, dans la zone sûre [safe] ; [vertical] : réglages
     * 9:16 du profil (le HUD recollé décide du coin).
     */
    fun filters(marks: List<Mark>, font: String, format: OutputFormat, h: Int, safe: SafeArea = SafeArea.NONE, vertical: VerticalSettings = VerticalSettings()): List<String> {
        if (marks.isEmpty()) return emptyList()
        val size = (h * SIZE).roundToInt()
        val border = (h * 0.004).roundToInt().coerceAtLeast(2)
        val portrait = format == OutputFormat.VERTICAL
        // En 9:16, collé au bord droit de la zone sûre (à 4 % du bord au moins), à 10 % du haut au moins ; au bord
        // gauche si le HUD recollé occupe le coin droit.
        val x = when {
            !portrait -> "w*${num(safe.left)}+(w*${num(safe.width)}-text_w)/2"
            rightCornerTaken(vertical) -> "w*${num(maxOf(safe.left, 0.04))}"
            else -> "w*${num(1 - maxOf(safe.right, 0.04))}-text_w"
        }
        val top = if (portrait) maxOf(safe.top, 0.10) else maxOf(safe.top, 0.11)
        val y = "h*${num(top)}"
        val ace = tr("video.counter.ace")
        return marks.flatMap { m ->
            val t0 = sec(m.from)
            val t1 = sec(m.to)
            val alpha = "if(lt(t\\,${sec(m.from + POP)})\\,(t-$t0)/${num(POP.inWholeMilliseconds / 1000.0)}\\,1)"
            val number = draw(font, m.count.toString(), size, border, x, y, t0, t1, alpha, "white")
            if (!m.ace) listOf(number)
            else listOf(number, draw(font, ace, (size * 0.55).roundToInt(), border, x, "$y+${(size * 1.05).roundToInt()}", t0, t1, alpha, "0xFFC93C"))
        }
    }

    private fun draw(font: String, text: String, size: Int, border: Int, x: String, y: String, t0: String, t1: String, alpha: String, color: String): String {
        val file = VideoFonts.drawtextPath(VideoFonts.forText(font, text))
        return "drawtext=fontfile='$file':text='$text':fontsize=$size:fontcolor=$color:borderw=$border:bordercolor=black" +
            ":x=$x:y=$y:enable='between(t\\,$t0\\,$t1)':alpha='$alpha'"
    }

    private fun sec(d: Duration) = Durations.ffmpegSeconds(d)

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}
