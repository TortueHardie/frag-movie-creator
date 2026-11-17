package dev.highlights.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr

/**
 * Montage automatique des soirées, dans la carte du dossier surveillé : la case (désactivée par défaut), le délai qui
 * dit qu'une soirée est finie et le minimum de kills, le montage en cours et le dernier fait.
 */
@Composable
fun EveningAutoSection(watch: WatchState, actions: UiActions) {
    val auto = watch.evenings
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { actions.setEveningMontage(!auto.enabled) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = auto.enabled, onCheckedChange = actions::setEveningMontage)
            Column {
                Text(tr("ui.evening.toggle"), style = MaterialTheme.typography.bodyMedium)
                Text(tr("ui.evening.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
        }
        if (auto.enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.width(40.dp))
                Text(tr("ui.evening.delay"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = watch.eveningDelayText,
                    onValueChange = actions::setEveningDelay,
                    singleLine = true,
                    isError = I18n.parseDuration(watch.eveningDelayText)?.isPositive() != true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(96.dp),
                )
                Text(tr("ui.evening.minKills"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = auto.minKills.toString(),
                    onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let(actions::setEveningMinKills) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
        watch.montaging?.let { label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("ui.evening.running", "evening" to label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(I18n.percent(watch.montageFraction), style = MaterialTheme.typography.labelLarge, color = Palette.accent)
            }
        }
        watch.lastEvening?.let { done ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(done.text, style = MaterialTheme.typography.bodySmall, color = Palette.success, modifier = Modifier.weight(1f))
                if (done.video != null) TextButton(onClick = actions::openLastEvening) { Text(tr("ui.evening.open")) }
            }
        }
        watch.eveningProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.danger, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}
