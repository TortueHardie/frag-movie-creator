package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.serialization.toTimecode
import dev.highlights.pipeline.FoundMoment
import dev.highlights.pipeline.MomentQuery
import dev.highlights.pipeline.Statistics
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Période de la recherche, en soirées. */
enum class SearchPeriod(val label: String, val days: Long?) {
    WEEK("7 jours", 7), MONTH("30 jours", 30), ALL("Tout", null),
}

/**
 * Recherche de moments dans les parties analysées : chargés une fois ([moments]), filtrés à la volée. Les moments
 * cochés ([picked], par [key]) font le montage kills.
 */
data class SearchState(
    val loading: Boolean = true,
    val moments: List<FoundMoment> = emptyList(),
    val game: String? = null,
    val period: SearchPeriod = SearchPeriod.ALL,
    val minKills: Int = 1,
    val ace: Boolean = false,
    val clutch: Boolean = false,
    val headshots: Boolean = false,
    val oneTaps: Boolean = false,
    /** Moments décochés (par défaut, tout ce qui répond aux critères est coché). */
    val unpicked: Set<String> = emptySet(),
) {
    fun query(today: LocalDate = Statistics.eveningOf(java.time.Instant.now())) =
        MomentQuery(game, period.days?.let { today.minusDays(it - 1) }, null, minKills, ace, clutch, headshots, oneTaps)

    val results: List<FoundMoment> get() = query().let { q -> moments.filter { q.matches(it) } }
    val picked: List<FoundMoment> get() = results.filter { key(it) !in unpicked }

    /** Jeux présents dans les parties analysées, le plus riche en moments d'abord. */
    val games: List<Pair<String, String>>
        get() = moments.groupBy { it.profileId to it.game }.entries.sortedByDescending { it.value.size }.map { it.key }

    companion object {
        fun key(m: FoundMoment) = "${m.sessionFile}@${m.kills.first().inWholeMilliseconds}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchView(search: SearchState, actions: UiActions, busy: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rechercher des moments", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::closeSearch) { Text("Analyses enregistrées") }
        }
        if (search.loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Filters(search, actions)
        val results = search.results
        val picked = search.picked
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${results.size} moment(s)" + if (picked.size != results.size) " · ${picked.size} coché(s)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textMuted,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = actions::montageFromSearch, enabled = picked.isNotEmpty() && !busy) {
                Text("Montage kills de ${picked.size} moment(s)")
            }
        }
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Aucun moment ne répond à ces critères. L'arme utilisée n'est pas connue : ni le jeu ni Outplayed ne la transmettent.",
                    color = Palette.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(460.dp),
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results, key = SearchState::key) { m -> MomentRow(m, SearchState.key(m) !in search.unpicked, actions) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Filters(search: SearchState, actions: UiActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val games = listOf<Pair<String?, String>>(null to "Tous les jeux") + search.games
        SingleChoiceSegmentedButtonRow {
            games.forEachIndexed { i, (id, name) ->
                SegmentedButton(
                    selected = search.game == id,
                    onClick = { actions.updateSearch { it.copy(game = id) } },
                    shape = SegmentedButtonDefaults.itemShape(i, games.size),
                ) { Text(name) }
            }
        }
        SingleChoiceSegmentedButtonRow {
            SearchPeriod.entries.forEachIndexed { i, period ->
                SegmentedButton(
                    selected = search.period == period,
                    onClick = { actions.updateSearch { it.copy(period = period) } },
                    shape = SegmentedButtonDefaults.itemShape(i, SearchPeriod.entries.size),
                ) { Text(period.label) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(1 to "Tous les kills", 2 to "Doublés et plus", 3 to "Triplés et plus").forEach { (n, label) ->
            FilterChip(selected = search.minKills == n, onClick = { actions.updateSearch { it.copy(minKills = n) } }, label = { Text(label) })
        }
        FilterChip(selected = search.ace, onClick = { actions.updateSearch { it.copy(ace = !it.ace) } }, label = { Text("Aces") })
        FilterChip(selected = search.clutch, onClick = { actions.updateSearch { it.copy(clutch = !it.clutch) } }, label = { Text("Clutchs") })
        FilterChip(selected = search.headshots, onClick = { actions.updateSearch { it.copy(headshots = !it.headshots) } }, label = { Text("Tout en headshot") })
        FilterChip(selected = search.oneTaps, onClick = { actions.updateSearch { it.copy(oneTaps = !it.oneTaps) } }, label = { Text("One taps") })
    }
}

@Composable
private fun MomentRow(m: FoundMoment, checked: Boolean, actions: UiActions) {
    val day = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.FRENCH)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (checked) Palette.surfaceHigh else Palette.surface,
        border = BorderStroke(1.dp, Palette.outline),
        modifier = Modifier.clickable { actions.toggleMoment(SearchState.key(m)) },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { actions.toggleMoment(SearchState.key(m)) })
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(
                        "ACE".takeIf { m.ace },
                        "CLUTCH".takeIf { m.clutch },
                        multiKillName(m.kills.size),
                        "${m.headshots} headshot${if (m.headshots > 1) "s" else ""}".takeIf { m.headshots > 0 },
                        "${m.oneTaps} one tap${if (m.oneTaps > 1) "s" else ""}".takeIf { m.oneTaps > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (m.ace || m.clutch || m.kills.size >= 3) Palette.accent else Palette.text,
                )
                Text(
                    "${m.game} · ${m.playedAt?.atZone(ZoneId.systemDefault())?.format(day) ?: "date inconnue"} · à ${m.kills.first().toTimecode()} dans ${m.source.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { actions.openLibraryItem(m.sessionFile) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("Ouvrir la partie")
            }
        }
    }
}

private fun multiKillName(kills: Int) = when (kills) {
    1 -> "1 kill"
    2 -> "Doublé"
    3 -> "Triplé"
    4 -> "Quadruplé"
    else -> "$kills kills"
}
