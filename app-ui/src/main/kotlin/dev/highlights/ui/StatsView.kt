package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.serialization.toShortText
import dev.highlights.pipeline.EveningStats
import dev.highlights.pipeline.GameStats
import dev.highlights.pipeline.Statistics
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Statistiques des parties analysées : un jeu à la fois, soirée par soirée. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsView(stats: StatsState, actions: UiActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Statistiques", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::closeStats) { Text("Analyses enregistrées") }
        }
        if (stats.loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (stats.evenings.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Pas encore de partie à compter : il faut des captures d'au moins 5 minutes, analysées avec un profil qui détecte les kills.",
                    color = Palette.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(460.dp),
                )
            }
            return@Column
        }
        if (stats.gameNames.size > 1) {
            SingleChoiceSegmentedButtonRow {
                stats.gameNames.forEachIndexed { i, name ->
                    SegmentedButton(
                        selected = stats.selectedGame == name,
                        onClick = { actions.setStatsGame(name) },
                        shape = SegmentedButtonDefaults.itemShape(i, stats.gameNames.size),
                    ) { Text(name) }
                }
            }
        }
        val evenings = stats.shown
        val outdatedGames = evenings.flatMap { e -> e.games.filter { it.outdated } }
        val outdated = outdatedGames.size
        if (outdated > 0) {
            val unknown = outdatedGames.count { it.clutches == null }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$outdated partie(s) analysée(s) avec d'anciens réglages de détection" +
                        (if (unknown > 0) ", dont $unknown aux clutchs inconnus" else "") +
                        ". La mise à jour les réanalyse (quelques secondes chacune ; les moments décochés redeviennent cochés).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.OutlinedButton(onClick = actions::refreshOutdated) { Text("Mettre à jour les analyses") }
            }
        }
        Summary(evenings)
        KillsChart(evenings.take(CHART_EVENINGS).reversed())
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(evenings, key = { "${it.date}-${it.game}" }) { EveningCard(it, actions) }
        }
    }
}

/** Soirées montrées dans le graphique : assez pour voir une tendance, pas au point d'écraser les barres. */
private const val CHART_EVENINGS = 14

@Composable
private fun Summary(evenings: List<EveningStats>) {
    val games = evenings.flatMap { it.games }
    val all = EveningStats(evenings.first().date, evenings.first().game, games)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile("Parties", "${games.size}", "${evenings.size} soirée(s)", Modifier.weight(1f))
        StatTile("Kills par partie", decimal(all.killsPerGame), "${all.kills} au total", Modifier.weight(1f))
        StatTile("K/D", all.kd?.let(::decimal) ?: "—", all.deaths?.let { "$it morts" } ?: "morts non détectées", Modifier.weight(1f))
        StatTile("Tirs à la tête", all.headshotRate?.let { "${(it * 100).toInt()} %" } ?: "—", all.headshots?.let { "$it headshots" } ?: "non détectés", Modifier.weight(1f))
        StatTile("Meilleur round", all.bestRound?.let { "$it kill${if (it > 1) "s" else ""}" } ?: "—", multiKillsLabel(all).ifEmpty { "aucun multi-kill" }, Modifier.weight(1.4f))
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
            Text(value, style = MaterialTheme.typography.titleLarge, color = Palette.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Kills par partie, une barre par soirée (de la plus ancienne à la plus récente), K/D dessous quand il est connu. */
@Composable
private fun KillsChart(evenings: List<EveningStats>) {
    if (evenings.size < 2) return
    val max = evenings.maxOf { it.killsPerGame }.coerceAtLeast(1.0)
    val day = DateTimeFormatter.ofPattern("dd/MM", Locale.FRENCH)
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kills par partie, soirée après soirée", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                evenings.forEachIndexed { i, e ->
                    val latest = i == evenings.lastIndex
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text(decimal(e.killsPerGame), style = MaterialTheme.typography.labelSmall, color = if (latest) Palette.accent else Palette.text)
                        Box(
                            Modifier.fillMaxWidth(0.7f).height((100 * e.killsPerGame / max).dp.coerceAtLeast(2.dp))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (latest) Palette.accent else Palette.cyan),
                        )
                        Text(e.date.format(day), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                        Text(e.kd?.let { "K/D ${decimal(it)}" } ?: " ", style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun EveningCard(e: EveningStats, actions: UiActions) {
    val date = e.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)).replaceFirstChar { it.uppercase() }
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${e.games.size} partie(s) · ${e.playTime.toShortText()}", style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
            Text(
                listOfNotNull(
                    "${e.kills} kills (${decimal(e.killsPerGame)} par partie)",
                    e.kd?.let { "K/D ${decimal(it)}" },
                    e.headshotRate?.let { "${(it * 100).toInt()} % HS" },
                    e.bestRound?.takeIf { it > 0 }?.let { "meilleur round : $it" },
                    multiKillsLabel(e).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.text,
            )
            e.games.forEach { GameRow(it, e.bestGame == it && e.games.size > 1, actions) }
        }
    }
}

@Composable
private fun GameRow(g: GameStats, best: Boolean, actions: UiActions) {
    val time = g.playedAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "--:--"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, modifier = Modifier.width(44.dp))
        Text(
            listOfNotNull(
                g.duration.toShortText(),
                "${g.kills} kills" + (g.deaths?.let { " / $it morts" } ?: ""),
                g.headshotRate?.let { "${(it * 100).toInt()} % HS" },
                g.bestRound?.takeIf { it > 0 }?.let { "round max $it" },
                multiKillsLabel(g.multiKills, g.aces, g.clutches).takeIf { it.isNotEmpty() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (best) Palette.accent else Palette.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { actions.openLibraryItem(g.sessionFile) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Ouvrir") }
    }
}

private fun multiKillsLabel(e: EveningStats) = multiKillsLabel(e.multiKills, e.aces, e.clutches)

/** « 2 doublés, 1 triplé, 1 ace » : du plus petit multi-kill au plus gros, puis aces et clutchs. */
internal fun multiKillsLabel(multi: Map<Int, Int>, aces: Int, clutches: Int?): String = buildList {
    val labels = mapOf(2 to "doublé", 3 to "triplé", 4 to "quadruplé", 5 to "quintuplé")
    multi.toSortedMap().forEach { (size, n) -> labels[size.coerceAtMost(5)]?.let { add("$n $it${if (n > 1) "s" else ""}") } }
    if (aces > 0) add("$aces ace${if (aces > 1) "s" else ""}")
    if (clutches != null && clutches > 0) add("$clutches clutch${if (clutches > 1) "s" else ""}")
}.joinToString(", ")

private fun decimal(v: Double) = String.format(Locale.FRENCH, "%.1f", v)

/** Statistiques affichées : parties chargées, jeu choisi (le plus joué par défaut). */
data class StatsState(
    val loading: Boolean = true,
    val games: List<GameStats> = emptyList(),
    val game: String? = null,
) {
    /** Toutes les soirées, par jeu, la plus récente d'abord. */
    val evenings: List<EveningStats> by lazy { Statistics.evenings(games) }

    /** Jeux présents, le plus joué d'abord. */
    val gameNames: List<String> by lazy { evenings.groupBy { it.game }.entries.sortedByDescending { e -> e.value.sumOf { it.games.size } }.map { it.key } }

    val selectedGame: String? get() = game?.takeIf { it in gameNames } ?: gameNames.firstOrNull()

    val shown: List<EveningStats> get() = evenings.filter { it.game == selectedGame }
}
