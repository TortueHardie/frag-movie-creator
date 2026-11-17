package dev.highlights.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.KillCounterMode

/** Compteur de kills du montage : aucun, compté dans chaque plan, ou cumulé sur tout le montage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KillCounterPicker(mode: KillCounterMode, onChange: (KillCounterMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("ui.montage.killCounter"), style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            KillCounterMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, KillCounterMode.entries.size),
                ) { Text(tr("ui.montage.killCounter.${m.name.lowercase()}")) }
            }
        }
        Text(tr("ui.montage.killCounter.hint", "ace" to tr("video.counter.ace")), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
    }
}
