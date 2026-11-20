package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.KillCounterMode
import dev.highlights.core.model.OutputFormat
import dev.highlights.montage.DropTime
import dev.highlights.montage.MusicDrops
import dev.highlights.montage.ThumbnailStyle
import dev.highlights.pipeline.HighlightPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Le parcours du montage kills (validé le 20/11), une seule fenêtre à cinq étapes : [WHAT] (pour où, avec quoi,
 * formats, style), [MUSIC] (morceau ou dossier, durée), [ON_MUSIC] (l'éditeur sur la musique : repères 3a, plans 3b),
 * [FINISHING] (son, textes, début et fin, effets), [PREVIEW] (aperçu, miniature, créer). Retour libre, réglages gardés ;
 * jamais de rendu sans passer par l'aperçu.
 */
enum class MontageStep { WHAT, MUSIC, ON_MUSIC, FINISHING, PREVIEW }

/** Un kill des parties ouvertes pour le panneau « Votre montage » : [key] désignation, [media] capture, [at] instant. */
data class FlowKill(val key: String, val media: Path, val at: Duration)

/** Le morceau choisi, lu pour l'aperçu : crêtes, durée, tempo, drop (posée ou détectée). */
data class FlowSong(val file: Path, val peaks: List<Float>, val duration: Duration, val bpm: Double, val drop: Duration?)

/** Réglages des finitions qui changent le plan (l'étape 3 se refait, repères gardés) : marqués ⟳. */
internal val PLAN_KNOBS = setOf("matchCut", "rounds", "skipAbilityKills")

/**
 * Logique du parcours, déléguée par [AppController] : aller d'une étape à l'autre (l'éditeur s'ouvre en entrant dans
 * l'étape 3, un dossier de musiques y choisit d'abord son morceau), « Tout en auto », kills et morceau du panneau vivant.
 */
internal class MontageFlowController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val pipeline: () -> HighlightPipeline?,
    private val replan: () -> Unit,
    private val openEditor: () -> Unit,
    private val closeEditor: () -> Unit,
    private val placeClips: () -> Unit,
    private val backToKills: () -> Unit,
) {
    private var songJob: Job? = null

    private val montage: MontageUiState? get() = state.value.montage

    private fun update(change: (MontageUiState) -> MontageUiState) = state.update { s -> s.copy(montage = s.montage?.let(change)) }

    /** Les kills des parties ouvertes (pour le panneau), et leurs vignettes en arrière-plan. */
    fun loadKills() {
        val s = state.value
        val p = pipeline() ?: return
        val sessions = s.session?.sessions ?: return
        val kills = sessions.flatMap { session ->
            val event = runCatching { p.profiles.byId(session.profileId).montage.killEvent }.getOrDefault("kill")
            session.timeline.events.filter { it.kind == event }.map { e -> FlowKill(dev.highlights.montage.KillRef.of(session.media, e.at).toString(), session.media.path, e.at) }
        }
        update { it.copy(flowKills = kills) }
        scope.launch {
            for (k in kills.take(MAX_THUMBS)) {
                if (montage?.flowThumbs?.containsKey(k.key) == true) continue
                val media = sessions.firstOrNull { it.media.path == k.media }?.media ?: continue
                val image = runCatching { withContext(Dispatchers.IO) { p.thumbnail(media, k.at) } }.getOrNull() ?: continue
                update { it.copy(flowThumbs = it.flowThumbs + (k.key to image)) }
            }
        }
    }

    /** Lit le morceau choisi pour l'aperçu (crêtes, tempo, drop) ; un dossier n'en a pas avant l'étape 3. */
    fun loadSong() {
        val m = montage ?: return
        val music = m.music?.takeIf { !m.useLibrary } ?: return update { it.copy(song = null) }
        if (m.song?.file == music) return
        val p = pipeline() ?: return
        songJob?.cancel()
        songJob = scope.launch {
            val tool = runCatching { withContext(Dispatchers.IO) { p.musicDropTool(music) } }.getOrNull() ?: return@launch
            val analysis = tool.byHand?.let { MusicDrops.apply(tool.detected, it) } ?: tool.detected
            val peaks = withContext(Dispatchers.Default) { tool.pcm.peaks(SONG_PEAKS).toList() }
            update { it.copy(song = FlowSong(music, peaks, tool.pcm.duration, analysis.bpm, analysis.beatTime(analysis.dropBeat))) }
        }
    }

    /** Va à l'étape [step] : l'éditeur s'ouvre en entrant dans l'étape 3 ; l'aperçu a besoin d'un plan. */
    fun go(step: MontageStep) {
        val m = montage ?: return
        if (step.ordinal > MontageStep.MUSIC.ordinal && m.musicSource == null) return
        update { it.copy(step = step, reached = maxOf(it.reached, step.ordinal)) }
        when (step) {
            MontageStep.ON_MUSIC -> if (m.editor == null) openEditor()
            MontageStep.PREVIEW -> if (m.plan?.view == null || m.planDirty) {
                state.update { s -> s.copy(montage = s.montage?.let { it.copy(plan = it.plan ?: MontagePlanState(), planDirty = false) }) }
                replan()
            }
            else -> {}
        }
    }

    /** « Suivant » : l'étape d'après ; dans l'éditeur, « Placer les plans » (3a → 3b) puis les finitions. */
    fun next() {
        val m = montage ?: return
        when (m.step) {
            MontageStep.ON_MUSIC -> if (m.editor?.step == EditorStep.KILLS) placeClips() else go(MontageStep.FINISHING)
            MontageStep.PREVIEW -> {}
            else -> go(MontageStep.entries[m.step.ordinal + 1])
        }
    }

    /** « Retour » : 3b revient à 3a, sinon l'étape d'avant. */
    fun back() {
        val m = montage ?: return
        when {
            m.step == MontageStep.ON_MUSIC && m.editor?.step == EditorStep.CLIPS -> backToKills()
            m.step.ordinal > 0 -> update { it.copy(step = MontageStep.entries[m.step.ordinal - 1]) }
        }
    }

    /** « Tout en auto » : pas de repères, pas de finitions touchées ; droit à l'aperçu, plan automatique. */
    fun allAuto() {
        val m = montage ?: return
        if (m.musicSource == null) return
        closeEditor()
        update { it.copy(constraints = dev.highlights.montage.MontageConstraints.NONE, step = MontageStep.PREVIEW, reached = MontageStep.PREVIEW.ordinal, plan = MontagePlanState(), planDirty = false) }
        replan()
    }

    /** Une autre musique : l'éditeur et ses repères repartent de zéro. */
    fun musicChanged() {
        closeEditor()
        update { it.copy(constraints = it.constraints.copy(intents = null, layout = null), reached = minOf(it.reached, MontageStep.MUSIC.ordinal), song = null) }
        loadSong()
    }

    companion object {
        const val MAX_THUMBS = 24
        const val SONG_PEAKS = 3000
    }
}

// =============================================================================================================== vues

private val CHANGES = Color(0xFFE8B84A)

/** La fenêtre du parcours (remplace le dialogue de montage et la fenêtre de l'éditeur). */
@Composable
fun MontageFlowWindow(montage: MontageUiState, state: UiState, actions: UiActions) {
    DialogWindow(
        onCloseRequest = actions::closeMontage,
        state = rememberDialogState(size = DpSize(1440.dp, 900.dp)),
        title = tr("ui.flow.title"),
    ) {
        HighlightsTheme { MontageFlowContent(montage, state, actions, Modifier.fillMaxSize()) }
    }
}

@Composable
fun MontageFlowContent(montage: MontageUiState, state: UiState, actions: UiActions, modifier: Modifier = Modifier) {
    Surface(modifier, color = Palette.background) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowHeader(montage, state, actions)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (montage.step) {
                        MontageStep.WHAT -> WhatStep(montage, state, actions)
                        MontageStep.MUSIC -> MusicStep(montage, actions)
                        MontageStep.ON_MUSIC -> {
                            val editor = montage.editor
                            if (editor == null) Busy(tr("ui.flow.opening"))
                            else MusicMontageContent(montage, editor, actions, Modifier.fillMaxSize(), embedded = true)
                        }
                        MontageStep.FINISHING -> FinishingStep(montage, state, actions)
                        MontageStep.PREVIEW -> PreviewStep(montage, actions)
                    }
                }
                if (montage.step == MontageStep.WHAT || montage.step == MontageStep.MUSIC || montage.step == MontageStep.FINISHING) YourMontage(montage, state)
            }
            FlowFooter(montage, actions)
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
    }
}

private fun stepKey(step: MontageStep) = "ui.flow.step." + step.name.lowercase()

@Composable
private fun FlowHeader(montage: MontageUiState, state: UiState, actions: UiActions) {
    val sessions = state.session?.sessions.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.width(260.dp)) {
            Text(tr("ui.flow.title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                tr("ui.flow.games", "game" to montage.gameName, "count" to sessions.size, "kills" to montage.flowKills.size),
                style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MontageStep.entries.forEach { step ->
                val on = step == montage.step
                val done = step.ordinal < montage.step.ordinal || (step.ordinal <= montage.reached && !on)
                val reachable = step.ordinal <= montage.reached && (step.ordinal <= MontageStep.MUSIC.ordinal || montage.musicSource != null)
                Text(
                    (if (done) "✓ " else "${step.ordinal + 1}  ") + tr(stepKey(step)), style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Palette.background else if (done) Palette.cyan else Palette.textMuted,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Palette.accent else Palette.surface)
                        .border(BorderStroke(1.dp, if (on) Palette.accent else Palette.outline), RoundedCornerShape(14.dp))
                        .clickable(enabled = reachable && !on) { actions.goMontageStep(step.name) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (step != MontageStep.PREVIEW) Text("›", color = Palette.textMuted, fontSize = 18.sp)
            }
        }
        OutlinedButton(onClick = actions::closeMontage) { Text(tr("ui.common.close")) }
    }
}

@Composable
private fun FlowFooter(montage: MontageUiState, actions: UiActions) {
    val editor = montage.editor
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = actions::previousMontageStep, enabled = montage.step != MontageStep.WHAT) { Text(tr("ui.flow.back")) }
            val (hint, warn) = when (montage.step) {
                MontageStep.WHAT -> tr("ui.flow.hint.what") to false
                MontageStep.MUSIC -> tr("ui.flow.hint.music") to false
                MontageStep.ON_MUSIC -> tr(if (editor?.step == EditorStep.CLIPS) "ui.flow.hint.clips" else "ui.flow.hint.marks") to false
                MontageStep.FINISHING -> if (montage.planDirty) tr("ui.flow.hint.planChanged") to true else tr("ui.flow.hint.finishing") to false
                MontageStep.PREVIEW -> tr("ui.flow.hint.preview") to false
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = if (warn) CHANGES else Palette.textMuted, modifier = Modifier.weight(1f))
            if (montage.step.ordinal <= MontageStep.MUSIC.ordinal) OutlinedButton(onClick = actions::montageAllAuto, enabled = montage.canCreate) { Text(tr("ui.flow.allAuto")) }
            when (montage.step) {
                MontageStep.PREVIEW -> {}
                MontageStep.ON_MUSIC -> Button(onClick = actions::nextMontageStep, enabled = editor != null && !editor.loading && (editor.step == EditorStep.CLIPS || editor.intents?.killMarks?.isNotEmpty() == true)) {
                    Text(tr(if (editor?.step == EditorStep.CLIPS) "ui.flow.toFinishing" else "ui.musicEditor.placeClips"))
                }
                MontageStep.MUSIC -> Button(onClick = actions::nextMontageStep, enabled = montage.canCreate) { Text(tr("ui.flow.next")) }
                else -> Button(onClick = actions::nextMontageStep) { Text(tr("ui.flow.next")) }
            }
        }
    }
}

// ------------------------------------------------------------------ éléments communs

@Composable
private fun FlowCard(title: String, modifier: Modifier = Modifier, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted) }
            content()
        }
    }
}

@Composable
private fun PickCard(title: String, text: String?, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), shape = RoundedCornerShape(10.dp),
        color = if (on) Palette.accent.copy(alpha = 0.12f) else Palette.surfaceHigh, border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Palette.accent else Palette.outline),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (on) Palette.accent else Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted) }
        }
    }
}

@Composable
private fun FlowChip(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        if (!enabled && on) "🔒 $text" else text, style = MaterialTheme.typography.labelLarge,
        color = if (on) Palette.accent else if (!enabled) Palette.textMuted.copy(alpha = 0.45f) else Palette.text,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Palette.accent.copy(alpha = 0.15f) else Palette.surfaceHigh)
            .border(BorderStroke(1.dp, if (on) Palette.accent else Palette.outline), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

@Composable
private fun FlowSwitch(text: String, on: Boolean, hint: String? = null, plan: Boolean = false, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Palette.text else Palette.textMuted)
                if (plan) Text("⟳", fontSize = 13.sp, color = CHANGES, fontWeight = FontWeight.Bold)
            }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted) }
        }
    }
}

/** Le morceau en petit : forme d'onde, passage du montage (cadre), drop. */
@Composable
private fun SongWave(song: FlowSong?, passage: Pair<Duration, Duration>?, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh)) {
        val s = song ?: return@Canvas
        val total = s.duration.inWholeMilliseconds.toDouble().coerceAtLeast(1.0)
        fun x(t: Duration) = (t.inWholeMilliseconds / total * size.width).toFloat()
        val cols = size.width.toInt().coerceAtLeast(1)
        val n = s.peaks.size
        for (c in 0 until cols) {
            val a = c * n / cols
            val b = ((c + 1) * n / cols).coerceAtLeast(a + 1).coerceAtMost(n)
            var p = 0f
            for (i in a until b) p = maxOf(p, s.peaks[i])
            val h = (p * size.height * 0.9f).coerceAtLeast(1f)
            val t = s.duration * (c.toDouble() / cols)
            val inside = passage == null || (t >= passage.first && t <= passage.second)
            drawRect((if (inside) Palette.cyan else Palette.textMuted).copy(alpha = if (inside) 0.85f else 0.35f), Offset(c.toFloat(), (size.height - h) / 2), Size(1f, h))
        }
        passage?.let { (a, b) -> drawRect(Palette.accent, Offset(x(a), 1f), Size(x(b) - x(a), size.height - 2f), style = Stroke(1.5f)) }
        s.drop?.let { d -> drawLine(Color(0xFFFF5A5A), Offset(x(d), 0f), Offset(x(d), size.height), 2f) }
    }
}

/** Passage du montage dans le morceau, quand un plan est là. */
private fun MontageUiState.passage(): Pair<Duration, Duration>? = plan?.view?.let { v -> v.musicStart to (v.musicStart + v.duration) }

/** Les vignettes des kills des parties. */
@Composable
private fun KillThumbs(montage: MontageUiState, columns: Int, modifier: Modifier = Modifier, limit: Int = MontageFlowController.MAX_THUMBS) {
    val kills = montage.flowKills.take(limit)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        kills.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { k ->
                    Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black)) {
                        rememberImage(montage.flowThumbs[k.key])?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Text(dev.highlights.montage.KillRef.precise(k.at), fontSize = 8.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 2.dp))
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Le panneau vivant « Votre montage » : cadrage, choix, morceau, kills. */
@Composable
private fun YourMontage(montage: MontageUiState, state: UiState) {
    Surface(Modifier.width(360.dp).fillMaxHeight(), shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("ui.flow.yours"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Cadrage 9:16 sur le premier kill (image de la capture), le format de sortie en coin.
                Box(Modifier.size(130.dp, 231.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                    rememberImage(montage.flowKills.firstOrNull()?.let { montage.flowThumbs[it.key] })?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val preset = state.presets.firstOrNull { it.id == montage.preset }
                    Fact(tr("ui.flow.fact.for"), preset?.name ?: montage.platform?.let { id -> state.platforms.firstOrNull { it.id == id }?.name } ?: tr("ui.flow.myWay"))
                    Fact(tr("ui.flow.fact.style"), montage.chosenStyle?.name ?: tr("ui.montage.styleNone"))
                    Fact(tr("ui.flow.fact.formats"), formatsText(montage, state))
                    Fact(tr("ui.flow.fact.kills"), "${montage.flowKills.size}")
                    Fact(tr("ui.flow.fact.length"), montage.plan?.view?.let { "≈ " + DropTime.format(it.duration).substringBefore('.') } ?: montage.maxDuration?.let { "≤ " + I18n.duration(it) } ?: "—")
                }
            }
            Text(tr("ui.flow.music"), style = MaterialTheme.typography.labelLarge, color = Palette.textMuted)
            val song = montage.song
            if (song != null) {
                Text(tr("ui.flow.songLine", "name" to song.file.fileName.toString().substringBeforeLast('.'), "bpm" to I18n.fixed(song.bpm, 0)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SongWave(song, montage.passage(), Modifier.fillMaxWidth().height(50.dp))
                song.drop?.let { Text(tr("ui.flow.dropAt", "time" to DropTime.format(it)), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted) }
            } else {
                Text(tr(if (montage.useLibrary && montage.musicLibrary != null) "ui.flow.songFromFolder" else "ui.flow.noSongYet"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
            Text(tr("ui.flow.yourKills", "count" to montage.flowKills.size), style = MaterialTheme.typography.labelLarge, color = Palette.textMuted)
            KillThumbs(montage, 4, Modifier.weight(1f).verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatLabel(f: OutputFormat): String = when (f) {
    OutputFormat.VERTICAL -> "9:16"
    OutputFormat.LANDSCAPE -> "16:9"
    OutputFormat.SOURCE -> tr("ui.flow.format.source")
}

private fun formatsText(montage: MontageUiState, state: UiState): String {
    val platform = montage.platform?.let { id -> state.platforms.firstOrNull { it.id == id } }
    val list = if (platform != null) listOf(platform.format) else OutputFormat.entries.filter { it in montage.formats }
    return list.joinToString(" + ") { formatLabel(it) }
}

// ------------------------------------------------------------------ 1. Quoi

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhatStep(montage: MontageUiState, state: UiState, actions: UiActions) {
    val presets = state.presets
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowCard(tr("ui.flow.where"), hint = tr("ui.flow.where.hint")) {
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { p ->
                    PickCard(p.name, p.shownDescription, montage.preset == p.id, Modifier.weight(1f).fillMaxHeight().heightIn(min = 92.dp)) { actions.updateMontage { it.choosePreset(p.id, presets) } }
                }
                PickCard(tr("ui.flow.myWay"), tr("ui.flow.myWay.hint"), montage.preset == null, Modifier.weight(1f).fillMaxHeight().heightIn(min = 92.dp)) { actions.updateMontage { it.choosePreset(null, presets) } }
            }
            if (montage.preset != null && montage.presetTouched.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("ui.preset.touched", "count" to montage.presetTouched.size), style = MaterialTheme.typography.bodySmall, color = Palette.accent, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { actions.updateMontage { it.resetToPreset(presets) } }) { Text(tr("ui.preset.reset")) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowCard(tr("ui.flow.madeOf"), Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowChip(tr("ui.flow.madeOf.kills"), !montage.oneTaps && !montage.reactionMontage) { actions.updateMontage { it.copy(oneTaps = false, reactionMontage = false) } }
                    FlowChip(tr("ui.flow.madeOf.reactions"), montage.reactionMontage) { actions.updateMontage { it.copy(reactionMontage = true, oneTaps = false) } }
                    FlowChip(tr("ui.flow.madeOf.oneTaps"), montage.oneTaps) { actions.updateMontage { it.copy(oneTaps = true, reactionMontage = false) } }
                }
                Text(
                    tr(if (montage.reactionMontage) "ui.flow.madeOf.reactions.hint" else if (montage.oneTaps) "ui.flow.madeOf.oneTaps.hint" else "ui.flow.madeOf.kills.hint"),
                    style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                )
                if (montage.reactionMontage) FlowSwitch(tr("ui.flow.reactionSpacing"), montage.reactionSpacing) { v -> actions.updateMontage { it.copy(reactionSpacing = v) } }
                // Moments choisis dans la recherche : seulement eux.
                montage.pick?.let { pick -> Text(tr("ui.montage.picked", "count" to pick.size), style = MaterialTheme.typography.bodySmall, color = Palette.accent) }
            }
            FlowCard(tr("ui.flow.formats"), Modifier.weight(1f)) {
                val platform = montage.platform?.let { id -> state.platforms.firstOrNull { it.id == id } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in listOf(OutputFormat.VERTICAL, OutputFormat.SOURCE, OutputFormat.LANDSCAPE)) {
                        // Une plateforme impose son format, et lui seul (le rendu n'en fait pas d'autre).
                        val forced = platform?.format == f
                        FlowChip(formatLabel(f), forced || (platform == null && f in montage.formats), enabled = platform == null) {
                            actions.updateMontage { m -> m.copy(formats = if (f in m.formats) m.formats - f else m.formats + f) }
                        }
                    }
                }
                Text(platform?.let { tr("ui.flow.formats.platform", "platform" to it.name) } ?: tr("ui.flow.formats.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
        }
        if (montage.styles.isNotEmpty()) FlowCard(tr("ui.flow.style"), hint = montage.preset?.let { id -> presets.firstOrNull { it.id == id }?.let { tr("ui.flow.style.fromPreset", "preset" to it.name) } }) {
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                montage.styles.forEach { s ->
                    PickCard(s.name, s.shownDescription, montage.style == s.id, Modifier.weight(1f).fillMaxHeight().heightIn(min = 92.dp)) { actions.updateMontage { it.chooseStyle(s.id) } }
                }
            }
            if (montage.touched.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("ui.montage.styleTouched", "count" to montage.touched.size), style = MaterialTheme.typography.bodySmall, color = Palette.accent, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { actions.updateMontage { it.resetToStyle() } }) { Text(tr("ui.montage.styleReset")) }
                }
            }
        }
        FlowCard(tr("ui.flow.games.title"), Modifier.fillMaxWidth(), hint = tr("ui.flow.games.hint")) {
            state.session?.entries?.forEach { e ->
                val source = e.session.media.path
                val kills = montage.flowKills.filter { it.media == source }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(220.dp)) {
                        Text(source.fileName.toString().substringBeforeLast('.'), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr("ui.flow.games.kills", "count" to kills.size), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    }
                    kills.take(8).forEach { k ->
                        Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(3.dp)).background(Color.Black)) {
                            rememberImage(montage.flowThumbs[k.key])?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                    if (kills.size > 8) Text("+${kills.size - 8}", color = Palette.textMuted)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 2. Musique

@Composable
private fun MusicStep(montage: MontageUiState, actions: UiActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowCard(tr("ui.flow.whichMusic")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickCard(tr("ui.flow.thisSong"), montage.music?.fileName?.toString() ?: tr("ui.montage.noTrack"), !montage.useLibrary, Modifier.weight(1f)) {
                    actions.updateMontage { it.copy(useLibrary = false) }
                    actions.montageMusicChanged()
                }
                PickCard(tr("ui.flow.aFolder"), (montage.musicLibrary?.toString()?.let { "$it · " } ?: "") + tr("ui.flow.aFolder.hint"), montage.useLibrary, Modifier.weight(1f)) {
                    actions.updateMontage { it.copy(useLibrary = true) }
                    actions.montageMusicChanged()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (montage.useLibrary) OutlinedButton(onClick = actions::chooseMusicLibrary) { Text(tr("ui.flow.chooseFolder")) }
                else {
                    OutlinedButton(onClick = actions::chooseMusic) { Text(tr(if (montage.music == null) "ui.flow.chooseSong" else "ui.flow.changeSong")) }
                    OutlinedButton(onClick = actions::openMusicDrop, enabled = montage.music != null) { Text(tr("ui.flow.hearDrop")) }
                }
            }
        }
        if (!montage.useLibrary) FlowCard(tr("ui.flow.theSong"), Modifier.fillMaxWidth(), hint = tr("ui.flow.theSong.hint")) {
            val song = montage.song
            if (song == null && montage.music != null) Busy(tr("ui.flow.readingSong"))
            SongWave(song, montage.passage(), Modifier.fillMaxWidth().height(170.dp))
            song?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("${I18n.fixed(it.bpm, 0)} BPM", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(DropTime.format(it.duration).substringBefore('.'), style = MaterialTheme.typography.bodyMedium)
                    it.drop?.let { d -> Text(tr("ui.flow.dropAt", "time" to DropTime.format(d)), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF7A7A)) }
                }
            }
            FlowSwitch(tr("ui.flow.fromStart"), montage.musicFromStart, tr("ui.flow.fromStart.hint")) { v -> actions.updateMontage { it.copy(musicFromStart = v) } }
        }
        montage.plan?.drop?.let { MusicDropPanel(it, actions) }
        FlowCard(tr("ui.flow.howLong"), Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FlowChip(tr("ui.flow.fitKills"), montage.fitKills) { actions.updateMontage { it.copy(fitKills = true) } }
                for (d in listOf(30.seconds, 60.seconds, 180.seconds)) {
                    FlowChip(I18n.duration(d), !montage.fitKills && montage.maxDuration == d) { actions.updateMontage { it.copy(fitKills = false, maxDurationText = I18n.duration(d)) } }
                }
                OutlinedTextField(
                    montage.maxDurationText, { v -> actions.updateMontage { it.copy(maxDurationText = v) } }, singleLine = true, modifier = Modifier.width(120.dp),
                    label = { Text(tr("ui.flow.max")) }, isError = montage.maxDuration == null,
                )
            }
            Text(tr("ui.flow.howLong.hint", "max" to (montage.maxDuration?.let { I18n.duration(it) } ?: "—")), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
        }
    }
}

// ------------------------------------------------------------------ 4. Finitions

@Composable
private fun FinishingStep(montage: MontageUiState, state: UiState, actions: UiActions) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowCard(tr("ui.flow.sound")) {
                Text(tr("ui.flow.gameSound"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowChip(tr("ui.flow.gameSound.full"), montage.gameAudio == GameAudio.FULL) { actions.updateMontage { it.copy(gameAudio = GameAudio.FULL) } }
                    FlowChip(tr("ui.flow.gameSound.kills"), montage.gameAudio == GameAudio.KILLS) { actions.updateMontage { it.copy(gameAudio = GameAudio.KILLS) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("ui.montage.balanceMusic"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    Slider(montage.balance.toFloat(), { v -> actions.updateMontage { it.copy(balance = v.toDouble()) } }, valueRange = -1f..1f, modifier = Modifier.weight(1f))
                    Text(tr("ui.montage.balanceGame"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                Text(balanceLabel(montage.balance), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                FlowSwitch(tr("ui.flow.reactions"), montage.reactions, tr("ui.flow.reactions.hint")) { v -> actions.updateMontage { it.copy(reactions = v) } }
                FlowSwitch(tr("ui.flow.whoosh"), montage.whoosh) { v -> actions.updateMontage { it.copy(whoosh = v) } }
            }
            FlowCard(tr("ui.flow.texts"), Modifier.fillMaxWidth()) {
                Text(tr("ui.flow.counter"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in KillCounterMode.entries) FlowChip(tr("ui.montage.killCounter.${mode.name.lowercase()}"), montage.killCounter == mode) { actions.updateMontage { it.copy(killCounter = mode) } }
                }
                FlowSwitch(tr("ui.flow.streakText", "double" to tr("video.streak.2"), "triple" to tr("video.streak.3")), montage.text) { v -> actions.updateMontage { it.copy(text = v) } }
                FlowSwitch(tr("ui.flow.roundScore"), montage.roundScore, if (montage.roundScoreKnown) null else tr("ui.flow.roundScore.unknown"), enabled = montage.roundScoreKnown) { v -> actions.updateMontage { it.copy(roundScore = v) } }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowCard(tr("ui.flow.cards")) {
                val cards = state.introCards
                FlowSwitch(tr("ui.flow.cards.on"), cards.enabled, tr("ui.flow.cards.hint"), plan = true) { v -> actions.setIntroEnabled(v); actions.updateMontage { it.copy(planDirty = true) } }
                OutlinedButton(onClick = actions::openIntro, enabled = state.config is ConfigStatus.Ready) { Text(tr("ui.flow.cards.edit")) }
            }
            FlowCard(tr("ui.flow.effects"), Modifier.fillMaxWidth(), hint = tr("ui.flow.effects.hint")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in EffectDensity.entries) FlowChip(densityLabel(d), montage.density == d) { actions.updateMontage { it.copy(density = d) } }
                }
                FlowSwitch(tr("ui.flow.zoom"), montage.zoom) { v -> actions.updateMontage { it.copy(zoom = v) } }
                FlowSwitch(tr("ui.flow.flash"), montage.flash) { v -> actions.updateMontage { it.copy(flash = v) } }
                FlowSwitch(tr("ui.flow.colors"), montage.boostColors) { v -> actions.updateMontage { it.copy(boostColors = v) } }
                FlowSwitch(tr("ui.flow.motionBlur"), montage.motionBlur) { v -> actions.updateMontage { it.copy(motionBlur = v) } }
                Text(
                    tr(if (montage.advanced) "ui.flow.more.hide" else "ui.flow.more.show"), style = MaterialTheme.typography.labelLarge, color = Palette.cyan,
                    modifier = Modifier.clickable { actions.updateMontage { it.copy(advanced = !it.advanced) } },
                )
                if (montage.advanced) {
                    FlowSwitch(tr("ui.flow.matchCut"), montage.matchCut, plan = true) { v -> actions.updateMontage { it.copy(matchCut = v, planDirty = true) } }
                    FlowSwitch(tr("ui.flow.rounds"), montage.rounds, plan = true) { v -> actions.updateMontage { it.copy(rounds = v, planDirty = true) } }
                    FlowSwitch(
                        tr("ui.flow.abilityKills"), montage.skipAbilityKills, if (montage.abilityKillsKnown) null else tr("ui.flow.abilityKills.unknown"),
                        plan = true, enabled = montage.abilityKillsKnown,
                    ) { v -> actions.updateMontage { it.copy(skipAbilityKills = v, planDirty = true) } }
                    Text(tr("ui.flow.planMark"), style = MaterialTheme.typography.bodySmall, color = CHANGES)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 5. Aperçu

@Composable
private fun PreviewStep(montage: MontageUiState, actions: UiActions) {
    val plan = montage.plan
    val view = plan?.view
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // À gauche : le kill de la drop (sinon le premier), cadré en 9:16, et le bouton qui lance l'aperçu.
        Surface(Modifier.width(340.dp).fillMaxHeight(), shape = RoundedCornerShape(12.dp), color = Color.Black, border = BorderStroke(1.dp, Palette.outline)) {
            Box(Modifier.fillMaxSize()) {
                val key = view?.clips?.firstOrNull { it.drop && it.group != null }?.group?.key ?: view?.clips?.firstNotNullOfOrNull { it.group }?.key
                rememberImage(key?.let { plan?.thumbnails?.get(it) })?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Column(Modifier.align(Alignment.BottomCenter).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = actions::previewEditedMontage, enabled = view != null) { Text(tr("ui.flow.watch")) }
                    Text(tr("ui.flow.watch.hint"), fontSize = 11.sp, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowCard(tr("ui.flow.watchBefore")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickCard(tr("ui.flow.mine"), tr("ui.flow.mine.hint"), true, Modifier.weight(1f)) { actions.previewEditedMontage() }
                    PickCard(tr("ui.flow.ideas"), tr("ui.flow.ideas.hint"), false, Modifier.weight(1f)) { actions.previewMontage() }
                }
            }
            FlowCard(tr("ui.flow.youGet"), Modifier.weight(1f).fillMaxWidth()) {
                plan?.error?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
                if (view == null) {
                    Busy(tr("ui.musicEditor.planning"))
                } else {
                    val kills = view.clips.sumOf { it.kills.size }
                    Text("· " + tr("ui.flow.youGet.length", "length" to DropTime.format(view.duration).substringBefore('.'), "music" to view.music.substringBeforeLast('.')), style = MaterialTheme.typography.bodyMedium)
                    Text("· " + tr("ui.flow.youGet.clips", "clips" to view.clips.size, "kills" to kills), style = MaterialTheme.typography.bodyMedium)
                    Text("· " + tr("ui.flow.youGet.style", "style" to (montage.chosenStyle?.name ?: tr("ui.montage.styleNone"))), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("ui.flow.order"), style = MaterialTheme.typography.labelLarge, color = Palette.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        view.clips.filter { it.group != null }.take(MAX_ORDER).forEachIndexed { i, c ->
                            Box(
                                Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(3.dp)).background(Color.Black)
                                    .border(BorderStroke(if (c.drop) 2.dp else 0.dp, if (c.drop) Color(0xFFFF5A5A) else Color.Transparent), RoundedCornerShape(3.dp)),
                            ) {
                                rememberImage(c.group?.key?.let { plan.thumbnails[it] })?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                Text("${i + 1}", fontSize = 9.sp, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 3.dp))
                            }
                        }
                    }
                    Text(tr("ui.flow.order.drop"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                Spacer(Modifier.weight(1f))
                Text(tr("ui.flow.changeSomething"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
            Surface(shape = RoundedCornerShape(12.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.accent)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("ui.flow.thumbnail"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FlowChip(tr("ui.flow.thumbnail.none"), !montage.thumbnail) { actions.updateMontage { it.copy(thumbnail = false) } }
                            for (style in ThumbnailStyle.entries) {
                                FlowChip(tr("ui.flow.thumbnail.${style.name.lowercase()}"), montage.thumbnail && montage.thumbnailStyle == style) {
                                    actions.updateMontage { it.copy(thumbnail = true, thumbnailStyle = style) }
                                }
                            }
                        }
                        Text(tr("ui.flow.thumbnail.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    }
                    Button(onClick = actions::createMontage, enabled = montage.canCreate && view != null, modifier = Modifier.height(52.dp)) {
                        Text(tr("ui.flow.create"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private const val MAX_ORDER = 20
