package dev.highlights.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.serialization.toShortText
import dev.highlights.pipeline.EveningStats
import dev.highlights.pipeline.GameStats
import dev.highlights.pipeline.Pipelines
import dev.highlights.pipeline.Statistics
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** « 2 doublés, 1 triplé » : multi-kills du plus petit au plus gros. */
internal fun multiKillsText(multi: Map<Int, Int>, aces: Int, clutches: Int?): String = buildList {
    val labels = mapOf(2 to "doublé", 3 to "triplé", 4 to "quadruplé", 5 to "quintuplé")
    multi.toSortedMap().forEach { (size, n) -> labels[size.coerceAtMost(5)]?.let { add("$n $it${if (n > 1) "s" else ""}") } }
    if (aces > 0) add("$aces ace${if (aces > 1) "s" else ""}")
    if (clutches != null && clutches > 0) add("$clutches clutch${if (clutches > 1) "s" else ""}")
}.joinToString(", ")

class StatsCommand : PipelineCommand("stats") {
    private val game by option("-g", "--game", help = "Un seul jeu (identifiant de profil : valorant, wardogs…)")
    private val last by option("--last", help = "Nombre de soirées affichées (défaut : 10)").int().restrictTo(min = 1).default(10)
    private val detail by option("--games", help = "Détail de chaque partie").flag()
    private val refresh by option("--refresh", help = "Réanalyse d'abord les parties analysées avec d'anciens réglages (leurs sessions sont remplacées)").flag()

    override fun help(context: Context) =
        "Statistiques des parties analysées, par soirée : kills, K/D, tirs à la tête, meilleur round, multi-kills, aces."

    override fun run() {
        val pipeline = Pipelines.create(env.config)
        if (refresh) {
            val progress = ConsoleProgress()
            val n = execute {
                try {
                    pipeline.refreshOutdated(ProgressTracker(listener = progress).root)
                } finally {
                    progress.finish()
                }
            }
            echo("$n partie(s) réanalysée(s).")
        }
        val games = execute { pipeline.statistics() }.filter { game == null || it.profileId == game }
        val evenings = Statistics.evenings(games)
        val outdated = games.filter { it.outdated }
        if (evenings.isEmpty()) {
            echo("Aucune partie analysée${game?.let { " pour $it" } ?: ""}.")
            return
        }
        val dates = evenings.map { it.date }.distinct().take(last).toSet()
        evenings.filter { it.date in dates }.forEach { e -> printEvening(e) }
        if (outdated.isNotEmpty()) {
            echo("")
            val unknown = outdated.count { it.clutches == null }
            echo("${outdated.size} partie(s) analysée(s) avec d'anciens réglages de détection${if (unknown > 0) " (dont $unknown aux clutchs inconnus)" else ""} : « app stats --refresh » les réanalyse.")
        }
        // Évolution, jeu par jeu : la dernière soirée face à la moyenne des précédentes.
        val trends = evenings.groupBy { it.game }.filterValues { it.size > 1 }
        if (trends.isNotEmpty()) echo("")
        trends.forEach { (name, list) ->
            val latest = list.first()
            val before = list.drop(1)
            val avgKills = before.sumOf { it.kills }.toDouble() / before.sumOf { it.games.size }.coerceAtLeast(1)
            echo("Tendance $name : ${fmt(latest.killsPerGame)} kills par partie à la dernière soirée, contre ${fmt(avgKills)} sur les ${before.size} d'avant.")
        }
    }

    private fun printEvening(e: EveningStats) {
        val date = e.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH))
        echo("")
        echo("$date — ${e.game}, ${e.games.size} partie(s), ${e.playTime.toShortText()}")
        val parts = listOfNotNull(
            "${e.kills} kills (${fmt(e.killsPerGame)} par partie)",
            e.deaths?.let { "${it} morts, K/D ${fmt(e.kd!!)}" },
            e.headshotRate?.let { "${(it * 100).toInt()} % de tirs à la tête" },
            e.bestRound?.takeIf { it > 0 }?.let { "meilleur round : $it kill(s)" },
            multiKillsText(e.multiKills, e.aces, e.clutches).takeIf { it.isNotEmpty() },
        )
        echo("  " + parts.joinToString(" · "))
        if (detail) e.games.forEach { printGame(it) }
    }

    private fun printGame(g: GameStats) {
        val time = g.playedAt?.atZone(ZoneId.systemDefault())?.toLocalTime()?.withSecond(0)?.withNano(0)
        val parts = listOfNotNull(
            "${g.kills} kills",
            g.deaths?.let { "$it morts" },
            g.headshotRate?.let { "${(it * 100).toInt()} % HS" },
            g.bestRound?.takeIf { it > 0 }?.let { "round max $it" },
            multiKillsText(g.multiKills, g.aces, g.clutches).takeIf { it.isNotEmpty() },
        )
        echo("    $time  ${g.game}  ${g.duration.toShortText()}  " + parts.joinToString(" · "))
    }

    private fun fmt(v: Double) = String.format(Locale.FRENCH, "%.1f", v)
}
