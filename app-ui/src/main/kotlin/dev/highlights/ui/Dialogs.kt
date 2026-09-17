package dev.highlights.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun ErrorDialog(error: ErrorInfo, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::dismissError,
        title = { Text(error.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(error.message)
                if (error.details.isNotEmpty()) {
                    SelectionContainer {
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Palette.background)
                                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp),
                        ) {
                            Text(error.details.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = actions::dismissError) { Text("OK") } },
        dismissButton = {
            if (error.details.isNotEmpty()) {
                TextButton(onClick = {
                    val text = (listOf(error.title, error.message) + error.details).joinToString("\n")
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                }) { Text("Copier le détail") }
            }
        },
    )
}

/** Fenêtre séparée (format portrait) pour l'aperçu 9:16 et le réglage des zones du HUD. */
@Composable
fun VerticalPreviewWindow(preview: ImagePreview, busy: Boolean, actions: UiActions) {
    DialogWindow(
        onCloseRequest = actions::dismissImagePreview,
        state = rememberDialogState(size = DpSize(520.dp, 1000.dp)),
        title = preview.title,
    ) {
        HighlightsTheme {
            Column(Modifier.fillMaxSize().background(Palette.background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val image = rememberImage(preview.path, preview.version)
                    if (image != null) {
                        Image(image, contentDescription = preview.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Text(
                    "Zones du HUD : « Éditer » le profil, enregistre, puis « Régénérer ».",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions::editProfile) { Text("Éditer le profil") }
                    Button(onClick = actions::regenerateVerticalPreview, enabled = !busy) { Text(if (busy) "Génération…" else "Régénérer") }
                    TextButton(onClick = { actions.reveal(preview.path) }) { Text("Fichier") }
                }
            }
        }
    }
}

/** Réglages du montage « tous les kills » : musique, durée, ordre, formats et effets. */
@Composable
fun MontageDialog(montage: MontageUiState, state: UiState, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::closeMontage,
        title = { Text("Montage kills") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Coupes et kills calés sur les temps de la musique, meilleur moment sur la partie la plus intense, " +
                        "voix et rires jamais coupés.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        montage.music?.fileName?.toString() ?: "Aucune musique choisie",
                        modifier = Modifier.weight(1f),
                        color = if (montage.music == null) Palette.textMuted else Palette.text,
                    )
                    OutlinedButton(onClick = actions::chooseMusic) { Text("Choisir…") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Durée maximale", modifier = Modifier.weight(1f))
                    androidx.compose.material3.OutlinedTextField(
                        value = montage.maxDurationText,
                        onValueChange = { v -> actions.updateMontage { it.copy(maxDurationText = v) } },
                        singleLine = true,
                        isError = montage.maxDuration == null,
                        modifier = Modifier.width(110.dp),
                    )
                }
                MontageToggle("Montée en puissance (meilleur kill sur la drop)", montage.buildUp) { v -> actions.updateMontage { it.copy(buildUp = v) } }
                Text("Formats", style = MaterialTheme.typography.labelLarge)
                dev.highlights.core.model.OutputFormat.entries.forEach { format ->
                    MontageToggle(formatLabel(format, state.source?.media), format in montage.formats) { v ->
                        actions.updateMontage { it.copy(formats = if (v) it.formats + format else it.formats - format) }
                    }
                }
                Text("Effets", style = MaterialTheme.typography.labelLarge)
                MontageToggle("Zoom punch sur le kill", montage.zoom) { v -> actions.updateMontage { it.copy(zoom = v) } }
                MontageToggle("Flash blanc aux coupes", montage.flash) { v -> actions.updateMontage { it.copy(flash = v) } }
                MontageToggle("Ralenti sur le kill", montage.slowMotion) { v -> actions.updateMontage { it.copy(slowMotion = v) } }
                MontageToggle("Textes DOUBLÉ / TRIPLÉ et compteur", montage.text) { v -> actions.updateMontage { it.copy(text = v) } }
            }
        },
        confirmButton = { Button(onClick = actions::createMontage, enabled = montage.canCreate) { Text("Créer le montage") } },
        dismissButton = { TextButton(onClick = actions::closeMontage) { Text("Annuler") } },
    )
}

@Composable
private fun MontageToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
