package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.tr
import dev.highlights.montage.DropTime
import dev.highlights.montage.MusicPcm
import dev.highlights.pipeline.HighlightPipeline
import dev.highlights.pipeline.MusicDropTool
import dev.highlights.pipeline.TasteState
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
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Outil de la drop d'un morceau, ouvert d'un clic sur la marque « DROP » de la frise : la forme d'onde du morceau
 * entier ([overview]) et celle des [DETAIL] autour du curseur ([detail], temps détectés en repères), le son qu'on
 * écoute, le curseur qu'on déplace (clic, ±10 ms, ±1 temps, saisie m:ss.mmm, « Poser ici » pendant l'écoute), puis
 * « La drop est ici » : l'instant devient la drop du morceau pour tous les montages suivants
 * ([HighlightPipeline.setMusicDrop], [dev.highlights.montage.MusicDrops]).
 * [detected] : drop détectée ; [byHand] : drop déjà posée ; [playhead] : instant joué pendant l'écoute.
 */
data class MusicDropUi(
    val music: Path,
    val cursor: Duration,
    val loading: Boolean = true,
    val error: String? = null,
    val duration: Duration = Duration.ZERO,
    val overview: List<Float> = emptyList(),
    val detail: List<Float> = emptyList(),
    val beats: List<Duration> = emptyList(),
    val detected: Duration? = null,
    val byHand: Duration? = null,
    val text: String = DropTime.format(cursor),
    val textError: Boolean = false,
    val playing: Boolean = false,
    val playhead: Duration? = null,
) {
    val name: String get() = music.name

    /** Début de la vue détaillée : [DETAIL] centrées sur le curseur, bornées au morceau. */
    val detailStart: Duration
        get() = (cursor - DETAIL / 2).coerceAtMost(duration - DETAIL).coerceAtLeast(Duration.ZERO)

    /** Écart du curseur à la drop détectée, en ms signées (« +952 »). */
    val offsetFromDetected: String? get() = detected?.let { KillTimingUi.signedMs(cursor - it) }

    companion object {
        /** Largeur de la vue détaillée : deux mesures de chaque côté à 120 BPM, assez pour voir la montée et la drop. */
        val DETAIL = 6.seconds

        /** Écoute « depuis 2 s avant » : départ avant le curseur, et arrêt après lui. */
        val BEFORE = 2.seconds
        val AFTER = 3.seconds

        /** Pas fin du curseur. */
        val FINE = 10.milliseconds

        const val OVERVIEW_BUCKETS = 1200
        const val DETAIL_BUCKETS = 900
    }
}

/** Écoute d'un passage du morceau en mémoire, sur la sortie son du lecteur ([AudioSink] : 48 kHz stéréo, 16 bits). */
internal class DropPlayback(private val sinks: () -> AudioSink?) {
    @Volatile private var sink: AudioSink? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var from: Duration = Duration.ZERO

    val playing: Boolean get() = thread?.isAlive == true

    /** Instant joué, d'après ce que la sortie a vraiment joué (pas ce qui lui a été écrit). */
    val position: Duration?
        get() {
            val s = sink ?: return null
            return from + (s.playedFrames * 1_000_000L / s.rate).microseconds
        }

    /** Joue [pcm] de [start] à [end] (null : jusqu'au bout) ; false sans sortie son. */
    fun play(pcm: MusicPcm, start: Duration, end: Duration?): Boolean {
        stop()
        val out = sinks() ?: return false
        sink = out
        from = start
        val first = pcm.indexOf(start)
        val last = end?.let(pcm::indexOf) ?: pcm.samples.size
        thread = Thread({
            val frames = 2048
            val bytes = ByteArray(frames * AudioSink.BYTES_PER_FRAME)
            var i = first
            try {
                while (i < last && !Thread.currentThread().isInterrupted) {
                    val n = minOf(frames, last - i)
                    for (k in 0 until n) {
                        val v = pcm.samples[i + k].toInt()
                        val o = k * 4
                        // Mono sur les deux canaux.
                        bytes[o] = v.toByte(); bytes[o + 1] = (v shr 8).toByte()
                        bytes[o + 2] = v.toByte(); bytes[o + 3] = (v shr 8).toByte()
                    }
                    out.write(bytes, n * AudioSink.BYTES_PER_FRAME)
                    i += n
                }
                if (!Thread.currentThread().isInterrupted) out.drain()
            } catch (_: Exception) {
            } finally {
                out.close()
            }
        }, "ecoute-drop").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        thread?.interrupt()
        sink?.close()
        thread = null
    }
}

/**
 * Logique de l'outil de la drop, déléguée par [AppController] : ouvrir sur la musique du plan, déplacer le curseur,
 * écouter, poser la drop (enregistrée pour ce morceau, puis plan recalculé aussitôt) ou revenir à la détection.
 */
internal class MusicDropController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val pipeline: () -> HighlightPipeline?,
    private val replan: () -> Unit,
    private val sinks: () -> AudioSink? = JavaSoundSink::create,
) {
    private var job: Job? = null
    private var follow: Job? = null
    private var pcm: MusicPcm? = null
    private val playback = DropPlayback(sinks)

    private val drop: MusicDropUi? get() = state.value.montage?.plan?.drop

    private fun set(drop: MusicDropUi?) = state.update { s ->
        s.copy(montage = s.montage?.let { m -> m.plan?.let { p -> m.copy(plan = p.copy(drop = drop)) } ?: m })
    }

    private fun update(change: (MusicDropUi) -> MusicDropUi) = state.update { s ->
        s.copy(montage = s.montage?.let { m -> m.plan?.let { p -> m.copy(plan = p.copy(drop = p.drop?.let(change))) } ?: m })
    }

    /** Ouvre l'outil sur la musique du plan affiché, curseur sur la drop du plan (posée ou détectée). */
    fun open() {
        val view = state.value.montage?.plan?.view ?: return
        val music = view.musicPath ?: return
        val start = view.dropAt?.let { view.musicStart + it } ?: view.musicStart
        stopPlayback()
        set(MusicDropUi(music, start))
        val p = pipeline() ?: return
        job?.cancel()
        job = scope.launch {
            try {
                val tool = withContext(Dispatchers.IO) { p.musicDropTool(music) }
                pcm = tool.pcm
                val cursor = tool.byHand ?: start
                val overview = withContext(Dispatchers.Default) { tool.pcm.peaks(MusicDropUi.OVERVIEW_BUCKETS).toList() }
                update {
                    it.loaded(tool, overview).copy(cursor = cursor, text = DropTime.format(cursor))
                }
                refreshDetail()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.localizedMessage ?: e.toString()) }
            }
        }
    }

    private fun MusicDropUi.loaded(tool: MusicDropTool, overview: List<Float>) = copy(
        loading = false, duration = tool.pcm.duration, overview = overview, beats = tool.detected.beats,
        detected = tool.detectedDrop, byHand = tool.byHand,
    )

    /** Forme d'onde des [MusicDropUi.DETAIL] autour du curseur, relue dans le son en mémoire. */
    private fun refreshDetail() {
        val d = drop ?: return
        val sound = pcm ?: return
        val from = sound.indexOf(d.detailStart)
        val to = sound.indexOf(d.detailStart + MusicDropUi.DETAIL)
        val peaks = MusicPcm(sound.rate, sound.samples.copyOfRange(from, to.coerceAtLeast(from))).peaks(MusicDropUi.DETAIL_BUCKETS).toList()
        update { it.copy(detail = peaks) }
    }

    private fun moveTo(t: Duration) {
        val d = drop ?: return
        val at = t.coerceIn(Duration.ZERO, d.duration.takeIf { it.isPositive() } ?: t)
        update { it.copy(cursor = at, text = DropTime.format(at), textError = false) }
        refreshDetail()
    }

    fun seek(seconds: Double) = moveTo((seconds * 1000).toLong().milliseconds)

    fun step(millis: Int) = drop?.let { moveTo(it.cursor + millis.milliseconds) }

    /** Au temps détecté suivant (ou précédent) du curseur. */
    fun stepBeat(beats: Int) {
        val d = drop ?: return
        if (d.beats.isEmpty() || beats == 0) return
        val target = if (beats > 0) d.beats.firstOrNull { it > d.cursor + 1.milliseconds } else d.beats.lastOrNull { it < d.cursor - 1.milliseconds }
        target?.let(::moveTo)
    }

    /** Saisie de l'instant : le curseur suit dès que le texte est un instant valable. */
    fun text(value: String) {
        val parsed = DropTime.parse(value)
        update { it.copy(text = value, textError = parsed == null) }
        if (parsed != null) {
            update { it.copy(cursor = parsed) }
            refreshDetail()
        }
    }

    /** Écoute : [fromBefore] depuis [MusicDropUi.BEFORE] avant le curseur jusqu'à [MusicDropUi.AFTER] après ; sinon depuis le curseur. Rejouer arrête. */
    fun play(fromBefore: Boolean) {
        val d = drop ?: return
        val sound = pcm ?: return
        if (playback.playing && !fromBefore) return stopPlayback()
        val start = if (fromBefore) (d.cursor - MusicDropUi.BEFORE).coerceAtLeast(Duration.ZERO) else d.cursor
        val end = if (fromBefore) d.cursor + MusicDropUi.AFTER else null
        if (!playback.play(sound, start, end)) return
        update { it.copy(playing = true, playhead = start) }
        follow?.cancel()
        follow = scope.launch {
            while (isActive && playback.playing) {
                update { it.copy(playhead = playback.position) }
                delay(40)
            }
            update { it.copy(playing = false) }
        }
    }

    /** Pendant l'écoute : le curseur à l'instant entendu. */
    fun markHere() {
        val at = playback.position ?: drop?.playhead ?: return
        moveTo(at)
    }

    private fun stopPlayback() {
        playback.stop()
        follow?.cancel()
        update { it.copy(playing = false) }
    }

    fun stop() = stopPlayback()

    /** « La drop est ici » : retenue pour ce morceau, puis le plan recalculé dessus. */
    fun confirm() {
        val d = drop ?: return
        val p = pipeline() ?: return
        close()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { p.setMusicDrop(d.music, d.cursor) } }
            replan()
        }
    }

    /** Revenir à la drop détectée (la drop posée est oubliée). */
    fun clear() {
        val d = drop ?: return
        val p = pipeline() ?: return
        close()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { p.setMusicDrop(d.music, null) } }
            replan()
        }
    }

    fun close() {
        stopPlayback()
        job?.cancel()
        pcm = null
        set(null)
    }
}

/**
 * Panneau de l'outil de la drop, dans la fenêtre du plan. Clavier : Espace écoute (ou arrête), R rejoue depuis 2 s
 * avant, ← → ±10 ms, Maj+← → ±1 temps, M pose le curseur à l'instant entendu, Entrée « La drop est ici », Échap ferme.
 */
@Composable
fun MusicDropPanel(d: MusicDropUi, actions: UiActions, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(d.music) { runCatching { focus.requestFocus() } }
    val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { if (e.isShiftPressed) actions.stepMusicDropBeat(-1) else actions.stepMusicDrop(-10); true }
            Key.DirectionRight -> { if (e.isShiftPressed) actions.stepMusicDropBeat(1) else actions.stepMusicDrop(10); true }
            Key.Spacebar -> { actions.playMusicDrop(false); true }
            Key.R -> { actions.playMusicDrop(true); true }
            Key.M -> { actions.markMusicDropHere(); true }
            Key.Enter -> { actions.confirmMusicDrop(); true }
            Key.Escape -> { actions.closeMusicDrop(); true }
            else -> false
        }
    }
    Surface(modifier.fillMaxWidth().then(keys), shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.accent)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("ui.musicDrop.title", "music" to d.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(tr("ui.musicDrop.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                if (d.byHand != null) TextButton(onClick = actions::clearMusicDrop) { Text(tr("ui.musicDrop.clear")) }
                Button(onClick = actions::confirmMusicDrop, enabled = !d.loading && !d.textError) { Text(tr("ui.musicDrop.confirm")) }
                OutlinedButton(onClick = actions::closeMusicDrop) { Text(tr("ui.common.close")) }
            }
            d.error?.let { Text(tr("ui.musicDrop.failed", "reason" to it), color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
            if (d.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr("ui.musicDrop.loading"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                return@Column
            }
            // Morceau entier : un clic y place le curseur.
            Waveform(
                peaks = d.overview, from = Duration.ZERO, length = d.duration, beats = emptyList(), d = d,
                onSeek = actions::seekMusicDrop, modifier = Modifier.fillMaxWidth().height(56.dp),
            )
            // Autour du curseur : les temps détectés en repères, un clic ou un glisser déplace le curseur.
            Waveform(
                peaks = d.detail, from = d.detailStart, length = MusicDropUi.DETAIL, beats = d.beats, d = d,
                onSeek = actions::seekMusicDrop, modifier = Modifier.fillMaxWidth().height(130.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = d.text, onValueChange = actions::setMusicDropText, singleLine = true, isError = d.textError,
                    label = { Text(tr("ui.musicDrop.time")) }, modifier = Modifier.width(150.dp),
                )
                OutlinedButton(onClick = { actions.stepMusicDropBeat(-1) }) { Text(tr("ui.musicDrop.beatBack")) }
                OutlinedButton(onClick = { actions.stepMusicDrop(-10) }) { Text(tr("ui.musicDrop.fineBack")) }
                OutlinedButton(onClick = { actions.stepMusicDrop(10) }) { Text(tr("ui.musicDrop.fineForward")) }
                OutlinedButton(onClick = { actions.stepMusicDropBeat(1) }) { Text(tr("ui.musicDrop.beatForward")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { actions.playMusicDrop(false) }) { Text(tr(if (d.playing) "ui.musicDrop.stop" else "ui.musicDrop.play")) }
                OutlinedButton(onClick = { actions.playMusicDrop(true) }) { Text(tr("ui.musicDrop.replay")) }
                OutlinedButton(onClick = actions::markMusicDropHere, enabled = d.playing) { Text(tr("ui.musicDrop.mark")) }
                Column(Modifier.padding(start = 8.dp)) {
                    d.detected?.let {
                        Text(
                            tr("ui.musicDrop.detected", "time" to DropTime.format(it), "ms" to (d.offsetFromDetected ?: "0")),
                            style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
                        )
                    }
                    d.byHand?.let { Text(tr("ui.musicDrop.byHand", "time" to DropTime.format(it)), style = MaterialTheme.typography.bodySmall, color = Palette.accent) }
                    d.playhead?.takeIf { d.playing }?.let { Text(tr("ui.musicDrop.playing", "time" to DropTime.format(it)), style = MaterialTheme.typography.bodySmall, color = Palette.cyan) }
                }
            }
        }
    }
}

/**
 * Forme d'onde de [from] sur [length] : crêtes symétriques, temps détectés en traits fins, drop détectée (gris),
 * drop posée (accent), curseur (blanc), instant entendu (cyan). Clic ou glisser : [onSeek] (secondes du morceau).
 */
@Composable
private fun Waveform(
    peaks: List<Float>,
    from: Duration,
    length: Duration,
    beats: List<Duration>,
    d: MusicDropUi,
    onSeek: (Double) -> Unit,
    modifier: Modifier,
) {
    val lengthS = length.inWholeMicroseconds / 1e6
    val fromS = from.inWholeMicroseconds / 1e6
    fun seconds(x: Float, width: Int) = fromS + (x / width.coerceAtLeast(1)) * lengthS
    Canvas(
        modifier
            .pointerInput(from, length) { detectTapGestures { onSeek(seconds(it.x, size.width)) } }
            .pointerInput(from, length) { detectDragGestures { change, _ -> onSeek(seconds(change.position.x, size.width)) } },
    ) {
        drawRect(Palette.surfaceHigh, size = size)
        if (lengthS <= 0) return@Canvas
        fun x(t: Duration) = ((t.inWholeMicroseconds / 1e6 - fromS) / lengthS * size.width).toFloat()
        val mid = size.height / 2
        beats.forEach { b ->
            val bx = x(b)
            if (bx in 0f..size.width) drawLine(Palette.outline, Offset(bx, 0f), Offset(bx, size.height), 1f)
        }
        if (peaks.isNotEmpty()) {
            val w = size.width / peaks.size
            peaks.forEachIndexed { i, p ->
                val h = (p * (size.height - 4)).coerceAtLeast(1f)
                drawRect(Palette.cyan.copy(alpha = 0.8f), Offset(i * w, mid - h / 2), Size(w.coerceAtLeast(1f), h))
            }
        }
        mark(d.detected, ::x, Palette.textMuted, 2f)
        mark(d.byHand, ::x, Palette.accent, 3f)
        mark(d.playhead?.takeIf { d.playing }, ::x, Palette.cyan, 2f)
        mark(d.cursor, ::x, Color.White, 2f)
    }
}

private fun DrawScope.mark(t: Duration?, x: (Duration) -> Float, color: Color, width: Float) {
    val at = t?.let(x) ?: return
    if (at in 0f..size.width) drawLine(color, Offset(at, 0f), Offset(at, size.height), width)
}

/** Drops posées à la main, dans la vue des goûts : une ligne par morceau, chacune oubliable. */
@Composable
fun DropsTasteCard(state: TasteState, actions: UiActions) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ui.taste.drops.title"), style = MaterialTheme.typography.titleMedium)
            Text(tr("ui.taste.drops.intro"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            if (state.drops.isEmpty()) Text(tr("ui.taste.drops.none"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            state.drops.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(d.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(DropTime.format(d.at), style = MaterialTheme.typography.bodyMedium, color = Palette.accent, modifier = Modifier.width(90.dp))
                    TextButton(onClick = { actions.forgetMusicDrop(d.key) }) { Text(tr("ui.taste.drops.forget")) }
                }
            }
            val learned = state.learnedDrop
            if (learned.songs > 0) {
                val muted = MaterialTheme.typography.bodySmall
                Text(
                    tr("ui.taste.drops.learned", "count" to learned.songs, "ms" to KillTimingUi.signedMs((learned.meanOffsetMs ?: 0L).milliseconds), "beats" to (learned.beatOffset ?: 0)),
                    style = muted, color = Palette.textMuted,
                )
                Text(
                    if (learned.biasActive) tr("ui.taste.drops.biasActive", "beats" to (learned.beatOffset ?: 0), "ms" to KillTimingUi.signedMs(learned.residual ?: Duration.ZERO))
                    else tr("ui.taste.drops.biasPending", "min" to dev.highlights.montage.DropLearner.MIN_SONGS),
                    style = muted, color = if (learned.biasActive) Palette.accent else Palette.textMuted,
                )
                val total = learned.songs.toString()
                Text(
                    tr(
                        if (learned.modelActive) "ui.taste.drops.modelActive" else "ui.taste.drops.modelPending",
                        "model" to (learned.modelHits?.toString() ?: "-"), "rule" to (learned.ruleHits?.toString() ?: "-"), "total" to total,
                        "min" to dev.highlights.montage.DropLearner.MIN_MODEL_SONGS,
                    ),
                    style = muted, color = if (learned.modelActive) Palette.accent else Palette.textMuted,
                )
                OutlinedButton(onClick = actions::resetDropLearning) { Text(tr("ui.taste.drops.resetLearning")) }
            }
        }
    }
}
