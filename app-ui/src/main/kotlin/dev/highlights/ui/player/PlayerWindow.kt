package dev.highlights.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.toTimecode
import dev.highlights.ui.HighlightsTheme
import dev.highlights.ui.Palette
import dev.highlights.ui.UiActions
import java.nio.file.Path as FilePath
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lecteur ouvert dans l'interface ([dev.highlights.ui.UiState.player]) : quelle vidéo, et d'où elle vient. [entry] :
 * capture de la session ouverte (la frise montre alors la tête de lecture) ; [range] : moment à repérer sur la barre.
 * [position], [playing] et [sync] recopient le moteur une fois par seconde, pour le pont de pilotage et la frise.
 */
data class PlayerUiState(
    val file: FilePath,
    val entry: Int? = null,
    val range: TimeRange? = null,
    val position: Duration = Duration.ZERO,
    val duration: Duration = Duration.ZERO,
    val playing: Boolean = false,
    val sync: SyncStats = SyncStats(),
)

/** Fenêtre du lecteur : image, lecture/pause, barre de position, temps, et le lecteur du système en secours. */
@Composable
fun VideoPlayerWindow(player: PlayerUiState, engine: VideoPlayer, actions: UiActions) {
    val view by engine.view.collectAsState()
    // Taille reprise de la vidéo déjà ouverte : la fenêtre est recréée à chaque changement de langue.
    val dialog = rememberDialogState(size = view.size?.let(::windowSize) ?: DpSize(960.dp, 640.dp))
    // Taille de la fenêtre ajustée à la vidéo dès qu'elle est connue : un montage 9:16 en hauteur, une capture en largeur.
    LaunchedEffect(view.size) {
        val s = view.size ?: return@LaunchedEffect
        dialog.size = windowSize(s)
    }
    DialogWindow(
        onCloseRequest = actions::closePlayer,
        state = dialog,
        title = tr("ui.player.title", "name" to player.file.fileName.toString()),
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@DialogWindow false
            when (event.key) {
                Key.Spacebar, Key.K -> { actions.playerToggle(); true }
                Key.DirectionLeft, Key.J -> { actions.playerSeek((view.position - SEEK_STEP).coerceAtLeast(Duration.ZERO)); true }
                Key.DirectionRight, Key.L -> { actions.playerSeek(view.position + SEEK_STEP); true }
                Key.Escape -> { actions.closePlayer(); true }
                else -> false
            }
        },
    ) {
        HighlightsTheme {
            Column(Modifier.fillMaxSize().background(Palette.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    VideoSurface(engine, Modifier.fillMaxSize())
                    val error = view.error
                    when {
                        error != null -> Text(
                            tr("ui.player.error", "detail" to error.text()),
                            color = Palette.accent,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                        engine.frame.collectAsState().value == null -> Text(tr("ui.player.loading"), color = Palette.textMuted)
                    }
                }
                Controls(player, view, actions)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("ui.player.keys"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { actions.open(player.file) }) { Text(tr("ui.player.system")) }
                    TextButton(onClick = actions::closePlayer) { Text(tr("ui.common.close")) }
                }
            }
        }
    }
}

/**
 * Image courante dessinée à la taille de la boîte, bandes noires comprises. La lecture de [VideoPlayer.frame] se fait
 * dans la phase de dessin : chaque nouvelle image redessine ce canevas seul, sans recomposer la fenêtre.
 */
@Composable
fun VideoSurface(engine: VideoPlayer, modifier: Modifier = Modifier) {
    val frame = engine.frame.collectAsState()
    val description = tr("ui.player.video")
    Canvas(modifier.semantics { contentDescription = description }) {
        val f = frame.value ?: return@Canvas
        val iw = f.image.width.toFloat()
        val ih = f.image.height.toFloat()
        val scale = minOf(size.width / iw, size.height / ih)
        val w = iw * scale
        val h = ih * scale
        drawImage(
            f.image,
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}

@Composable
private fun Controls(player: PlayerUiState, view: PlayerView, actions: UiActions) {
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val total = view.duration
    val fraction = if (total.isPositive()) (view.position / total).toFloat().coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragged else fraction
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val label = tr(if (view.playing) "ui.player.pause" else "ui.player.play")
        FilledIconButton(onClick = actions::playerToggle, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) {
            PlayPauseIcon(view.playing)
        }
        Text(clock(if (dragging) total * shown.toDouble() else view.position), style = MaterialTheme.typography.labelLarge, color = Palette.text)
        Column(Modifier.weight(1f)) {
            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    dragged = it
                    if (total.isPositive()) actions.playerScrub(total * it.toDouble())
                },
                onValueChangeFinished = {
                    dragging = false
                    actions.playerScrubEnd()
                },
                colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent),
                modifier = Modifier.semantics { contentDescription = tr("ui.player.seek") },
            )
            // Moment de la session ouvert depuis la frise : repère sous la barre.
            player.range?.takeIf { total.isPositive() }?.let { range -> RangeMarker(range, total) }
        }
        Text(clock(total), style = MaterialTheme.typography.labelLarge, color = Palette.textMuted)
    }
}

@Composable
private fun RangeMarker(range: TimeRange, total: Duration) {
    Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 10.dp)) {
        val x0 = (range.start / total).toFloat() * size.width
        val x1 = (range.end / total).toFloat() * size.width
        drawRect(Palette.accent, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(3f), size.height))
    }
}

/** Triangle ou deux barres, dessinés : les icônes de lecture ne sont pas dans le jeu d'icônes de base de Material. */
@Composable
private fun PlayPauseIcon(playing: Boolean) {
    Canvas(Modifier.size(16.dp)) {
        val color = Color.White
        if (playing) {
            val bar = size.width * 0.32f
            drawRect(color, Offset(size.width * 0.08f, 0f), Size(bar, size.height))
            drawRect(color, Offset(size.width * 0.6f, 0f), Size(bar, size.height))
        } else {
            val triangle = Path().apply {
                moveTo(size.width * 0.15f, 0f)
                lineTo(size.width, size.height / 2)
                lineTo(size.width * 0.15f, size.height)
                close()
            }
            drawPath(triangle, color)
        }
    }
}

/** « 01:07 » : minutes et secondes, sans les millièmes. */
private fun clock(d: Duration): String = d.toTimecode().substringBefore('.')

/** Fenêtre qui tient la vidéo à sa taille de décodage (≤ 1100×700 dp), plus la barre de commandes. */
internal fun windowSize(s: DecodeSize): DpSize {
    val scale = minOf(1100f / s.width, 700f / s.height, 1.6f)
    val w = (s.width * scale).coerceAtLeast(MIN_WIDTH)
    return DpSize((w + 24).dp, (s.height * scale + CONTROLS_HEIGHT).dp)
}

private const val MIN_WIDTH = 520f
private const val CONTROLS_HEIGHT = 150f
private val SEEK_STEP = 5.seconds
