package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
enum class SearchPeriod(val days: Long?) {
    WEEK(7), MONTH(30), ALL(null),
    ;

    val label: String get() = days?.let { tr("ui.search.days", "count" to it.toInt()) } ?: tr("ui.search.allTime")
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
            Text(tr("ui.search.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::closeSearch) { Text(tr("ui.library.title")) }
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
                tr("ui.search.results", "count" to results.size) + if (picked.size != results.size) " · " + tr("ui.search.picked", "count" to picked.size) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textMuted,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = actions::montageFromSearch, enabled = picked.isNotEmpty() && !busy) {
                Text(tr("ui.search.montage", "count" to picked.size))
            }
        }
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    tr("ui.search.empty"),
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Filters(search: SearchState, actions: UiActions) {
    // Puces dans des FlowRow : dans une langue aux libellés plus longs (allemand), elles passent à la ligne au lieu
    // d'être coupées (les boutons segmentés ne prennent pas la largeur de leur texte).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val games = listOf<Pair<String?, String>>(null to tr("ui.search.allGames")) + search.games
        games.forEach { (id, name) ->
            FilterChip(selected = search.game == id, onClick = { actions.updateSearch { it.copy(game = id) } }, label = { Text(name, maxLines = 1) })
        }
        Spacer(Modifier.width(12.dp))
        SearchPeriod.entries.forEach { period ->
            FilterChip(selected = search.period == period, onClick = { actions.updateSearch { it.copy(period = period) } }, label = { Text(period.label, maxLines = 1) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(1 to tr("ui.search.allKills"), 2 to tr("ui.search.doublesUp"), 3 to tr("ui.search.triplesUp")).forEach { (n, label) ->
            FilterChip(selected = search.minKills == n, onClick = { actions.updateSearch { it.copy(minKills = n) } }, label = { Text(label) })
        }
        FilterChip(selected = search.ace, onClick = { actions.updateSearch { it.copy(ace = !it.ace) } }, label = { Text(tr("ui.search.aces")) })
        FilterChip(selected = search.clutch, onClick = { actions.updateSearch { it.copy(clutch = !it.clutch) } }, label = { Text(tr("ui.search.clutches")) })
        FilterChip(selected = search.headshots, onClick = { actions.updateSearch { it.copy(headshots = !it.headshots) } }, label = { Text(tr("ui.search.allHeadshots")) })
        FilterChip(selected = search.oneTaps, onClick = { actions.updateSearch { it.copy(oneTaps = !it.oneTaps) } }, label = { Text(tr("ui.search.oneTaps")) })
    }
}

@Composable
private fun MomentRow(m: FoundMoment, checked: Boolean, actions: UiActions) {
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
                        tr("video.ace").takeIf { m.ace },
                        tr("video.clutch").takeIf { m.clutch },
                        multiKillName(m.kills.size),
                        tr("ui.count.headshots", "count" to m.headshots).takeIf { m.headshots > 0 },
                        tr("ui.count.oneTaps", "count" to m.oneTaps).takeIf { m.oneTaps > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (m.ace || m.clutch || m.kills.size >= 3) Palette.accent else Palette.text,
                )
                Text(
                    tr("ui.search.where", "game" to m.game, "date" to (m.playedAt?.let { I18n.date(it.atZone(ZoneId.systemDefault()), "format.dateTime.medium") } ?: tr("ui.search.unknownDate")), "at" to m.kills.first().toTimecode(), "file" to m.source.fileName.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { actions.openLibraryItem(m.sessionFile) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(tr("ui.search.openGame"))
            }
        }
    }
}

/** « 1 kill », « Double kill »… : le nom d'un multi-kill, le nombre de kills au-delà du quadruplé. */
private fun multiKillName(kills: Int) = when (kills) {
    2, 3, 4 -> tr("ui.multiKill.$kills")
    else -> tr("ui.count.kills", "count" to kills)
}
