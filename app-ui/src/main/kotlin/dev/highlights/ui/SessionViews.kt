package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import dev.highlights.core.model.MomentOrigin
import dev.highlights.core.model.aspectLabel
import dev.highlights.core.serialization.toShortText
import dev.highlights.core.serialization.toTimecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun JobCard(job: JobState, actions: UiActions) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(job.stage.ifEmpty { tr("ui.job.preparing") }, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                Text(I18n.percent(job.fraction), style = MaterialTheme.typography.titleLarge, color = Palette.accent)
                if (job.cancellable) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = actions::cancelJob) { Text(tr("ui.common.cancel")) }
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
                job.eta?.let { tr("ui.job.time", "elapsed" to job.elapsed, "remaining" to it) } ?: tr("ui.job.timeEstimating", "elapsed" to job.elapsed),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textMuted,
            )
        }
    }
}

/** Nouvelle version disponible : un bouton la télécharge, ferme l'application, l'installe et la relance. */
@Composable
fun UpdateCard(update: UpdateState, busy: Boolean, actions: UiActions) {
    val version = update.release.version
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.accent)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val title = when (update) {
                        is UpdateState.Downloading -> tr("ui.update.downloading", "version" to version)
                        is UpdateState.Installing -> tr("ui.update.installing", "version" to version)
                        is UpdateState.Failed -> tr("ui.update.failed", "version" to version)
                        is UpdateState.Available -> tr("ui.update.available", "version" to version)
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    val detail = when (update) {
                        is UpdateState.Downloading -> tr("ui.update.downloadingHint")
                        is UpdateState.Installing -> tr("ui.update.installingHint")
                        is UpdateState.Failed -> update.message
                        is UpdateState.Available ->
                            if (busy) tr("ui.update.busyHint")
                            else tr("ui.update.availableHint")
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = if (update is UpdateState.Failed) Palette.danger else Palette.textMuted)
                }
                when (update) {
                    is UpdateState.Downloading -> {
                        Text(I18n.percent(update.fraction), style = MaterialTheme.typography.titleLarge, color = Palette.accent)
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = actions::dismissUpdate) { Text(tr("ui.common.cancel")) }
                    }
                    is UpdateState.Installing -> Unit
                    is UpdateState.Available, is UpdateState.Failed -> {
                        TextButton(onClick = actions::dismissUpdate) { Text(tr("ui.update.later")) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = actions::installUpdate, enabled = !busy) {
                            Text(if (update is UpdateState.Failed) tr("ui.common.retry") else tr("ui.update.install"))
                        }
                    }
                }
            }
            if (update is UpdateState.Downloading) {
                LinearProgressIndicator(
                    progress = { update.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Palette.accent,
                    trackColor = Palette.surfaceHigh,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
fun SessionHeader(state: UiState, session: SessionState, actions: UiActions) {
    val target = state.settings.target
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (session.multiple) tr("ui.session.titleMany", "count" to session.entries.size) else tr("ui.session.title"), style = MaterialTheme.typography.titleLarge)
            val targetText = when {
                target?.totalDuration != null -> tr("ui.session.targetDuration", "duration" to target.totalDuration!!)
                target?.all == true -> tr("ui.session.targetAll")
                target?.topN != null -> tr("ui.session.targetTop", "count" to target.topN!!)
                else -> tr("ui.session.targetInvalid")
            }
            Text(
                tr("ui.session.summary", "count" to session.enabledCount, "total" to session.highlights.size, "duration" to session.enabledDuration, "target" to targetText),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.textMuted,
            )
        }
        val kills = session.eventCounts["kill"] ?: 0
        Button(onClick = actions::openMontage, enabled = kills > 0 && state.job == null) {
            Text(if (kills > 0) tr("ui.session.killMontageCount", "count" to kills) else tr("ui.session.killMontage"))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { actions.setAllSegments(true) }, enabled = session.highlights.isNotEmpty()) { Text(tr("ui.session.checkAll")) }
        TextButton(onClick = { actions.setAllSegments(false) }, enabled = session.highlights.isNotEmpty()) { Text(tr("ui.session.uncheckAll")) }
    }
}

@Composable
fun TimelineCard(session: SessionState, threshold: Double, actions: UiActions, playhead: Duration? = null) {
    // Avec plusieurs captures, la courbe est celle de la capture du segment sélectionné.
    val entry = session.shownEntry
    val timeline = session.entries[entry].session.timeline
    val total = timeline.grid.total
    val segments = session.segments.filter { it.entry == entry }
    val highlights = segments.map { it.highlight }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.textMuted, fontSize = 11.sp)

    Surface(shape = RoundedCornerShape(10.dp), color = Palette.surface, border = BorderStroke(1.dp, Palette.outline)) {
      Column {
        if (session.multiple) {
            Text(
                tr("ui.session.videoOf", "index" to entry + 1, "count" to session.entries.size, "name" to session.entries[entry].session.media.path.name),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
            )
        }
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 14.dp, vertical = 10.dp).pointerInput(highlights, total, entry) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (total.isPositive() && size.width > 0) actions.addSegmentAt(entry, total * (offset.x / size.width).toDouble().coerceIn(0.0, 1.0))
                    },
                ) { offset ->
                    if (!total.isPositive() || size.width == 0) return@detectTapGestures
                    val t = total * (offset.x / size.width).toDouble()
                    val hit = segments.minByOrNull { s ->
                        val h = s.highlight
                        when {
                            t < h.range.start -> h.range.start - t
                            t > h.range.end -> t - h.range.end
                            else -> Duration.ZERO
                        }
                    }
                    val tolerance = total * (8.0 / size.width)
                    if (hit != null && t >= hit.highlight.range.start - tolerance && t <= hit.highlight.range.end + tolerance) actions.selectSegment(hit.key)
                }
            }.pointerInput(total, entry) {
                // Glisser sur la frise parcourt la capture dans le lecteur intégré (image par image, en pause) ;
                // relâcher lance la lecture à cet endroit, un glissement annulé la laisse en pause.
                fun at(x: Float) = total * (x / size.width).toDouble().coerceIn(0.0, 1.0)
                detectHorizontalDragGestures(
                    onDragStart = { offset -> if (total.isPositive() && size.width > 0) actions.scrubSource(entry, at(offset.x)) },
                    onDragEnd = { actions.scrubSourceEnd(play = true) },
                    onDragCancel = { actions.scrubSourceEnd(play = false) },
                ) { change, _ ->
                    if (total.isPositive() && size.width > 0) actions.scrubSource(entry, at(change.position.x))
                }
            },
        ) {
            val plotBottom = size.height - 18f
            val plotHeight = plotBottom
            fun x(d: Duration) = if (total.isPositive()) (d / total).toFloat() * size.width else 0f

            // Segments : bande sur toute la hauteur + repère plein en haut, visible même pour 10 s sur 1 h
            segments.forEach { s ->
                val h = s.highlight
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
                if (s.key == session.selectedId) {
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

            // Tête du lecteur intégré, quand il montre cette capture
            playhead?.let { p ->
                val px = x(p)
                drawLine(Palette.text, Offset(px, 0f), Offset(px, plotBottom), strokeWidth = 2f)
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
        Text(
            tr("ui.session.addHint") + " " + tr("ui.session.scrubHint"),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        )
      }
    }
}

@Composable
fun SegmentList(session: SessionState, state: UiState, actions: UiActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val segments = session.segments
    // Avec plusieurs captures, chacune a son titre dans la liste : il compte dans la position à atteindre.
    val selectedIndex = segments.indexOfFirst { it.key == session.selectedId }.let { i ->
        if (i < 0 || !session.multiple) i else i + segments[i].entry + 1
    }
    LaunchedEffect(session.selectedId) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }
    if (segments.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(tr("ui.session.empty"), color = Palette.textMuted)
        }
        return
    }
    val montage = MomentRetouch.montageOrder(session)
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        session.entries.forEachIndexed { i, entry ->
            val own = segments.filter { it.entry == i }
            if (session.multiple) {
                item(key = "video-$i") { VideoHeader(i, entry, own) }
            }
            items(own, key = { it.key }) { s ->
                SegmentRow(
                    segment = s,
                    selected = s.key == session.selectedId,
                    thumbnail = session.thumbnailOf(s),
                    clipBusy = s.key in state.busyClips,
                    verticalBusy = state.busyVerticalPreview == s.key,
                    verticalAvailable = state.busyVerticalPreview == null,
                    position = montage.indexOfFirst { it.key == s.key },
                    montageSize = montage.size,
                    actions = actions,
                )
            }
        }
    }
}

/** Titre d'une capture dans la liste : son rang dans la soirée, son nom et ce qu'elle apporte au montage. */
@Composable
private fun VideoHeader(index: Int, entry: SessionEntry, segments: List<Segment>) {
    val enabled = segments.map { it.highlight }.filter { it.enabled }
    val duration = enabled.fold(Duration.ZERO) { acc, h -> acc + h.range.length }
    Row(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}.", style = MaterialTheme.typography.titleSmall, color = Palette.accent)
        Spacer(Modifier.width(8.dp))
        Text(
            entry.session.media.path.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (segments.isEmpty()) tr("ui.session.videoEmpty") else tr("ui.session.videoSummary", "count" to enabled.size, "total" to segments.size, "duration" to duration),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )
    }
}

@Composable
private fun SegmentRow(
    segment: Segment,
    selected: Boolean,
    thumbnail: java.nio.file.Path?,
    clipBusy: Boolean,
    verticalBusy: Boolean,
    verticalAvailable: Boolean,
    /** Rang dans le montage (0 = premier), -1 si décoché. */
    position: Int,
    montageSize: Int,
    actions: UiActions,
) {
    val highlight = segment.highlight
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Palette.surfaceHigh else Palette.surface,
        border = BorderStroke(1.dp, if (selected) Palette.accent else Palette.outline),
        modifier = Modifier.fillMaxWidth().clickable { actions.selectSegment(segment.key) },
    ) {
      Column {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = highlight.enabled, onCheckedChange = { actions.toggleSegment(segment.key) })
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
                    Text(I18n.duration(highlight.range.length), style = MaterialTheme.typography.bodyMedium, color = Palette.textMuted)
                    originLabel(highlight.origin)?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.cyan)
                    }
                    if (position >= 0 && montageSize > 1) {
                        Spacer(Modifier.width(10.dp))
                        Text(tr("ui.session.position", "position" to position + 1), style = MaterialTheme.typography.labelMedium, color = Palette.textMuted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreBar(highlight.score, Modifier.width(140.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.fixed(highlight.score, 2), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(14.dp))
                    // Les événements cèdent la place quand la ligne est pleine (VALORANT : tirs, balles, kills…), pas la
                    // part des signaux : sinon « son 0,74 » devenait « son … ».
                    if (highlight.events.isNotEmpty()) {
                        Text(
                            eventsLabel(highlight.events),
                            style = MaterialTheme.typography.labelLarge,
                            color = Palette.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        highlight.contributions.entries.filter { it.value > 0.005 }.joinToString("   ") { (k, v) -> "${signalLabel(k)} ${I18n.fixed(v, 2)}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { actions.previewClip(segment.key) }, enabled = !clipBusy) {
                Text(if (clipBusy) tr("ui.job.preparing") else tr("ui.session.replay"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { actions.previewVertical(segment.key) }, enabled = verticalAvailable) {
                Text(if (verticalBusy) "…" else "9:16")
            }
        }
        if (selected) RetouchBar(segment.key, position, montageSize, actions)
      }
    }
}

/** Retouche du moment sélectionné : début et fin par pas d'une demi-seconde ou d'une seconde, place dans le montage. */
@Composable
private fun RetouchBar(key: String, position: Int, montageSize: Int, actions: UiActions) {
    val steps = listOf("ui.session.minusOne" to (-1).seconds, "ui.session.minusHalf" to (-500).milliseconds, "ui.session.plusHalf" to 500.milliseconds, "ui.session.plusOne" to 1.seconds)
    Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(false to "ui.session.start", true to "ui.session.end").forEach { (end, label) ->
            Text(tr(label), style = MaterialTheme.typography.labelLarge, color = Palette.textMuted)
            Spacer(Modifier.width(4.dp))
            steps.forEach { (text, delta) ->
                TextButton(onClick = { actions.nudgeSegment(key, end, delta) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr(text)) }
            }
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.weight(1f))
        // Ce moment seul, en vidéo 9:16 de 15 à 30 s prête pour TikTok ou Shorts.
        OutlinedButton(onClick = { actions.clipMoment(key) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.session.clip")) }
        Spacer(Modifier.width(6.dp))
        if (position >= 0 && montageSize > 1) {
            OutlinedButton(onClick = { actions.moveSegment(key, -1) }, enabled = position > 0, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.session.earlier")) }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { actions.moveSegment(key, 1) }, enabled = position < montageSize - 1, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.session.later")) }
        }
    }
}

private fun originLabel(origin: MomentOrigin): String? = when (origin) {
    MomentOrigin.DETECTED -> null
    MomentOrigin.ADJUSTED -> tr("ui.session.adjusted")
    MomentOrigin.ADDED -> tr("ui.session.added")
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
            ExportBarPresets(state, actions)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("ui.export.title"), style = MaterialTheme.typography.titleMedium)
                    val platform = state.platforms.firstOrNull { it.id == state.settings.platform }
                    val formats = platform?.let { "${it.name} (${it.format.label})" } ?: state.settings.orderedFormats.joinToString(" + ") {
                        when (it) {
                            dev.highlights.core.model.OutputFormat.SOURCE -> state.source?.media?.video?.let { v -> aspectLabel(v.width, v.height) } ?: tr("format.source.short")
                            else -> it.label
                        }
                    }.ifEmpty { tr("ui.export.noFormat") }
                    val videos = if (session.multiple) tr("ui.export.videosInOrder", "count" to session.entries.size) else null
                    Text(
                        listOfNotNull(tr("ui.export.segments", "count" to session.enabledCount), I18n.duration(session.enabledDuration), formats, videos).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.textMuted,
                    )
                }
                Button(onClick = actions::export, enabled = state.canExport, modifier = Modifier.height(44.dp)) {
                    Text(tr("ui.export.button"), style = MaterialTheme.typography.titleMedium)
                }
            }
            state.lastExport?.let { result ->
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(tr("ui.export.done", "duration" to result.duration, "encoder" to result.encoder), color = Palette.success, style = MaterialTheme.typography.labelLarge)
                    result.music?.let { music ->
                        Text(tr("ui.export.music", "name" to music.fileName.toString()), style = MaterialTheme.typography.bodyMedium, color = Palette.textMuted)
                    }
                    // Montage kills : le rythme rapide convient-il à cette musique ? La réponse règle les prochains.
                    if (result.fastShots != null && result.music != null) MusicFeelQuestion(result, state.musicFeels[result.report], actions)
                    // Avertissements du travail (montage presque vide…) : à lire avant de publier.
                    result.notices.forEach { notice ->
                        Text(notice.text(), style = MaterialTheme.typography.bodyMedium, color = Palette.accent)
                    }
                    state.lastUpload?.let { uploaded ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("ui.export.onYouTube", "url" to uploaded.url.toString()), color = Palette.success, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { actions.browse(uploaded.url) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.export.view")) }
                            TextButton(onClick = { actions.browse(uploaded.studio) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Studio") }
                        }
                    }
                    (result.videos.values + listOf(result.report)).forEach { file ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (file != result.report) {
                                TextButton(onClick = { actions.playVideo(file) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.player.play")) }
                                TextButton(onClick = { actions.openPublish(file) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("YouTube") }
                            }
                            TextButton(onClick = { actions.open(file) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.common.open")) }
                            TextButton(onClick = { actions.reveal(file) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(tr("ui.common.showInFolder")) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Question sous le résultat d'un montage kills : le rythme rapide sur cette musique, bien, trop rapide ou pas assez ?
 * Une fois répondu, ce qui est retenu pour les prochains montages sur elle ([saved]).
 */
@Composable
fun MusicFeelQuestion(result: dev.highlights.export.ExportResult, saved: dev.highlights.core.model.MusicFeel?, actions: UiActions) {
    val name = result.music?.fileName?.toString() ?: return
    if (saved != null) {
        val key = if (saved == dev.highlights.core.model.MusicFeel.FAST) "ui.feel.saved.fast" else "ui.feel.saved.calm"
        Text(tr(key, "name" to name), style = MaterialTheme.typography.bodyMedium, color = Palette.success)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr("ui.feel.question", "name" to name),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        listOf(
            dev.highlights.montage.FeelVote.GOOD to "ui.feel.good",
            dev.highlights.montage.FeelVote.TOO_FAST to "ui.feel.tooFast",
            dev.highlights.montage.FeelVote.NOT_FAST_ENOUGH to "ui.feel.notFastEnough",
        ).forEach { (vote, key) ->
            TextButton(onClick = { actions.rateMusicFeel(vote) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr(key)) }
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

/** Nom traduit d'un signal (« game-audio » → son) ; l'identifiant du profil s'il n'en a pas. */
private fun signalLabel(id: String) = I18n.textOrNull("signal.$id") ?: id
