package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.pipeline.PlaySessions
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Une ligne de la liste des analyses : une capture seule, ou une séance de clips rangés ensemble. */
sealed interface LibraryRow {
    val key: Path

    data class Single(val item: LibraryItem) : LibraryRow {
        override val key: Path get() = item.sessionFile
    }

    /**
     * Clips d'un même jeu sauvegardés à la suite (moins de [PlaySessions.CLIP_GAP] d'écart), le plus récent d'abord comme
     * le reste de la liste. [key] : la session du plus ancien, stable quand un clip de plus arrive.
     */
    data class Clips(val items: List<LibraryItem>) : LibraryRow {
        override val key: Path get() = items.last().sessionFile
        val profileId: String get() = items.first().profileId
        val start: Instant? get() = items.mapNotNull { it.recordedAt }.minOrNull()
        val end: Instant? get() = items.mapNotNull { i -> i.recordedAt?.plus(i.duration.toJavaDuration()) }.maxOrNull()
        val events: Map<String, Int> get() = items.flatMap { it.events.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val files: List<Path> get() = items.map { it.sessionFile }
    }
}

object LibraryRows {
    /**
     * [items] (la plus récente d'abord) en lignes : deux clips ou plus d'un même jeu à la suite font une séance, comme
     * dans les statistiques ([PlaySessions]) ; une partie entière, un clip isolé ou sans heure restent seuls. Les
     * comptes d'événements de la séance additionnent ceux des clips : un kill vu par deux clips qui se chevauchent
     * compte deux fois ici (les statistiques, elles, le retirent).
     */
    fun of(items: List<LibraryItem>, gap: Duration = PlaySessions.CLIP_GAP): List<LibraryRow> {
        val rows = mutableListOf<LibraryRow>()
        var run = mutableListOf<LibraryItem>()
        fun flush() {
            if (run.size >= 2) rows += LibraryRow.Clips(run) else run.forEach { rows += LibraryRow.Single(it) }
            run = mutableListOf()
        }
        // Par heure de jeu, la plus récente d'abord : une capture réanalysée (remontée en tête par sa date d'analyse) ne
        // coupe pas la séance de ses voisines.
        for (item in items.sortedByDescending { it.recordedAt ?: it.analyzedAt }) {
            val previous = run.lastOrNull()
            val joins = previous != null && item.clip && item.profileId == previous.profileId && item.recordedAt != null &&
                run.mapNotNull { it.recordedAt }.minOrNull()?.let { earliest -> !item.recordedAt.plus(item.duration.toJavaDuration()).plus(gap.toJavaDuration()).isBefore(earliest) } == true
            if (!joins) flush()
            if (item.clip && item.recordedAt != null) run.add(item) else rows += LibraryRow.Single(item)
        }
        flush()
        return rows
    }
}

/** Séance de clips repliée en une ligne : la cocher coche tous ses clips, cliquer les ouvre ensemble. */
@Composable
fun ClipsRow(row: LibraryRow.Clips, profileName: String, selection: Set<Path>, fresh: Set<Path>, open: Boolean, enabled: Boolean, actions: UiActions) {
    val checked = selection.containsAll(row.files)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (checked) Palette.surfaceHigh else Palette.surface,
            border = BorderStroke(1.dp, if (checked) Palette.accent else Palette.outline),
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { actions.openLibraryItems(row.files) },
        ) {
            Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { actions.toggleLibraryItems(row.files) }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("ui.library.clipsTitle", "count" to row.items.size, "game" to profileName),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.files.any { it in fresh }) {
                            Spacer(Modifier.width(8.dp))
                            Text(tr("ui.library.new"), style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                        }
                    }
                    val zone = ZoneId.systemDefault()
                    Text(
                        listOfNotNull(
                            row.start?.let { I18n.date(it.atZone(zone), "format.date.long") },
                            row.start?.let { s -> tr("ui.library.clipsWhen", "start" to I18n.date(s.atZone(zone), "format.time"), "end" to I18n.date((row.end ?: s).atZone(zone), "format.time")) },
                            eventsLabel(row.events).ifEmpty { null },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.items.any { !it.sourceExists }) {
                        Text(tr("ui.library.missingClips"), style = MaterialTheme.typography.bodySmall, color = Palette.danger)
                    }
                }
                TextButton(onClick = { actions.toggleClipGroup(row.key) }) {
                    Text(if (open) tr("ui.library.clipsHide") else tr("ui.library.clipsShow"))
                }
            }
        }
        if (open) {
            Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                row.items.forEach { item ->
                    LibraryItemRow(item, profileName, checked = item.sessionFile in selection, fresh = item.sessionFile in fresh, enabled = enabled, actions = actions)
                }
            }
        }
    }
}
