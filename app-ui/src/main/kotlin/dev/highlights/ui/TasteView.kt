package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.montage.GroupTasteLearner
import dev.highlights.montage.GroupTrait
import dev.highlights.montage.TasteLearner
import dev.highlights.pipeline.TasteState
import kotlin.math.abs

/**
 * Goûts appris (ce que `app gouts` montre en console) : le poids de chaque critère de la note, appris des variantes
 * choisies, et les sortes de kills préférées ou évitées, apprises des corrections de l'éditeur. Lisible sans connaître
 * le moteur : une barre par ligne, et l'état « en apprentissage » tant qu'il n'y a pas assez de choix.
 */
@Composable
fun TasteView(taste: TasteUiState, actions: UiActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("ui.taste.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::closeTaste) { Text(tr("ui.library.title")) }
        }
        val state = taste.state
        if (state == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                if (state.enabledProfiles.isEmpty()) tr("ui.taste.off") else tr("ui.taste.intro"),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textMuted,
            )
            CriteriaCard(state)
            GroupsCard(state)
            ResetRow(taste, state, actions)
        }
    }
}

@Composable
private fun CriteriaCard(state: TasteState) {
    val learned = state.learned
    Card {
        Text(tr("ui.taste.criteria.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            if (learned.active) {
                tr("ui.taste.criteria.active", "count" to learned.choices, "before" to I18n.percent(learned.agreementBefore), "after" to I18n.percent(learned.agreementAfter))
            } else {
                tr("ui.taste.criteria.waiting", "count" to learned.choices, "needed" to TasteLearner.MIN_CHOICES)
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )
        // Écart relatif au poids d'origine : ×4 (le plus fort permis) remplit la barre vers la droite, ×0,25 vers la gauche.
        learned.base.keys.sortedByDescending { learned.weights.getValue(it) / learned.base.getValue(it) }.forEach { k ->
            val ratio = learned.weights.getValue(k) / learned.base.getValue(k)
            val change = ratio - 1.0
            val side = if (change >= 0) change / (TasteLearner.MAX_FACTOR - 1) else change / (1 - TasteLearner.MIN_FACTOR)
            val value = if (abs(change) < 0.005) tr("ui.taste.unchanged") else tr("ui.taste.change", "sign" to (if (change > 0) "+" else "−"), "percent" to I18n.percent(abs(change)))
            BarRow(tr("ui.taste.criterion.$k"), side, value, muted = !learned.active)
        }
    }
}

@Composable
private fun GroupsCard(state: TasteState) {
    val groups = state.groups
    Card {
        Text(tr("ui.taste.groups.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            if (groups.active) tr("ui.taste.groups.active", "count" to groups.edits)
            else tr("ui.taste.groups.waiting", "count" to groups.edits, "needed" to GroupTasteLearner.MIN_EDITS),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )
        GroupTrait.entries.sortedByDescending { groups.bonus[it] ?: 0.0 }.forEach { t ->
            val bonus = groups.bonus[t]
            val actionsCount = groups.actions[t] ?: 0
            val value = when {
                actionsCount < GroupTasteLearner.MIN_ACTIONS -> tr("ui.taste.groups.few", "count" to actionsCount)
                bonus == null || abs(bonus) < 0.05 -> tr("ui.taste.groups.neutral")
                bonus > 0 -> tr("ui.taste.groups.prefer")
                else -> tr("ui.taste.groups.avoid")
            }
            BarRow(tr("ui.taste.trait.${t.key}"), (bonus ?: 0.0) / GroupTasteLearner.MAX_BONUS, value, muted = !groups.active)
        }
        Text(tr("ui.taste.groups.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
    }
}

@Composable
private fun ResetRow(taste: TasteUiState, state: TasteState, actions: UiActions) {
    taste.resetDone?.let { Text(tr("ui.taste.reset.done", "file" to it), style = MaterialTheme.typography.bodySmall, color = Palette.success) }
    if (state.choices == 0 && state.edits == 0) return
    if (!taste.confirmReset) {
        OutlinedButton(onClick = actions::askResetTaste) { Text(tr("ui.taste.reset")) }
        return
    }
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surfaceHigh, border = BorderStroke(1.dp, Palette.danger)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ui.taste.reset.confirm", "count" to state.choices + state.edits), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::cancelResetTaste) { Text(tr("ui.common.cancel")) }
            Button(onClick = actions::confirmResetTaste, colors = ButtonDefaults.buttonColors(containerColor = Palette.danger)) { Text(tr("ui.taste.reset.yes")) }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/**
 * Une ligne : le nom, une barre centrée ([side] de −1, tout à gauche, à 1, tout à droite : orange quand le goût pousse
 * vers le haut, cyan vers le bas) et sa lecture en mots. Grisée tant que l'apprentissage n'est pas actif.
 */
@Composable
private fun BarRow(label: String, side: Double, value: String, muted: Boolean) {
    val s = side.coerceIn(-1.0, 1.0).toFloat()
    val color = if (s >= 0) Palette.accent else Palette.cyan
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (muted) Palette.textMuted else Palette.text, modifier = Modifier.width(330.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.surfaceHigh)) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                if (s < 0) Box(Modifier.fillMaxWidth(-s).fillMaxHeight().background(if (muted) color.copy(alpha = 0.4f) else color))
            }
            Spacer(Modifier.width(2.dp).fillMaxHeight().background(Palette.outline))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (s > 0) Box(Modifier.fillMaxWidth(s).fillMaxHeight().background(if (muted) color.copy(alpha = 0.4f) else color))
            }
        }
        Text(value, style = MaterialTheme.typography.bodySmall, color = if (muted) Palette.textMuted else Palette.text, modifier = Modifier.width(170.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Écran des goûts : [state] chargé (null pendant le chargement), confirmation de l'oubli, [resetDone] l'historique mis de côté. */
data class TasteUiState(
    val state: TasteState? = null,
    val confirmReset: Boolean = false,
    val resetDone: String? = null,
)
