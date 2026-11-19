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

/** Réglages du montage « tous les kills » : musique, durée, ordre, formats et effets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MontageDialog(montage: MontageUiState, state: UiState, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::closeMontage,
        title = { Text(tr("ui.montage.title")) },
        text = {
            // La liste dépasse les petits écrans : elle défile plutôt que de rogner les boutons du bas.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    tr("ui.montage.intro"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                )

                montage.pick?.let { pick ->
                    Text(
                        tr("ui.montage.picked", "count" to pick.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.accent,
                    )
                }

                MontageStylePicker(montage, actions)

                DialogGroup(tr("ui.montage.music")) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to tr("ui.montage.oneTrack"), true to tr("ui.montage.fromFolder")).forEachIndexed { i, (library, label) ->
                            SegmentedButton(
                                selected = montage.useLibrary == library,
                                onClick = { actions.updateMontage { it.copy(useLibrary = library) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                    if (montage.useLibrary) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                montage.musicLibrary?.toString() ?: tr("ui.montage.noFolder"),
                                modifier = Modifier.weight(1f),
                                color = if (montage.musicLibrary == null) Palette.textMuted else Palette.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            OutlinedButton(onClick = actions::chooseMusicLibrary) { Text(tr("ui.common.chooseEllipsis")) }
                        }
                        Text(
                            tr("ui.montage.folderHint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textMuted,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                montage.music?.fileName?.toString() ?: tr("ui.montage.noTrack"),
                                modifier = Modifier.weight(1f),
                                color = if (montage.music == null) Palette.textMuted else Palette.text,
                            )
                            OutlinedButton(onClick = actions::chooseMusic) { Text(tr("ui.common.chooseEllipsis")) }
                        }
                        MontageToggle(tr("ui.montage.fromStart"), montage.musicFromStart) { v ->
                            actions.updateMontage { it.copy(musicFromStart = v) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("ui.montage.maxDuration"), modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = montage.maxDurationText,
                            onValueChange = { v -> actions.updateMontage { it.copy(maxDurationText = v) } },
                            singleLine = true,
                            isError = montage.maxDuration == null,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                    MontageToggle(tr("ui.montage.fitKills"), montage.fitKills) { v ->
                        actions.updateMontage { it.copy(fitKills = v) }
                    }
                }

                MontagePlanEntry(montage, actions)

                DialogGroup(tr("ui.montage.formats")) {
                    PlatformPicker(state.platforms, montage.platform) { id -> actions.updateMontage { it.copy(platform = id) } }
                    // Une plateforme impose son format (et sa durée maximale devient un plafond de plus).
                    if (montage.platform == null) {
                        OutputFormat.entries.forEach { format ->
                            MontageToggle(formatLabel(format, state.source?.media), format in montage.formats) { v ->
                                actions.updateMontage { it.copy(formats = if (v) it.formats + format else it.formats - format) }
                            }
                        }
                    }
                }

                DialogGroup(tr("ui.montage.sound")) {
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
                        MontageToggle(tr("ui.montage.reactions"), montage.reactions) { v ->
                            actions.updateMontage { it.copy(reactions = v) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("ui.montage.balance"), modifier = Modifier.weight(1f))
                        Text(balanceLabel(montage.balance), color = Palette.accent)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("ui.montage.balanceMusic"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                        Slider(
                            value = montage.balance.toFloat(),
                            onValueChange = { v -> actions.updateMontage { it.copy(balance = v.toDouble()) } },
                            valueRange = -1f..1f,
                            // Crans de 0,25 : 1,5 dB de chaque côté, assez fin sans devenir un réglage au hasard.
                            steps = 7,
                            modifier = Modifier.weight(1f),
                        )
                        Text(tr("ui.montage.balanceGame"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    }
                }

                // Le détail des cases, replié : le style les règle d'un coup ; une case modifiée ici passe devant lui.
                TextButton(onClick = { actions.updateMontage { it.copy(advanced = !it.advanced) } }) {
                    Text(tr(if (montage.advanced) "ui.montage.advancedHide" else "ui.montage.advancedShow"))
                }
                if (montage.advanced) {
                    DialogGroup(tr("ui.montage.pace")) {
                        MontageToggle(tr("ui.montage.buildUp"), montage.buildUp) { v ->
                            actions.updateMontage { it.copy(buildUp = v) }
                        }
                        MontageToggle(tr("ui.montage.oneTaps"), montage.oneTaps) { v ->
                            actions.updateMontage { it.copy(oneTaps = v) }
                        }
                        MontageToggle(tr("ui.montage.fast"), montage.fast) { v ->
                            actions.updateMontage { it.copy(fast = v) }
                        }
                        // Sans objet quand tout le montage est déjà au rythme rapide.
                        if (!montage.fast && !montage.oneTaps) {
                            MontageToggle(tr("ui.montage.fastParts"), montage.fastParts) { v ->
                                actions.updateMontage { it.copy(fastParts = v) }
                            }
                        }
                    }
                    DialogGroup(tr("ui.montage.effects")) {
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
                        MontageToggle(tr("ui.montage.zoom"), montage.zoom) { v -> actions.updateMontage { it.copy(zoom = v) } }
                        MontageToggle(tr("ui.montage.flash"), montage.flash) { v -> actions.updateMontage { it.copy(flash = v) } }
                        MontageToggle(tr("ui.montage.whip"), montage.whip) { v -> actions.updateMontage { it.copy(whip = v) } }
                        if (montage.whip) {
                            MontageToggle(tr("ui.montage.whoosh"), montage.whoosh) { v -> actions.updateMontage { it.copy(whoosh = v) } }
                        }
                        MontageToggle(tr("ui.montage.showcase"), montage.showcase) { v -> actions.updateMontage { it.copy(showcase = v) } }
                        MontageToggle(tr("ui.montage.colors"), montage.boostColors) { v -> actions.updateMontage { it.copy(boostColors = v) } }
                        MontageToggle(tr("ui.montage.motionBlur"), montage.motionBlur) { v -> actions.updateMontage { it.copy(motionBlur = v) } }
                        MontageToggle(tr("ui.montage.matchCut"), montage.matchCut) { v -> actions.updateMontage { it.copy(matchCut = v) } }
                        MontageToggle(tr("ui.montage.rounds"), montage.rounds) { v -> actions.updateMontage { it.copy(rounds = v) } }
                        if (montage.abilityKillsKnown) {
                            MontageToggle(tr("ui.montage.skipAbilityKills"), montage.skipAbilityKills) { v -> actions.updateMontage { it.copy(skipAbilityKills = v) } }
                        }
                        MontageToggle(tr("ui.montage.slowMotion"), montage.slowMotion) { v -> actions.updateMontage { it.copy(slowMotion = v) } }
                        MontageToggle(tr("ui.montage.text", "double" to tr("video.streak.2"), "triple" to tr("video.streak.3")), montage.text) { v -> actions.updateMontage { it.copy(text = v) } }
                        KillCounterPicker(montage.killCounter) { mode -> actions.updateMontage { it.copy(killCounter = mode) } }
                    }
                }
            }
        },
        confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PreviewVariantsButton(montage, actions); Button(onClick = actions::createMontage, enabled = montage.canCreate) { Text(tr("ui.montage.create")) } } },
        dismissButton = { TextButton(onClick = actions::closeMontage) { Text(tr("ui.common.cancel")) } },
    )
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
