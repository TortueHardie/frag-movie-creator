package dev.highlights.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.ExportPreset

/*
 * Préréglages d'export dans l'application : une puce par préréglage de `config/export-presets.yaml`, plus
 * « Personnalisé ». Choisir une puce pose plateforme (ou format), durée, style et miniature ; un de ces réglages changé
 * ensuite à la main est noté et ne bouge plus quand on change de préréglage, comme les cases d'un style de montage
 * ([trackedFrom]). « Personnalisé » ne change rien : les réglages restent tels qu'ils sont, sans préréglage.
 */

/** Réglages qu'un préréglage pose, par leur nom (celui noté dans `presetTouched`). */
internal object PresetKnob {
    const val PLATFORM = "platform"
    const val FORMATS = "formats"
    const val LENGTH = "length"
    const val STYLE = "style"
    const val THUMBNAIL = "thumbnail"
}

// ---------------------------------------------------------------- highlights (barre d'export)

/**
 * Choisit le préréglage [id] parmi [presets] (null : « Personnalisé », rien ne change) : chaque réglage prend la valeur
 * du préréglage, sauf ceux changés à la main, qui gardent la leur. Un préréglage avec un format retire la plateforme.
 */
fun SettingsState.choosePreset(id: String?, presets: List<ExportPreset>): SettingsState {
    val preset = id?.let { p -> presets.find { it.id == p } } ?: return copy(preset = null, presetTouched = emptySet())
    fun free(knob: String) = knob !in presetTouched
    var next = copy(preset = preset.id)
    if (free(PresetKnob.PLATFORM)) {
        if (preset.platform != null) next = next.copy(platform = preset.platform)
        else if (preset.format != null) next = next.copy(platform = null)
    }
    if (free(PresetKnob.FORMATS)) preset.format?.let { next = next.copy(formats = setOf(it)) }
    if (free(PresetKnob.LENGTH)) preset.length?.let { next = next.copy(targetMode = TargetMode.DURATION, durationText = I18n.duration(it)) }
    if (free(PresetKnob.STYLE)) preset.highlightsStyle?.let { next = next.copy(style = it) }
    if (free(PresetKnob.THUMBNAIL)) preset.thumbnail?.let { next = next.copy(thumbnail = it) }
    return next
}

/**
 * Après une modification partie de [before] : préréglage changé (puce, ou simple copie par le pont de pilotage), ses
 * réglages sont posés ; sinon les réglages changés sous un préréglage sont notés comme modifiés à la main.
 */
fun SettingsState.presetTrackedFrom(before: SettingsState, presets: List<ExportPreset>): SettingsState = when {
    preset != before.preset -> choosePreset(preset, presets)
    preset == null || presetTouched != before.presetTouched -> this
    else -> buildSet {
        if (platform != before.platform) add(PresetKnob.PLATFORM)
        if (formats != before.formats) add(PresetKnob.FORMATS)
        if (targetMode != before.targetMode || durationText != before.durationText) add(PresetKnob.LENGTH)
        if (style != before.style) add(PresetKnob.STYLE)
        if (thumbnail != before.thumbnail) add(PresetKnob.THUMBNAIL)
    }.let { changed -> if (changed.isEmpty()) this else copy(presetTouched = presetTouched + changed) }
}

/** Oublie les réglages changés à la main : tous reprennent la valeur du préréglage choisi. */
fun SettingsState.resetToPreset(presets: List<ExportPreset>): SettingsState = copy(presetTouched = emptySet()).choosePreset(preset, presets)

// ---------------------------------------------------------------- montage kills (fenêtre de montage)

/**
 * Même chose pour la fenêtre de montage : plateforme ou format, durée maximale, style de montage (posé par
 * [chooseStyle], qui garde les cases du style modifiées à la main) et miniature.
 */
fun MontageUiState.choosePreset(id: String?, presets: List<ExportPreset>): MontageUiState {
    val preset = id?.let { p -> presets.find { it.id == p } } ?: return copy(preset = null, presetTouched = emptySet())
    fun free(knob: String) = knob !in presetTouched
    var next = copy(preset = preset.id)
    if (free(PresetKnob.PLATFORM)) {
        if (preset.platform != null) next = next.copy(platform = preset.platform)
        else if (preset.format != null) next = next.copy(platform = null)
    }
    if (free(PresetKnob.FORMATS)) preset.format?.let { next = next.copy(formats = setOf(it)) }
    if (free(PresetKnob.LENGTH)) preset.length?.let { next = next.copy(maxDurationText = I18n.duration(it)) }
    if (free(PresetKnob.STYLE)) preset.montageStyle?.takeIf { s -> styles.any { it.id == s } }?.let { next = next.chooseStyle(it) }
    if (free(PresetKnob.THUMBNAIL)) preset.thumbnail?.let { next = next.copy(thumbnail = it) }
    return next
}

/** Voir [SettingsState.presetTrackedFrom] ; à appeler après [trackedFrom], qui a déjà posé un style changé. */
fun MontageUiState.presetTrackedFrom(before: MontageUiState, presets: List<ExportPreset>): MontageUiState = when {
    preset != before.preset -> choosePreset(preset, presets)
    preset == null || presetTouched != before.presetTouched -> this
    else -> buildSet {
        if (platform != before.platform) add(PresetKnob.PLATFORM)
        if (formats != before.formats) add(PresetKnob.FORMATS)
        if (maxDurationText != before.maxDurationText) add(PresetKnob.LENGTH)
        if (style != before.style) add(PresetKnob.STYLE)
        if (thumbnail != before.thumbnail) add(PresetKnob.THUMBNAIL)
    }.let { changed -> if (changed.isEmpty()) this else copy(presetTouched = presetTouched + changed) }
}

fun MontageUiState.resetToPreset(presets: List<ExportPreset>): MontageUiState = copy(presetTouched = emptySet()).choosePreset(preset, presets)

// ---------------------------------------------------------------- puces

/**
 * Puces des préréglages, la phrase qui dit ce que fait celui choisi, les réglages changés à la main (qu'on peut rendre
 * au préréglage) et la case de la miniature, que les préréglages posent aussi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportPresetPicker(
    presets: List<ExportPreset>,
    selected: String?,
    touched: Int,
    thumbnail: Boolean,
    onChoose: (String?) -> Unit,
    onReset: () -> Unit,
    onThumbnail: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (presets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { preset ->
                    FilterChip(selected = selected == preset.id, onClick = { onChoose(preset.id) }, label = { Text(preset.name, maxLines = 1) })
                }
                FilterChip(selected = selected == null, onClick = { onChoose(null) }, label = { Text(tr("ui.preset.custom"), maxLines = 1) })
            }
            val chosen = presets.find { it.id == selected }
            val description = chosen?.shownDescription ?: tr("ui.preset.custom.hint").takeIf { selected == null }
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted) }
            if (chosen != null && touched > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("ui.preset.touched", "count" to touched),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.accent,
                    )
                    TextButton(onClick = onReset) { Text(tr("ui.preset.reset")) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = thumbnail, onCheckedChange = onThumbnail)
            Text(tr("ui.preset.thumbnail"), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Préréglages en haut de la barre d'export des highlights. */
@Composable
internal fun ExportBarPresets(state: UiState, actions: UiActions) {
    val presets = state.presets
    val settings = state.settings
    ExportPresetPicker(
        presets, settings.preset, settings.presetTouched.size, settings.thumbnail,
        onChoose = { id -> actions.updateExportSettings { it.choosePreset(id, presets) } },
        onReset = { actions.updateExportSettings { it.resetToPreset(presets) } },
        onThumbnail = { on -> actions.updateExportSettings { it.copy(thumbnail = on) } },
    )
}

/** Préréglages en haut de la fenêtre de montage kills. */
@Composable
internal fun MontagePresets(montage: MontageUiState, state: UiState, actions: UiActions) {
    val presets = state.presets
    DialogGroup(tr("ui.preset.title")) {
        ExportPresetPicker(
            presets, montage.preset, montage.presetTouched.size, montage.thumbnail,
            onChoose = { id -> actions.updateMontage { it.choosePreset(id, presets) } },
            onReset = { actions.updateMontage { it.resetToPreset(presets) } },
            onThumbnail = { on -> actions.updateMontage { it.copy(thumbnail = on) } },
        )
    }
}
