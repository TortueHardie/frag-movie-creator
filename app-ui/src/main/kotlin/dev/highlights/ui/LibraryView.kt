package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.profile.GameProfile
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.serialization.toTimecode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.name

/**
 * Écran d'accueil quand aucune analyse n'est ouverte : le dossier surveillé et les analyses déjà faites, à rouvrir
 * d'un clic (ou plusieurs cochées ensemble, pour un seul montage de la soirée).
 */
@Composable
fun LibraryView(state: UiState, actions: UiActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        startHint(state)?.let { Text(it, color = Palette.textMuted, style = MaterialTheme.typography.bodyMedium) }
        WatchCard(state, actions)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("ui.library.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.library.isNotEmpty()) {
                OutlinedButton(onClick = actions::showSearch) { Text(tr("ui.library.search")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = actions::showStats) { Text(tr("ui.library.stats")) }
            }
            Spacer(Modifier.width(8.dp))
            val selected = state.librarySelection.size
            if (selected > 0) {
                Button(onClick = actions::openLibrarySelection, enabled = state.job == null) {
                    Text(if (selected > 1) tr("ui.library.openTogether", "count" to selected) else tr("ui.common.open"))
                }
            }
        }
        if (state.library.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    tr("ui.library.empty"),
                    color = Palette.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(420.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val profiles = (state.config as? ConfigStatus.Ready)?.profiles.orEmpty()
                items(state.library, key = { it.sessionFile.toString() }) { item ->
                    LibraryRow(
                        item,
                        profileName = profiles.firstOrNull { it.id == item.profileId }?.name ?: GameProfile.localizedName(item.profileId),
                        checked = item.sessionFile in state.librarySelection,
                        fresh = item.sessionFile in state.watch.fresh,
                        enabled = state.job == null,
                        actions = actions,
                    )
                }
            }
        }
    }
}

private fun startHint(state: UiState): String? = when {
    state.config is ConfigStatus.Failed -> tr("ui.library.hintConfig")
    state.source == null -> tr("ui.library.hintStart")
    else -> tr("ui.library.hintAnalyze")
}

@Composable
private fun WatchCard(state: UiState, actions: UiActions) {
    val watch = state.watch
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("ui.watch.title"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        watch.folder?.toString()
                            ?: tr("ui.watch.hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (watch.folder == null) {
                    Button(onClick = actions::chooseWatchFolder, enabled = state.config is ConfigStatus.Ready) { Text(tr("ui.watch.choose")) }
                } else {
                    OutlinedButton(onClick = actions::chooseWatchFolder) { Text(tr("ui.common.changeEllipsis")) }
                    TextButton(onClick = actions::stopWatching) { Text(tr("ui.watch.stop")) }
                }
            }
            if (watch.folder != null) {
                val current = watch.current
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (watch.pending.isNotEmpty()) tr("ui.watch.analyzingQueued", "name" to current.name, "count" to watch.pending.size) else tr("ui.watch.analyzing", "name" to current.name),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(I18n.percent(watch.fraction), style = MaterialTheme.typography.labelLarge, color = Palette.accent)
                    }
                    LinearProgressIndicator(
                        progress = { watch.fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Palette.accent,
                        trackColor = Palette.surfaceHigh,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                } else {
                    Text(
                        when {
                            watch.pending.isNotEmpty() -> tr("ui.watch.pending", "count" to watch.pending.size)
                            else -> tr("ui.watch.idle")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                    )
                }
                watch.lastError?.let { Text(tr("ui.watch.failed", "detail" to it), style = MaterialTheme.typography.bodySmall, color = Palette.danger, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, profileName: String, checked: Boolean, fresh: Boolean, enabled: Boolean, actions: UiActions) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (checked) Palette.surfaceHigh else Palette.surface,
        border = BorderStroke(1.dp, if (checked) Palette.accent else Palette.outline),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { actions.openLibraryItem(item.sessionFile) },
    ) {
        Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { actions.toggleLibraryItem(item.sessionFile) }, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.source.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (fresh) {
                        Spacer(Modifier.width(8.dp))
                        Text(tr("ui.library.new"), style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                    }
                }
                val played = (item.recordedAt ?: item.analyzedAt).atZone(ZoneId.systemDefault())
                Text(
                    listOfNotNull(
                        I18n.date(played, "format.dateTime.medium"),
                        item.duration.toTimecode().substringBefore('.'),
                        profileName,
                        eventsLabel(item.events).ifEmpty { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.sourceExists) {
                    Text(tr("ui.library.missingVideo"), style = MaterialTheme.typography.bodySmall, color = Palette.danger)
                }
            }
        }
    }
}

