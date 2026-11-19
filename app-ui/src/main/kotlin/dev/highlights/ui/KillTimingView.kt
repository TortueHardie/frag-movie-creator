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
) {
    val detected: Duration get() = ref.at

    /** Instant choisi ; null tant que les images ne sont pas lues. */
    val chosen: Duration? get() = frames.getOrNull(selected)?.at

    /** Une image de la capture : le pas du décalage du temps. */
    val frameStep: Duration get() = (1.0 / (media.video?.fps?.takeIf { it > 0 } ?: 60.0)).seconds

    /** Image la plus proche de [t]. */
    fun nearest(t: Duration): Int = frames.indices.minByOrNull { (frames[it].at - t).absoluteValue } ?: -1

    /** Image de la détection (repère dans la bande). */
    val detectedIndex: Int get() = nearest(detected)

    companion object {
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
) {
    private var job: Job? = null

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
        val p = pipeline() ?: return
        job?.cancel()
        job = scope.launch {
            try {
                val frames = withContext(Dispatchers.IO) { p.killFrames(media, shown.ref.at) }
                update { t -> if (t.ref != shown.ref) t else t.copy(frames = frames, loading = false, selected = t.copy(frames = frames).nearest(t.current)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.localizedMessage ?: e.toString()) }
            }
        }
    }

    fun step(delta: Int) = update { t -> if (t.frames.isEmpty()) t else t.copy(selected = (t.selected + delta).coerceIn(0, t.frames.lastIndex)) }

    fun pick(index: Int) = update { t -> if (index in t.frames.indices) t.copy(selected = index) else t }

    /** Décalage du temps de [frames] images, borné à ce que le planificateur accepte. */
    fun shiftBeat(frames: Int) = update { t ->
        val next = t.beatShift + t.frameStep * frames
        val max = dev.highlights.montage.MontageEdits.MAX_BEAT_SHIFT
        t.copy(beatShift = next.coerceIn(-max, max))
    }

    /** Pose le calage choisi et replanifie (comme une épingle posée à la souris). */
    fun apply() {
        val t = timing ?: return
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
    }

    fun close() {
        job?.cancel()
        set(null)
    }
}

/**
 * Panneau du calage, sous la frise : la bande des images (la détection marquée), l'image choisie en grand avec un
 * zoom sur le réticule (là où se lit l'impact), les flèches pour avancer d'une image, le décalage du temps, puis
 * appliquer (le plan est recalculé aussitôt), revenir à la détection ou écarter le kill.
 */
@Composable
fun KillTimingPanel(t: KillTimingUi, actions: UiActions, modifier: Modifier = Modifier) {
    // Le clavier va au panneau, qui prend le focus à l'ouverture : sans élément focalisé, la fenêtre ne voyait pas les
    // flèches (vérifié en pilotant l'application). ← → : une image ; Entrée : appliquer ; Échap : fermer le panneau.
    val focus = remember { FocusRequester() }
    LaunchedEffect(t.ref) { runCatching { focus.requestFocus() } }
    val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { actions.stepKillFrame(-1); true }
            Key.DirectionRight -> { actions.stepKillFrame(1); true }
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
                Button(onClick = actions::applyKillTiming, enabled = t.chosen != null) { Text(tr("ui.killTiming.apply")) }
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
                // L'image choisie, entière, puis le réticule agrandi : c'est là que l'impact se lit.
                val image = rememberImage(frame.file)
                Box(Modifier.height(190.dp).width(338.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh)) {
                    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Box(Modifier.size(190.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHigh).border(BorderStroke(1.dp, Palette.outline), RoundedCornerShape(6.dp))) {
                    image?.let {
                        Image(it, contentDescription = tr("ui.killTiming.zoom"), contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 2.5f, scaleY = 2.5f))
                    }
                    Text(tr("ui.killTiming.zoom"), style = MaterialTheme.typography.labelSmall, color = Palette.text,
                        modifier = Modifier.padding(4.dp).background(Palette.background.copy(alpha = 0.7f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr("ui.killTiming.frame", "n" to t.selected + 1, "total" to t.frames.size, "time" to KillRef.precise(frame.at)),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        tr("ui.killTiming.offset", "ms" to KillTimingUi.signedMs(frame.at - t.detected)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t.selected == t.detectedIndex) Palette.textMuted else Palette.accent,
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
