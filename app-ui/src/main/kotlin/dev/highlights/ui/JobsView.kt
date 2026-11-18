package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr

/**
 * Carte de la file des traitements : celui en cours (avancement, annuler), ceux qui attendent (ordre modifiable,
 * annuler), les derniers finis (ouvrir le résultat, voir l'erreur). Rien quand la file est vide.
 *
 * Hauteur bornée ([MAX_HEIGHT], et la part que lui laisse [modifier]) : l'en-tête et le traitement en cours restent
 * fixes, l'attente et les finis défilent. Sans borne, une dizaine de traitements finis poussaient « Exporter le
 * montage » hors de l'écran, à une hauteur de 0.
 */
@Composable
fun JobsCard(jobs: List<QueuedJob>, actions: UiActions, modifier: Modifier = Modifier) {
    if (jobs.isEmpty()) return
    val running = jobs.filter { it.status == JobStatus.RUNNING }
    val waiting = jobs.filter { it.status == JobStatus.WAITING }
    val finished = jobs.filter { !it.active }.reversed()
    Surface(modifier.heightIn(max = MAX_HEIGHT), shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("ui.jobs.title"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (waiting.isNotEmpty()) Text(tr("ui.jobs.waitingCount", "count" to waiting.size), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                if (finished.isNotEmpty()) TextButton(onClick = actions::clearFinishedJobs) { Text(tr("ui.jobs.clear")) }
            }
            running.forEach { RunningRow(it, actions) }
            if (waiting.isNotEmpty() || finished.isNotEmpty()) {
                // La partie qui grandit : elle prend la place qui reste dans la carte, et défile au-delà.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    waiting.forEachIndexed { i, job -> WaitingRow(job, i, waiting.size, actions) }
                    if (finished.isNotEmpty()) {
                        HorizontalDivider(color = Palette.outline)
                        finished.forEach { FinishedRow(it, actions) }
                    }
                }
            }
        }
    }
}

/** Hauteur maximale de la carte : en-tête, traitement en cours et trois ou quatre lignes ; le reste défile. */
private val MAX_HEIGHT = 360.dp

@Composable
private fun RunningRow(job: QueuedJob, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(job.stage.ifEmpty { tr("ui.job.preparing") }, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(I18n.percent(job.fraction), style = MaterialTheme.typography.titleLarge, color = Palette.accent)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { actions.cancelQueuedJob(job.id) }) { Text(tr("ui.common.cancel")) }
        }
        LinearProgressIndicator(
            progress = { job.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Palette.accent,
            trackColor = Palette.surfaceHigh,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            job.eta?.let { tr("ui.job.time", "elapsed" to job.elapsed, "remaining" to it) } ?: tr("ui.job.timeEstimating", "elapsed" to job.elapsed),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )
    }
}

@Composable
private fun WaitingRow(job: QueuedJob, index: Int, count: Int, actions: UiActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("ui.jobs.position", "position" to index + 1), style = MaterialTheme.typography.labelLarge, color = Palette.textMuted, maxLines = 1, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tr("ui.jobs.waiting"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
        }
        TextButton(onClick = { actions.moveQueuedJob(job.id, -1) }, enabled = index > 0) { Text(tr("ui.jobs.up")) }
        TextButton(onClick = { actions.moveQueuedJob(job.id, 1) }, enabled = index < count - 1) { Text(tr("ui.jobs.down")) }
        TextButton(onClick = { actions.cancelQueuedJob(job.id) }) { Text(tr("ui.common.cancel")) }
    }
}

@Composable
private fun FinishedRow(job: QueuedJob, actions: UiActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (label, color) = when (job.status) {
            JobStatus.DONE -> tr("ui.jobs.done") to Palette.success
            JobStatus.FAILED -> tr("ui.jobs.failed") to Palette.danger
            else -> tr("ui.jobs.cancelled") to Palette.textMuted
        }
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (job.status == JobStatus.FAILED && job.error != null) "$label · ${job.error}" else label,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (job.result != null) TextButton(onClick = { actions.openJobResult(job.id) }) { Text(tr("ui.jobs.open")) }
        if (job.status == JobStatus.FAILED) TextButton(onClick = { actions.showJobError(job.id) }) { Text(tr("ui.jobs.details")) }
    }
}

/** Fermeture avec des traitements en cours ou en attente : ils seront annulés, rien ne reprend au prochain lancement. */
@Composable
fun QuitDialog(count: Int, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::cancelQuit,
        title = { Text(tr("ui.jobs.quitTitle")) },
        text = { Text(tr("ui.jobs.quitMessage", "count" to count)) },
        confirmButton = { Button(onClick = actions::confirmQuit) { Text(tr("ui.jobs.quitConfirm")) } },
        dismissButton = { TextButton(onClick = actions::cancelQuit) { Text(tr("ui.jobs.quitStay")) } },
    )
}
