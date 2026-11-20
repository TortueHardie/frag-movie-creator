package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.MediaInfo
import dev.highlights.montage.KillFrame
import dev.highlights.montage.KillRef
import dev.highlights.montage.KillTimingLearner
import dev.highlights.montage.LearnedOffset
import dev.highlights.pipeline.HighlightPipeline
import dev.highlights.pipeline.TasteState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.isShiftPressed
import dev.highlights.montage.DropTime
import dev.highlights.ui.player.VideoPlayer
import dev.highlights.ui.player.VideoSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Calage d'un kill à l'image, ouvert d'un clic sur son trait dans la frise : toutes les images de la capture autour du
 * kill ([frames], une demi-seconde de chaque côté de l'instant [ref] détecté), celle choisie ([selected]) devient
 * l'instant qui tombe sur le temps ([dev.highlights.montage.MontageConstraints.anchors]). [beatShift] : le kill posé
 * après (+) ou avant (−) son temps, par pas d'une image, quand c'est le temps de la musique qui est décalé.
 * [current] : instant du kill dans le plan affiché (calé, appris ou détecté) ; [learned] : déplacé par un décalage
 * appris ; [songShift] : décalage appris pour la musique, appliqué au plan tant qu'on n'en pose pas un à la main.
 */
data class KillTimingUi(
    val ref: KillRef,
    val media: MediaInfo,
    val current: Duration,
    val learned: Boolean = false,
    val frames: List<KillFrame> = emptyList(),
    val loading: Boolean = true,
    val selected: Int = -1,
    val beatShift: Duration = Duration.ZERO,
    val songShift: Duration = Duration.ZERO,
    val error: String? = null,
    /**
     * Instant du kill posé à l'écoute (« Kill ici ») : curseur de la forme d'onde, déplacé par image, par 10 ms, à la
     * saisie ou à l'instant entendu ; null : l'image choisie ([selected]) fait foi.
     */
    val cursor: Duration? = null,
    /** Forme d'onde du son de la capture de [waveStart] sur [waveLength] (vide : pas de son, ou pas encore lu). */
    val wave: List<Float> = emptyList(),
    val waveStart: Duration = Duration.ZERO,
    val waveLength: Duration = Duration.ZERO,
    val text: String = "",
    val textError: Boolean = false,
    /** Lecture en cours dans le lecteur du panneau (image et son), et l'instant de l'image montrée. */
    val playing: Boolean = false,
    val playhead: Duration? = null,
    /** Début d'une section posé au curseur (« Début de section ici »), en attente de sa fin ; null : aucune. */
    val sectionStart: Duration? = null,
) {
    val detected: Duration get() = ref.at

    /** Instant choisi : le curseur, sinon l'image choisie ; null tant que rien n'est lu. */
    val chosen: Duration? get() = cursor ?: frames.getOrNull(selected)?.at

    /** « Kill ici » possible au curseur : à [allowed] de la détection (les autres repères vont n'importe où). */
    val killHereAllowed: Boolean get() = chosen?.let { it in allowed } == true

    /** Le calage permis autour de la détection ([dev.highlights.montage.MontageEdits.MAX_ANCHOR] de chaque côté). */
    val allowed: ClosedRange<Duration>
        get() = (detected - dev.highlights.montage.MontageEdits.MAX_ANCHOR)..(detected + dev.highlights.montage.MontageEdits.MAX_ANCHOR)

    /** Une image de la capture : le pas du décalage du temps. */
    val frameStep: Duration get() = (1.0 / (media.video?.fps?.takeIf { it > 0 } ?: 60.0)).seconds

    /** Image la plus proche de [t]. */
    fun nearest(t: Duration): Int = frames.indices.minByOrNull { (frames[it].at - t).absoluteValue } ?: -1

    /** Image de la détection (repère dans la bande). */
    val detectedIndex: Int get() = nearest(detected)

    companion object {
        /** Écoute « depuis 1 s avant » : départ avant le curseur, arrêt après lui. */
        val BEFORE: Duration = 1.seconds
        val AFTER: Duration = 1.seconds

        const val WAVE_BUCKETS = 800

        /** « +33 », « −17 », « 0 » : écart en millisecondes, signe toujours écrit. */
        fun signedMs(d: Duration): String {
            val ms = d.inWholeMilliseconds
            return when {
                ms > 0 -> "+$ms"
                ms < 0 -> "−${-ms}"
                else -> "0"
            }
        }
    }
}

/**
 * Logique du calage, déléguée par [AppController] : ouvrir sur un kill de la frise, parcourir ses images, appliquer
 * (contrainte posée puis plan recalculé aussitôt, comme une épingle), revenir à la détection, écarter le kill.
 */
internal class KillTimingController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val pipeline: () -> HighlightPipeline?,
    private val updateMontage: ((MontageUiState) -> MontageUiState) -> Unit,
    private val replan: () -> Unit,
    /** Lecteur du panneau (image et son de la capture) ; null : pas d'écoute (tests, FFmpeg absent). */
    private val players: () -> VideoPlayer? = { null },
) {
    private var job: Job? = null
    private var follow: Job? = null
    private var engine: VideoPlayer? = null

    /** Lecteur en service, dont le panneau montre les images pendant l'écoute. */
    val player: VideoPlayer? get() = engine

    private fun update(change: (KillTimingUi) -> KillTimingUi) = state.update { s ->
        s.copy(montage = s.montage?.let { m -> m.plan?.let { p -> m.copy(plan = p.copy(timing = p.timing?.let(change))) } ?: m })
    }

    private fun set(timing: KillTimingUi?) = state.update { s ->
        s.copy(montage = s.montage?.let { m -> m.plan?.let { p -> m.copy(plan = p.copy(timing = timing)) } ?: m })
    }

    private val timing: KillTimingUi? get() = state.value.montage?.plan?.timing

    /** [kill] : désignation « capture@instant » d'un kill du plan (son instant détecté). */
    fun open(kill: String) {
        val montage = state.value.montage ?: return
        val view = montage.plan?.view ?: return
        val ref = runCatching { KillRef.parse(kill) }.getOrNull() ?: return
        val (clip, shown) = view.clips.firstNotNullOfOrNull { c -> c.kills.firstOrNull { it.ref.toString() == ref.toString() }?.let { c to it } } ?: return
        val media = clip.group?.media ?: return
        val constraints = montage.constraints
        val anchored = constraints.anchors.entries.firstOrNull { it.key.toString() == ref.toString() }?.value
        val shift = constraints.beatShifts.entries.firstOrNull { (r, _) -> clip.kills.any { it.ref.toString() == r.toString() } }?.value
        val current = anchored ?: shown.source
        set(
            KillTimingUi(
                ref = shown.ref, media = media, current = current, learned = anchored == null && shown.retimed && !shown.byHand,
                beatShift = shift ?: Duration.ZERO, songShift = if (shift == null) clip.beatShift else Duration.ZERO,
            ),
        )
        update { it.copy(cursor = current, text = KillRef.precise(current)) }
        val p = pipeline() ?: return
        job?.cancel()
        job = scope.launch {
            try {
                val frames = withContext(Dispatchers.IO) { p.killFrames(media, shown.ref.at) }
                update { t -> if (t.ref != shown.ref) t else t.copy(frames = frames, loading = false, selected = t.copy(frames = frames).nearest(t.cursor ?: t.current)) }
                // Le son ensuite : la bande d'images sert déjà pendant qu'il se lit.
                val audio = runCatching { withContext(Dispatchers.IO) { p.killAudio(media, shown.ref.at) } }.getOrNull()
                if (audio != null) {
                    val peaks = withContext(Dispatchers.Default) { audio.pcm.peaks(KillTimingUi.WAVE_BUCKETS).toList() }
                    update { t -> if (t.ref != shown.ref) t else t.copy(wave = peaks, waveStart = audio.start, waveLength = audio.pcm.duration) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.localizedMessage ?: e.toString()) }
            }
        }
    }

    /** Curseur à [t], borné au calage permis ; l'image la plus proche devient l'image montrée. */
    private fun moveTo(t: Duration) = update { k ->
        val at = t.coerceIn(k.media.bounds.start, k.media.duration)
        k.copy(cursor = at, text = KillRef.precise(at), textError = false, selected = if (k.frames.isEmpty()) k.selected else k.nearest(at))
    }

    /** D'une image à l'autre (instants exacts de la capture) ; au-delà de la bande, d'un pas d'image. */
    fun step(delta: Int) {
        val t = timing ?: return
        val from = t.chosen ?: t.current
        val index = if (t.frames.isEmpty()) -1 else t.nearest(from) + delta
        moveTo(if (index in t.frames.indices && (t.frames[t.nearest(from)].at - from).absoluteValue < 1.milliseconds) t.frames[index].at else from + t.frameStep * delta)
    }

    fun pick(index: Int) {
        val t = timing ?: return
        if (index in t.frames.indices) moveTo(t.frames[index].at)
    }

    fun stepMs(millis: Int) {
        val t = timing ?: return
        moveTo((t.chosen ?: t.current) + millis.milliseconds)
    }

    fun seek(seconds: Double) = moveTo((seconds * 1000).toLong().milliseconds)

    /** Saisie de l'instant (m:ss.mmm, ou h:mm:ss.mmm) : le curseur suit dès que le texte est un instant valable. */
    fun text(value: String) {
        val parsed = DropTime.parse(value) ?: runCatching { KillRef.parse("x@$value").at }.getOrNull()
        update { it.copy(text = value, textError = parsed == null) }
        if (parsed != null) update { k -> k.copy(cursor = parsed.coerceIn(k.media.bounds.start, k.media.duration), selected = if (k.frames.isEmpty()) k.selected else k.nearest(parsed)) }
    }

    /**
     * Écoute dans le lecteur du panneau, image et son du jeu : [fromBefore] depuis [KillTimingUi.BEFORE] avant le
     * curseur jusqu'à [KillTimingUi.AFTER] après ; sinon depuis le curseur (rejouer arrête).
     */
    fun play(fromBefore: Boolean) {
        val t = timing ?: return
        if (t.playing && !fromBefore) return stopPlayback()
        val e = engine ?: players()?.also { engine = it } ?: return
        val cursor = t.chosen ?: t.current
        val start = if (fromBefore) (cursor - KillTimingUi.BEFORE).coerceAtLeast(Duration.ZERO) else cursor
        val end = if (fromBefore) cursor + KillTimingUi.AFTER else null
        e.open(t.media.path, start, play = true)
        update { it.copy(playing = true, playhead = start) }
        follow?.cancel()
        follow = scope.launch {
            // L'ouverture passe par la file du lecteur : attendre qu'elle ait commencé (vu : sans cette attente, la vue
            // encore vide disait « ni lecture ni chargement » et l'écoute s'arrêtait avant de partir).
            var waited = 0
            while (isActive && !e.view.value.playing && waited < 5000) { delay(40); waited += 40 }
            while (isActive) {
                val at = e.frame.value?.pts
                update { it.copy(playhead = at ?: it.playhead) }
                val view = e.view.value
                if ((end != null && at != null && at >= end) || (!view.playing && !view.loading)) break
                delay(40)
            }
            e.pause()
            update { it.copy(playing = false) }
        }
    }

    /** Pendant l'écoute : le curseur sur l'image montrée à cet instant. */
    fun markHere() {
        val at = engine?.frame?.value?.pts ?: timing?.playhead ?: return
        moveTo(at)
    }

    private fun stopPlayback() {
        follow?.cancel()
        engine?.pause()
        update { it.copy(playing = false) }
    }

    /** Décalage du temps de [frames] images, borné à ce que le planificateur accepte. */
    fun shiftBeat(frames: Int) = update { t ->
        val next = t.beatShift + t.frameStep * frames
        val max = dev.highlights.montage.MontageEdits.MAX_BEAT_SHIFT
        t.copy(beatShift = next.coerceIn(-max, max))
    }

    /**
     * « Le plan commence ici » ([start]) ou « finit ici » au curseur ([dev.highlights.montage.MontageConstraints.cut]),
     * puis le plan recalculé ; le panneau reste ouvert pour poser d'autres repères.
     */
    fun markCut(start: Boolean) {
        val t = timing ?: return
        val at = t.chosen ?: return
        updateMontage { m -> m.copy(constraints = if (start) m.constraints.cut(t.ref, start = at) else m.constraints.cut(t.ref, end = at)) }
        replan()
    }

    /** « Début de section ici » : la fin se pose ensuite, avec la sorte de la section. */
    fun markSectionStart() = update { it.copy(sectionStart = it.chosen ?: it.current) }

    /** Fin de la section au curseur : sans kill ([keep] faux) ou à garder entière, dans la capture du kill ; plan recalculé. */
    fun markSectionEnd(keep: Boolean) {
        val t = timing ?: return
        val from = t.sectionStart ?: return
        val to = t.chosen ?: return
        if (from == to) return
        val range = dev.highlights.core.model.TimeRange(minOf(from, to), maxOf(from, to))
        val kind = if (keep) dev.highlights.montage.RangeKind.KEEP else dev.highlights.montage.RangeKind.SKIP
        updateMontage { m -> m.copy(constraints = m.constraints.addRange(dev.highlights.montage.CaptureRange(t.ref.capture, range, kind))) }
        update { it.copy(sectionStart = null) }
        replan()
    }

    /** Pose le calage choisi et replanifie (comme une épingle posée à la souris). */
    fun apply() {
        val t = timing ?: return
        if (!t.killHereAllowed) return
        val chosen = t.chosen ?: return
        updateMontage { m -> m.copy(constraints = m.constraints.anchor(t.ref, chosen).shiftBeat(t.ref, t.beatShift)) }
        close()
        replan()
    }

    /** Retire le calage de ce kill : la détection (ou ce qui a été appris) reprend la main. */
    fun clear() {
        val t = timing ?: return
        updateMontage { m -> m.copy(constraints = m.constraints.clearTiming(t.ref)) }
        close()
        replan()
    }

    /** L'ancien clic sur un trait de kill : l'écarter du montage. */
    fun exclude() {
        val t = timing ?: return
        updateMontage { m -> m.copy(constraints = m.constraints.exclude(t.ref)) }
        close()
        replan()
    }

    fun close() {
        job?.cancel()
        follow?.cancel()
        engine?.dispose()
        engine = null
        set(null)
    }
}

/**
 * Panneau du calage, sous la frise : la bande des images (la détection marquée), l'image choisie entière et en grand,
 * les flèches pour avancer d'une image, le décalage du temps, puis appliquer (le plan est recalculé aussitôt), revenir
 * à la détection ou écarter le kill. Pas de zoom sur le réticule : l'utilisateur préfère l'image entière plus grande
 * (20/11), le kill se juge à l'ennemi et à l'éclair du tir autant qu'au centre.
 */
@Composable
fun KillTimingPanel(t: KillTimingUi, actions: UiActions, modifier: Modifier = Modifier, constraints: dev.highlights.montage.MontageConstraints = dev.highlights.montage.MontageConstraints.NONE) {
    // Le clavier va au panneau, qui prend le focus à l'ouverture : sans élément focalisé, la fenêtre ne voyait pas les
    // flèches (vérifié en pilotant l'application). Comme l'outil de la drop : ← → une image (Maj : 10 ms), Espace
    // écouter, R rejouer depuis 1 s avant, M marquer l'instant vu et entendu, Entrée « Kill ici », Échap fermer.
    val focus = remember { FocusRequester() }
    LaunchedEffect(t.ref) { runCatching { focus.requestFocus() } }
    val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { if (e.isShiftPressed) actions.stepKillMs(-10) else actions.stepKillFrame(-1); true }
            Key.DirectionRight -> { if (e.isShiftPressed) actions.stepKillMs(10) else actions.stepKillFrame(1); true }
            Key.Spacebar -> { actions.playKillTiming(false); true }
            Key.R -> { actions.playKillTiming(true); true }
            Key.M -> { actions.markKillHere(); true }
            Key.Enter -> { actions.applyKillTiming(); true }
            Key.Escape -> { actions.closeKillTiming(); true }
            else -> false
        }
    }
    Surface(modifier.fillMaxWidth().then(keys), shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.accent)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("ui.killTiming.title", "kill" to t.ref.toString()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(tr("ui.killTiming.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                TextButton(onClick = actions::excludeTimedKill) { Text(tr("ui.killTiming.exclude"), color = Palette.danger) }
                TextButton(onClick = actions::clearKillTiming) { Text(tr("ui.killTiming.clear")) }
                Button(onClick = actions::applyKillTiming, enabled = t.killHereAllowed) { Text(tr("ui.killTiming.apply")) }
                OutlinedButton(onClick = actions::closeKillTiming) { Text(tr("ui.common.close")) }
            }
            t.error?.let { Text(tr("ui.killTiming.failed", "reason" to it), color = Palette.danger, style = MaterialTheme.typography.bodySmall) }
            if (t.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(tr("ui.killTiming.loading"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                return@Column
            }
            if (t.frames.isEmpty()) return@Column
            val frame = t.frames[t.selected.coerceIn(0, t.frames.lastIndex)]
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                // L'image choisie, entière : 640 dp de large, une capture 21:9 y tient sur 274 dp de haut.
                // Pendant l'écoute, le lecteur du panneau (image et son du jeu) ; sinon l'image du curseur.
                val image = rememberImage(frame.file)
                val engine = actions.killTimingPlayer
                Box(Modifier.height(300.dp).width(640.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh)) {
                    if (t.playing && engine != null) VideoSurface(engine, Modifier.fillMaxSize())
                    else image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr("ui.killTiming.frame", "n" to t.selected + 1, "total" to t.frames.size, "time" to KillRef.precise(frame.at)),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    )
                    val chosen = t.chosen ?: frame.at
                    Text(
                        tr("ui.killTiming.offset", "ms" to KillTimingUi.signedMs(chosen - t.detected)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if ((chosen - t.detected).absoluteValue < 1.milliseconds) Palette.textMuted else Palette.accent,
                    )
                    if (t.learned) {
                        Text(tr("ui.killTiming.learned", "ms" to KillTimingUi.signedMs(t.current - t.detected)), style = MaterialTheme.typography.bodySmall, color = Palette.cyan)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { actions.stepKillFrame(-1) }, enabled = t.selected > 0) { Text(tr("ui.killTiming.prev")) }
                        OutlinedButton(onClick = { actions.stepKillFrame(1) }, enabled = t.selected < t.frames.lastIndex) { Text(tr("ui.killTiming.next")) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tr("ui.killTiming.beat"), style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { actions.shiftKillBeat(-1) }) { Text(tr("ui.killTiming.earlier")) }
                        Text(
                            tr("ui.killTiming.beatShift", "ms" to KillTimingUi.signedMs(t.beatShift)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t.beatShift == Duration.ZERO) Palette.text else Palette.accent,
                            modifier = Modifier.width(190.dp),
                        )
                        OutlinedButton(onClick = { actions.shiftKillBeat(1) }) { Text(tr("ui.killTiming.later")) }
                    }
                    if (t.songShift != Duration.ZERO) {
                        Text(tr("ui.killTiming.songShift", "ms" to KillTimingUi.signedMs(t.songShift)), style = MaterialTheme.typography.bodySmall, color = Palette.cyan)
                    }
                }
            }
            KillListening(t, actions, constraints)
            KillMarkers(t, actions)
            // Toutes les images, celle de la détection soulignée, la choisie encadrée.
            val scroll = rememberScrollState()
            val step = with(LocalDensity.current) { 76.dp.toPx() }
            LaunchedEffect(t.selected, t.frames.size, scroll.viewportSize) {
                // Garde l'image choisie au milieu de la bande : 76 dp par vignette (72 + 4 d'écart) ; relancé quand la
                // largeur de la bande est connue (0 au premier passage : l'image choisie partait au bord gauche).
                val px = (t.selected * step - scroll.viewportSize / 2f + step / 2).roundToInt()
                scroll.animateScrollTo(px.coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
            }
            Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                t.frames.forEachIndexed { i, f ->
                    FilmFrame(f, selected = i == t.selected, detected = i == t.detectedIndex) { actions.pickKillFrame(i) }
                }
            }
        }
    }
}

@Composable
private fun FilmFrame(frame: KillFrame, selected: Boolean, detected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp, 54.dp).clip(shape).background(Palette.surfaceHigh)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Palette.accent else Palette.outline), shape)
                .clickable(onClick = onClick),
        ) {
            // Le centre de l'image (le réticule) : en vignette, l'image entière d'une capture 21:9 ne montre rien.
            rememberImage(frame.file)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.6f, scaleY = 1.6f)) }
        }
        Box(Modifier.width(72.dp).height(3.dp).background(if (detected) Palette.cyan else Palette.background))
    }
}

/**
 * « Kill ici » à l'écoute, comme la drop : le son du jeu ±2 s autour de la détection (images de la bande en repères,
 * détection en gris, hors du calage permis assombri), le curseur, sa saisie et ses pas, l'écoute image et son.
 */
@Composable
private fun KillListening(t: KillTimingUi, actions: UiActions, constraints: dev.highlights.montage.MontageConstraints) {
    if (t.wave.isNotEmpty()) {
        // Repères déjà posés sur cette capture : sections (rouge : sans kill, vert : à garder), bornes du plan (accent).
        val ranges = constraints.ranges.filter { it.capture.equals(t.ref.capture, ignoreCase = true) }
        val cut = constraints.cuts.entries.firstOrNull { it.key.toString() == t.ref.toString() }?.value
        TimeWaveform(
            peaks = t.wave, from = t.waveStart, length = t.waveLength, ticks = t.frames.map { it.at },
            marks = listOf(
                WaveMark(t.detected, Palette.textMuted, 2f), WaveMark(cut?.start, Palette.accent, 3f), WaveMark(cut?.end, Palette.accent, 3f),
                WaveMark(t.sectionStart, Palette.cyan.copy(alpha = 0.7f), 2f),
                WaveMark(t.playhead?.takeIf { t.playing }, Palette.cyan, 2f), WaveMark(t.chosen, Color.White, 2f),
            ),
            onSeek = actions::seekKillTiming, modifier = Modifier.fillMaxWidth().height(110.dp), allowed = t.allowed,
            zones = ranges.map { r -> WaveZone(r.range.start..r.range.end, if (r.kind == dev.highlights.montage.RangeKind.SKIP) Palette.danger.copy(alpha = 0.3f) else Color(0x5543A047)) },
        )
    } else {
        Text(tr("ui.killTiming.noAudio"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = t.text, onValueChange = actions::setKillTimingText, singleLine = true, isError = t.textError,
            label = { Text(tr("ui.killTiming.time"), maxLines = 1) }, modifier = Modifier.width(210.dp),
        )
        OutlinedButton(onClick = { actions.stepKillFrame(-1) }) { Text(tr("ui.killTiming.earlier")) }
        OutlinedButton(onClick = { actions.stepKillMs(-10) }) { Text(tr("ui.musicDrop.fineBack")) }
        OutlinedButton(onClick = { actions.stepKillMs(10) }) { Text(tr("ui.musicDrop.fineForward")) }
        OutlinedButton(onClick = { actions.stepKillFrame(1) }) { Text(tr("ui.killTiming.later")) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { actions.playKillTiming(false) }) { Text(tr(if (t.playing) "ui.musicDrop.stop" else "ui.musicDrop.play")) }
        OutlinedButton(onClick = { actions.playKillTiming(true) }) { Text(tr("ui.killTiming.replay")) }
        OutlinedButton(onClick = actions::markKillHere, enabled = t.playing) { Text(tr("ui.musicDrop.mark")) }
        t.playhead?.takeIf { t.playing }?.let { Text(tr("ui.musicDrop.playing", "time" to KillRef.precise(it)), style = MaterialTheme.typography.bodySmall, color = Palette.cyan) }
    }
}

/**
 * Repères posés au curseur, en plus de « Kill ici » : bornes du plan (« Couper ici ») et sections de la capture (sans
 * kill, à garder entière). Chacun devient une contrainte du plan, visible et effaçable sur la frise.
 */
@Composable
private fun KillMarkers(t: KillTimingUi, actions: UiActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("ui.killTiming.markers"), style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
        OutlinedButton(onClick = { actions.markKillCut(true) }) { Text(tr("ui.killTiming.cutStart")) }
        OutlinedButton(onClick = { actions.markKillCut(false) }) { Text(tr("ui.killTiming.cutEnd")) }
        Spacer(Modifier.width(8.dp))
        if (t.sectionStart == null) {
            OutlinedButton(onClick = actions::markSectionStart) { Text(tr("ui.killTiming.sectionStart")) }
        } else {
            Text(tr("ui.killTiming.sectionPending", "time" to KillRef.precise(t.sectionStart)), style = MaterialTheme.typography.bodySmall, color = Palette.cyan)
            OutlinedButton(onClick = { actions.markSectionEnd(false) }) { Text(tr("ui.killTiming.sectionSkip")) }
            OutlinedButton(onClick = { actions.markSectionEnd(true) }) { Text(tr("ui.killTiming.sectionKeep")) }
        }
    }
    if (t.chosen != null && !t.killHereAllowed) {
        Text(tr("ui.killTiming.killFar"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
    }
}

/** Calages appris, dans la vue des goûts : décalage par source de détection et par morceau, et de quoi les oublier. */
@Composable
fun TimingTasteCard(state: TasteState, actions: UiActions) {
    val timing = state.timing
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ui.taste.timing.title"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("ui.taste.timing.intro", "source" to KillTimingLearner.MIN_SOURCE, "song" to KillTimingLearner.MIN_SONG),
                style = MaterialTheme.typography.bodySmall, color = Palette.textMuted,
            )
            if (timing.isEmpty) Text(tr("ui.taste.timing.none"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            timing.sources.forEach { o -> OffsetRow(tr("ui.taste.timing.source", "profile" to o.key.substringBefore('|'), "source" to o.key.substringAfter('|')), o) }
            timing.songs.forEach { o -> OffsetRow(tr("ui.taste.timing.song", "song" to o.key.substringBefore('|')), o) }
            if (!timing.isEmpty) OutlinedButton(onClick = actions::resetTiming) { Text(tr("ui.taste.timing.reset")) }
            // Longueurs de plan apprises des coupes posées à la main, par profil.
            val lengths = state.lengths
            (lengths.before.keys + lengths.after.keys).forEach { p ->
                val b = lengths.before[p]
                val a = lengths.after[p]
                val active = b?.active == true || a?.active == true
                fun sec(l: dev.highlights.montage.LearnedLength?) = l?.let { dev.highlights.core.i18n.I18n.fixed(it.length.inWholeMilliseconds / 1000.0, 2) } ?: "-"
                Text(
                    tr(
                        if (active) "ui.taste.timing.lengthsActive" else "ui.taste.timing.lengthsPending",
                        "profile" to p, "before" to sec(b), "after" to sec(a), "count" to maxOf(b?.count ?: 0, a?.count ?: 0),
                        "min" to dev.highlights.montage.ClipLengthLearner.MIN_CUTS,
                    ),
                    style = MaterialTheme.typography.bodySmall, color = if (active) Palette.accent else Palette.textMuted,
                )
            }
            if (!lengths.isEmpty) OutlinedButton(onClick = actions::resetLengths) { Text(tr("ui.taste.timing.resetLengths")) }
        }
    }
}

@Composable
private fun OffsetRow(label: String, o: LearnedOffset) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (o.active) Palette.text else Palette.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
        Text(tr("ui.taste.timing.offset", "ms" to KillTimingUi.signedMs(o.offset)), style = MaterialTheme.typography.bodyMedium, color = if (o.active) Palette.accent else Palette.textMuted, modifier = Modifier.width(90.dp))
        Text(
            if (o.active) tr("ui.taste.timing.active", "count" to o.count, "spread" to o.spread.inWholeMilliseconds)
            else tr("ui.taste.timing.pending", "count" to o.count, "spread" to o.spread.inWholeMilliseconds),
            style = MaterialTheme.typography.bodySmall, color = Palette.textMuted, modifier = Modifier.width(260.dp), maxLines = 1,
        )
    }
}
