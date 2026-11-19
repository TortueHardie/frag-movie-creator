package dev.highlights.montage

import dev.highlights.core.i18n.tr
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SafeArea
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.serialization.Durations
import dev.highlights.core.session.Session
import dev.highlights.editing.story.VideoFonts
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

/** Score des rounds à un instant : rounds gagnés par l'équipe du joueur, par l'adversaire. */
data class RoundScore(val ally: Int, val enemy: Int) {
    /** Round en cours : celui qui suit les rounds déjà joués. */
    val round: Int get() = ally + enemy + 1

    companion object {
        /** Événements du détecteur `round-score` (types par défaut de ses paramètres). */
        const val WON = "round-won"
        const val LOST = "round-lost"
        const val RESET = "score-reset"

        /**
         * Score affiché à l'instant [at] : le compte des rounds gagnés et perdus jusque-là, depuis le dernier retour à 0-0.
         * null si la capture n'a aucun score lu (autre jeu, analysée sans `round-score`, HUD illisible).
         */
        fun at(events: List<TimelineEvent>, at: Duration): RoundScore? {
            val score = events.filter { it.kind == WON || it.kind == LOST || it.kind == RESET }.sortedBy { it.at }
            if (score.isEmpty()) return null
            var ally = 0
            var enemy = 0
            for (e in score) {
                if (e.at > at) break
                when (e.kind) {
                    WON -> ally++
                    LOST -> enemy++
                    else -> { ally = 0; enemy = 0 }
                }
            }
            return RoundScore(ally, enemy)
        }
    }
}

/** Événements de score de chaque capture d'un montage, pour retrouver le score d'un plan. */
class RoundScores(private val byMedia: Map<Path, List<TimelineEvent>>) {
    /**
     * Score au premier kill du plan (au moment du tir : le round que le kill joue, pas celui qu'il fait peut-être gagner,
     * le HUD ne change que quelques secondes après). null pour un plan sans kill (ouverture vitrine) ou sans score lu.
     */
    fun forClip(clip: MontageClip): RoundScore? {
        if (clip.showcase) return null
        val events = byMedia[clip.group.media.path] ?: return null
        return RoundScore.at(events, clip.kills.firstOrNull() ?: clip.anchor)
    }

    companion object {
        val NONE = RoundScores(emptyMap())

        fun from(sessions: List<Session>): RoundScores = RoundScores(
            sessions.associate { s -> s.media.path to s.timeline.events.filter { it.kind == RoundScore.WON || it.kind == RoundScore.LOST || it.kind == RoundScore.RESET } }
                .filterValues { it.isNotEmpty() },
        )
    }
}

/**
 * Bandeau du score des rounds ([dev.highlights.core.model.TextEffect.roundScore]) : un petit cartouche sombre, « ROUND 13 »
 * au-dessus de « 7 – 5 » (équipe du joueur en sarcelle, adversaire en rouge, les couleurs du HUD de VALORANT), tenu
 * tout le plan.
 *
 * En 9:16, centré en haut, sous ce qui y est recollé du HUD (la barre du haut de VALORANT : le score du jeu y est, mais
 * à 19 px de haut sur 1920), à 10 % du haut au moins et dans la zone sûre : entre le compteur de kills (coin haut droit
 * ou gauche) et les libellés DOUBLÉ/TRIPLÉ (25 %), sans toucher ni l'un ni l'autre. Dans les autres formats, posé sur la
 * barre du haut du jeu, au centre (le chrono) : sous lui, le compteur de kills et les libellés occupent déjà 11 à 30 %.
 */
object RoundScoreBanner {
    /** Hauteur des chiffres du score, en part de la hauteur de l'image : lisible sans rivaliser avec le compteur (6,5 %). */
    private const val SIZE = 0.032

    /** Couleurs des deux équipes dans le HUD de VALORANT. */
    private const val ALLY = "0x5CE6C8"
    private const val ENEMY = "0xFF5A64"

    /**
     * Haut du bandeau (part de la hauteur) en 9:16 : sous les éléments du HUD recollés au-dessus du tiers haut qui
     * croisent le centre de l'image (de 30 à 70 % de la largeur), à 10 % du haut au moins, dans la zone sûre.
     */
    internal fun top(format: OutputFormat, safe: SafeArea, vertical: VerticalSettings): Double {
        if (format != OutputFormat.VERTICAL) return maxOf(safe.top, 0.012)
        val ref = vertical.reference ?: FrameSize(1920, 1080)
        val out = vertical.size
        val below = vertical.hud.filter { it.enabled && it.target.y < 0.30 && it.target.x < 0.70 && it.target.x + it.target.width > 0.30 }
            .maxOfOrNull { o -> o.target.y + o.target.width * out.width * (o.source.height * ref.height) / (o.source.width * ref.width) / out.height + 0.01 }
            ?: 0.0
        return maxOf(safe.top, 0.10, below)
    }

    /** Filtres du bandeau d'un plan de [length], [w]x[h] pixels ; rien sans [score]. */
    fun filters(
        score: RoundScore?,
        length: Duration,
        font: String,
        format: OutputFormat,
        w: Int,
        h: Int,
        safe: SafeArea = SafeArea.NONE,
        vertical: VerticalSettings = VerticalSettings(),
    ): List<String> {
        if (score == null) return emptyList()
        val size = (h * SIZE).roundToInt().coerceAtLeast(8)
        val small = (size * 0.5).roundToInt().coerceAtLeast(6)
        val pad = (size * 0.18).roundToInt().coerceAtLeast(2)
        val boxW = (size * 3.6).roundToInt()
        val boxH = small + size + 3 * pad
        val y0 = (top(format, safe, vertical) * h).roundToInt()
        val cx = "(w*${num(safe.left)}+w*${num(safe.width)}/2)"
        val boxX = (w * (safe.left + safe.width / 2)).roundToInt() - boxW / 2
        val enable = "enable='between(t\\,0\\,${Durations.ffmpegSeconds(length)})'"
        val border = (size * 0.06).roundToInt().coerceAtLeast(1)
        val label = tr("video.score.round", "round" to score.round)
        val scoreY = y0 + pad * 2 + small
        val gap = (size * 0.42).roundToInt()
        return listOf(
            "drawbox=x=$boxX:y=$y0:w=$boxW:h=$boxH:color=black@0.55:t=fill:$enable",
            draw(font, label, small, border, "$cx-text_w/2", "${y0 + pad}", "0xD8D8D8", enable),
            draw(font, score.ally.toString(), size, border, "$cx-$gap-text_w", "$scoreY", ALLY, enable),
            draw(font, "–", size, border, "$cx-text_w/2", "$scoreY", "white", enable),
            draw(font, score.enemy.toString(), size, border, "$cx+$gap", "$scoreY", ENEMY, enable),
        )
    }

    private fun draw(font: String, text: String, size: Int, border: Int, x: String, y: String, color: String, enable: String): String {
        val file = VideoFonts.drawtextPath(VideoFonts.forText(font, text))
        return "drawtext=fontfile='$file':text='$text':fontsize=$size:fontcolor=$color:borderw=$border:bordercolor=black:x=$x:y=$y:$enable"
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}
