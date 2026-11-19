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
        fun at(events: List<TimelineEvent>, at: Duration): RoundScore? =
            timeline(events)?.let { t -> t.lastOrNull { it.first <= at }?.second ?: RoundScore(0, 0) }

        /** Scores successifs de la capture : (instant où il s'affiche, score), dans l'ordre ; null sans score lu. */
        fun timeline(events: List<TimelineEvent>): List<Pair<Duration, RoundScore>>? {
            val score = events.filter { it.kind == WON || it.kind == LOST || it.kind == RESET }.sortedBy { it.at }
            if (score.isEmpty()) return null
            var ally = 0
            var enemy = 0
            val out = mutableListOf<Pair<Duration, RoundScore>>()
            for (e in score) {
                when (e.kind) {
                    WON -> ally++
                    LOST -> enemy++
                    else -> { ally = 0; enemy = 0 }
                }
                if (out.lastOrNull()?.first == e.at) out.removeAt(out.lastIndex)
                out += e.at to RoundScore(ally, enemy)
            }
            return out
        }
    }
}

/** Événements de score de chaque capture d'un montage, pour retrouver le score d'un plan. */
class RoundScores(private val byMedia: Map<Path, List<TimelineEvent>>) {
    /** Un score affiché de [from] à [to], en temps du plan. */
    data class Mark(val score: RoundScore, val from: Duration, val to: Duration)

    /**
     * Ce que le plan affiche : le score du HUD à chaque instant de ce qu'il montre (au moment du tir, celui du round que
     * le kill joue ; le HUD ne change que quelques secondes après le kill qui le gagne). S'il change pendant le plan, le
     * bandeau change avec lui : la barre du HUD recollée en haut du 9:16 le montre aussi, et un score figé au premier
     * kill disait 9-9 sous un HUD passé à 10-9. [lead] : décalage du plan (coupe avancée), [length] : sa longueur. Rien
     * pour l'ouverture vitrine ni pour une capture sans score lu.
     */
    fun marks(clip: MontageClip, lead: Duration, length: Duration): List<Mark> {
        if (clip.showcase) return emptyList()
        val timeline = byMedia[clip.group.media.path]?.let { RoundScore.timeline(it) } ?: return emptyList()
        val first = timeline.lastOrNull { it.first <= clip.start }?.second ?: RoundScore(0, 0)
        val changes = timeline.filter { it.first > clip.start && it.first < clip.end }
            .map { (clip.toOutput(it.first) + lead).coerceIn(Duration.ZERO, length) to it.second }
        val starts = listOf(Duration.ZERO to first) + changes
        return starts.mapIndexedNotNull { i, (from, score) ->
            val to = starts.getOrNull(i + 1)?.first ?: length
            if (to > from) Mark(score, from, to) else null
        }
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

    /** Filtres du bandeau d'un plan de [length], [w]x[h] pixels : le cartouche tout le plan, chaque score de [marks]. */
    fun filters(
        marks: List<RoundScores.Mark>,
        length: Duration,
        font: String,
        format: OutputFormat,
        w: Int,
        h: Int,
        safe: SafeArea = SafeArea.NONE,
        vertical: VerticalSettings = VerticalSettings(),
    ): List<String> {
        if (marks.isEmpty()) return emptyList()
        val size = (h * SIZE).roundToInt().coerceAtLeast(8)
        val small = (size * 0.5).roundToInt().coerceAtLeast(6)
        val pad = (size * 0.18).roundToInt().coerceAtLeast(2)
        val boxW = (size * 3.6).roundToInt()
        val boxH = small + size + 3 * pad
        val y0 = (top(format, safe, vertical) * h).roundToInt()
        val cx = "(w*${num(safe.left)}+w*${num(safe.width)}/2)"
        val boxX = (w * (safe.left + safe.width / 2)).roundToInt() - boxW / 2
        fun enable(from: Duration, to: Duration) = "enable='between(t\\,${Durations.ffmpegSeconds(from)}\\,${Durations.ffmpegSeconds(to)})'"
        val border = (size * 0.06).roundToInt().coerceAtLeast(1)
        // Textes posés sur leur ligne de base (`max_glyph_a` : hauteur des glyphes tracés au-dessus d'elle) : posé par son
        // haut, le tiret, sans rien au-dessus de lui, montait en haut des chiffres.
        val labelBase = y0 + pad + (small * 0.8).roundToInt()
        val scoreBase = y0 + pad * 2 + small + (size * 0.8).roundToInt()
        val gap = (size * 0.42).roundToInt()
        val box = "drawbox=x=$boxX:y=$y0:w=$boxW:h=$boxH:color=black@0.55:t=fill:${enable(Duration.ZERO, marks.maxOf { it.to })}"
        return listOf(box) + marks.flatMap { m ->
            val on = enable(m.from, m.to)
            listOf(
                draw(font, tr("video.score.round", "round" to m.score.round), small, border, "$cx-text_w/2", "$labelBase-max_glyph_a", "0xD8D8D8", on),
                draw(font, m.score.ally.toString(), size, border, "$cx-$gap-text_w", "$scoreBase-max_glyph_a", ALLY, on),
                draw(font, "–", size, border, "$cx-text_w/2", "$scoreBase-max_glyph_a", "white", on),
                draw(font, m.score.enemy.toString(), size, border, "$cx+$gap", "$scoreBase-max_glyph_a", ENEMY, on),
            )
        }
    }

    private fun draw(font: String, text: String, size: Int, border: Int, x: String, y: String, color: String, enable: String): String {
        val file = VideoFonts.drawtextPath(VideoFonts.forText(font, text))
        return "drawtext=fontfile='$file':text='$text':fontsize=$size:fontcolor=$color:borderw=$border:bordercolor=black:x=$x:y=$y:$enable"
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}
