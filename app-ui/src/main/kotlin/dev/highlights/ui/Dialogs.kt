package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
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
import androidx.compose.ui.text.style.TextOverflow
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
        confirmButton = { Button(onClick = actions::dismissError) { Text(tr("ui.common.ok")) } },
        dismissButton = {
            Row {
                TextButton(onClick = actions::reportError) { Text(tr("ui.error.report")) }
                if (error.details.isNotEmpty()) {
                    TextButton(onClick = {
                        val text = (listOf(error.title, error.message) + error.details).joinToString("\n")
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    }) { Text(tr("ui.error.copyDetails")) }
                }
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
                    tr("ui.preview.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions::editProfile) { Text(tr("ui.preview.editProfile")) }
                    Button(onClick = actions::regenerateVerticalPreview, enabled = !busy) { Text(if (busy) tr("ui.preview.generating") else tr("ui.preview.regenerate")) }
                    TextButton(onClick = { actions.reveal(preview.path) }) { Text(tr("ui.preview.file")) }
                }
            }
        }
    }
}

/**
 * Choix du style en haut de la fenêtre : une puce par style, plus « Aucun » (réglages du profil seuls), la phrase qui
 * dit ce que fait le style choisi, et les cases modifiées à la main, qu'on peut rendre au style.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MontageStylePicker(montage: MontageUiState, actions: UiActions) {
    if (montage.styles.isEmpty()) return
    DialogGroup(tr("ui.montage.style")) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.FilterChip(
                selected = montage.style == null,
                onClick = { actions.updateMontage { it.chooseStyle(null) } },
                label = { Text(tr("ui.montage.styleNone"), maxLines = 1) },
            )
            montage.styles.forEach { style ->
                androidx.compose.material3.FilterChip(
                    selected = montage.style == style.id,
                    onClick = { actions.updateMontage { it.chooseStyle(style.id) } },
                    label = { Text(style.name, maxLines = 1) },
                )
            }
        }
        val description = montage.chosenStyle?.shownDescription ?: tr("ui.montage.styleNone.hint").takeIf { montage.style == null }
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted) }
        if (montage.touched.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("ui.montage.styleTouched", "count" to montage.touched.size),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.accent,
                )
                TextButton(onClick = { actions.updateMontage { it.resetToStyle() } }) { Text(tr("ui.montage.styleReset")) }
            }
        }
    }
}

internal fun densityLabel(density: EffectDensity) = tr("ui.montage.density.${density.name.lowercase()}")

internal fun densityHint(density: EffectDensity) = tr("ui.montage.density.${density.name.lowercase()}.hint")

internal fun gameAudioLabel(mode: GameAudio) = tr("ui.montage.gameAudio.${mode.name.lowercase()}")

internal fun gameAudioHint(mode: GameAudio, reactions: Boolean = false) = when (mode) {
    GameAudio.FULL -> tr(if (reactions) "ui.montage.gameAudio.full.hintReactions" else "ui.montage.gameAudio.full.hint")
    GameAudio.KILLS -> tr("ui.montage.gameAudio.kills.hint")
}

/** Écart entre jeu et musique, en dB : chaque côté bouge de 6 dB par cran de 1, en sens opposés. */
internal fun balanceLabel(balance: Double): String {
    val db = Math.round(balance * 12 * 2) / 2.0
    return when {
        db == 0.0 -> tr("ui.montage.balanceNeutral")
        db > 0 -> tr("ui.montage.balanceGameDb", "db" to I18n.decimal(db))
        else -> tr("ui.montage.balanceMusicDb", "db" to I18n.decimal(-db))
    }
}


/** Un bloc du dialogue : un intitulé discret et ses réglages, séparés du bloc suivant. */
@Composable
internal fun DialogGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
        content()
    }
}

@Composable
internal fun MontageToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
