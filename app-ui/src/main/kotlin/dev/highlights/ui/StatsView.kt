package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
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
            Text(tr("ui.library.stats"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::closeStats) { Text(tr("ui.library.title")) }
        }
        if (stats.loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (stats.evenings.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    tr("ui.stats.empty"),
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
                    if (unknown > 0) tr("ui.stats.outdatedClutches", "count" to outdated, "unknown" to unknown) else tr("ui.stats.outdated", "count" to outdated),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.OutlinedButton(onClick = actions::refreshOutdated) { Text(tr("ui.stats.refresh")) }
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
        // Une séance de clips compte pour une partie (voir EveningStats.parts).
        StatTile(tr("ui.stats.games"), "${evenings.sumOf { it.parts.size }}", tr("ui.stats.evenings", "count" to evenings.size), Modifier.weight(1f))
        StatTile(tr("ui.stats.killsPerGame"), decimal(all.killsPerGame), tr("ui.stats.totalKills", "count" to all.kills), Modifier.weight(1f))
        StatTile(tr("ui.stats.kd"), all.kd?.let(::decimal) ?: "—", all.deaths?.let { tr("ui.count.deaths", "count" to it) } ?: tr("ui.stats.deathsUnknown"), Modifier.weight(1f))
        StatTile(tr("ui.stats.headshots"), all.headshotRate?.let { I18n.percent(it) } ?: "—", all.headshots?.let { tr("ui.count.headshots", "count" to it) } ?: tr("ui.stats.headshotsUnknown"), Modifier.weight(1f))
        StatTile(tr("ui.stats.bestRound"), all.bestRound?.let { tr("ui.count.kills", "count" to it) } ?: "—", multiKillsLabel(all).ifEmpty { tr("ui.stats.noMultiKill") }, Modifier.weight(1.4f))
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
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ui.stats.chart"), style = MaterialTheme.typography.labelLarge)
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
                        Text(I18n.date(e.date, "format.date.dayMonth"), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                        Text(e.kd?.let { tr("ui.stats.kdValue", "value" to decimal(it)) } ?: " ", style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun EveningCard(e: EveningStats, actions: UiActions) {
    val date = I18n.date(e.date, "format.date.long").replaceFirstChar { it.uppercase() }
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (e.clips == 0) tr("ui.stats.eveningGames", "count" to e.games.size, "duration" to e.playTime) else tr("ui.stats.eveningClips", "count" to e.clips, "duration" to e.playTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                )
                // Récap de la soirée : passe par la file des traitements, comme un export.
                androidx.compose.material3.TextButton(onClick = { actions.recapEvening(e.date.toString(), e.games.first().profileId) }) { Text(tr("ui.stats.recap")) }
            }
            Text(
                listOfNotNull(
                    tr("ui.stats.eveningKills", "count" to e.kills, "perGame" to decimal(e.killsPerGame)),
                    e.kd?.let { tr("ui.stats.kdValue", "value" to decimal(it)) },
                    e.headshotRate?.let { tr("ui.stats.hsRate", "percent" to I18n.percent(it)) },
                    e.bestRound?.takeIf { it > 0 }?.let { tr("ui.stats.bestRoundValue", "count" to it) },
                    multiKillsLabel(e).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.text,
            )
            // Clips sauvegardés à la suite : une ligne pour la séance ; parties entières : une ligne chacune.
            val parts = e.parts
            val best = parts.maxByOrNull { it.kills }.takeIf { parts.size > 1 }
            parts.forEach { p ->
                if (p.clips && p.games.size > 1) ClipSessionRow(p, p == best, actions) else p.games.forEach { GameRow(it, p == best, actions) }
            }
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
                I18n.duration(g.duration),
                g.deaths?.let { tr("ui.stats.killsDeaths", "kills" to g.kills, "deaths" to it) } ?: tr("ui.count.kills", "count" to g.kills),
                g.headshotRate?.let { tr("ui.stats.hsRate", "percent" to I18n.percent(it)) },
                g.bestRound?.takeIf { it > 0 }?.let { tr("ui.stats.maxRound", "count" to it) },
                multiKillsLabel(g.multiKills, g.aces, g.clutches).takeIf { it.isNotEmpty() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (best) Palette.accent else Palette.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { actions.openLibraryItem(g.sessionFile) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.common.open")) }
    }
}

/** Une séance de clips : heures de début et de fin, nombre de clips, kills ; « Ouvrir » les ouvre tous ensemble. */
@Composable
private fun ClipSessionRow(p: dev.highlights.pipeline.PlaySession, best: Boolean, actions: UiActions) {
    val zone = ZoneId.systemDefault()
    val time = p.start?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "--:--"
    val deaths = p.games.mapNotNull { it.deaths }.takeIf { it.size == p.games.size }?.sum()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, modifier = Modifier.width(44.dp))
        Text(
            listOfNotNull(
                tr("ui.stats.clipSession", "count" to p.games.size, "end" to (p.end?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "--:--")),
                deaths?.let { tr("ui.stats.killsDeaths", "kills" to p.kills, "deaths" to it) } ?: tr("ui.count.kills", "count" to p.kills),
                multiKillsLabel(
                    p.games.flatMap { it.multiKills.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() },
                    p.games.sumOf { it.aces },
                    p.games.mapNotNull { it.clutches }.takeIf { it.isNotEmpty() }?.sum(),
                ).takeIf { it.isNotEmpty() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (best) Palette.accent else Palette.text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { actions.openLibraryItems(p.games.map { it.sessionFile }) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.common.open")) }
    }
}

private fun multiKillsLabel(e: EveningStats) = multiKillsLabel(e.multiKills, e.aces, e.clutches)

/** « 2 doublés, 1 triplé, 1 ace » : du plus petit multi-kill au plus gros, puis aces et clutchs. */
internal fun multiKillsLabel(multi: Map<Int, Int>, aces: Int, clutches: Int?): String = buildList {
    multi.toSortedMap().forEach { (size, n) -> if (size >= 2) add(tr("youtube.count.multi${size.coerceAtMost(5)}", "count" to n)) }
    if (aces > 0) add(tr("youtube.count.ace", "count" to aces))
    if (clutches != null && clutches > 0) add(tr("youtube.count.clutch", "count" to clutches))
}.joinToString(tr("format.listComma"))

private fun decimal(v: Double) = I18n.fixed(v, 1)

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
