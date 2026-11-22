package dev.highlights.pipeline

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import java.nio.file.Path
import java.time.ZoneId

/** Rapport JSON du récap (à côté de la vidéo) : les parties montrées et ce qu'elles valent. */
@kotlinx.serialization.Serializable
data class RecapReport(
    val date: String,
    val game: String,
    val kills: Int,
    val games: List<Game>,
    val outputs: List<String>,
    /** Début de chaque carton dans la vidéo (secondes) : intercalaires dans l'ordre, puis l'écran de fin. */
    val cards: List<Double> = emptyList(),
) {
    @kotlinx.serialization.Serializable
    data class Game(
        val number: Int,
        val source: String,
        val kills: Int,
        val moments: Int,
        /** Séance de clips : leur nombre ([source] est alors le premier) ; null pour une partie entière. */
        val clips: Int? = null,
    )
}

/** Une séance montrée par le récap : son numéro dans la soirée, ses parties ou clips, leurs sessions aux moments choisis. */
internal data class RecapPiece(val number: Int, val part: PlaySession, val sessions: List<dev.highlights.core.session.Session>)

/** Récap d'une soirée : formats, style (simple ou story) et plateforme des exports de chaque partie. */
data class RecapOptions(
    val formats: List<OutputFormat>? = null,
    val style: EditStyle? = null,
    val platform: String? = null,
    val outputDir: Path? = null,
)

/**
 * Textes incrustés du récap (clés `video.recap.*`, dans la langue de l'interface comme les autres textes incrustés).
 * Ni la carte ni le mode d'une partie ne sont connus des analyses : le sous-titre dit le jeu et l'heure.
 */
object RecapText {
    /** « Partie 3 – 14 kills » : [number] dans l'ordre de la soirée, parties sans moment comprises. */
    fun gameTitle(number: Int, kills: Int): String = tr("video.recap.game", "number" to number, "count" to kills)

    /** « Wardogs · 21:09 » ; l'heure seule manque si la capture ne la donne pas. */
    fun gameSubtitle(game: GameStats, zone: ZoneId = ZoneId.systemDefault()): String =
        game.playedAt?.let { tr("video.recap.when", "game" to game.game, "time" to I18n.date(it.atZone(zone), "format.time")) } ?: game.game

    /** « Clips – 18 kills » : une séance de clips sauvegardés à la suite ([PlaySession]). */
    fun clipsTitle(kills: Int): String = tr("video.recap.clips", "count" to kills)

    /** « VALORANT · 21:04 – 22:10 » ; le jeu seul si les clips n'ont pas d'heure. */
    fun clipsSubtitle(part: PlaySession, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = part.start ?: return part.game
        val end = part.end ?: start
        return tr(
            "video.recap.clipsWhen",
            "game" to part.game,
            "start" to I18n.date(start.atZone(zone), "format.time"),
            "end" to I18n.date(end.atZone(zone), "format.time"),
        )
    }

    fun endTitle(evening: Evening): String = tr("video.recap.endTitle", "date" to I18n.date(evening.date, "format.date.dayMonth"))

    /**
     * « 47 kills · meilleure partie : n°3 (14 kills) » ; la meilleure : le plus de kills, la première à égalité. Les
     * numéros sont ceux des séances ([Evening.parts]) ; une soirée d'une seule séance de clips dit ses kills seuls, une
     * séance de clips la meilleure dit « meilleure séance » (choix du joueur, 22/11).
     */
    fun endSubtitle(evening: Evening): String {
        val parts = evening.parts
        val best = parts.indices.maxByOrNull { parts[it].kills }
        if (best == null || (parts.size == 1 && parts[0].clips)) return tr("video.recap.endKills", "count" to evening.kills)
        val key = if (parts[best].clips) "video.recap.endStatsSession" else "video.recap.endStats"
        return tr(key, "count" to evening.kills, "number" to best + 1, "best" to parts[best].kills)
    }
}
