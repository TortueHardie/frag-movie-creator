package dev.highlights.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.OutputFormat
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MontageDialog(montage: MontageUiState, state: UiState, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::closeMontage,
        title = { Text("Montage kills") },
        text = {
            // La liste dépasse les petits écrans : elle défile plutôt que de rogner les boutons du bas.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Coupes et kills calés sur les temps de la musique, meilleur moment sur la partie la plus intense. " +
                        "C'est l'écran qui compte : le micro reste au niveau du jeu, sauf si tu mets les réactions en avant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                )

                DialogGroup("Musique") {
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
                        OutlinedTextField(
                            value = montage.maxDurationText,
                            onValueChange = { v -> actions.updateMontage { it.copy(maxDurationText = v) } },
                            singleLine = true,
                            isError = montage.maxDuration == null,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                    MontageToggle("Montée en puissance (meilleur kill sur la drop)", montage.buildUp) { v ->
                        actions.updateMontage { it.copy(buildUp = v) }
                    }
                }

                DialogGroup("Formats") {
                    OutputFormat.entries.forEach { format ->
                        MontageToggle(formatLabel(format, state.source?.media), format in montage.formats) { v ->
                            actions.updateMontage { it.copy(formats = if (v) it.formats + format else it.formats - format) }
                        }
                    }
                }

                DialogGroup("Effets") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        EffectDensity.entries.forEachIndexed { i, density ->
                            SegmentedButton(
                                selected = montage.density == density,
                                onClick = { actions.updateMontage { it.copy(density = density) } },
                                shape = SegmentedButtonDefaults.itemShape(i, EffectDensity.entries.size),
                            ) { Text(densityLabel(density)) }
                        }
                    }
                    Text(densityHint(montage.density), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    MontageToggle("Zoom punch sur le kill", montage.zoom) { v -> actions.updateMontage { it.copy(zoom = v) } }
                    MontageToggle("Flash blanc aux coupes fortes", montage.flash) { v -> actions.updateMontage { it.copy(flash = v) } }
                    MontageToggle("Ralenti sur le kill", montage.slowMotion) { v -> actions.updateMontage { it.copy(slowMotion = v) } }
                    MontageToggle("Textes DOUBLÉ / TRIPLÉ", montage.text) { v -> actions.updateMontage { it.copy(text = v) } }
                }

                DialogGroup("Son") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        GameAudio.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = montage.gameAudio == mode,
                                onClick = { actions.updateMontage { it.copy(gameAudio = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, GameAudio.entries.size),
                            ) { Text(gameAudioLabel(mode)) }
                        }
                    }
                    Text(gameAudioHint(montage.gameAudio, montage.reactions), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    if (montage.gameAudio == GameAudio.FULL) {
                        MontageToggle("Mettre en avant les réactions (voix, rires)", montage.reactions) { v ->
                            actions.updateMontage { it.copy(reactions = v) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Équilibre", modifier = Modifier.weight(1f))
                        Text(balanceLabel(montage.balance), color = Palette.accent)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Musique", style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                        Slider(
                            value = montage.balance.toFloat(),
                            onValueChange = { v -> actions.updateMontage { it.copy(balance = v.toDouble()) } },
                            valueRange = -1f..1f,
                            // Crans de 0,25 : 1,5 dB de chaque côté, assez fin sans devenir un réglage au hasard.
                            steps = 7,
                            modifier = Modifier.weight(1f),
                        )
                        Text("Jeu", style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = actions::createMontage, enabled = montage.canCreate) { Text("Créer le montage") } },
        dismissButton = { TextButton(onClick = actions::closeMontage) { Text("Annuler") } },
    )
}

internal fun densityLabel(density: EffectDensity) = when (density) {
    EffectDensity.SOBER -> "Sobre"
    EffectDensity.BALANCED -> "Normal"
    EffectDensity.HEAVY -> "Chargé"
}

internal fun densityHint(density: EffectDensity) = when (density) {
    EffectDensity.SOBER -> "Ralenti sur la drop seulement, aucun zoom : l'action reste lisible."
    EffectDensity.BALANCED -> "Une emphase par plan : ralenti sur les moments forts, zoom sur les autres."
    EffectDensity.HEAVY -> "Tous les effets sur tous les plans : l'image finit par être surchargée."
}

internal fun gameAudioLabel(mode: GameAudio) = when (mode) {
    GameAudio.FULL -> "Tout le jeu"
    GameAudio.KILLS -> "Kills seulement"
}

internal fun gameAudioHint(mode: GameAudio, reactions: Boolean = false) = when (mode) {
    GameAudio.FULL -> if (reactions) {
        "Son du jeu en continu, plus fort aux kills ; la voix est montée et la musique baisse quand on parle ou qu'on rit."
    } else {
        "Son du jeu en continu, plus fort aux kills ; le micro reste au niveau du jeu."
    }
    GameAudio.KILLS -> "Seul le son du kill s'entend, et la musique baisse dessous pour le laisser passer."
}

/** Écart entre jeu et musique, en dB : chaque côté bouge de 6 dB par cran de 1, en sens opposés. */
internal fun balanceLabel(balance: Double): String {
    val db = Math.round(balance * 12 * 2) / 2.0
    return when {
        db == 0.0 -> "Neutre"
        db > 0 -> "Jeu +%s dB".format(fmtDb(db))
        else -> "Musique +%s dB".format(fmtDb(-db))
    }
}

private fun fmtDb(db: Double) = if (db % 1.0 == 0.0) "%.0f".format(db) else "%.1f".format(db)

/** Un bloc du dialogue : un intitulé discret et ses réglages, séparés du bloc suivant. */
@Composable
private fun DialogGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
        content()
    }
}

@Composable
private fun MontageToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
