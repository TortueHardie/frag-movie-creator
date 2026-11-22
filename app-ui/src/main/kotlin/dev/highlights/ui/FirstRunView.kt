package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.profile.ProfileRepository
import java.time.Instant
import java.time.ZoneId
import kotlin.io.path.name

/**
 * Écran de premier lancement, à la place de toute la fenêtre : enregistreurs trouvés et leurs dossiers, jeux reconnus,
 * langue, puis « Faire mon premier montage ». Page des conseils par enregistreur ([SetupPage.TIPS]) ; écran vide qui
 * explique comment enregistrer quand rien n'est trouvé.
 */
@Composable
fun FirstRunView(setup: SetupUiState, state: UiState, actions: UiActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr(if (setup.page == SetupPage.TIPS) "ui.setup.tips.title" else "ui.setup.title"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    tr(
                        when {
                            setup.page == SetupPage.TIPS -> "ui.setup.tips.subtitle"
                            setup.empty -> "ui.setup.subtitleEmpty"
                            else -> "ui.setup.subtitle"
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge, color = Palette.textMuted,
                )
            }
            LanguagePicker(state.language, actions)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = actions::closeSetup) { Text(tr("ui.setup.skip")) }
        }
        // Contenu centré et de largeur bornée : sur un grand écran, pas de bande vide sous les cartes.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val body = Modifier.widthIn(max = 1320.dp)
        when {
            setup.page == SetupPage.TIPS -> TipsPage(setup, state, actions, body)
            setup.loading && setup.recorders.isEmpty() -> Box(body.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(tr("ui.setup.searching"), color = Palette.textMuted)
                }
            }
            setup.empty -> EmptyPage(actions, body)
            else -> WelcomePage(setup, state, actions, body)
        }
        }
    }
}

@Composable
private fun WelcomePage(setup: SetupUiState, state: UiState, actions: UiActions, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1.35f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(tr("ui.setup.recorders"))
            setup.recorders.forEach { RecorderCard(it, selected = it.folder == setup.folder, actions) }
            setup.other?.let { RecorderCard(it, selected = it.folder == setup.folder, actions) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = actions::chooseOtherSetupFolder, enabled = !setup.loading) { Text(tr("ui.setup.otherFolder")) }
                if (setup.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            val missing = RecorderKind.entries.filter { k -> setup.recorders.none { it.kind == k } }
            if (missing.isNotEmpty()) {
                Text(
                    tr("ui.setup.notFound", "names" to missing.joinToString(tr("format.listComma")) { it.displayName }),
                    style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FirstMontageCard(setup, actions, Modifier.fillMaxWidth())
            RecorderTipsCard(setup.chosen, state, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecorderCard(recorder: SetupRecorder, selected: Boolean, actions: UiActions) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Palette.surfaceHigh else Palette.surface,
        border = BorderStroke(1.dp, if (selected) Palette.accent else Palette.outline),
        modifier = Modifier.fillMaxWidth().clickable { actions.chooseSetupFolder(recorder.folder) },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = { actions.chooseSetupFolder(recorder.folder) })
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(recorder.kind?.displayName ?: tr("ui.setup.yourFolder"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        if (recorder.videos.isEmpty()) tr("ui.setup.noVideo") else tr("ui.setup.videos", "count" to recorder.videos.size, "date" to shortDate(recorder.latest!!)),
                        style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                    )
                }
                Text(
                    recorder.folder.toString() + if (recorder.fromSettings) "" else "  " + tr("ui.setup.defaultFolder"),
                    style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (recorder.games.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recorder.games.take(6).forEach { GameChip(it) }
                    }
                }
                when (recorder.separateMic) {
                    true -> Text(tr("ui.setup.mic.separate"), style = MaterialTheme.typography.bodySmall, color = Palette.success)
                    false -> Text(tr("ui.setup.mic.mixed"), style = MaterialTheme.typography.bodySmall, color = Palette.accent)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun GameChip(game: SetupGame) {
    val known = game.profileId != ProfileRepository.DEFAULT_ID
    Surface(shape = RoundedCornerShape(50), color = if (known) Palette.accentSoft else Palette.background, border = BorderStroke(1.dp, Palette.outline)) {
        Text(
            tr("ui.setup.game", "game" to (if (known) game.name else tr("ui.setup.otherGame")), "count" to game.count),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FirstMontageCard(setup: SetupUiState, actions: UiActions, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = modifier) {
        Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("ui.setup.first.title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val chosen = setup.chosen
            if (setup.firstPick.isEmpty()) {
                Text(
                    tr("ui.setup.first.nothing", "recorder" to (chosen?.kind?.displayName ?: tr("ui.setup.yourRecorder"))),
                    color = Palette.textMuted,
                )
            } else {
                Text(tr("ui.setup.first.what", "count" to setup.firstPick.size), color = Palette.textMuted)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    setup.firstPick.forEach {
                        Text("• " + it.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { actions.setSetupWatch(!setup.watch) }) {
                Checkbox(checked = setup.watch, onCheckedChange = actions::setSetupWatch, enabled = setup.folder != null)
                Text(tr("ui.setup.watch"), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = actions::startFirstMontage, enabled = setup.canStart, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(tr("ui.setup.first.start"), style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = actions::closeSetup) { Text(tr(if (setup.firstPick.isEmpty()) "ui.setup.done" else "ui.setup.later")) }
        }
    }
}

/** Réglages conseillés pour l'enregistreur choisi (les conseils généraux pour un dossier choisi à la main). */
@Composable
private fun RecorderTipsCard(recorder: SetupRecorder?, state: UiState, actions: UiActions) {
    val kind = recorder?.kind
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (kind == null) tr("ui.setup.tips.all") else tr("ui.setup.tipsFor", "recorder" to kind.displayName),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            (if (kind == null) generalTips(state) else recorderTips(kind)).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Row {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { actions.setSetupPage(SetupPage.TIPS) }) { Text(tr("ui.setup.tips.open")) }
            }
        }
    }
}

private fun recorderTips(kind: RecorderKind): List<String> =
    (1..3).mapNotNull { i -> I18n.textOrNull("ui.setup.tip.${kind.name.lowercase()}.$i") }

private fun generalTips(state: UiState): List<String> {
    val games = (state.config as? ConfigStatus.Ready)?.profiles.orEmpty().filter { it.id != ProfileRepository.DEFAULT_ID }
        .map { I18n.textOrNull("profile.${it.id}") ?: it.displayName }
    return listOf(
        tr("ui.setup.tip.all.mic"),
        tr("ui.setup.tip.all.games", "games" to games.joinToString(tr("format.listComma")), "newGame" to tr("ui.newgame.open")),
        tr("ui.setup.tip.all.name"),
    )
}

/** Rien trouvé : comment enregistrer, et quel enregistreur installer. */
@Composable
private fun EmptyPage(actions: UiActions, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("ui.setup.empty.title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(tr("ui.setup.empty.text"), color = Palette.textMuted)
            Spacer(Modifier.height(4.dp))
            RecorderKind.entries.forEach { kind ->
                Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(kind.displayName, fontWeight = FontWeight.SemiBold)
                            Text(tr("ui.setup.pitch.${kind.name.lowercase()}"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                        }
                        TextButton(onClick = { actions.openRecorderSite(kind) }) { Text(tr("ui.setup.site")) }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = Modifier.weight(0.8f)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr("ui.setup.empty.already"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(tr("ui.setup.empty.alreadyText"), color = Palette.textMuted)
                Button(onClick = actions::chooseOtherSetupFolder, modifier = Modifier.fillMaxWidth()) { Text(tr("ui.setup.otherFolder")) }
                OutlinedButton(onClick = actions::chooseSetupVideo, modifier = Modifier.fillMaxWidth()) { Text(tr("ui.setup.chooseVideo")) }
                Row {
                    TextButton(onClick = actions::closeSetup) { Text(tr("ui.setup.later")) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { actions.setSetupPage(SetupPage.TIPS) }) { Text(tr("ui.setup.tips.open")) }
                }
            }
        }
    }
}

/** Ce qu'il faut régler dans chaque enregistreur pour le meilleur résultat : les enregistreurs trouvés d'abord. */
@Composable
private fun TipsPage(setup: SetupUiState, state: UiState, actions: UiActions, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val found = setup.recorders.mapNotNull { it.kind }.toSet()
        val cards = listOf(Triple(tr("ui.setup.tips.all"), false, generalTips(state))) +
            RecorderKind.entries.sortedBy { it !in found }.map { Triple(it.displayName, it in found, recorderTips(it)) }
        cards.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (title, installed, lines) -> TipCard(title, installed, lines, Modifier.weight(1f).fillMaxHeight()) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row {
            TextButton(onClick = { actions.setSetupPage(SetupPage.WELCOME) }) { Text(tr("ui.setup.tips.back")) }
        }
    }
}

@Composable
private fun TipCard(title: String, installed: Boolean, lines: List<String>, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp), color = Palette.surface,
        border = BorderStroke(1.dp, if (installed) Palette.accent else Palette.outline),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (installed) Text(tr("ui.setup.installed"), style = MaterialTheme.typography.labelMedium, color = Palette.accent)
            }
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun shortDate(at: Instant): String = I18n.date(at.atZone(ZoneId.systemDefault()), "format.dateTime.short")
