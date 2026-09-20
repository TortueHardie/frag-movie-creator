package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.highlights.core.model.Highlight
import dev.highlights.core.serialization.toShortText
import dev.highlights.core.serialization.toTimecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun JobCard(job: JobState, actions: UiActions) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(job.stage.ifEmpty { "Préparation…" }, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                Text("%.0f %%".format(job.fraction * 100), style = MaterialTheme.typography.titleLarge, color = Palette.accent)
                if (job.cancellable) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = actions::cancelJob) { Text("Annuler") }
                }
            }
            LinearProgressIndicator(
                progress = { job.fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Palette.accent,
                trackColor = Palette.surfaceHigh,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(
                "Écoulé ${job.elapsed.toShortText()} · reste ${job.eta?.toShortText() ?: "estimation en cours"}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textMuted,
            )
        }
    }
}

@Composable
fun SessionHeader(state: UiState, session: SessionState, actions: UiActions) {
    val target = state.settings.target
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Moments détectés", style = MaterialTheme.typography.titleLarge)
            val targetText = when {
                target?.totalDuration != null -> "cible ${target.totalDuration!!.toShortText()}"
                target?.all == true -> "tout garder"
                target?.topN != null -> "cible ${target.topN} moments"
                else -> "cible invalide"
            }
            Text(
                "${session.enabledCount} / ${session.highlights.size} cochés · ${session.enabledDuration.toShortText()} · $targetText",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textMuted,
            )
        }
        val kills = session.eventCounts["kill"] ?: 0
        Button(onClick = actions::openMontage, enabled = kills > 0 && state.job == null) {
            Text(if (kills > 0) "Montage kills ($kills)" else "Montage kills")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { actions.setAllSegments(true) }, enabled = session.highlights.isNotEmpty()) { Text("Tout cocher") }
        TextButton(onClick = { actions.setAllSegments(false) }, enabled = session.highlights.isNotEmpty()) { Text("Tout décocher") }
    }
}

@Composable
fun TimelineCard(session: SessionState, threshold: Double, actions: UiActions) {
    val timeline = session.session.timeline
    val total = timeline.grid.total
    val highlights = session.highlights
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.textMuted, fontSize = 11.sp)

    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 14.dp, vertical = 10.dp).pointerInput(highlights, total) {
                detectTapGestures { offset ->
                    if (!total.isPositive() || size.width == 0) return@detectTapGestures
                    val t = total * (offset.x / size.width).toDouble()
                    val hit = highlights.minByOrNull { h ->
                        when {
                            t < h.range.start -> h.range.start - t
                            t > h.range.end -> t - h.range.end
                            else -> Duration.ZERO
                        }
                    }
                    val tolerance = total * (8.0 / size.width)
                    if (hit != null && t >= hit.range.start - tolerance && t <= hit.range.end + tolerance) actions.selectSegment(hit.id)
                }
            },
        ) {
            val plotBottom = size.height - 18f
            val plotHeight = plotBottom
            fun x(d: Duration) = if (total.isPositive()) (d / total).toFloat() * size.width else 0f

            // Segments : bande sur toute la hauteur + repère plein en haut, visible même pour 10 s sur 1 h
            highlights.forEach { h ->
                val x0 = x(h.range.start)
                val w = (x(h.range.end) - x0).coerceAtLeast(4f)
                drawRect(
                    color = if (h.enabled) Palette.accentSoft else Palette.disabledSegment,
                    topLeft = Offset(x0, 0f),
                    size = Size(w, plotBottom),
                )
                drawRect(
                    color = if (h.enabled) Palette.accent else Palette.textMuted,
                    topLeft = Offset(x0, 0f),
                    size = Size(w, 6f),
                )
                if (h.id == session.selectedId) {
                    drawRect(Palette.accent, Offset(x0, 0f), Size(w, plotBottom), style = Stroke(width = 2f))
                }
            }

            // Hors jeu (menus, chargement) : grisé
            timeline.excluded.forEach { r ->
                drawRect(Palette.background.copy(alpha = 0.75f), Offset(x(r.start), 0f), Size((x(r.end) - x(r.start)).coerceAtLeast(1f), plotBottom))
            }

            // Courbe de score : maximum par colonne de pixels
            val scores = timeline.total
            // Les scores dépassent 1 quand un kill s'ajoute à l'intensité : l'axe s'adapte au maximum.
            val scale = maxOf(1.0, scores.maxOrNull() ?: 1.0).toFloat()
            val columns = size.width.toInt().coerceAtLeast(1)
            if (scores.isNotEmpty()) {
                val path = Path()
                for (c in 0 until columns) {
                    val from = (c.toLong() * scores.size / columns).toInt()
                    val to = (((c + 1).toLong() * scores.size / columns).toInt()).coerceAtLeast(from + 1).coerceAtMost(scores.size)
                    var max = 0.0
                    for (i in from until to) if (scores[i] > max) max = scores[i]
                    val y = plotBottom - (max.toFloat() / scale * plotHeight)
                    if (c == 0) path.moveTo(c.toFloat(), y) else path.lineTo(c.toFloat(), y)
                }
                drawPath(path, Palette.cyan, style = Stroke(width = 1.2f))
            }

            // Événements : kills en orange (triangle), autres en cyan (trait)
            timeline.events.forEach { e ->
                val ex = x(e.at)
                if (e.kind == "kill") {
                    val marker = Path().apply {
                        moveTo(ex, plotBottom - 12f)
                        lineTo(ex - 5f, plotBottom - 2f)
                        lineTo(ex + 5f, plotBottom - 2f)
                        close()
                    }
                    drawPath(marker, Palette.accent)
                } else {
                    drawLine(Palette.cyan, Offset(ex, plotBottom - 9f), Offset(ex, plotBottom - 1f), strokeWidth = 2f)
                }
            }

            // Seuil
            val ty = plotBottom - threshold.toFloat() / scale * plotHeight
            drawLine(Palette.accent, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))

            // Axe du temps
            drawLine(Palette.outline, Offset(0f, plotBottom), Offset(size.width, plotBottom), strokeWidth = 1f)
            val step = listOf(10.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes, 10.minutes, 15.minutes, 30.minutes)
                .firstOrNull { total / it <= 12 } ?: 60.minutes
            var tick = Duration.ZERO
            while (tick <= total) {
                val tx = x(tick)
                drawLine(Palette.outline, Offset(tx, plotBottom), Offset(tx, plotBottom + 4f), strokeWidth = 1f)
                val label = tick.toTimecode().substringBefore('.')
                val layout = measurer.measure(label, labelStyle)
                val lx = (tx - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                drawText(layout, topLeft = Offset(lx, plotBottom + 4f))
                tick += step
            }
        }
    }
}

@Composable
fun SegmentList(session: SessionState, state: UiState, actions: UiActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val selectedIndex = session.highlights.indexOfFirst { it.id == session.selectedId }
    LaunchedEffect(session.selectedId) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }
    if (session.highlights.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Aucun moment au-dessus du seuil : baisse-le à gauche.", color = Palette.textMuted)
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(session.highlights, key = { it.id }) { h ->
            SegmentRow(
                highlight = h,
                selected = h.id == session.selectedId,
                thumbnail = session.thumbnailOf(h),
                clipBusy = h.id in state.busyClips,
                verticalBusy = state.busyVerticalPreview == h.id,
                verticalAvailable = state.busyVerticalPreview == null,
                actions = actions,
            )
        }
    }
}

@Composable
private fun SegmentRow(
    highlight: Highlight,
    selected: Boolean,
    thumbnail: java.nio.file.Path?,
    clipBusy: Boolean,
    verticalBusy: Boolean,
    verticalAvailable: Boolean,
    actions: UiActions,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Palette.surfaceHigh else Palette.surface,
        border = BorderStroke(1.dp, if (selected) Palette.accent else Palette.outline),
        modifier = Modifier.fillMaxWidth().clickable { actions.selectSegment(highlight.id) },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = highlight.enabled, onCheckedChange = { actions.toggleSegment(highlight.id) })
            Spacer(Modifier.width(6.dp))
            Thumbnail(thumbnail, highlight.enabled)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(highlight.id, style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${highlight.range.start.toTimecode().substringBefore('.')} → ${highlight.range.end.toTimecode().substringBefore('.')}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (highlight.enabled) Palette.text else Palette.textMuted,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(highlight.range.length.toShortText(), style = MaterialTheme.typography.bodyMedium, color = Palette.textMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBar(highlight.score, Modifier.width(140.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("%.2f".format(highlight.score), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(14.dp))
                    if (highlight.events.isNotEmpty()) {
                        Text(eventsLabel(highlight.events), style = MaterialTheme.typography.labelLarge, color = Palette.accent)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        highlight.contributions.entries.filter { it.value > 0.005 }.joinToString("   ") { (k, v) -> "${signalLabel(k)} %.2f".format(v) },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { actions.previewClip(highlight.id) }, enabled = !clipBusy) {
                Text(if (clipBusy) "Préparation…" else "▶ Revoir")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { actions.previewVertical(highlight.id) }, enabled = verticalAvailable) {
                Text(if (verticalBusy) "…" else "9:16")
            }
        }
    }
}

@Composable
private fun Thumbnail(path: java.nio.file.Path?, enabled: Boolean) {
    val image = rememberImage(path)
    Box(
        Modifier.width(150.dp).height(64.dp).clip(RoundedCornerShape(6.dp)).background(Palette.background),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, alpha = if (enabled) 1f else 0.4f, modifier = Modifier.fillMaxWidth().fillMaxHeight())
        } else {
            Text("…", color = Palette.textMuted)
        }
    }
}

@Composable
private fun ScoreBar(score: Double, modifier: Modifier) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.surfaceHigh)) {
        Box(
            // Barre pleine à 1,5 : un kill (+0,8) pendant un combat intense.
            Modifier.fillMaxWidth((score / 1.5).toFloat().coerceIn(0f, 1f)).height(6.dp).background(
                if (score >= 1.0) Palette.accent else if (score >= 0.6) Palette.cyan else Palette.textMuted,
            ),
        )
    }
}

@Composable
fun ExportBar(state: UiState, session: SessionState, actions: UiActions) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Montage", style = MaterialTheme.typography.titleMedium)
                    val formats = state.settings.orderedFormats.joinToString(" + ") {
                        when (it) {
                            dev.highlights.core.model.OutputFormat.SOURCE -> state.source?.media?.video?.let { v -> aspectLabel(v.width, v.height) } ?: "source"
                            else -> it.label
                        }
                    }.ifEmpty { "aucun format coché" }
                    Text(
                        "${session.enabledCount} segment(s) · ${session.enabledDuration.toShortText()} · $formats",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.textMuted,
                    )
                }
                Button(onClick = actions::export, enabled = state.canExport, modifier = Modifier.height(44.dp)) {
                    Text("Exporter le montage", style = MaterialTheme.typography.titleMedium)
                }
            }
            state.lastExport?.let { result ->
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text("Export terminé (${result.duration.toShortText()}, ${result.encoder})", color = Palette.success, style = MaterialTheme.typography.labelLarge)
                    (result.videos.values + listOf(result.report)).forEach { file ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { actions.open(file) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Ouvrir") }
                            TextButton(onClick = { actions.reveal(file) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Dans le dossier") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberImage(path: java.nio.file.Path?, version: Long = 0): ImageBitmap? {
    var image by remember(path, version) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, version) {
        image = path?.let {
            withContext(Dispatchers.IO) {
                runCatching { SkiaImage.makeFromEncoded(Files.readAllBytes(it)).toComposeImageBitmap() }.getOrNull()
            }
        }
    }
    return image
}

private fun signalLabel(id: String) = when (id) {
    "game-audio" -> "son"
    "mic-audio" -> "voix"
    else -> id
}
