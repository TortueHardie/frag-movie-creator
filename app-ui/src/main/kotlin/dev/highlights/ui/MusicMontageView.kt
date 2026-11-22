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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
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
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.pipeline.TasteState
import dev.highlights.montage.DropTime
import dev.highlights.montage.IntentLayout
import dev.highlights.montage.MusicIntents
import dev.highlights.montage.ExtraMarks
import dev.highlights.montage.IntentRange
import dev.highlights.montage.IntentMark
import dev.highlights.montage.IntentKind
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/**
 * Les deux étapes de l'écran (demande du 20/11) : d'abord les kills sur la musique, sans plans ([KILLS]) ; ensuite les
 * plans construits autour de leurs instants ([CLIPS]).
 */
enum class EditorStep { KILLS, CLIPS }

/** Un kill posé sur la musique à l'étape 1 : [key] désigne son groupe (son dernier kill), [at] l'instant du morceau. */
data class LaidKill(val key: String, val at: Duration)

/**
 * Ce qu'on fait glisser dans l'éditeur : un kill placé ([KILL]), un kill de la liste ([NEW]), la drop ([DROP]), un
 * repère de l'étape 1 ([MARK], clé : son numéro), un bord de la vitrine ([SHOWCASE_START], [SHOWCASE_END]).
 */
enum class EditorDragKind { KILL, NEW, DROP, MARK, SHOWCASE_START, SHOWCASE_END, SHOWCASE_MOVE }

/** Ce qu'un double-clic pose à l'étape 1 : un repère d'une sorte ([IntentKind]) ou la vitrine. */
enum class EditorTool(val kind: IntentKind?) { KILL(IntentKind.KILL), MULTI(IntentKind.MULTI), CUT(IntentKind.CUT), SLOW(IntentKind.SLOW), TRANSITION(IntentKind.TRANSITION), SHOWCASE(null) }

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
    val step: EditorStep = EditorStep.KILLS,
    /** Étape 1 : les kills posés (tous ceux du montage) ; null tant que le placement automatique n'est pas arrivé. */
    val layout: List<LaidKill>? = null,
    /** Le placement automatique proposé (« tout remettre en auto » y revient). */
    val auto: List<LaidKill> = emptyList(),
    /** Tous les groupes de kills des parties (posés ou non), pour la liste et les vignettes. */
    val groups: List<PlanGroup> = emptyList(),
    /** Avertissement doux de l'étape 1 (un kill poussé parce que trop proche d'un autre) ; null : aucun. */
    val notice: dev.highlights.core.i18n.Message? = null,
    /** La drop du morceau (posée ou détectée), instant du morceau. */
    val dropAt: Duration? = null,
    /**
     * Étape 1 (choix du 20/11) : les repères d'intention posés sur le morceau, sans dire quel kill ; null tant que la
     * proposition n'est pas arrivée.
     */
    val intents: MusicIntents? = null,
    /** Les repères proposés par l'algorithme (« tout remettre en auto » y revient). */
    val proposal: MusicIntents? = null,
    /** Ce qu'un double-clic pose. */
    val tool: EditorTool = EditorTool.KILL,
    /** Force de chaque temps ([beats]) : la valeur d'un repère, comme à l'étape 2 ([IntentLayout.assign]). */
    val strengths: List<Double> = emptyList(),
) {
    /** Repères « kill » sans kill (plus que de groupes) : ceux où la musique frappe le moins, comme l'étape 2 les choisit. */
    fun extraMarks(): List<IntentMark> {
        val marks = intents?.killMarks.orEmpty()
        val over = marks.size - groups.size
        if (over <= 0) return emptyList()
        fun value(m: IntentMark): Double {
            val b = beats.indices.minByOrNull { (beats[it] - m.at).absoluteValue } ?: return 0.0
            val drop = dropAt?.let { d -> beats.indices.minByOrNull { (beats[it] - d).absoluteValue } }
            return strengths.getOrElse(b) { 0.0 } + (if (drop != null && abs(b - drop) <= 1) IntentLayout.DROP_BONUS else 0.0)
        }
        return marks.sortedBy(::value).take(over)
    }

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
    /** « Placer les plans » : la proposition et le placement final, à enregistrer pour apprendre. */
    private val recordLayout: (auto: MusicIntents, final: MusicIntents) -> Unit = { _, _ -> },
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
        // Étape 1 : on repart du placement automatique, ou des kills déjà posés (rouverture).
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

    /**
     * Un plan est arrivé : les groupes connus pour la liste, et, à la première fois, les kills posés (étape 1) : ceux du
     * plan automatique, ou ceux déjà posés ([MontageConstraints.layout]).
     */
    fun planned(v: PlanView) {
        val e = editor ?: return
        val groups = v.clips.mapNotNull { it.group } + v.left
        val placed = v.clips.mapNotNull { c -> c.group?.let { g -> c.kills.lastOrNull()?.let { LaidKill(g.key, v.musicStart + it.at) } } }
        val laid = state.value.montage?.constraints?.layout
        val posed = state.value.montage?.constraints?.intents
        update {
            it.copy(
                groups = (groups + it.groups).distinctBy { g -> g.key },
                layout = it.layout ?: laid?.map { (ref, at) -> LaidKill(ref.toString(), at) }?.sortedBy { k -> k.at } ?: placed,
                auto = if (laid == null && it.auto.isEmpty()) placed else it.auto,
                // Étape 1 : les repères posés (rouverture), sinon la proposition de l'algorithme.
                intents = it.intents ?: posed ?: v.proposal,
                proposal = it.proposal ?: v.proposal,
            )
        }
    }

    /** « Placer les plans » : les kills posés deviennent la contrainte du plan, l'étape 2 montre les plans construits autour. */
    fun placeClips() {
        val e = editor ?: return
        val intents = e.intents ?: return
        if (intents.killMarks.isEmpty()) return
        // Apprendre d'où l'utilisateur veut ses kills, coupes, ralentis… (demande insistante du 20/11) : proposition et repères.
        e.proposal?.let { auto -> runCatching { recordLayout(auto, intents) } }
        // Les kills mis à la main sur un repère (étape 2) restent sur les repères qui existent encore.
        val ids = intents.marks.map { it.id }.toSet()
        updateMontage { m ->
            val fixed = m.constraints.intents?.fixed.orEmpty().filterKeys { it in ids }
            m.copy(constraints = m.constraints.copy(intents = intents.copy(fixed = fixed), layout = null, pins = emptyMap()))
        }
        update { it.copy(step = EditorStep.CLIPS, selected = null, notice = null, drag = null, from = null, to = null) }
        replan()
    }

    /** « Revenir aux kills » : l'étape 1, les kills posés gardés ; replacer les plans refait l'étape 2. */
    fun backToKills() = update { it.copy(step = EditorStep.KILLS, selected = null, drag = null, from = null, to = null) }

    fun close() {
        stopPlayback()
        job?.cancel()
        pcm = null
        state.update { s -> s.copy(montage = s.montage?.copy(editor = null, plan = null)) }
    }

    // ------------------------------------------------------------------ vue

    /** Partie affichée : celle posée, sinon (étape 1) le morceau entier, (étape 2) le passage du montage avec une marge. */
    fun window(e: MusicEditorUi = editor ?: MusicEditorUi(), v: PlanView? = view): Pair<Duration, Duration> {
        if (e.from != null && e.to != null) return e.from to e.to
        if (e.step == EditorStep.KILLS && e.duration.isPositive()) return Duration.ZERO to e.duration
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
        // Étape 1 : repères et vitrine ; étape 2 : un kill sur un repère (échanger, déplacer) ; la drop aux deux.
        if (e.step == EditorStep.CLIPS && kind in setOf(EditorDragKind.MARK, EditorDragKind.SHOWCASE_START, EditorDragKind.SHOWCASE_END, EditorDragKind.SHOWCASE_MOVE)) return
        if (e.step == EditorStep.KILLS && (kind == EditorDragKind.KILL || kind == EditorDragKind.NEW)) return
        val raw = (seconds * 1000).toLong().milliseconds.coerceIn(Duration.ZERO, e.duration.takeIf { it.isPositive() } ?: Duration.INFINITE)
        val (at, snap) = if (kind == EditorDragKind.DROP || kind == EditorDragKind.SHOWCASE_START || kind == EditorDragKind.SHOWCASE_END || kind == EditorDragKind.SHOWCASE_MOVE) snapBeat(e, raw, free) else snap(e, raw, free)
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
            // Étape 1 : un repère déplacé devient « à vous » ; jamais de blocage, des repères proches sont permis.
            EditorDragKind.MARK -> {
                val id = d.key?.toIntOrNull() ?: return
                updateIntents { i -> i.copy(marks = i.marks.map { m -> if (m.id == id) m.copy(at = d.at, byHand = true) else m }.sortedBy { it.at }) }
                update { it.copy(selected = markKey(id)) }
                listenAround(d.at)
            }
            EditorDragKind.SHOWCASE_START, EditorDragKind.SHOWCASE_END -> updateIntents { i ->
                val r = i.showcase ?: return@updateIntents i
                val next = if (d.kind == EditorDragKind.SHOWCASE_START) r.copy(start = minOf(d.at, r.end - MIN_SHOWCASE), byHand = true)
                else r.copy(end = maxOf(d.at, r.start + MIN_SHOWCASE), byHand = true)
                i.copy(showcase = next)
            }
            // Toute la vitrine déplacée : même longueur, début à l'instant visé, sans sortir du morceau.
            EditorDragKind.SHOWCASE_MOVE -> updateIntents { i ->
                val r = i.showcase ?: return@updateIntents i
                val length = r.end - r.start
                val last = (e.duration.takeIf { it.isPositive() } ?: (d.at + length)) - length
                val start = d.at.coerceIn(Duration.ZERO, last.coerceAtLeast(Duration.ZERO))
                i.copy(showcase = r.copy(start = start, end = start + length, byHand = true))
            }
            EditorDragKind.KILL, EditorDragKind.NEW -> {
                if (!d.over) return
                val refs = groupRefs(d.key ?: return) ?: return
                // Étape 2 avec des repères : le kill va sur le repère le plus proche ; celui qui y était prend sa place.
                state.value.montage?.constraints?.intents?.let { intents ->
                    putOnMark(intents, d.key, refs, d.at)
                    return
                }
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

    // ------------------------------------------------------------------ repères (étape 1)

    private fun updateIntents(change: (MusicIntents) -> MusicIntents) = update { e -> e.intents?.let { e.copy(intents = change(it)) } ?: e }

    fun setTool(tool: EditorTool) = update { it.copy(tool = tool) }

    /** Double-clic sur le morceau : pose l'outil choisi à [seconds] (aimanté), la vitrine sur deux temps à partir de là. */
    fun addMark(seconds: Double, free: Boolean = false) {
        val e = editor ?: return
        if (e.step != EditorStep.KILLS) return
        val raw = (seconds * 1000).toLong().milliseconds.coerceIn(Duration.ZERO, e.duration.takeIf { it.isPositive() } ?: Duration.INFINITE)
        val kind = e.tool.kind
        if (kind == null) {
            val (start, _) = snapBeat(e, raw, free)
            val period = e.beats.zipWithNext().firstOrNull()?.let { (a, b) -> b - a } ?: 500.milliseconds
            updateIntents { it.copy(showcase = IntentRange(start, start + period * 4, byHand = true)) }
            update { it.copy(selected = SHOWCASE_KEY) }
            return
        }
        val (at, _) = snap(e, raw, free)
        val intents = e.intents ?: MusicIntents(emptyList())
        val mark = IntentMark(intents.nextId, kind, at, byHand = true)
        update { it.copy(intents = intents.copy(marks = (intents.marks + mark).sortedBy { m -> m.at }), selected = markKey(mark.id)) }
        listenAround(at)
    }

    /** Repères « kill » en trop : en vitrine, ou laissés vides. */
    fun setExtras(mode: ExtraMarks) = updateIntents { it.copy(extras = mode) }

    /**
     * Étape 2 : le kill [key] (son groupe [refs]) mis sur le repère « kill » le plus proche de [at] ; s'il était sur un
     * autre repère, le kill de celui-ci prend sa place (échange). Mis à la main : gardé tel quel aux plans suivants.
     */
    private fun putOnMark(intents: MusicIntents, key: String, refs: List<KillRef>, at: Duration) {
        val v = view ?: return
        val owners = markOwners(v, intents)
        val ref = refs.last()
        val keyOfGroup = v.clips.mapNotNull { it.group }.firstOrNull { g -> g.key == key || g.refs.any { it.toString() == key } }?.key ?: key
        val source = owners.entries.firstOrNull { it.value.key == keyOfGroup }?.key
        val target = intents.killMarks.filter { (it.at - at).absoluteValue <= MARK_REACH }.minByOrNull { (it.at - at).absoluteValue }
        if (target == null || target.id == source) {
            // Lâché loin de tout repère : le plan va là (son repère déplacé), ou un nouveau plan s'ajoute là.
            val moved = intents.marks.map { m -> if (m.id == source) m.copy(at = at, byHand = true) else m }
            val next = if (source != null) intents.copy(marks = moved.sortedBy { it.at }, fixed = intents.fixed + (source to ref))
            else IntentMark(intents.nextId, IntentKind.KILL, at, byHand = true).let { m -> intents.copy(marks = (intents.marks + m).sortedBy { it.at }, fixed = intents.fixed + (m.id to ref)) }
            updateMontage { m -> m.copy(constraints = m.constraints.copy(intents = next, forced = m.constraints.forced + refs, excluded = m.constraints.excluded - refs.toSet())) }
            update { it.copy(selected = ref.toString(), intents = next) }
            replan()
            listenAround(at)
            return
        }
        var fixed = intents.fixed.filterValues { r -> refs.none { it.toString() == r.toString() } } + (target.id to ref)
        val displaced = owners[target.id]
        if (source != null && source != target.id && displaced != null) fixed = fixed + (source to displaced.refs.last())
        updateMontage { m -> m.copy(constraints = m.constraints.copy(intents = intents.copy(fixed = fixed), forced = m.constraints.forced + refs, excluded = m.constraints.excluded - refs.toSet())) }
        update { it.copy(selected = ref.toString(), intents = intents.copy(fixed = fixed)) }
        replan()
        listenAround(target.at)
    }

    /** Étape 3b : retire le plan du kill [kill] et son repère (ou ses repères rapprochés) ; le montage se refait. */
    fun removeClip(kill: String) {
        val v = view ?: return
        val intents = state.value.montage?.constraints?.intents ?: return remove(kill)
        val g = (v.clips.mapNotNull { it.group } + v.left).firstOrNull { g -> g.key == kill || g.refs.any { it.toString() == kill } } ?: return
        val marks = markOwners(v, intents).filterValues { it.key == g.key }.keys
        if (marks.isEmpty()) return remove(kill)
        val next = intents.copy(marks = intents.marks.filter { it.id !in marks }, fixed = intents.fixed - marks)
        if (next.killMarks.isEmpty()) return
        updateMontage { m -> m.copy(constraints = m.constraints.copy(intents = next)) }
        update { it.copy(selected = null, intents = next) }
        replan()
    }

    /** Étape 3b : un passage sans kill (vitrine) de deux temps à partir de [seconds] ; une seule plage de vitrine. */
    fun addShowcase(seconds: Double) {
        val intents = state.value.montage?.constraints?.intents ?: return
        val e = editor ?: return
        val at = (seconds * 1000).toLong().milliseconds
        val start = e.beats.minByOrNull { (it - at).absoluteValue } ?: at
        val period = e.beats.zipWithNext().firstOrNull()?.let { (a, b) -> b - a } ?: 500.milliseconds
        val next = intents.copy(showcase = IntentRange(start, start + period * 2, byHand = true))
        updateMontage { m -> m.copy(constraints = m.constraints.copy(intents = next)) }
        update { it.copy(intents = next) }
        replan()
    }

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

    /** Étape 1 : retire le repère [kill] (« mark:<n> ») ou la vitrine ; étape 2 : retire le kill [kill] (son groupe). */
    fun remove(kill: String) {
        val e = editor ?: return
        if (e.step == EditorStep.KILLS) {
            if (kill == SHOWCASE_KEY) updateIntents { it.copy(showcase = null) }
            else markId(kill)?.let { id -> updateIntents { i -> i.copy(marks = i.marks.filter { it.id != id }) } }
            update { it.copy(selected = null, notice = null) }
            return
        }
        val refs = groupRefs(kill) ?: return
        // Avec des repères : le repère garde sa place et prend le meilleur kill qui reste.
        updateMontage { m ->
            val intents = m.constraints.intents?.let { i -> i.copy(fixed = i.fixed.filterValues { r -> refs.none { it.toString() == r.toString() } }) }
            m.copy(constraints = m.constraints.edit(refs, KillEdit.EXCLUDE).copy(intents = intents))
        }
        update { it.copy(selected = null) }
        replan()
    }

    /** Tout remettre en automatique : plus aucune contrainte (la drop posée à la main reste, c'est celle du morceau). */
    fun resetAuto() {
        val e = editor
        if (e != null && e.step == EditorStep.KILLS) {
            update { it.copy(layout = it.auto, intents = it.proposal, selected = null, notice = null) }
            updateMontage { m -> m.copy(constraints = m.constraints.copy(layout = null, intents = null)) }
            return
        }
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

        /** Lâché à moins de ça d'un repère « kill », un kill va sur ce repère (remplace, échange) ; plus loin, il en pose un. */
        val MARK_REACH = 400.milliseconds

        /** Vitrine d'un temps au moins. */
        val MIN_SHOWCASE = 250.milliseconds
        const val SHOWCASE_KEY = "showcase"
        fun markKey(id: Int) = "mark:$id"
        fun markId(key: String?): Int? = key?.removePrefix("mark:")?.takeIf { key.startsWith("mark:") }?.toIntOrNull()

        /**
         * Qui est sur quel repère « kill » au plan [v] : le kill dont le dernier tombe sur le repère (à 5 ms près) ; les
         * repères rapprochés d'un multi-kill vont à lui aussi.
         */
        fun markOwners(v: PlanView, intents: MusicIntents): Map<Int, PlanGroup> {
            val out = mutableMapOf<Int, PlanGroup>()
            v.clips.forEach { c ->
                val g = c.group ?: return@forEach
                val last = c.kills.lastOrNull() ?: return@forEach
                val t = v.musicStart + last.at
                val m = intents.killMarks.firstOrNull { (it.at - t).absoluteValue <= 5.milliseconds } ?: return@forEach
                out[m.id] = g
                // Repères rapprochés avant lui pris par le même multi-kill.
                val first = c.kills.first().let { v.musicStart + it.at }
                intents.killMarks.filter { it.at >= first - 5.milliseconds && it.at < m.at && it.kind == IntentKind.KILL && it.id !in out }.forEach { out[it.id] = g }
            }
            return out
        }

        /** Un kill poussé va au moins à ce nombre de temps de celui qui prend sa place (son plan ne peut pas le partager). */
        const val PUSH_GAP = 2

    }
}

/** Le morceau analysé : temps, attaques (temps et contretemps qui frappent), sections. */
internal fun MusicEditorUi.withSong(a: MusicAnalysis): MusicEditorUi {
    val last = a.beats.lastIndex.coerceAtLeast(0)
    val notes = if (a.beats.isEmpty()) emptyList() else a.hits(0, last).filter { it.strength >= NOTE_STRENGTH }.map { it.at }
    return copy(
        beats = a.beats, notes = notes, dropAt = a.beatTime(a.dropBeat), strengths = a.beats.indices.map { a.strength(it) },
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
fun MusicMontageContent(montage: MontageUiState, editor: MusicEditorUi, actions: UiActions, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val plan = montage.plan
    val view = plan?.view
    var trackBounds by remember { mutableStateOf(Rect.Zero) }
    Surface(modifier, color = Palette.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header(montage, editor, view, actions, embedded)
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
            val kills = editor.step == EditorStep.KILLS
            if (kills) IntentToolbar(editor, actions)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("ui.musicEditor.song", "duration" to "%d:%02d".format(editor.duration.inWholeSeconds / 60, editor.duration.inWholeSeconds % 60)).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                    SongOverview(editor, view, from, to, actions)
                    Text(tr(if (kills) "ui.musicEditor.killsZoom" else "ui.musicEditor.passage").uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
                    Box(
                        Modifier.fillMaxWidth().weight(1f).alpha(if (plan.loading && !kills) 0.6f else 1f)
                            .onGloballyPositioned { trackBounds = it.boundsInRoot() },
                    ) {
                        if (kills) IntentsTrack(editor, from, to, actions)
                        else Track(montage.constraints, editor, view, plan.thumbnails, from, to, actions)
                    }
                    plan.timing?.let { KillTimingPanel(it, actions, constraints = montage.constraints) }
                    plan.drop?.let { MusicDropPanel(it, actions) }
                    if (!kills) MarkerChips(montage.constraints, actions)
                    if (kills) IntentsBar(editor, actions) else EditorBar(editor, view, montage.constraints, actions)
                }
                // Étape 1 : ce qu'on prépare ; étape 2 : les kills non utilisés, à glisser sur un repère.
                if (kills) IntentPanel(editor, actions)
                else UnusedPanel(view.left, plan.thumbnails, editor, actions, trackBounds, from, to, draggable = montage.constraints.intents != null)
            }
        }
    }
}

/** Partie affichée (voir [MusicEditorController.window]). */
internal fun editorWindow(e: MusicEditorUi, v: PlanView?): Pair<Duration, Duration> {
    if (e.from != null && e.to != null) return e.from to e.to
    // Étape 1 : tout le morceau (le zoom reste à la molette).
    if (e.step == EditorStep.KILLS && e.duration.isPositive()) return Duration.ZERO to e.duration
    if (v != null) {
        val end = v.musicStart + v.duration + MusicEditorUi.MARGIN
        return (v.musicStart - MusicEditorUi.MARGIN).coerceAtLeast(Duration.ZERO) to (if (e.duration.isPositive()) end.coerceAtMost(e.duration) else end)
    }
    return Duration.ZERO to e.duration
}

@Composable
private fun Header(montage: MontageUiState, e: MusicEditorUi, view: PlanView?, actions: UiActions, embedded: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            if (!embedded) Text(tr("ui.musicEditor.title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val kills = view?.clips?.sumOf { it.kills.size } ?: 0
            Text(
                view?.let {
                    // Étape 1 : rien n'est encore placé, seulement le morceau.
                    if (e.step == EditorStep.KILLS) tr("ui.musicEditor.subtitleSong", "music" to it.music, "bpm" to dev.highlights.core.i18n.I18n.fixed(it.bpm, 1))
                    else tr(
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
        StepTabs(e.step, embedded)
        Button(onClick = actions::toggleEditorPlayback) {
            Text(if (e.playing) tr("ui.musicEditor.pause", "time" to DropTime.format(e.playhead ?: Duration.ZERO)) else tr("ui.musicEditor.play"))
        }
        OutlinedButton(onClick = actions::toggleEditorMagnet) { Text(tr(if (e.magnet) "ui.musicEditor.magnetOn" else "ui.musicEditor.magnetOff")) }
        if (e.step == EditorStep.KILLS) {
            OutlinedButton(onClick = actions::resetEditorAuto, enabled = e.intents != null && e.intents != e.proposal) { Text(tr("ui.musicEditor.reset")) }
            if (!embedded) Button(onClick = actions::placeEditorClips, enabled = e.intents?.killMarks?.isNotEmpty() == true) { Text(tr("ui.musicEditor.placeClips")) }
        } else if (!embedded) {
            OutlinedButton(onClick = actions::backEditorToKills) { Text(tr("ui.musicEditor.backToKills")) }
            OutlinedButton(onClick = actions::previewEditedMontage, enabled = view != null) { Text(tr("ui.musicEditor.preview")) }
            Button(onClick = actions::createMontage, enabled = montage.canCreate) { Text(tr("ui.musicEditor.render")) }
        }
        if (!embedded) OutlinedButton(onClick = actions::closeMusicEditor) { Text(tr("ui.common.close")) }
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
            // Repères de l'étape 1 : chaque kill tombe sur le sien (losange numéroté au-dessus des plans).
            edits.intents?.let { intents ->
                intents.killMarks.forEach { m -> diamond(Offset(x(m.at), laneTop - 8f), 6f, if (m.byHand) MARK_YOU else MARK_AUTO, m.byHand) }
            }
            e.playhead?.let { drawLine(Palette.cyan, Offset(x(it), 0f), Offset(x(it), size.height), 2f) }
        }
        edits.intents?.killMarks?.forEachIndexed { i, m ->
            Text("${i + 1}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (m.byHand) MARK_YOU else MARK_AUTO, modifier = Modifier.offset(x = xDp(m.at) + 7.dp, y = with(density) { (waveTop + waveH - 10).toDp() }))
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
        // Un « + » sur chaque coupe : un passage sans kill (vitrine) à cet endroit ; un kill, lui, se glisse là.
        if (edits.intents != null) v.clips.zipWithNext().filter { (a, b) -> a.group != null && b.group != null }.forEach { (_, c) ->
            val at = musicStart + c.start
            Text(
                "+", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.background,
                modifier = Modifier.offset(x = xDp(at) - 6.dp, y = with(density) { (waveTop + waveH + 32).toDp() }).size(12.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Palette.cyan).clickable { actions.addEditorShowcase(at.inWholeMicroseconds / 1e6) }.padding(start = 3.dp),
            )
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
                Text(tr(if (constraints.intents != null) "ui.musicEditor.clipsHintIntents" else "ui.musicEditor.clipsHint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, modifier = Modifier.weight(1f))
                return@Row
            }
            val g = chosen.group
            val kill = chosen.kills.lastOrNull()
            val at = kill?.let { v.musicStart + it.at } ?: v.musicStart
            // Posé à la main : épinglé, ou posé à l'étape 1 (tous les kills le sont alors).
            val laidOut = constraints.layout != null
            // Mis à la main sur un repère (étape 2) : posé, comme une épingle.
            val onMark = constraints.intents?.fixed?.values?.any { r -> g.refs.any { it.toString() == r.toString() } } == true
            val pinned = laidOut || onMark || constraints.pins.keys.any { r -> g.refs.any { it.toString() == r.toString() } }
            Column(Modifier.weight(1f)) {
                Text(tr("ui.musicEditor.selected", "kill" to g.key, "time" to DropTime.format(at)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    tr(if (pinned) "ui.musicEditor.selectedPinned" else "ui.musicEditor.selectedAuto", "from" to DropTime.format(v.musicStart + chosen.start), "to" to DropTime.format(v.musicStart + chosen.end)),
                    style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                )
            }
            OutlinedButton(onClick = { actions.listenEditorAround(at.inWholeMicroseconds / 1e6) }) { Text(tr("ui.musicEditor.listen")) }
            kill?.let { k -> OutlinedButton(onClick = { actions.openKillTiming(k.ref.toString()) }) { Text(tr(if (constraints.intents != null) "ui.musicEditor.passage3b" else "ui.musicEditor.timing")) } }
            if (constraints.intents != null) OutlinedButton(onClick = { actions.removeEditorClip(g.key) }) { Text(tr("ui.musicEditor.removeClip"), color = Palette.danger) }
            // Avec des kills posés (étape 1), on les retire ou les remet là-bas : « Revenir aux kills ».
            if (pinned && !laidOut && !onMark) OutlinedButton(onClick = { actions.editMontageKill(g.refs.map { it.toString() }, KillEdit.CLEAR) }) { Text(tr("ui.musicEditor.unpin")) }
            if (!laidOut) OutlinedButton(onClick = { actions.removeEditorKill(g.key) }) { Text(tr(if (constraints.intents != null) "ui.musicEditor.removeKill" else "ui.musicEditor.remove"), color = Palette.danger) }
            // Avec des repères, le repère reste et prend le meilleur kill suivant ; glisser un kill sur un autre : échange.
        }
    }
}

/** Kills non utilisés : chacun se glisse sur la musique pour l'ajouter à l'instant où on le lâche. */
@Composable
private fun UnusedPanel(groups: List<PlanGroup>, thumbnails: Map<String, java.nio.file.Path>, e: MusicEditorUi, actions: UiActions, track: Rect, from: Duration, to: Duration, draggable: Boolean) {
    Surface(Modifier.width(300.dp).fillMaxHeight(), shape = RoundedCornerShape(8.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ui.musicEditor.unused", "count" to groups.size).uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
            Text(
                tr(if (groups.isEmpty()) "ui.musicEditor.unusedNone" else if (draggable) "ui.musicEditor.unusedHint" else "ui.musicEditor.unusedStep2"),
                style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
            )
            groups.forEach { g ->
                var bounds by remember(g.key) { mutableStateOf(Rect.Zero) }
                var rootX by remember(g.key) { mutableStateOf(0f) }
                val dragging = e.drag?.takeIf { it.kind == EditorDragKind.NEW && it.key == g.key }
                val shape = RoundedCornerShape(6.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(if (dragging != null) Palette.cyan.copy(alpha = 0.15f) else Palette.surfaceHigh)
                        .border(BorderStroke(if (dragging != null) 2.dp else 1.dp, if (dragging != null) Palette.cyan else Palette.outline), shape)
                        .onGloballyPositioned { bounds = it.boundsInRoot() }
                        .pointerInput(g.key, track, from, to, draggable) {
                            if (!draggable) return@pointerInput
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

/** Les deux étapes, l'en cours en évidence. */
@Composable
private fun StepTabs(step: EditorStep, embedded: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Dans le parcours, l'éditeur est l'étape 3 : ses deux temps s'appellent 3a et 3b.
        val tabs = if (embedded) listOf(EditorStep.KILLS to "ui.flow.sub.marks", EditorStep.CLIPS to "ui.flow.sub.clips")
        else listOf(EditorStep.KILLS to "ui.musicEditor.step1", EditorStep.CLIPS to "ui.musicEditor.step2")
        for ((s, key) in tabs) {
            val on = s == step
            Text(
                tr(key), style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) Palette.background else Palette.textMuted,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Palette.accent else Palette.surfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}


/**
 * Où l'utilisateur pose ses kills (étape 1), dit en clair dans « Goûts appris » (« Vous posez vos kills ~20 ms après
 * le temps, sur les temps qui frappent ») : chaque goût dit s'il est appliqué à la proposition ; contretemps et kills
 * après la drop sont montrés sans être appliqués ; de quoi l'oublier.
 */
@Composable
fun LayoutTasteCard(state: TasteState, actions: UiActions) {
    val l = state.layout
    val min = dev.highlights.montage.LayoutTasteLearner.MIN_MONTAGES
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("ui.taste.layout.title"), style = MaterialTheme.typography.titleMedium)
            if (l.isEmpty) {
                Text(tr("ui.taste.layout.none"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                return@Column
            }
            Text(tr("ui.taste.layout.intro", "count" to l.montages, "chosen" to l.chosen, "min" to min), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            fun status(active: Boolean) = if (active) tr("ui.taste.layout.applied") else tr("ui.taste.layout.pending", "min" to min)
            @Composable
            fun line(text: String, active: Boolean) =
                Text(text + " : " + status(active), style = MaterialTheme.typography.bodySmall, color = if (active) Palette.accent else Palette.textMuted)
            l.offset?.let { o ->
                val ms = o.inWholeMilliseconds
                line(tr(if (ms >= 0) "ui.taste.layout.offsetAfter" else "ui.taste.layout.offsetBefore", "ms" to kotlin.math.abs(ms)), l.offsetActive)
            }
            l.accentGain?.let { g ->
                val text = I18n.fixed(kotlin.math.abs(g), 2)
                line(tr(if (g >= 0) "ui.taste.layout.accentStrong" else "ui.taste.layout.accentCalm", "gain" to (if (g >= 0) text else "-$text")), l.accentActive)
            }
            l.density?.let { d -> line(tr(if (d >= 1) "ui.taste.layout.denser" else "ui.taste.layout.sparser", "factor" to I18n.fixed(d, 2)), l.densityActive) }
            val pct = { x: Double? -> I18n.percent(x ?: 0.0) }
            Text(
                tr("ui.taste.layout.observed", "you" to pct(l.offbeatShare), "auto" to pct(l.autoOffbeatShare), "youDrop" to pct(l.afterDropShare), "autoDrop" to pct(l.autoAfterDropShare)),
                style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
            )
            OutlinedButton(onClick = actions::resetLayoutTaste) { Text(tr("ui.taste.layout.reset")) }
        }
    }
}

// =============================================================================================================== étape 1

/** Couleurs des repères : proposés par l'algorithme, posés par l'utilisateur, vitrine, avertissement. */
private val MARK_AUTO = Color(0xFF7FB8C8)
private val MARK_YOU = Color(0xFFFF8A3D)
private val SHOWCASE_COLOR = Color(0xFFB98CFF)
private val WARN_COLOR = Color(0xFFE8B84A)
private val CUT_COLOR = Color(0xFFE6E6E6)

private fun DrawScope.diamond(c: Offset, r: Float, color: Color, filled: Boolean) {
    val p = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }
    if (filled) drawPath(p, color) else {
        drawPath(p, color.copy(alpha = 0.18f))
        drawPath(p, color, style = Stroke(1.6f))
    }
}

private fun toolKey(t: EditorTool) = when (t) {
    EditorTool.KILL -> "ui.musicEditor.tool.kill"
    EditorTool.MULTI -> "ui.musicEditor.tool.multi"
    EditorTool.CUT -> "ui.musicEditor.tool.cut"
    EditorTool.SLOW -> "ui.musicEditor.tool.slow"
    EditorTool.TRANSITION -> "ui.musicEditor.tool.transition"
    EditorTool.SHOWCASE -> "ui.musicEditor.tool.showcase"
}

private fun kindKey(k: IntentKind) = when (k) {
    IntentKind.KILL -> "ui.musicEditor.kind.kill"
    IntentKind.MULTI -> "ui.musicEditor.kind.multi"
    IntentKind.CUT -> "ui.musicEditor.kind.cut"
    IntentKind.SLOW -> "ui.musicEditor.kind.slow"
    IntentKind.TRANSITION -> "ui.musicEditor.kind.transition"
}

private fun toolColor(t: EditorTool) = when (t) {
    EditorTool.KILL, EditorTool.MULTI -> MARK_YOU
    EditorTool.CUT -> CUT_COLOR
    EditorTool.SLOW -> Palette.cyan
    EditorTool.TRANSITION -> Color(0xFFFFD166)
    EditorTool.SHOWCASE -> SHOWCASE_COLOR
}

/** Outils de l'étape 1 (ce qu'un double-clic pose) et le compteur des kills disponibles. */
@Composable
private fun IntentToolbar(e: MusicEditorUi, actions: UiActions) {
    val marks = e.intents?.killMarks?.size ?: 0
    val over = marks > e.groups.size
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorTool.entries.forEach { t ->
            val on = e.tool == t
            val color = toolColor(t)
            Text(
                tr(toolKey(t)), style = MaterialTheme.typography.labelLarge, color = color,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) color.copy(alpha = 0.18f) else Palette.surface)
                    .border(BorderStroke(1.dp, if (on) color else Palette.outline), RoundedCornerShape(16.dp))
                    .clickable { actions.setEditorTool(t.name) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            tr("ui.musicEditor.counter", "kills" to e.groups.size, "marks" to marks), style = MaterialTheme.typography.labelLarge,
            color = if (over) WARN_COLOR else Palette.text,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (over) WARN_COLOR.copy(alpha = 0.14f) else Palette.surface)
                .border(BorderStroke(1.dp, if (over) WARN_COLOR else Palette.outline), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Étape 1 : le morceau entier (molette : zoom), sections, drop, et les repères d'intention, sans aucun kill nommé :
 * kill et multi-kill (losanges numérotés : creux = proposés, pleins = à vous), coupes (traits), ralentis et transitions
 * (pastilles), la vitrine (plage, bords glissables). Glisser : déplacer (aimant, Alt : sans) ; clic : choisir ;
 * double-clic : poser l'outil choisi.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun IntentsTrack(e: MusicEditorUi, from: Duration, to: Duration, actions: UiActions) {
    val spanS = (to - from).inWholeMicroseconds / 1e6
    val fromS = from.inWholeMicroseconds / 1e6
    val intents = e.intents ?: MusicIntents(emptyList())
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
        val waveTop = 56f
        val waveH = with(density) { 170.dp.toPx() }
        val laneTop = waveTop + waveH + 18f
        val extras = e.extraMarks().map { it.id }.toSet()
        val killMarks = intents.killMarks
        val shown = { m: IntentMark -> e.drag?.takeIf { it.kind == EditorDragKind.MARK && it.key == m.id.toString() }?.at ?: m.at }
        val sc = intents.showcase?.let { r ->
            val d = e.drag
            when (d?.kind) {
                EditorDragKind.SHOWCASE_MOVE -> IntentRange(d.at, d.at + (r.end - r.start), r.byHand)
                else -> IntentRange(if (d?.kind == EditorDragKind.SHOWCASE_START) d.at else r.start, if (d?.kind == EditorDragKind.SHOWCASE_END) d.at else r.end, r.byHand)
            }
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(fromS, spanS) {
                detectTapGestures(
                    onTap = { p -> if (p.y < 52.dp.toPx()) actions.seekEditor(fromS + p.x / size.width * spanS) else actions.selectEditorKill(null) },
                    onDoubleTap = { p -> if (p.y >= 52.dp.toPx()) actions.addEditorMark(fromS + p.x / size.width * spanS, false) },
                )
            },
        ) {
            fun x(t: Duration) = ((t.inWholeMicroseconds / 1e6 - fromS) / spanS * size.width).toFloat()
            e.sections.filter { it.end > from && it.start < to }.forEach { s ->
                drawRect(sectionTint(s.kind).copy(alpha = 0.75f), Offset(x(maxOf(s.start, from)), 30f), Size(x(minOf(s.end, to)) - x(maxOf(s.start, from)), 18f))
            }
            val beatsIn = e.beats.filter { it in from..to }
            if (beatsIn.size > 1 && size.width / beatsIn.size >= 4f) beatsIn.forEach { b -> drawLine(Palette.outline.copy(alpha = 0.5f), Offset(x(b), waveTop), Offset(x(b), waveTop + waveH), 1f) }
            if (e.peaks.isNotEmpty() && e.duration.isPositive()) {
                val total = e.duration.inWholeMicroseconds / 1e6
                val i0 = (fromS / total * e.peaks.size).toInt().coerceIn(0, e.peaks.lastIndex)
                val i1 = ((fromS + spanS) / total * e.peaks.size).toInt().coerceIn(0, e.peaks.lastIndex)
                val count = (i1 - i0 + 1).coerceAtLeast(1)
                val columns = size.width.toInt().coerceAtLeast(1)
                for (c in 0 until columns) {
                    val a = i0 + (c.toLong() * count / columns).toInt()
                    val b = (i0 + ((c + 1).toLong() * count / columns).toInt()).coerceAtLeast(a + 1).coerceAtMost(i1 + 1)
                    var p = 0f
                    for (i in a until b) p = maxOf(p, e.peaks[i])
                    val h = (p * waveH).coerceAtLeast(1f)
                    drawRect(Palette.cyan.copy(alpha = 0.75f), Offset(c.toFloat(), waveTop + (waveH - h) / 2), Size(1f, h))
                }
            }
            // La vitrine : une plage, pleine quand elle est à vous.
            sc?.let { r ->
                val a = x(r.start)
                val w = x(r.end) - a
                drawRect(SHOWCASE_COLOR.copy(alpha = if (r.byHand) 0.22f else 0.12f), Offset(a, waveTop), Size(w, size.height - waveTop))
                drawRect(SHOWCASE_COLOR, Offset(a, waveTop), Size(w, size.height - waveTop), style = Stroke(1.5f, pathEffect = if (r.byHand) null else PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
            }
            // Coupes : un trait pointillé sur la forme d'onde.
            intents.of(IntentKind.CUT).forEach { m ->
                drawLine(CUT_COLOR, Offset(x(shown(m)), waveTop - 4f), Offset(x(shown(m)), size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f)))
            }
            drawRect(Color.White.copy(alpha = 0.03f), Offset(0f, laneTop), Size(size.width, size.height - laneTop))
            // Kills et multi-kills : losanges, sur des rangées quand ils sont serrés.
            val rows = FloatArray(4) { Float.NEGATIVE_INFINITY }
            killMarks.forEach { m ->
                val cx = x(shown(m))
                val row = rows.indexOfFirst { cx - it > 22f }.takeIf { it >= 0 } ?: 0
                rows[row] = cx
                val cy = laneTop + 22f + row * 30f
                val color = if (m.byHand) MARK_YOU else MARK_AUTO
                drawLine(color.copy(alpha = 0.55f), Offset(cx, waveTop), Offset(cx, cy - 9f), 1.2f, pathEffect = if (m.byHand) null else PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
                diamond(Offset(cx, cy), 9f, color, m.byHand)
                if (m.kind == IntentKind.MULTI) diamond(Offset(cx + 7f, cy), 9f, color, m.byHand)
                if (m.id in extras) drawCircle(WARN_COLOR, 15f, Offset(cx, cy), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))))
                if (e.selected == MusicEditorController.markKey(m.id)) drawCircle(Color.White, 13f, Offset(cx, cy), style = Stroke(2f))
            }
            // Ralentis et transitions : une pastille sous la forme d'onde.
            (intents.of(IntentKind.SLOW) + intents.of(IntentKind.TRANSITION)).forEach { m ->
                val color = if (m.kind == IntentKind.SLOW) Palette.cyan else Color(0xFFFFD166)
                drawLine(color.copy(alpha = 0.7f), Offset(x(shown(m)), waveTop + waveH - 30f), Offset(x(shown(m)), waveTop + waveH + 4f), 2f)
                if (e.selected == MusicEditorController.markKey(m.id)) drawCircle(Color.White, 12f, Offset(x(shown(m)), waveTop + waveH - 8f), style = Stroke(2f))
            }
            e.playhead?.let { drawLine(Palette.cyan, Offset(x(it), 0f), Offset(x(it), size.height), 2f) }
        }
        var t = kotlin.math.ceil(fromS).toInt()
        val labelStep = when {
            spanS > 150 -> 15
            spanS > 60 -> 10
            spanS > 20 -> 2
            else -> 1
        }
        while (t <= fromS + spanS) {
            if (t % labelStep == 0) Text("%d:%02d".format(t / 60, t % 60), fontSize = 10.sp, color = Palette.textMuted, modifier = Modifier.offset(x = xDp(t.seconds) + 2.dp, y = 12.dp))
            t++
        }
        e.sections.filter { it.end > from && it.start < to && xDp(minOf(it.end, to)) - xDp(maxOf(it.start, from)) > 40.dp }.forEach { s ->
            Text(sectionName(s.kind), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, modifier = Modifier.offset(x = xDp(maxOf(s.start, from)) + 4.dp, y = 31.dp))
        }
        // La drop, glissable (aimantée aux temps).
        e.dropAt?.let { drop ->
            val dropShown = e.drag?.takeIf { it.kind == EditorDragKind.DROP }?.at ?: drop
            var acc by remember { mutableStateOf(0.0) }
            Box(Modifier.offset(x = xDp(dropShown) - 1.dp, y = 56.dp).width(3.dp).height(170.dp).background(Palette.accent))
            Text(
                tr("ui.musicEditor.drop", "time" to DropTime.format(dropShown)), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.background,
                modifier = Modifier.offset(x = xDp(dropShown) + 4.dp, y = 58.dp).background(Palette.accent, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
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
        // Vitrine : libellé (clic : choisir ; glisser : déplacer toute la plage) et bords à glisser. Les gestes ne sont
        // liés qu'à leur poignée, jamais à la position affichée : celle-ci suit la souris, et un geste lié à elle
        // repartait de zéro à chaque mouvement (22/11 : bords et repères qui lâchaient en route).
        val storedShowcase by rememberUpdatedState(intents.showcase)
        sc?.let { r ->
            var moved by remember { mutableStateOf(0.0) }
            Text(
                tr("ui.musicEditor.showcaseLabel"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SHOWCASE_COLOR, maxLines = 1,
                // Sous la forme d'onde, hors des bords à glisser : sur une vitrine courte, le bord de fin le couvrait.
                modifier = Modifier.offset(x = xDp(r.start) + 6.dp, y = with(density) { (waveTop + waveH + 1).toDp() })
                    .background(SHOWCASE_COLOR.copy(alpha = 0.18f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)
                    .testTag("showcase-move")
                    .pointerInput(Unit) {
                        dragLoop(
                            onStart = { moved = (storedShowcase?.start ?: Duration.ZERO).inWholeMicroseconds / 1e6 },
                            onMove = { dx, alt -> moved += dx / pxPerSecond; actions.dragEditor(EditorDragKind.SHOWCASE_MOVE.name, null, moved, alt, true) },
                            onEnd = actions::dropEditor,
                            onClick = { actions.selectEditorKill(MusicEditorController.SHOWCASE_KEY) },
                        )
                    },
            )
            for ((kind, at) in listOf(EditorDragKind.SHOWCASE_START to r.start, EditorDragKind.SHOWCASE_END to r.end)) {
                var acc by remember(kind) { mutableStateOf(0.0) }
                Box(
                    Modifier.offset(x = xDp(at) - 5.dp, y = with(density) { waveTop.toDp() }).width(10.dp).height(with(density) { waveH.toDp() })
                        .testTag(if (kind == EditorDragKind.SHOWCASE_START) "showcase-start" else "showcase-end")
                        .pointerInput(kind) {
                            dragLoop(
                                onStart = {
                                    val s = storedShowcase
                                    acc = ((if (kind == EditorDragKind.SHOWCASE_START) s?.start else s?.end) ?: Duration.ZERO).inWholeMicroseconds / 1e6
                                },
                                onMove = { dx, alt -> acc += dx / pxPerSecond; actions.dragEditor(kind.name, null, acc, alt, true) },
                                onEnd = actions::dropEditor,
                                onClick = { actions.selectEditorKill(MusicEditorController.SHOWCASE_KEY) },
                            )
                        },
                )
            }
        }
        // Les repères : une poignée (numéro ou symbole) qu'on glisse ; clic : choisir.
        val storedMarks by rememberUpdatedState(intents.marks.associate { it.id to it.at })
        val rows = MutableList(4) { (-1000).dp }
        var number = 0
        intents.marks.sortedBy { it.at }.forEach { m -> key(m.id) {
            val at = shown(m)
            val key = MusicEditorController.markKey(m.id)
            val (label, y, color) = when (m.kind) {
                IntentKind.KILL, IntentKind.MULTI -> {
                    number++
                    val cx = xDp(at)
                    val row = rows.indexOfFirst { cx - it > with(density) { 22f.toDp() } }.takeIf { it >= 0 } ?: 0
                    rows[row] = cx
                    Triple("$number", with(density) { (laneTop + 34f + row * 30f).toDp() }, if (m.byHand) MARK_YOU else MARK_AUTO)
                }
                IntentKind.CUT -> Triple("┃ " + tr("ui.musicEditor.kind.cut"), with(density) { (waveTop + waveH - 22).toDp() }, CUT_COLOR)
                IntentKind.SLOW -> Triple("◐", with(density) { (waveTop + waveH - 20).toDp() }, Palette.cyan)
                IntentKind.TRANSITION -> Triple("↯", with(density) { (waveTop + waveH - 20).toDp() }, Color(0xFFFFD166))
            }
            var acc by remember(m.id) { mutableStateOf(0.0) }
            Text(
                label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
                modifier = Modifier.offset(x = xDp(at) - (if (m.kind.takesKill) 4.dp else 6.dp), y = y)
                    .background(if (e.selected == key) Palette.surfaceHigh else Color.Transparent, RoundedCornerShape(3.dp))
                    .testTag("mark-${m.id}")
                    .pointerInput(m.id) {
                        dragLoop(
                            onStart = { acc = (storedMarks[m.id] ?: at).inWholeMicroseconds / 1e6 },
                            onMove = { dx, alt -> acc += dx / pxPerSecond; actions.dragEditor(EditorDragKind.MARK.name, m.id.toString(), acc, alt, true) },
                            onEnd = actions::dropEditor,
                            onClick = { actions.selectEditorKill(key) },
                        )
                    },
            )
        } }
        // Les losanges aussi se glissent (zone sous chaque repère « kill »).
        val hitRows = FloatArray(4) { Float.NEGATIVE_INFINITY }
        killMarks.forEach { m -> key(m.id) {
            val at = shown(m)
            val cxPx = ((at.inWholeMicroseconds / 1e6 - fromS) / spanS * widthPx).toFloat()
            val row = hitRows.indexOfFirst { cxPx - it > 22f }.takeIf { it >= 0 } ?: 0
            hitRows[row] = cxPx
            var acc by remember(m.id) { mutableStateOf(0.0) }
            Box(
                Modifier.offset(x = xDp(at) - 11.dp, y = with(density) { (laneTop + 22f + row * 30f).toDp() } - 11.dp).size(22.dp)
                    .pointerInput(m.id) {
                        dragLoop(
                            onStart = { acc = (storedMarks[m.id] ?: at).inWholeMicroseconds / 1e6 },
                            onMove = { dx, alt -> acc += dx / pxPerSecond; actions.dragEditor(EditorDragKind.MARK.name, m.id.toString(), acc, alt, true) },
                            onEnd = actions::dropEditor,
                            onClick = { actions.selectEditorKill(MusicEditorController.markKey(m.id)) },
                        )
                    },
            )
        } }
        // Glisser en cours : l'instant visé et l'aimant.
        e.drag?.takeIf { it.kind == EditorDragKind.MARK }?.let { d ->
            val label = when (d.snap) {
                "note" -> tr("ui.musicEditor.snapNote", "time" to DropTime.format(d.at))
                "beat" -> tr("ui.musicEditor.snapBeat", "time" to DropTime.format(d.at))
                else -> tr("ui.musicEditor.free", "time" to DropTime.format(d.at))
            }
            Box(Modifier.offset(x = xDp(d.at) - 1.dp, y = 50.dp).width(2.dp).height(with(density) { (waveTop + waveH).toDp() } - 50.dp).background(MARK_YOU))
            Text(
                label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.background,
                modifier = Modifier.offset(x = xDp(d.at) + 6.dp, y = 60.dp).background(MARK_YOU, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Barre du bas de l'étape 1 : le repère choisi (écouter autour, retirer), sinon la légende et l'aide. */
@Composable
private fun IntentsBar(e: MusicEditorUi, actions: UiActions) {
    val intents = e.intents
    val id = MusicEditorController.markId(e.selected)
    val mark = intents?.marks?.firstOrNull { it.id == id }
    val showcase = intents?.showcase?.takeIf { e.selected == MusicEditorController.SHOWCASE_KEY }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = Palette.surface, border = BorderStroke(1.dp, if (mark != null || showcase != null) Palette.accent else Palette.outline)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                mark != null -> {
                    val number = intents.killMarks.indexOfFirst { it.id == mark.id }.takeIf { it >= 0 }?.let { "${it + 1} · " }.orEmpty()
                    Text(number + tr("ui.musicEditor.markSelected", "kind" to tr(kindKey(mark.kind)), "time" to DropTime.format(mark.at)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    OutlinedButton(onClick = { actions.listenEditorAround(mark.at.inWholeMicroseconds / 1e6) }) { Text(tr("ui.musicEditor.listen")) }
                    OutlinedButton(onClick = { actions.removeEditorKill(MusicEditorController.markKey(mark.id)) }) { Text(tr("ui.musicEditor.remove"), color = Palette.danger) }
                }
                showcase != null -> {
                    Text(tr("ui.musicEditor.showcaseSelected", "from" to DropTime.format(showcase.start), "to" to DropTime.format(showcase.end)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    OutlinedButton(onClick = { actions.listenEditorAround(showcase.start.inWholeMicroseconds / 1e6) }) { Text(tr("ui.musicEditor.listen")) }
                    OutlinedButton(onClick = { actions.removeEditorKill(MusicEditorController.SHOWCASE_KEY) }) { Text(tr("ui.musicEditor.remove"), color = Palette.danger) }
                }
                else -> {
                    Legend(MARK_AUTO, false, tr("ui.musicEditor.legendAuto"))
                    Legend(MARK_YOU, true, tr("ui.musicEditor.legendYou"))
                    Text(tr("ui.musicEditor.intentsHint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, filled: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(14.dp)) { diamond(Offset(size.width / 2, size.height / 2), size.width / 2.2f, color, filled) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Panneau de l'étape 1 : ce qu'on prépare, l'avertissement des repères en trop, ce que fera l'étape 2. */
@Composable
private fun IntentPanel(e: MusicEditorUi, actions: UiActions) {
    val intents = e.intents ?: MusicIntents(emptyList())
    val kills = intents.killMarks
    val extras = e.extraMarks()
    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelCard(tr("ui.musicEditor.prepTitle")) {
            @Composable
            fun row(label: String, value: String, color: Color) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = color)
                Text(value, style = MaterialTheme.typography.bodySmall, color = Palette.text)
            }
            fun count(list: List<IntentMark>): String {
                if (list.isEmpty()) return tr("ui.musicEditor.none")
                val yours = list.count { it.byHand }
                return if (yours == 0) "${list.size}" else tr("ui.musicEditor.prepYours", "count" to list.size, "yours" to yours)
            }
            row(tr("ui.musicEditor.tool.kill"), count(kills.filter { it.kind == IntentKind.KILL }), MARK_YOU)
            row(tr("ui.musicEditor.tool.multi"), count(kills.filter { it.kind == IntentKind.MULTI }), MARK_YOU)
            row(tr("ui.musicEditor.tool.cut"), count(intents.of(IntentKind.CUT)), CUT_COLOR)
            row(tr("ui.musicEditor.tool.slow"), count(intents.of(IntentKind.SLOW)), Palette.cyan)
            row(tr("ui.musicEditor.tool.transition"), count(intents.of(IntentKind.TRANSITION)), Color(0xFFFFD166))
            row(tr("ui.musicEditor.tool.showcase"), intents.showcase?.let { "${DropTime.format(it.start)} → ${DropTime.format(it.end)}" } ?: tr("ui.musicEditor.none"), SHOWCASE_COLOR)
            e.dropAt?.let { row(tr("ui.musicEditor.drop", "time" to ""), DropTime.format(it), Palette.accent) }
        }
        if (extras.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(10.dp), color = WARN_COLOR.copy(alpha = 0.10f), border = BorderStroke(1.dp, WARN_COLOR)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("ui.musicEditor.extrasTitle", "marks" to kills.size, "kills" to e.groups.size), style = MaterialTheme.typography.titleSmall, color = WARN_COLOR)
                    Text(
                        tr(if (intents.extras == ExtraMarks.SHOWCASE) "ui.musicEditor.extrasShowcase" else "ui.musicEditor.extrasEmpty", "count" to extras.size),
                        style = MaterialTheme.typography.bodySmall, color = Palette.text,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((mode, key) in listOf(ExtraMarks.SHOWCASE to "ui.musicEditor.extras.showcase", ExtraMarks.EMPTY to "ui.musicEditor.extras.empty")) {
                            val on = intents.extras == mode
                            Text(
                                tr(key), style = MaterialTheme.typography.labelMedium, color = if (on) WARN_COLOR else Palette.textMuted,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) WARN_COLOR.copy(alpha = 0.2f) else Color.Transparent)
                                    .border(BorderStroke(1.dp, if (on) WARN_COLOR else Palette.outline), RoundedCornerShape(14.dp))
                                    .clickable { actions.setEditorExtras(mode.name) }.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        PanelCard(tr("ui.musicEditor.step2Title")) {
            listOf("ui.musicEditor.step2.choose", "ui.musicEditor.step2.multi", "ui.musicEditor.step2.build", "ui.musicEditor.step2.edit")
                .forEach { Text("· " + tr(it), style = MaterialTheme.typography.bodySmall, color = Palette.text) }
        }
    }
}

@Composable
private fun PanelCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
