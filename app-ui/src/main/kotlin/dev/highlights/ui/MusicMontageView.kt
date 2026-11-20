package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.highlights.core.i18n.tr
import dev.highlights.montage.DropTime
import dev.highlights.montage.KillRef
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontageEdits
import dev.highlights.montage.MontagePin
import dev.highlights.montage.MusicAnalysis
import dev.highlights.montage.MusicDrops
import dev.highlights.montage.MusicPcm
import dev.highlights.montage.SectionKind
import dev.highlights.pipeline.HighlightPipeline
import dev.highlights.ui.player.AudioSink
import dev.highlights.ui.player.JavaSoundSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Section du morceau entier, pour l'éditeur (instants dans le morceau). */
data class SongSection(val start: Duration, val end: Duration, val kind: SectionKind, val intensity: Double)

/** Ce qu'on fait glisser dans l'éditeur : un kill placé ([KILL]), un kill de la liste ([NEW]), la drop ([DROP]). */
enum class EditorDragKind { KILL, NEW, DROP }

/**
 * Glisser en cours : [key] désigne le kill (sa désignation, celle d'un groupe : son dernier kill) ; [at] : l'instant
 * visé dans le morceau, aimanté ; [snap] : sur quoi il s'est aimanté (« beat », « note »), null : nulle part ;
 * [over] : le pointeur est au-dessus de la musique (un kill de la liste lâché ailleurs n'est pas ajouté).
 */
data class EditorDrag(val kind: EditorDragKind, val key: String?, val at: Duration, val snap: String? = null, val over: Boolean = true)

/**
 * « Montage sur la musique » (mode automatique) : le morceau entier, les kills placés par le planificateur à l'instant
 * où ils tombent, et ce qu'on y fait à la main (déplacer, retirer, ajouter un kill de la liste, déplacer la drop), qui
 * devient des contraintes du plan ([MontageConstraints]) ; le planificateur comble le reste. Partage le plan de la frise
 * ([MontagePlanState]) : [MontageUiState.plan].
 * [peaks] : crêtes du morceau entier ; [beats]/[notes] : temps et attaques pour l'aimant ; [from]/[to] : partie affichée.
 */
data class MusicEditorUi(
    val loading: Boolean = true,
    val error: String? = null,
    val duration: Duration = Duration.ZERO,
    val peaks: List<Float> = emptyList(),
    val beats: List<Duration> = emptyList(),
    val notes: List<Duration> = emptyList(),
    val sections: List<SongSection> = emptyList(),
    val from: Duration? = null,
    val to: Duration? = null,
    val playing: Boolean = false,
    val playhead: Duration? = null,
    val selected: String? = null,
    val drag: EditorDrag? = null,
    val magnet: Boolean = true,
) {
    companion object {
        /** Crêtes du morceau entier : ~20 ms chacune pour un morceau de 4 min, assez fin pour la vue zoomée. */
        const val PEAKS = 12_000

        /** Distance d'aimantation à un temps ou à une note. */
        val SNAP = 70.milliseconds

        /** Marge autour du passage du montage dans la vue de départ. */
        val MARGIN = 1.5.seconds

        /** Plus petite partie affichée (zoom maximal). */
        val MIN_SPAN = 2.seconds
    }
}

/**
 * Logique de l'éditeur, déléguée par [AppController] : ouvrir (plan calculé et morceau lu), vue (zoom, défilement),
 * lecture, sélection, glisser-déposer des kills et de la drop, retirer, tout remettre en auto.
 */
internal class MusicEditorController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val pipeline: () -> HighlightPipeline?,
    private val updateMontage: ((MontageUiState) -> MontageUiState) -> Unit,
    private val replan: () -> Unit,
    private val sinks: () -> AudioSink? = JavaSoundSink::create,
) {
    private var job: Job? = null
    private var follow: Job? = null
    private var pcm: MusicPcm? = null
    private val playback = DropPlayback(sinks)

    private val editor: MusicEditorUi? get() = state.value.montage?.editor
    private val view: PlanView? get() = state.value.montage?.plan?.view

    private fun update(change: (MusicEditorUi) -> MusicEditorUi) = state.update { s -> s.copy(montage = s.montage?.let { m -> m.editor?.let { m.copy(editor = change(it)) } ?: m }) }

    /** Ouvre l'éditeur : plan calculé (le même que la frise) et morceau lu (forme d'onde, temps, notes, sections). */
    fun open() {
        val montage = state.value.montage ?: return
        val music = montage.music?.takeIf { !montage.useLibrary } ?: return
        state.update { s -> s.copy(montage = s.montage?.copy(editor = MusicEditorUi(), plan = s.montage.plan ?: MontagePlanState())) }
        replan()
        val p = pipeline() ?: return
        job?.cancel()
        job = scope.launch {
            try {
                val tool = withContext(Dispatchers.IO) { p.musicDropTool(music) }
                val analysis = tool.byHand?.let { MusicDrops.apply(tool.detected, it) } ?: tool.detected
                val peaks = withContext(Dispatchers.Default) { tool.pcm.peaks(MusicEditorUi.PEAKS).toList() }
                pcm = tool.pcm
                update { e -> e.copy(loading = false, duration = tool.pcm.duration, peaks = peaks, error = null).withSong(analysis) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.localizedMessage ?: e.toString()) }
            }
        }
    }

    fun close() {
        stopPlayback()
        job?.cancel()
        pcm = null
        state.update { s -> s.copy(montage = s.montage?.copy(editor = null, plan = null)) }
    }

    // ------------------------------------------------------------------ vue

    /** Partie affichée : celle posée, sinon le passage du montage (avec une marge), sinon le morceau entier. */
    fun window(e: MusicEditorUi = editor ?: MusicEditorUi(), v: PlanView? = view): Pair<Duration, Duration> {
        if (e.from != null && e.to != null) return e.from to e.to
        if (v != null) return (v.musicStart - MusicEditorUi.MARGIN).coerceAtLeast(Duration.ZERO) to (v.musicStart + v.duration + MusicEditorUi.MARGIN).coerceAtMost(e.duration.takeIf { it.isPositive() } ?: (v.musicStart + v.duration + MusicEditorUi.MARGIN))
        return Duration.ZERO to e.duration
    }

    /** Zoom de [factor] (< 1 : plus près) autour de l'instant [center] (secondes du morceau). */
    fun zoom(factor: Double, center: Double) {
        val e = editor ?: return
        val (from, to) = window(e)
        val c = (center * 1000).toLong().milliseconds
        val span = ((to - from) * factor).coerceIn(MusicEditorUi.MIN_SPAN, e.duration.takeIf { it.isPositive() } ?: (to - from))
        val left = c - (c - from) * ((span / (to - from)))
        setWindow(left, left + span)
    }

    /** Défile de [seconds] (positif : vers la droite). */
    fun scroll(seconds: Double) {
        val e = editor ?: return
        val (from, to) = window(e)
        val d = (seconds * 1000).toLong().milliseconds
        setWindow(from + d, to + d)
    }

    /** Centre la vue sur [seconds] (clic sur le morceau entier). */
    fun center(seconds: Double) {
        val e = editor ?: return
        val (from, to) = window(e)
        val half = (to - from) / 2
        val c = (seconds * 1000).toLong().milliseconds
        setWindow(c - half, c + half)
    }

    private fun setWindow(from: Duration, to: Duration) = update { e ->
        val span = to - from
        val total = e.duration.takeIf { it.isPositive() } ?: to
        val f = from.coerceIn(Duration.ZERO, (total - span).coerceAtLeast(Duration.ZERO))
        e.copy(from = f, to = f + span)
    }

    // ------------------------------------------------------------------ lecture

    fun seek(seconds: Double) {
        val at = (seconds * 1000).toLong().milliseconds
        val wasPlaying = editor?.playing == true
        update { it.copy(playhead = at) }
        if (wasPlaying) play(at, null)
    }

    /** Lecture ou pause, depuis la tête de lecture (ou la drop). */
    fun toggle() {
        val e = editor ?: return
        if (e.playing) return stopPlayback()
        val from = e.playhead ?: view?.let { v -> v.dropAt?.let { v.musicStart + it } ?: v.musicStart } ?: Duration.ZERO
        play(from, null)
    }

    /** Écoute autour de [at] : de 1,5 s avant à 1,5 s après. */
    fun listenAround(at: Duration) = play((at - LISTEN).coerceAtLeast(Duration.ZERO), at + LISTEN)

    private fun play(from: Duration, end: Duration?) {
        val sound = pcm ?: return
        if (!playback.play(sound, from, end)) return
        update { it.copy(playing = true, playhead = from) }
        follow?.cancel()
        follow = scope.launch {
            while (isActive && playback.playing) {
                update { it.copy(playhead = playback.position ?: it.playhead) }
                delay(40)
            }
            update { it.copy(playing = false) }
        }
    }

    private fun stopPlayback() {
        playback.stop()
        follow?.cancel()
        update { it.copy(playing = false) }
    }

    fun toggleMagnet() = update { it.copy(magnet = !it.magnet) }

    fun select(kill: String?) = update { it.copy(selected = kill) }

    // ------------------------------------------------------------------ glisser-déposer

    /** Instant aimanté : la note ou le temps le plus proche à [MusicEditorUi.SNAP], sauf aimant coupé ou Alt. */
    internal fun snap(e: MusicEditorUi, at: Duration, free: Boolean): Pair<Duration, String?> {
        if (free || !e.magnet) return at to null
        val note = e.notes.minByOrNull { (it - at).absoluteValue }?.takeIf { (it - at).absoluteValue <= MusicEditorUi.SNAP }
        val beat = e.beats.minByOrNull { (it - at).absoluteValue }?.takeIf { (it - at).absoluteValue <= MusicEditorUi.SNAP }
        return when {
            note != null && (beat == null || (note - at).absoluteValue < (beat - at).absoluteValue) -> note to "note"
            beat != null -> beat to "beat"
            else -> at to null
        }
    }

    /** Glisser en cours vers [seconds] (instant du morceau) ; [free] : Alt, sans aimant ; [over] : au-dessus de la musique. */
    fun drag(kind: EditorDragKind, key: String?, seconds: Double, free: Boolean, over: Boolean = true) {
        val e = editor ?: return
        val raw = (seconds * 1000).toLong().milliseconds.coerceIn(Duration.ZERO, e.duration.takeIf { it.isPositive() } ?: Duration.INFINITE)
        val (at, snap) = if (kind == EditorDragKind.DROP) snapBeat(e, raw, free) else snap(e, raw, free)
        update { it.copy(drag = EditorDrag(kind, key, at, snap, over)) }
    }

    /** La drop s'aimante aux temps seulement (elle ouvre une section). */
    private fun snapBeat(e: MusicEditorUi, at: Duration, free: Boolean): Pair<Duration, String?> {
        if (free || !e.magnet) return at to null
        val beat = e.beats.minByOrNull { (it - at).absoluteValue }?.takeIf { (it - at).absoluteValue <= MusicEditorUi.SNAP } ?: return at to null
        return beat to "beat"
    }

    /** Lâcher : le kill déplacé ou ajouté est posé à l'instant visé, la drop déplacée ; le plan est recalculé. */
    fun drop() {
        val e = editor ?: return
        val d = e.drag ?: return
        update { it.copy(drag = null) }
        when (d.kind) {
            EditorDragKind.KILL, EditorDragKind.NEW -> {
                if (!d.over) return
                val refs = groupRefs(d.key ?: return) ?: return
                updateMontage { m -> m.copy(constraints = pushed(m.constraints, refs, d.at, e)) }
                update { it.copy(selected = refs.last().toString()) }
                replan()
                listenAround(d.at)
            }
            EditorDragKind.DROP -> {
                val music = state.value.montage?.music ?: return
                val p = pipeline() ?: return
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { p.setMusicDrop(music, d.at) } }
                    val analysis = runCatching { withContext(Dispatchers.IO) { p.musicDropTool(music) } }.getOrNull()
                    analysis?.let { t -> update { it.withSong(t.byHand?.let { b -> MusicDrops.apply(t.detected, b) } ?: t.detected) } }
                    replan()
                }
            }
        }
    }

    fun cancelDrag() = update { it.copy(drag = null) }

    /**
     * Le groupe [refs] épinglé à [at]. Un kill déjà épinglé sur le même temps est poussé au temps libre le plus proche
     * (choix de l'utilisateur, 20/11 : pas d'échange) ; un kill placé par le planificateur, lui, est replacé par lui.
     */
    internal fun pushed(c: MontageConstraints, refs: List<KillRef>, at: Duration, e: MusicEditorUi): MontageConstraints {
        val beats = e.beats
        fun beatOf(t: Duration) = beats.indices.minByOrNull { (beats[it] - t).absoluteValue } ?: 0
        var next = c.pinAt(refs, at)
        if (beats.isEmpty()) return next
        val target = beatOf(at)
        val others = next.pins.filter { (ref, pin) -> pin is MontagePin.At && refs.none { it.toString() == ref.toString() } }
        for ((ref, pin) in others) {
            if (beatOf((pin as MontagePin.At).music) != target) continue
            val taken = next.pins.values.filterIsInstance<MontagePin.At>().map { beatOf(it.music) }.toSet()
            val free = (1..beats.size).asSequence().flatMap { k -> sequenceOf(target + k, target - k) }
                .firstOrNull { b -> b in beats.indices && b !in taken && abs(b - target) >= PUSH_GAP } ?: continue
            next = next.pin(ref, MontagePin.At(beats[free]))
        }
        return next
    }

    /** Retire le kill [kill] (son groupe entier) du montage. */
    fun remove(kill: String) {
        val refs = groupRefs(kill) ?: return
        updateMontage { m -> m.copy(constraints = m.constraints.edit(refs, KillEdit.EXCLUDE)) }
        update { it.copy(selected = null) }
        replan()
    }

    /** Tout remettre en automatique : plus aucune contrainte (la drop posée à la main reste, c'est celle du morceau). */
    fun resetAuto() {
        updateMontage { m -> m.copy(constraints = MontageConstraints.NONE) }
        update { it.copy(selected = null) }
        replan()
    }

    /** Les désignations du groupe d'un kill (placé ou de la liste), une épingle le désigne par son dernier. */
    private fun groupRefs(kill: String): List<KillRef>? {
        val v = view ?: return null
        val groups = v.clips.mapNotNull { it.group } + v.left
        return groups.firstOrNull { g -> g.key == kill || g.refs.any { it.toString() == kill } }?.refs
            ?: runCatching { listOf(KillRef.parse(kill)) }.getOrNull()
    }

    companion object {
        val LISTEN = 1.5.seconds

        /** Un kill poussé va au moins à ce nombre de temps de celui qui prend sa place (son plan ne peut pas le partager). */
        const val PUSH_GAP = 2
    }
}

/** Le morceau analysé : temps, attaques (temps et contretemps qui frappent), sections. */
internal fun MusicEditorUi.withSong(a: MusicAnalysis): MusicEditorUi {
    val last = a.beats.lastIndex.coerceAtLeast(0)
    val notes = if (a.beats.isEmpty()) emptyList() else a.hits(0, last).filter { it.strength >= NOTE_STRENGTH }.map { it.at }
    return copy(
        beats = a.beats, notes = notes,
        sections = a.sections.map { SongSection(a.beatTime(it.startBeat), a.beatTime(it.endBeat), it.kind, it.intensity) },
    )
}

/** Vignette d'un kill, normale et réduite ; rangées de vignettes au plus. */
private val CARD_W = 96.dp
private val SMALL_CARD_W = 60.dp
private const val MAX_ROWS = 4

/** Force minimale d'une frappe pour que l'aimant la propose comme note. */
private const val NOTE_STRENGTH = 0.5

// =============================================================================================================== vues

/** Fenêtre de l'éditeur, à part du dialogue : elle a besoin de toute la largeur. */
@Composable
fun MusicMontageWindow(montage: MontageUiState, editor: MusicEditorUi, actions: UiActions) {
    DialogWindow(
        onCloseRequest = actions::closeMusicEditor,
        state = rememberDialogState(size = DpSize(1600.dp, 960.dp)),
        title = tr("ui.musicEditor.title"),
    ) {
        HighlightsTheme { MusicMontageContent(montage, editor, actions, Modifier.fillMaxSize()) }
    }
}

/**
 * Contenu de l'éditeur. En-tête (écouter, aperçu, aimant, tout remettre en auto, rendre), le morceau entier (cliquer :
 * y aller), le passage zoomé (molette : zoom ; Maj+molette : défiler ; clic sur la règle : tête de lecture) avec les
 * plans et un kill par vignette à l'instant où il tombe (glisser : déplacer, Alt : sans aimant ; clic : choisir),
 * la drop (glisser), la liste des kills non utilisés (glisser sur la musique : ajouter), la barre du kill choisi.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MusicMontageContent(montage: MontageUiState, editor: MusicEditorUi, actions: UiActions, modifier: Modifier = Modifier) {
    val plan = montage.plan
    val view = plan?.view
    var trackBounds by remember { mutableStateOf(Rect.Zero) }
    Surface(modifier, color = Palette.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(montage, editor, view, actions)
            plan?.error?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
            editor.error?.let { Text(tr("ui.musicEditor.failed", "reason" to it), color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
            if (editor.loading || view == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr(if (editor.loading) "ui.musicEditor.reading" else "ui.musicEditor.planning"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                if (editor.loading || view == null) return@Column
            }
            val (from, to) = editorWindow(editor, view)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("ui.musicEditor.song", "duration" to "%d:%02d".format(editor.duration.inWholeSeconds / 60, editor.duration.inWholeSeconds % 60)).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                    SongOverview(editor, view, from, to, actions)
                    Text(tr("ui.musicEditor.passage").uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                    Box(
                        Modifier.fillMaxWidth().weight(1f).alpha(if (plan.loading) 0.6f else 1f)
                            .onGloballyPositioned { trackBounds = it.boundsInRoot() },
                    ) {
                        Track(montage.constraints, editor, view, plan.thumbnails, from, to, actions)
                    }
                    plan.timing?.let { KillTimingPanel(it, actions, constraints = montage.constraints) }
                    EditorBar(editor, view, montage.constraints, actions)
                }
                UnusedPanel(view, plan.thumbnails, editor, actions, trackBounds, from, to)
            }
        }
    }
}

/** Partie affichée (voir [MusicEditorController.window]). */
internal fun editorWindow(e: MusicEditorUi, v: PlanView?): Pair<Duration, Duration> {
    if (e.from != null && e.to != null) return e.from to e.to
    if (v != null) {
        val end = v.musicStart + v.duration + MusicEditorUi.MARGIN
        return (v.musicStart - MusicEditorUi.MARGIN).coerceAtLeast(Duration.ZERO) to (if (e.duration.isPositive()) end.coerceAtMost(e.duration) else end)
    }
    return Duration.ZERO to e.duration
}

@Composable
private fun Header(montage: MontageUiState, e: MusicEditorUi, view: PlanView?, actions: UiActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(tr("ui.musicEditor.title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val kills = view?.clips?.sumOf { it.kills.size } ?: 0
            Text(
                view?.let {
                    tr(
                        "ui.musicEditor.subtitle", "music" to it.music, "bpm" to dev.highlights.core.i18n.I18n.fixed(it.bpm, 1), "count" to kills,
                        "from" to DropTime.format(it.musicStart), "to" to DropTime.format(it.musicStart + it.duration),
                    )
                } ?: montage.music?.fileName?.toString().orEmpty(),
                style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, maxLines = 2,
            )
            Text(
                tr(if (montage.constraints.isEmpty) "ui.musicEditor.modeAuto" else "ui.musicEditor.modeAutoEdited", "count" to montage.constraints.count),
                style = MaterialTheme.typography.labelMedium, color = Palette.cyan,
            )
        }
        Button(onClick = actions::toggleEditorPlayback) {
            Text(if (e.playing) tr("ui.musicEditor.pause", "time" to DropTime.format(e.playhead ?: Duration.ZERO)) else tr("ui.musicEditor.play"))
        }
        OutlinedButton(onClick = actions::previewEditedMontage, enabled = view != null) { Text(tr("ui.musicEditor.preview")) }
        OutlinedButton(onClick = actions::toggleEditorMagnet) { Text(tr(if (e.magnet) "ui.musicEditor.magnetOn" else "ui.musicEditor.magnetOff")) }
        OutlinedButton(onClick = actions::resetEditorAuto, enabled = !montage.constraints.isEmpty) { Text(tr("ui.musicEditor.reset")) }
        Button(onClick = actions::createMontage, enabled = montage.canCreate) { Text(tr("ui.musicEditor.render")) }
        OutlinedButton(onClick = actions::closeMusicEditor) { Text(tr("ui.common.close")) }
    }
}

private fun sectionTint(kind: SectionKind): Color = when (kind) {
    SectionKind.DROP -> Color(0xFFB4501E)
    SectionKind.BUILD_UP -> Color(0xFF8C6D1F)
    SectionKind.BREAKDOWN -> Color(0xFF4B3A6B)
    SectionKind.INTRO, SectionKind.OUTRO -> Color(0xFF3A4350)
    else -> Color(0xFF2F5D7C)
}

/** Le morceau entier : sections, passage du montage, partie affichée ; un clic y centre la vue. */
@Composable
private fun SongOverview(e: MusicEditorUi, v: PlanView, from: Duration, to: Duration, actions: UiActions) {
    val total = e.duration.inWholeMicroseconds / 1e6
    Canvas(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(6.dp))
            .pointerInput(total) { detectTapGestures { actions.centerEditor(it.x / size.width * total) } },
    ) {
        drawRect(Palette.surfaceHigh, size = size)
        if (total <= 0) return@Canvas
        fun x(t: Duration) = (t.inWholeMicroseconds / 1e6 / total * size.width).toFloat()
        e.sections.forEach { s -> drawRect(sectionTint(s.kind).copy(alpha = 0.7f), Offset(x(s.start), 0f), Size(x(s.end) - x(s.start), 8f)) }
        val w = size.width / e.peaks.size.coerceAtLeast(1)
        // Une crête sur quatre suffit à cette largeur.
        for (i in e.peaks.indices step 4) {
            val h = (e.peaks[i] * (size.height - 14)).coerceAtLeast(1f)
            drawRect(Palette.cyan.copy(alpha = 0.6f), Offset(i * w, 10 + (size.height - 10 - h) / 2), Size((w * 4).coerceAtLeast(1f), h))
        }
        drawRect(Palette.accent.copy(alpha = 0.25f), Offset(x(v.musicStart), 0f), Size(x(v.musicStart + v.duration) - x(v.musicStart), size.height))
        drawRect(Color.White, Offset(x(from), 1f), Size(x(to) - x(from), size.height - 2), style = Stroke(2f))
        v.dropAt?.let { drawLine(Palette.accent, Offset(x(v.musicStart + it), 0f), Offset(x(v.musicStart + it), size.height), 2f) }
        e.playhead?.let { drawLine(Palette.cyan, Offset(x(it), 0f), Offset(x(it), size.height), 2f) }
    }
}

/** Boucle de glisser : [onMove] (déplacement en px, Alt) à chaque mouvement, [onEnd] au relâcher, [onClick] sans mouvement. */
private suspend fun PointerInputScope.dragLoop(onStart: () -> Unit, onMove: (Float, Boolean) -> Unit, onEnd: () -> Unit, onClick: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var last = down.position
        var moved = 0f
        var started = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (started) onEnd() else onClick()
                break
            }
            val dx = change.position.x - last.x
            last = change.position
            moved += abs(dx)
            if (!started && moved > 3f) {
                started = true
                onStart()
            }
            if (started) {
                change.consume()
                onMove(dx, event.keyboardModifiers.isAltPressed)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Track(
    edits: MontageConstraints,
    e: MusicEditorUi,
    v: PlanView,
    thumbnails: Map<String, java.nio.file.Path>,
    from: Duration,
    to: Duration,
    actions: UiActions,
) {
    val spanS = (to - from).inWholeMicroseconds / 1e6
    val fromS = from.inWholeMicroseconds / 1e6
    BoxWithConstraints(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Palette.surface)
            .onPointerEvent(PointerEventType.Scroll) { ev ->
                val change = ev.changes.first()
                val dy = change.scrollDelta.y
                if (ev.keyboardModifiers.isShiftPressed) actions.scrollEditor(dy * spanS * 0.1)
                else actions.zoomEditor(if (dy > 0) 1.25 else 0.8, fromS + change.position.x / size.width * spanS)
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current
        fun xDp(t: Duration): Dp = maxWidth * ((t.inWholeMicroseconds / 1e6 - fromS) / spanS).toFloat()
        val pxPerSecond = widthPx / spanS
        val musicStart = v.musicStart
        val waveTop = 56f
        val waveH = with(density) { 190.dp.toPx() }
        // Règle, sections, forme d'onde, temps, plans, tête de lecture.
        Canvas(
            Modifier.fillMaxSize().pointerInput(fromS, spanS) {
                detectTapGestures { p -> if (p.y < 52.dp.toPx()) actions.seekEditor(fromS + p.x / size.width * spanS) else actions.selectEditorKill(null) }
            },
        ) {
            fun x(t: Duration) = ((t.inWholeMicroseconds / 1e6 - fromS) / spanS * size.width).toFloat()
            var t = kotlin.math.ceil(fromS)
            val step = if (spanS > 60) 5.0 else 1.0
            while (t <= fromS + spanS) {
                drawLine(Palette.outline, Offset(x((t * 1000).toLong().milliseconds), 0f), Offset(x((t * 1000).toLong().milliseconds), 10f), 1f)
                t += step
            }
            e.sections.filter { it.end > from && it.start < to }.forEach { s ->
                drawRect(sectionTint(s.kind).copy(alpha = 0.75f), Offset(x(maxOf(s.start, from)), 30f), Size(x(minOf(s.end, to)) - x(maxOf(s.start, from)), 18f))
            }
            e.beats.filter { it in from..to }.forEach { b -> drawLine(Palette.outline.copy(alpha = 0.5f), Offset(x(b), waveTop), Offset(x(b), waveTop + waveH), 1f) }
            if (e.peaks.isNotEmpty() && e.duration.isPositive()) {
                val total = e.duration.inWholeMicroseconds / 1e6
                val i0 = (fromS / total * e.peaks.size).toInt().coerceIn(0, e.peaks.lastIndex)
                val i1 = ((fromS + spanS) / total * e.peaks.size).toInt().coerceIn(0, e.peaks.lastIndex)
                val w = (size.width / (i1 - i0 + 1)).coerceAtLeast(1f)
                for (i in i0..i1) {
                    val h = (e.peaks[i] * waveH).coerceAtLeast(1f)
                    drawRect(Palette.cyan.copy(alpha = 0.75f), Offset((i - i0) * size.width / (i1 - i0 + 1), waveTop + (waveH - h) / 2), Size(w, h))
                }
            }
            // Hors du passage du montage : assombri.
            drawRect(Palette.background.copy(alpha = 0.55f), Offset(0f, waveTop), Size(x(musicStart).coerceIn(0f, size.width), waveH))
            val end = x(musicStart + v.duration).coerceIn(0f, size.width)
            drawRect(Palette.background.copy(alpha = 0.55f), Offset(end, waveTop), Size(size.width - end, waveH))
            // Plans : bandes alternées (début et fin de chaque plan sur la musique).
            val laneTop = waveTop + waveH + 8
            v.clips.forEachIndexed { i, c ->
                val color = if (c.group == null) Palette.surfaceHigh else if (i % 2 == 0) Color(0xFF5A6B80) else Color(0xFF3B4757)
                drawRect(color, Offset(x(musicStart + c.start) + 1, laneTop), Size(x(musicStart + c.end) - x(musicStart + c.start) - 2, 22f))
            }
            // Plan du kill déplacé, à sa nouvelle place (même longueur, pointillés).
            e.drag?.takeIf { it.kind == EditorDragKind.KILL }?.let { d ->
                v.clips.firstOrNull { c -> c.group?.key == d.key }?.let { c ->
                    val k = c.kills.lastOrNull()?.at ?: return@let
                    val before = k - c.start
                    val after = c.end - k
                    drawRect(Palette.accent, Offset(x(d.at - before), laneTop - 2), Size(x(d.at + after) - x(d.at - before), 26f), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                }
            }
            e.playhead?.let { drawLine(Palette.cyan, Offset(x(it), 0f), Offset(x(it), size.height), 2f) }
        }
        // Libellés : règle et sections.
        var t = kotlin.math.ceil(fromS).toInt()
        val labelStep = when {
            spanS > 120 -> 20
            spanS > 60 -> 10
            spanS > 20 -> 2
            else -> 1
        }
        while (t <= fromS + spanS) {
            if (t % labelStep == 0) Text("%d:%02d".format(t / 60, t % 60), fontSize = 10.sp, color = Palette.textMuted, modifier = Modifier.offset(x = xDp(t.seconds) + 2.dp, y = 12.dp))
            t++
        }
        e.sections.filter { it.end > from && it.start < to }.forEach { s ->
            Text(sectionName(s.kind), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.offset(x = xDp(maxOf(s.start, from)) + 4.dp, y = 31.dp))
        }
        var n = 0
        v.clips.forEach { c ->
            if (c.group == null) return@forEach
            n++
            Text("$n", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.offset(x = xDp(musicStart + c.start) + 4.dp, y = with(density) { (waveTop + waveH + 10).toDp() }))
        }
        // La drop : poignée qu'on glisse (aimantée aux temps).
        v.dropAt?.let { dropOut ->
            val drop = musicStart + dropOut
            val dragging = e.drag?.takeIf { it.kind == EditorDragKind.DROP }
            val shown = dragging?.at ?: drop
            var acc by remember { mutableStateOf(0.0) }
            Box(Modifier.offset(x = xDp(shown) - 1.dp, y = 56.dp).width(3.dp).height(190.dp).background(Palette.accent))
            Text(
                tr("ui.musicEditor.drop", "time" to DropTime.format(shown)), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.background,
                modifier = Modifier.offset(x = xDp(shown) + 4.dp, y = 58.dp).background(Palette.accent, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                    .pointerInput(drop) {
                        dragLoop(
                            onStart = { acc = drop.inWholeMicroseconds / 1e6 },
                            onMove = { dx, alt -> acc += dx / pxPerSecond; actions.dragEditor(EditorDragKind.DROP.name, null, acc, alt, true) },
                            onEnd = actions::dropEditor,
                            onClick = {},
                        )
                    },
            )
        }
        // Les kills : une vignette reliée à l'instant exact où le kill tombe. Les vignettes vont sur la première rangée
        // où elles ne chevauchent pas la précédente (quatre au plus) ; trop serrées (plans d'un temps des parties
        // rapides : 14 kills en 7 s sur Rome Is Burning), elles rétrécissent. La molette zoome pour les écarter.
        val cardTop = with(density) { (waveTop + waveH + 44).toDp() }
        val lineTop = with(density) { (waveTop + waveH + 30).toDp() }
        val shownKills = v.clips.flatMap { c -> c.group?.let { g -> c.kills.map { k -> Triple(c, g, k) } }.orEmpty() }
        val positions = shownKills.map { (_, _, k) -> xDp(musicStart + k.at) }
        fun layout(cardW: Dp): List<Int>? {
            val ends = MutableList(MAX_ROWS) { (-1000).dp }
            return positions.map { x ->
                val row = ends.indexOfFirst { it <= x - cardW / 2 }.takeIf { it >= 0 } ?: return null
                ends[row] = x + cardW / 2 + 4.dp
                row
            }
        }
        val compact = layout(CARD_W) == null
        val cardW = if (compact) SMALL_CARD_W else CARD_W
        val rows = layout(cardW) ?: positions.indices.map { it % MAX_ROWS }
        val rowH = if (compact) 52.dp else 76.dp
        shownKills.forEachIndexed { index, (_, g, k) ->
            val at = musicStart + k.at
            val y = cardTop + rowH * rows[index]
            val key = k.ref.toString()
            val dragged = e.drag?.takeIf { it.kind == EditorDragKind.KILL && it.key == g.key }
            val pinned = edits.pins.keys.any { r -> g.refs.any { it.toString() == r.toString() } }
            val selected = e.selected != null && (e.selected == key || e.selected == g.key)
            val dim = e.drag != null && dragged == null
            Box(Modifier.offset(x = xDp(at) - 1.dp, y = lineTop).width(2.dp).height(y - lineTop + 4.dp).alpha(if (dim) 0.4f else 1f).background(if (dragged != null) Palette.textMuted else Palette.text))
            var acc by remember(key) { mutableStateOf(0.0) }
            EditorKillCard(
                thumbnails[g.key], DropTime.format(at), pinned = pinned, selected = selected, ghost = dragged != null, width = cardW,
                modifier = Modifier.offset(x = xDp(at) - cardW / 2, y = y).alpha(if (dim) 0.45f else 1f).pointerInput(key, at) {
                    dragLoop(
                        onStart = { acc = at.inWholeMicroseconds / 1e6 },
                        onMove = { dx, alt -> acc += dx / pxPerSecond; actions.dragEditor(EditorDragKind.KILL.name, g.key, acc, alt, true) },
                        onEnd = actions::dropEditor,
                        onClick = { actions.selectEditorKill(g.key) },
                    )
                },
            )
        }
        // La vignette soulevée sous les rangées, sans sortir de la piste (fenêtre basse).
        val liftTop = minOf(cardTop + rowH * MAX_ROWS + 8.dp, maxHeight - 76.dp)
        // Ce qu'on fait glisser : la vignette soulevée, la ligne de guidage et l'instant (aimanté ou non).
        e.drag?.takeIf { it.over && it.kind != EditorDragKind.DROP }?.let { d ->
            val color = if (d.kind == EditorDragKind.NEW) Palette.cyan else Color.White
            Box(Modifier.offset(x = xDp(d.at) - 1.dp, y = 50.dp).width(2.dp).height(liftTop - 50.dp).background(color))
            val label = when {
                d.kind == EditorDragKind.NEW -> tr("ui.musicEditor.addHere", "time" to DropTime.format(d.at))
                d.snap == "note" -> tr("ui.musicEditor.snapNote", "time" to DropTime.format(d.at))
                d.snap == "beat" -> tr("ui.musicEditor.snapBeat", "time" to DropTime.format(d.at))
                else -> tr("ui.musicEditor.free", "time" to DropTime.format(d.at))
            }
            Text(
                label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.background,
                modifier = Modifier.offset(x = xDp(d.at) + 6.dp, y = 60.dp).background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            EditorKillCard(
                d.key?.let { thumbnails[it] }, DropTime.format(d.at), pinned = true, selected = true, lifted = true,
                modifier = Modifier.offset(x = xDp(d.at) - 48.dp, y = liftTop).shadow(12.dp, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
private fun sectionName(kind: SectionKind): String = tr(
    when (kind) {
        SectionKind.INTRO -> "ui.musicEditor.section.intro"
        SectionKind.BUILD_UP -> "ui.musicEditor.section.buildUp"
        SectionKind.DROP -> "ui.musicEditor.section.drop"
        SectionKind.BREAKDOWN -> "ui.musicEditor.section.breakdown"
        SectionKind.OUTRO -> "ui.musicEditor.section.outro"
        else -> "ui.musicEditor.section.body"
    },
)

@Composable
private fun EditorKillCard(
    image: java.nio.file.Path?, time: String, pinned: Boolean, selected: Boolean, modifier: Modifier,
    ghost: Boolean = false, lifted: Boolean = false, width: Dp = CARD_W,
) {
    val shape = RoundedCornerShape(6.dp)
    val border = when {
        lifted || selected -> Palette.accent
        pinned -> Palette.accent.copy(alpha = 0.7f)
        else -> Palette.outline
    }
    Column(modifier.width(width).clip(shape).background(Palette.surfaceHigh).border(BorderStroke(if (lifted || selected || pinned) 2.dp else 1.dp, border), shape).alpha(if (ghost) 0.3f else 1f)) {
        Box(Modifier.width(width).height(width * 9 / 16).background(Color.Black)) {
            rememberImage(image)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(time, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (lifted || selected) Palette.accent else Palette.text, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Barre du bas : le kill choisi et ses actions, sinon l'aide. */
@Composable
private fun EditorBar(e: MusicEditorUi, v: PlanView, constraints: MontageConstraints, actions: UiActions) {
    val chosen = e.selected?.let { s -> v.clips.firstOrNull { c -> c.group?.key == s || c.kills.any { it.ref.toString() == s } } }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Palette.surface, border = BorderStroke(1.dp, if (chosen != null) Palette.accent else Palette.outline)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chosen == null || chosen.group == null) {
                Text(tr("ui.musicEditor.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, modifier = Modifier.weight(1f))
                return@Row
            }
            val g = chosen.group
            val kill = chosen.kills.lastOrNull()
            val at = kill?.let { v.musicStart + it.at } ?: v.musicStart
            val pinned = constraints.pins.keys.any { r -> g.refs.any { it.toString() == r.toString() } }
            Column(Modifier.weight(1f)) {
                Text(tr("ui.musicEditor.selected", "kill" to g.key, "time" to DropTime.format(at)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    tr(if (pinned) "ui.musicEditor.selectedPinned" else "ui.musicEditor.selectedAuto", "from" to DropTime.format(v.musicStart + chosen.start), "to" to DropTime.format(v.musicStart + chosen.end)),
                    style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                )
            }
            OutlinedButton(onClick = { actions.listenEditorAround(at.inWholeMicroseconds / 1e6) }) { Text(tr("ui.musicEditor.listen")) }
            kill?.let { k -> OutlinedButton(onClick = { actions.openKillTiming(k.ref.toString()) }) { Text(tr("ui.musicEditor.timing")) } }
            if (pinned) OutlinedButton(onClick = { actions.editMontageKill(g.refs.map { it.toString() }, KillEdit.CLEAR) }) { Text(tr("ui.musicEditor.unpin")) }
            OutlinedButton(onClick = { actions.removeEditorKill(g.key) }) { Text(tr("ui.musicEditor.remove"), color = Palette.danger) }
        }
    }
}

/** Kills non utilisés : chacun se glisse sur la musique pour l'ajouter à l'instant où on le lâche. */
@Composable
private fun UnusedPanel(v: PlanView, thumbnails: Map<String, java.nio.file.Path>, e: MusicEditorUi, actions: UiActions, track: Rect, from: Duration, to: Duration) {
    Surface(Modifier.width(300.dp).fillMaxHeight(), shape = RoundedCornerShape(8.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ui.musicEditor.unused", "count" to v.left.size).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
            Text(tr(if (v.left.isEmpty()) "ui.musicEditor.unusedNone" else "ui.musicEditor.unusedHint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            v.left.forEach { g ->
                var bounds by remember(g.key) { mutableStateOf(Rect.Zero) }
                var rootX by remember(g.key) { mutableStateOf(0f) }
                val dragging = e.drag?.takeIf { it.kind == EditorDragKind.NEW && it.key == g.key }
                val shape = RoundedCornerShape(6.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(if (dragging != null) Palette.cyan.copy(alpha = 0.15f) else Palette.surfaceHigh)
                        .border(BorderStroke(if (dragging != null) 2.dp else 1.dp, if (dragging != null) Palette.cyan else Palette.outline), shape)
                        .onGloballyPositioned { bounds = it.boundsInRoot() }
                        .pointerInput(g.key, track, from, to) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                rootX = bounds.left + down.position.x
                                var started = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) {
                                        if (started) actions.dropEditor()
                                        break
                                    }
                                    val px = bounds.left + ch.position.x
                                    val py = bounds.top + ch.position.y
                                    if (!started && abs(px - rootX) > 4f) started = true
                                    if (started) {
                                        ch.consume()
                                        val over = track.contains(Offset(px, py))
                                        val seconds = (from + (to - from) * ((px - track.left) / track.width.coerceAtLeast(1f)).toDouble()).inWholeMicroseconds / 1e6
                                        actions.dragEditor(EditorDragKind.NEW.name, g.key, seconds, ev.keyboardModifiers.isAltPressed, over)
                                    }
                                }
                            }
                        }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black)) {
                        rememberImage(thumbnails[g.key])?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("ui.musicEditor.killLabel", "time" to KillRef.precise(g.kills.last())), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                        Text(g.media.path.fileName.toString().removeSuffix(".mp4"), fontSize = 10.sp, color = Palette.textMuted, maxLines = 1)
                    }
                    Text("⠿", fontSize = 16.sp, color = Palette.textMuted)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
