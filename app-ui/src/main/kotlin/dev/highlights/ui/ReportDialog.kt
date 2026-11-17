package dev.highlights.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * « Signaler un problème » : ce que contiendra le ZIP (avant tout enregistrement), puis, une fois enregistré, le dossier
 * à ouvrir et le ticket GitHub prérempli. Rien n'est envoyé par l'application.
 */
@Composable
fun ReportDialog(report: ReportUiState, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::closeReport,
        modifier = Modifier.widthIn(max = 640.dp),
        title = { Text(tr("ui.report.title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    report.failed != null -> Text(tr("ui.report.failed", "detail" to report.failed), color = Palette.danger)
                    report.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("ui.report.collecting"), color = Palette.textMuted)
                    }
                    report.saved != null -> {
                        Text(tr("ui.report.saved"))
                        SelectionContainer { Text(report.saved.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        Text(tr("ui.report.nextSteps"), style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        Text(tr("ui.report.intro"))
                        Column(
                            Modifier.fillMaxWidth().background(Palette.background, RoundedCornerShape(8.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            report.items.forEach { item ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            item.files.joinToString(I18n.text("format.listComma")),
                                            style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    // Moins d'un Ko s'afficherait « 0 Ko » : arrondi au Ko supérieur.
                                    Text(formatBytes(item.size.coerceAtLeast(1024)), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                                }
                            }
                        }
                        Text(tr("ui.report.total", "count" to report.items.sumOf { it.files.size }, "size" to formatBytes(report.totalSize)), style = MaterialTheme.typography.bodySmall)
                        Text(tr("ui.report.privacy"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    }
                }
            }
        },
        confirmButton = {
            when {
                report.saved != null -> Button(onClick = actions::openIssuePage) { Text(tr("ui.report.openIssue")) }
                else -> Button(onClick = actions::saveReport, enabled = !report.loading && report.failed == null) { Text(tr("ui.report.save")) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.saved != null) OutlinedButton(onClick = actions::revealReport) { Text(tr("ui.common.showInFolder")) }
                TextButton(onClick = actions::closeReport) { Text(tr(if (report.saved != null) "ui.common.close" else "ui.common.cancel")) }
            }
        },
    )
}

/** Après un plantage (exception non gérée, ou JVM tombée la dernière fois) : proposer de le signaler. */
@Composable
fun CrashDialog(crash: CrashNotice, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::dismissCrash,
        title = { Text(tr("ui.crash.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val at = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(I18n.locale).withZone(ZoneId.systemDefault()).format(crash.at)
                Text(tr(if (crash.jvm) "ui.crash.jvm" else "ui.crash.message", "time" to at))
                Text(
                    crash.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                Text(tr("ui.crash.hint"), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = actions::reportCrash) { Text(tr("ui.crash.report")) } },
        dismissButton = { TextButton(onClick = actions::dismissCrash) { Text(tr("ui.update.later")) } },
    )
}

/** Pas assez de mémoire disponible pour lancer le traitement : expliquer plutôt que tomber en route. */
@Composable
fun MemoryDialog(warning: MemoryWarning, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::dismissMemoryWarning,
        title = { Text(tr("ui.memory.title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("ui.memory.message", "task" to warning.task, "available" to formatBytes(warning.available), "needed" to formatBytes(warning.needed)))
                Text(tr("ui.memory.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
        },
        confirmButton = { Button(onClick = actions::retryMemoryCheck) { Text(tr("ui.common.retry")) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions::startDespiteMemory) { Text(tr("ui.memory.startAnyway")) }
                TextButton(onClick = actions::dismissMemoryWarning) { Text(tr("ui.common.cancel")) }
            }
        },
    )
}
