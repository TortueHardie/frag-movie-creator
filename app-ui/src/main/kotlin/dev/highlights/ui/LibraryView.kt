package dev.highlights.ui

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
            Text("Analyses enregistrées", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val selected = state.librarySelection.size
            if (selected > 0) {
                Button(onClick = actions::openLibrarySelection, enabled = state.job == null) {
                    Text(if (selected > 1) "Ouvrir les $selected ensemble" else "Ouvrir")
                }
            }
        }
        if (state.library.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "Aucune analyse pour l'instant. Chaque capture analysée apparaîtra ici et se rouvrira sans être réanalysée.",
                    color = Palette.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(420.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.library, key = { it.sessionFile.toString() }) { item ->
                    LibraryRow(
                        item,
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
    state.config is ConfigStatus.Failed -> "Corrige la configuration à gauche pour commencer."
    state.source == null -> "Choisis une ou plusieurs captures à gauche (ou glisse-les dans la fenêtre), ou rouvre une analyse ci-dessous. " +
        "Plusieurs captures donnent un seul montage, dans l'ordre où les parties ont été jouées."
    else -> "Vérifie les réglages puis clique sur « Analyser »."
}

@Composable
private fun WatchCard(state: UiState, actions: UiActions) {
    val watch = state.watch
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dossier surveillé", style = MaterialTheme.typography.titleMedium)
                    Text(
                        watch.folder?.toString()
                            ?: "Choisis le dossier où ton enregistreur (Outplayed, OBS…) dépose les parties : chaque nouvelle capture y sera analysée en fond.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (watch.folder == null) {
                    Button(onClick = actions::chooseWatchFolder, enabled = state.config is ConfigStatus.Ready) { Text("Surveiller un dossier…") }
                } else {
                    OutlinedButton(onClick = actions::chooseWatchFolder) { Text("Changer…") }
                    TextButton(onClick = actions::stopWatching) { Text("Arrêter") }
                }
            }
            if (watch.folder != null) {
                val current = watch.current
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Analyse de ${current.name}" + if (watch.pending.isNotEmpty()) " · ${watch.pending.size} en attente" else "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("%.0f %%".format(watch.fraction * 100), style = MaterialTheme.typography.labelLarge, color = Palette.accent)
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
                            watch.pending.isNotEmpty() -> "${watch.pending.size} capture(s) en attente, analysées dès la fin du traitement en cours."
                            else -> "En attente de nouvelles captures. Une partie est analysée quelques secondes après la fin de son enregistrement."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                    )
                }
                watch.lastError?.let { Text("Échec : $it", style = MaterialTheme.typography.bodySmall, color = Palette.danger, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, checked: Boolean, fresh: Boolean, enabled: Boolean, actions: UiActions) {
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
                        Text("NOUVEAU", style = MaterialTheme.typography.labelSmall, color = Palette.accent)
                    }
                }
                val played = (item.recordedAt ?: item.analyzedAt).atZone(ZoneId.systemDefault())
                Text(
                    listOfNotNull(
                        LIBRARY_DATE.format(played),
                        item.duration.toTimecode().substringBefore('.'),
                        item.profileId,
                        eventsLabel(item.events).ifEmpty { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.sourceExists) {
                    Text("Vidéo introuvable : l'analyse se rouvre, mais sans export ni aperçu.", style = MaterialTheme.typography.bodySmall, color = Palette.danger)
                }
            }
        }
    }
}

private val LIBRARY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.FRENCH)
