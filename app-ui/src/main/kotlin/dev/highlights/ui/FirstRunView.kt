package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import java.util.Locale
import kotlin.io.path.name

/**
 * Assistant de premier lancement, à la place de toute la fenêtre, en quatre étapes ([SetupStep]) : enregistreur (et ses
 * réglages conseillés), jeux (celui du premier montage), langue, premier montage. « Passer » à chaque étape ; écran vide
 * qui explique comment enregistrer quand rien n'est trouvé.
 */
@Composable
fun FirstRunView(setup: SetupUiState, state: UiState, actions: UiActions) {
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepTrail(setup.step, actions, Modifier.weight(1f))
            if (setup.step != SetupStep.LANGUAGE) LanguagePicker(state.language, actions)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = actions::closeSetup) { Text(tr("ui.setup.skip")) }
        }
        // Bloc de l'étape (titre, contenu, boutons) de largeur bornée, en haut : le titre ne bouge pas d'une étape à
        // l'autre et les boutons suivent le contenu (pas de bande vide entre les cartes et des boutons collés en bas).
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    val (title, subtitle) = when (setup.step) {
                        SetupStep.RECORDER -> "ui.setup.title" to (if (setup.empty) "ui.setup.subtitleEmpty" else "ui.setup.subtitle")
                        SetupStep.GAMES -> "ui.setup.games.title" to "ui.setup.games.subtitle"
                        SetupStep.LANGUAGE -> "ui.setup.language.title" to "ui.setup.language.subtitle"
                        SetupStep.FIRST -> "ui.setup.first.title" to "ui.setup.first.subtitle"
                    }
                    Text(tr(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(tr(subtitle), style = MaterialTheme.typography.bodyLarge, color = Palette.textMuted)
                }
                Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    val body = Modifier
                    when {
                        setup.loading && setup.recorders.isEmpty() && setup.step == SetupStep.RECORDER -> Box(body.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(tr("ui.setup.searching"), color = Palette.textMuted)
                            }
                        }
                        setup.step == SetupStep.RECORDER && setup.empty -> EmptyStep(actions, body)
                        setup.step == SetupStep.RECORDER -> RecorderStep(setup, state, actions, body)
                        setup.step == SetupStep.GAMES -> GamesStep(setup, state, actions, body)
                        setup.step == SetupStep.LANGUAGE -> LanguageStep(state.language, actions, body)
                        else -> FirstStep(setup, state.language, actions, body)
                    }
                }
                HorizontalDivider(color = Palette.outline)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val previous = SetupStep.entries.getOrNull(setup.step.ordinal - 1)
                    val next = SetupStep.entries.getOrNull(setup.step.ordinal + 1)
                    if (previous != null) TextButton(onClick = { actions.setSetupStep(previous) }) { Text("← " + tr("ui.setup.back")) }
                    Spacer(Modifier.weight(1f))
                    if (next != null) {
                        Button(onClick = { actions.setSetupStep(next) }, enabled = !setup.loading || setup.recorders.isNotEmpty(), modifier = Modifier.height(44.dp)) {
                            Text(tr("ui.setup.next") + " →", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

/** Fil des étapes : numéro et nom, l'étape en cours en couleur ; un clic y va (rien n'est perdu). */
@Composable
private fun StepTrail(current: SetupStep, actions: UiActions, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SetupStep.entries.forEach { step ->
            val active = step == current
            val done = step.ordinal < current.ordinal
            Row(
                Modifier.clickable { actions.setSetupStep(step) }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (active) Palette.accent else if (done) Palette.accentSoft else Palette.surface,
                    border = BorderStroke(1.dp, if (active || done) Palette.accent else Palette.outline),
                    contentColor = if (active) Palette.background else Palette.text,
                    modifier = Modifier.size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (done) "✓" else "${step.ordinal + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    tr("ui.setup.step.${step.name.lowercase()}"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (active) FontWeight.SemiBold else null,
                    color = if (active) Palette.text else Palette.textMuted,
                )
            }
            if (step != SetupStep.entries.last()) Text("—", color = Palette.outline)
        }
    }
}

/** Étape 1 : enregistreurs trouvés et leur dossier, réglages conseillés pour celui choisi. */
@Composable
private fun RecorderStep(setup: SetupUiState, state: UiState, actions: UiActions, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1.35f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        RecorderTipsCard(setup.chosen, state, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecorderCard(recorder: SetupRecorder, selected: Boolean, actions: UiActions) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Palette.surfaceHigh else Palette.surface,
        border = BorderStroke(1.dp, if (selected) Palette.accent else Palette.outline),
        // surfaceHigh n'est pas une couleur du thème : sans contentColor, le texte de la carte choisie passait en gris.
        contentColor = Palette.text,
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
                    recorder.folder.toString() + if (recorder.fromSettings || recorder.kind == null) "" else "  " + tr("ui.setup.defaultFolder"),
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
            tr("ui.setup.game", "game" to gameName(game), "count" to game.count),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun gameName(game: SetupGame): String = if (game.profileId != ProfileRepository.DEFAULT_ID) game.name else tr("ui.setup.otherGame")

/** Réglages conseillés pour l'enregistreur choisi (les conseils généraux pour un dossier choisi à la main). */
@Composable
private fun RecorderTipsCard(recorder: SetupRecorder?, state: UiState, modifier: Modifier) {
    val kind = recorder?.kind
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = modifier) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (kind == null) tr("ui.setup.tips.all") else tr("ui.setup.tipsFor", "recorder" to kind.displayName),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            (if (kind == null) generalTips(state) else recorderTips(kind) + tr("ui.setup.tip.all.name")).forEach {
                Text("• $it", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun recorderTips(kind: RecorderKind): List<String> =
    (1..3).mapNotNull { i -> I18n.textOrNull("ui.setup.tip.${kind.name.lowercase()}.$i") }

private fun generalTips(state: UiState): List<String> = listOf(tr("ui.setup.tip.all.mic"), gamesTip(state), tr("ui.setup.tip.all.name"))

private fun gamesTip(state: UiState): String {
    val games = (state.config as? ConfigStatus.Ready)?.profiles.orEmpty().filter { it.id != ProfileRepository.DEFAULT_ID }
        .map { I18n.textOrNull("profile.${it.id}") ?: it.displayName }
    return tr("ui.setup.tip.all.games", "games" to games.joinToString(tr("format.listComma")), "newGame" to tr("ui.newgame.open"))
}

/** Étape 1 quand rien n'est trouvé : comment enregistrer, et quel enregistreur installer. */
@Composable
private fun EmptyStep(actions: UiActions, modifier: Modifier) {
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
            }
        }
    }
}

/** Étape 2 : jeux du dossier choisi ; celui du premier montage (le plus joué, ou un autre d'un clic). */
@Composable
private fun GamesStep(setup: SetupUiState, state: UiState, actions: UiActions, modifier: Modifier) {
    val games = setup.chosen?.games.orEmpty()
    val chosen = setup.montageGame
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1.35f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (games.isEmpty()) {
                Text(tr("ui.setup.games.none"), color = Palette.textMuted)
            }
            games.forEachIndexed { i, game ->
                val selected = game == chosen
                val known = game.profileId != ProfileRepository.DEFAULT_ID
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) Palette.surfaceHigh else Palette.surface,
                    border = BorderStroke(1.dp, if (selected) Palette.accent else Palette.outline),
                    contentColor = Palette.text,
                    modifier = Modifier.fillMaxWidth().clickable { actions.chooseSetupGame(game.profileId) },
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { actions.chooseSetupGame(game.profileId) })
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(gameName(game), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (i == 0 && games.size > 1) {
                                    Spacer(Modifier.width(10.dp))
                                    Text(tr("ui.setup.games.mostPlayed"), style = MaterialTheme.typography.labelMedium, color = Palette.accent)
                                }
                            }
                            if (!known) {
                                Text(tr("ui.setup.games.unknown", "newGame" to tr("ui.newgame.open")), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                            }
                        }
                        Text(tr("ui.setup.games.count", "count" to game.count), style = MaterialTheme.typography.bodyMedium, color = Palette.textMuted)
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("ui.setup.first.title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (chosen != null && setup.firstPick.isNotEmpty()) {
                    Text(tr("ui.setup.games.evening", "game" to gameName(chosen), "count" to setup.firstPick.size))
                    PickList(setup)
                } else {
                    Text(tr("ui.setup.games.none"), color = Palette.textMuted)
                }
                Spacer(Modifier.height(4.dp))
                Text("• " + gamesTip(state), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
        }
    }
}

@Composable
private fun PickList(setup: SetupUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        setup.firstPick.forEach {
            Text("• " + it.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Étape 3 : langue de l'interface (la même liste que le menu de l'en-tête). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageStep(current: String, actions: UiActions, modifier: Modifier) {
    val currentTag = I18n.fileTag(Locale.forLanguageTag(current.replace('_', '-')))
    FlowRow(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        I18n.available().forEach { lang ->
            val selected = lang.tag == currentTag
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) Palette.surfaceHigh else Palette.surface,
                border = BorderStroke(1.dp, if (selected) Palette.accent else Palette.outline),
                contentColor = Palette.text,
                modifier = Modifier.width(240.dp).clickable { actions.setLanguage(lang.tag) },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { actions.setLanguage(lang.tag) })
                    Spacer(Modifier.width(6.dp))
                    Text(lang.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else null)
                }
            }
        }
    }
}

/** Étape 4 : parties retenues, surveillance du dossier, « Faire mon premier montage » ; à côté, les choix des étapes d'avant. */
@Composable
private fun FirstStep(setup: SetupUiState, language: String, actions: UiActions, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        FirstMontageCard(setup, actions, Modifier.weight(1.35f))
        SummaryCard(setup, language, actions, Modifier.weight(1f))
    }
}

/** Ce que les étapes d'avant ont choisi ; un clic sur une ligne y retourne. */
@Composable
private fun SummaryCard(setup: SetupUiState, language: String, actions: UiActions, modifier: Modifier) {
    val currentTag = I18n.fileTag(Locale.forLanguageTag(language.replace('_', '-')))
    val rows = listOf(
        SetupStep.RECORDER to (setup.chosen?.kind?.displayName ?: setup.folder?.toString() ?: "—"),
        SetupStep.GAMES to (setup.montageGame?.let(::gameName) ?: "—"),
        SetupStep.LANGUAGE to (I18n.available().firstOrNull { it.tag == currentTag }?.name ?: language),
    )
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = modifier) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("ui.setup.summary"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            rows.forEach { (step, value) ->
                Row(
                    Modifier.fillMaxWidth().clickable { actions.setSetupStep(step) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("ui.setup.step.${step.name.lowercase()}"), color = Palette.textMuted, modifier = Modifier.width(150.dp))
                    Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("✎", color = Palette.accent)
                }
            }
        }
    }
}

@Composable
private fun FirstMontageCard(setup: SetupUiState, actions: UiActions, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline), modifier = modifier) {
        Column(Modifier.padding(26.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val chosen = setup.chosen
            val game = setup.montageGame
            if (setup.firstPick.isEmpty() || game == null) {
                Text(
                    tr("ui.setup.first.nothing", "recorder" to (chosen?.kind?.displayName ?: tr("ui.setup.yourRecorder"))),
                    color = Palette.textMuted,
                )
                if (chosen == null) OutlinedButton(onClick = actions::chooseSetupVideo) { Text(tr("ui.setup.chooseVideo")) }
            } else {
                Text(tr("ui.setup.games.evening", "game" to gameName(game), "count" to setup.firstPick.size), style = MaterialTheme.typography.titleMedium)
                PickList(setup)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = setup.folder != null) { actions.setSetupWatch(!setup.watch) }) {
                Checkbox(checked = setup.watch && setup.folder != null, onCheckedChange = actions::setSetupWatch, enabled = setup.folder != null)
                Column {
                    Text(tr("ui.setup.watch"), style = MaterialTheme.typography.bodyMedium)
                    setup.folder?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Button(onClick = actions::startFirstMontage, enabled = setup.canStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(tr("ui.setup.first.start"), style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = actions::closeSetup) { Text(tr(if (setup.firstPick.isEmpty()) "ui.setup.done" else "ui.setup.later")) }
        }
    }
}

private fun shortDate(at: Instant): String = I18n.date(at.atZone(ZoneId.systemDefault()), "format.dateTime.short")
