package dev.highlights.pipeline

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import java.nio.file.Path
import java.time.ZoneId

/** Rapport JSON du récap (à côté de la vidéo) : les parties montrées et ce qu'elles valent. */
@kotlinx.serialization.Serializable
data class RecapReport(val date: String, val game: String, val kills: Int, val games: List<Game>, val outputs: List<String>) {
    @kotlinx.serialization.Serializable
    data class Game(val number: Int, val source: String, val kills: Int, val moments: Int)
}

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

    fun endTitle(evening: Evening): String = tr("video.recap.endTitle", "date" to I18n.date(evening.date, "format.date.dayMonth"))

    /** « 47 kills · meilleure partie : n°3 (14 kills) » ; la meilleure : le plus de kills, la première à égalité. */
    fun endSubtitle(evening: Evening): String {
        val best = evening.games.indices.maxByOrNull { evening.games[it].kills } ?: return tr("video.recap.endKills", "count" to evening.kills)
        return tr("video.recap.endStats", "count" to evening.kills, "number" to best + 1, "best" to evening.games[best].kills)
    }
}
