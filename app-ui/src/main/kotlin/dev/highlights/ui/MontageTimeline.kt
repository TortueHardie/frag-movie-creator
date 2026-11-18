package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.unit.DpSize
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.MediaInfo
import dev.highlights.montage.KillGroup
import dev.highlights.montage.KillRef
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontagePin
import dev.highlights.montage.SectionKind
import dev.highlights.pipeline.PlannedMontage
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Frise du plan de montage : la musique (volume par temps, sections, drop), les groupes de kills placés dessus et ceux
 * que le plan écarte. L'utilisateur y pose ses corrections ([MontageConstraints]) puis replanifie, sans réanalyser.
 * [loading] : un plan est en cours de calcul ([fraction] de 0 à 1) ; [view] : le dernier plan calculé, gardé pendant
 * le calcul du suivant ; [planned] : les corrections avec lesquelles il l'a été (différentes de celles du montage : à
 * replanifier) ; [error] : la dernière correction impossible, dite par le planificateur ; [thumbnails] : image de chaque
 * groupe (clé : [PlanGroup.key]), arrivées une à une.
 */
data class MontagePlanState(
    val loading: Boolean = true,
    val fraction: Double = 0.0,
    val view: PlanView? = null,
    val planned: MontageConstraints = MontageConstraints.NONE,
    val error: String? = null,
    val thumbnails: Map<String, Path> = emptyMap(),
)

/** Groupes écartés dont on cherche la vignette : au-delà, une rangée qu'on ne fait plus défiler. */
internal const val PLAN_THUMBNAILS = 40

/** Correction d'un groupe depuis la frise. */
enum class KillEdit { EXCLUDE, FORCE, DROP, CLEAR }

/** [kills] : les kills d'un groupe ; écarter les écarte tous, forcer ou épingler désigne le groupe par son dernier. */
fun MontageConstraints.edit(kills: List<KillRef>, edit: KillEdit): MontageConstraints {
    if (kills.isEmpty()) return this
    val cleared = kills.fold(this) { c, k -> c.clear(k) }
    return when (edit) {
        KillEdit.EXCLUDE -> kills.fold(cleared) { c, k -> c.exclude(k) }
        KillEdit.FORCE -> cleared.force(kills.last())
        KillEdit.DROP -> cleared.pin(kills.last(), MontagePin.Drop)
        KillEdit.CLEAR -> cleared
    }
}

/** Un groupe de kills tel que la frise le montre : capture, kills (désignations), ce qu'il vaut. */
data class PlanGroup(val media: MediaInfo, val kills: List<Duration>, val rank: Double) {
    val refs: List<KillRef> get() = kills.map { KillRef.of(media, it) }
    /** Désignation du groupe : son dernier kill, celui qui tombe sur le temps. */
    val key: String get() = refs.last().toString()
    val capture: String get() = media.path.fileName.toString()

    /** Ce que l'utilisateur a demandé pour ce groupe. */
    fun mark(c: MontageConstraints): GroupMark {
        // Désignations de la frise : le kill exact (à la milliseconde), jamais son voisin recalé à 0,1 s.
        fun any(refs: Collection<KillRef>) = kills.any { k -> refs.any { it.exactly(media, k) } }
        val excluded = kills.count { k -> c.excluded.any { it.exactly(media, k) } }
        val pin = c.pins.entries.firstOrNull { (ref, _) -> kills.any { ref.exactly(media, it) } }?.value
        return when {
            excluded == kills.size -> GroupMark.EXCLUDED
            pin == MontagePin.Drop -> GroupMark.DROP
            pin != null -> GroupMark.PINNED
            any(c.forced) -> GroupMark.FORCED
            excluded > 0 -> GroupMark.PARTLY_EXCLUDED
            else -> GroupMark.NONE
        }
    }

    companion object {
        fun of(g: KillGroup) = PlanGroup(g.media, g.kills, g.rank)
    }
}

enum class GroupMark { NONE, EXCLUDED, PARTLY_EXCLUDED, FORCED, DROP, PINNED }

/** Kill montré : son instant dans le montage, sa désignation. */
data class PlanKill(val at: Duration, val ref: KillRef)

/** Plan placé sur la musique : [start]/[end] dans le montage, [kills] ceux qu'il montre ; [group] null : plan vitrine. */
data class PlanClip(val group: PlanGroup?, val start: Duration, val end: Duration, val kills: List<PlanKill>, val drop: Boolean)

data class PlanSection(val start: Duration, val end: Duration, val kind: SectionKind, val intensity: Double)

/**
 * Ce que montre la frise, tiré d'un [PlannedMontage] : temps en temps du montage (0 = première image). [energy] : volume
 * de chaque temps (0..1) à l'instant [beats] du même rang.
 */
data class PlanView(
    val music: String,
    val bpm: Double,
    val musicStart: Duration,
    val duration: Duration,
    val beats: List<Duration>,
    val energy: List<Double>,
    val sections: List<PlanSection>,
    val dropAt: Duration?,
    val clips: List<PlanClip>,
    val left: List<PlanGroup>,
) {
    companion object {
        fun of(planned: PlannedMontage): PlanView {
            val plan = planned.plan
            val music = plan.music
            val start = plan.musicStart
            val range = plan.startBeat until plan.endBeat
            val rawEnergy = range.map { music.beatEnergy.getOrElse(it) { 0.0 } }
            val top = rawEnergy.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            // Groupe d'avant les corrections : celui que l'utilisateur reconnaît (kills exclus compris).
            fun candidate(g: KillGroup) = planned.candidates.firstOrNull { c -> c.media.path == g.media.path && c.kills.any { it in g.kills } } ?: g
            val clips = plan.clips.zip(plan.clipOffsets()).map { (c, offset) ->
                val end = offset + (music.beatTime(c.slot.endBeat) - music.beatTime(c.slot.startBeat))
                if (c.showcase) PlanClip(null, offset, end, emptyList(), false)
                else PlanClip(PlanGroup.of(candidate(c.group)), offset, end, c.kills.map { PlanKill(offset + c.toOutput(it), KillRef.of(c.group.media, it)) }, c.slot.dropBeat != null)
            }
            val placed = clips.mapNotNull { it.group }
            val left = planned.candidates.filter { g -> placed.none { p -> p.media.path == g.media.path && p.kills.any { it in g.kills } } }
                .sortedByDescending { it.rank }.map(PlanGroup::of)
            return PlanView(
                music = music.file.fileName.toString(),
                bpm = music.bpm,
                musicStart = start,
                duration = plan.duration,
                beats = range.map { music.beatTime(it) - start },
                energy = rawEnergy.map { it / top },
                sections = music.sections.filter { it.endBeat > plan.startBeat && it.startBeat < plan.endBeat }.map {
                    PlanSection(
                        music.beatTime(maxOf(it.startBeat, plan.startBeat)) - start,
                        music.beatTime(minOf(it.endBeat, plan.endBeat)) - start,
                        it.kind, it.intensity,
                    )
                },
                dropAt = plan.dropAt,
                clips = clips,
                left = left,
            )
        }
    }
}

/** Ligne du dialogue de montage : ouvrir la frise, et les corrections déjà posées. */
@Composable
fun MontagePlanEntry(montage: MontageUiState, actions: UiActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = actions::openMontagePlan, enabled = montage.musicSource != null) { Text(tr("ui.montagePlan.open")) }
        val c = montage.constraints
        val count = c.excluded.size + c.forced.size + c.pins.size
        Text(
            if (count == 0) tr("ui.montagePlan.noEdits") else tr("ui.montagePlan.edits", "count" to count),
            style = MaterialTheme.typography.bodySmall,
            color = if (count == 0) Palette.textMuted else Palette.accent,
        )
    }
}

/** Fenêtre de la frise, à part du dialogue : elle a besoin de largeur. */
@Composable
fun MontagePlanWindow(montage: MontageUiState, plan: MontagePlanState, actions: UiActions) {
    DialogWindow(
        onCloseRequest = actions::closeMontagePlan,
        state = rememberDialogState(size = DpSize(1320.dp, 640.dp)),
        title = tr("ui.montagePlan.title"),
    ) {
        HighlightsTheme {
            MontagePlanContent(montage.constraints, plan, actions, Modifier.fillMaxSize())
        }
    }
}

private fun sectionColor(kind: SectionKind, intensity: Double): Color = when (kind) {
    SectionKind.DROP -> Palette.accent
    SectionKind.BUILD_UP -> Color(0xFFE0B13F)
    SectionKind.BREAKDOWN -> Color(0xFF6C7BD9)
    SectionKind.INTRO, SectionKind.OUTRO -> Color(0xFF5B6475)
    else -> Palette.cyan.copy(alpha = (0.45 + 0.55 * intensity).toFloat())
}

private fun markColor(mark: GroupMark): Color? = when (mark) {
    GroupMark.EXCLUDED -> Palette.danger
    GroupMark.PARTLY_EXCLUDED -> Palette.danger.copy(alpha = 0.6f)
    GroupMark.FORCED -> Palette.success
    GroupMark.DROP, GroupMark.PINNED -> Palette.accent
    GroupMark.NONE -> null
}

private fun markLabel(mark: GroupMark): String? = when (mark) {
    GroupMark.NONE -> null
    else -> tr("ui.montagePlan.mark.${mark.name.lowercase()}")
}

private fun clock(d: Duration) = KillRef.clock(d).substringBefore('.')

/**
 * Contenu de la frise (sans fenêtre, pour les captures de test) : en-tête et boutons, la frise, les groupes écartés.
 * Clic sur un plan : l'écarter (ou annuler la correction) ; clic sur un trait de kill : écarter ce kill seul ; clic sur
 * un groupe écarté : le forcer ; glisser un groupe sur la drop : l'y épingler.
 */
@Composable
fun MontagePlanContent(constraints: MontageConstraints, plan: MontagePlanState, actions: UiActions, modifier: Modifier = Modifier) {
    var dragged by remember { mutableStateOf<PlanGroup?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var dropZone by remember { mutableStateOf(Rect.Zero) }
    fun dragHandlers(group: PlanGroup, origin: () -> Offset) = Modifier.pointerInput(group.key) {
        detectDragGestures(
            onDragStart = { at -> dragged = group; pointer = origin() + at },
            onDrag = { change, amount -> change.consume(); pointer += amount },
            onDragEnd = {
                if (dropZone.contains(pointer)) actions.editMontageKill(group.refs.map { it.toString() }, KillEdit.DROP)
                dragged = null
            },
            onDragCancel = { dragged = null },
        )
    }

    // Surface : la fenêtre de la frise n'est pas sous celle de l'application ; sans couleur de contenu, les textes sans
    // couleur propre (titre, instant d'un groupe écarté) sortaient en noir sur le fond sombre.
    androidx.compose.material3.Surface(modifier, color = Palette.background, contentColor = Palette.text) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val view = plan.view
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        view?.let { tr("ui.montagePlan.heading", "track" to it.music, "bpm" to I18n.decimal(it.bpm, 0), "duration" to I18n.duration(it.duration), "clips" to it.clips.count { c -> c.group != null }) }
                            ?: tr("ui.montagePlan.title"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(tr("ui.montagePlan.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }
                TextButton(onClick = actions::resetMontagePlan, enabled = !constraints.isEmpty) { Text(tr("ui.montagePlan.reset")) }
                val pending = constraints != plan.planned
                Button(onClick = actions::replanMontage, enabled = !plan.loading && (pending || plan.error != null)) {
                    Text(if (pending) tr("ui.montagePlan.replanPending") else tr("ui.montagePlan.replan"))
                }
                OutlinedButton(onClick = actions::closeMontagePlan) { Text(tr("ui.common.close")) }
            }
            if (plan.loading) LinearProgressIndicator(progress = { plan.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            plan.error?.let { message ->
                Text(
                    tr("ui.montagePlan.impossible", "reason" to message),
                    color = Palette.danger,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().background(Palette.danger.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(10.dp),
                )
            }
            if (view == null) {
                if (!plan.loading) Text(tr("ui.montagePlan.empty"), color = Palette.textMuted)
                return@Column
            }
            Box(Modifier.alpha(if (plan.loading) 0.55f else 1f)) {
                Timeline(view, constraints, plan.thumbnails, dragged != null, actions, ::dragHandlers) { dropZone = it }
            }
            Text(tr("ui.montagePlan.left", "count" to view.left.size).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
            if (view.left.isEmpty()) {
                Text(tr("ui.montagePlan.noneLeft"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    view.left.forEach { g -> LeftCard(g, g.mark(constraints), plan.thumbnails[g.key], actions, ::dragHandlers) }
                }
            }
        }
        // Le groupe qu'on glisse suit le pointeur.
        dragged?.let { g ->
            val density = LocalDensity.current
            Text(
                tr("ui.montagePlan.dragging", "kill" to g.key),
                color = Palette.background,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.offset { IntOffset(pointer.x.roundToInt() + with(density) { 12.dp.roundToPx() }, pointer.y.roundToInt()) }
                    .background(Palette.accent, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
}

@Composable
private fun Timeline(
    view: PlanView,
    constraints: MontageConstraints,
    thumbnails: Map<String, Path>,
    dragging: Boolean,
    actions: UiActions,
    drag: (PlanGroup, () -> Offset) -> Modifier,
    onDropZone: (Rect) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        val total = view.duration.inWholeMicroseconds.toDouble().coerceAtLeast(1.0)
        fun x(t: Duration) = width * (t.inWholeMicroseconds / total).toFloat()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Sections du morceau, nommées.
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                view.sections.forEach { s ->
                    Box(
                        Modifier.offset(x = x(s.start)).width(x(s.end) - x(s.start)).height(20.dp).padding(end = 1.dp)
                            .background(sectionColor(s.kind, s.intensity).copy(alpha = 0.35f)),
                    ) {
                        Text(
                            tr("ui.montagePlan.section.${s.kind.name.lowercase()}"),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            // Volume de chaque temps, coloré par section, et la drop.
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val barWidth = (w / view.beats.size.coerceAtLeast(1) * 0.7f).coerceAtLeast(1f)
                    view.beats.forEachIndexed { i, t ->
                        val px = (t.inWholeMicroseconds / total).toFloat() * w
                        val section = view.sections.lastOrNull { it.start <= t } ?: view.sections.firstOrNull()
                        val color = section?.let { sectionColor(it.kind, it.intensity) } ?: Palette.cyan
                        val bar = (view.energy[i].toFloat() * h).coerceAtLeast(2f)
                        drawRect(color, Offset(px, (h - bar) / 2), Size(barWidth, bar))
                    }
                }
                view.dropAt?.let { drop ->
                    // Zone où déposer un groupe pour l'épingler sur la drop.
                    Box(
                        Modifier.offset(x = x(drop) - 36.dp).width(72.dp).fillMaxSize()
                            .onGloballyPositioned { onDropZone(it.boundsInRoot()) }
                            .then(
                                if (dragging) Modifier.background(Palette.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                    .border(BorderStroke(2.dp, Palette.accent), RoundedCornerShape(6.dp))
                                else Modifier,
                            ),
                    )
                    Box(Modifier.offset(x = x(drop) - 1.dp).width(2.dp).fillMaxSize().background(Palette.accent))
                    Text(
                        tr("ui.montagePlan.drop"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Palette.background,
                        modifier = Modifier.offset(x = x(drop) + 4.dp).background(Palette.accent, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
                    )
                }
            }
            // Les plans, une vignette chacun, les kills en traits.
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                view.clips.forEachIndexed { i, clip ->
                    ClipBlock(i, clip, x(clip.start), x(clip.end) - x(clip.start), clip.group?.mark(constraints) ?: GroupMark.NONE, clip.group?.let { thumbnails[it.key] }, actions, drag, ::x)
                }
            }
            // Repères de temps.
            Box(Modifier.fillMaxWidth().height(14.dp)) {
                val step = listOf(5, 10, 15, 30).map { it.toDouble() }.firstOrNull { view.duration.inWholeSeconds / it <= 10 } ?: 60.0
                var t = 0.0
                while (t * 1e6 <= total) {
                    Text(
                        clock(t.seconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.textMuted,
                        modifier = Modifier.offset(x = width * (t * 1e6 / total).toFloat()),
                    )
                    t += step
                }
            }
        }
    }
}

@Composable
private fun ClipBlock(
    index: Int,
    clip: PlanClip,
    left: androidx.compose.ui.unit.Dp,
    width: androidx.compose.ui.unit.Dp,
    mark: GroupMark,
    thumbnail: Path?,
    actions: UiActions,
    drag: (PlanGroup, () -> Offset) -> Modifier,
    x: (Duration) -> androidx.compose.ui.unit.Dp,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val group = clip.group
    val shape = RoundedCornerShape(4.dp)
    val border = markColor(mark) ?: if (clip.drop) Palette.accent else Palette.outline
    Box(
        Modifier.offset(x = left).width(width).fillMaxSize().padding(end = 2.dp)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .clip(shape)
            .background(Palette.surfaceHigh)
            .border(BorderStroke(if (mark != GroupMark.NONE || clip.drop) 2.dp else 1.dp, border), shape)
            .then(
                if (group == null) Modifier
                else drag(group, { origin }).clickable {
                    // Clic : écarter le groupe, ou annuler la correction qu'il porte.
                    actions.editMontageKill(group.refs.map { it.toString() }, if (mark == GroupMark.NONE) KillEdit.EXCLUDE else KillEdit.CLEAR)
                },
            ),
    ) {
        val image = rememberImage(thumbnail)
        if (image != null) {
            Image(image, contentDescription = group?.key, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(if (mark == GroupMark.EXCLUDED) 0.3f else 0.85f))
        }
        if (mark == GroupMark.EXCLUDED) Box(Modifier.fillMaxSize().background(Palette.danger.copy(alpha = 0.25f)))
        // Marges réduites et numéro sur une ligne : en rythme rapide (40 plans), « 12 » passait à la ligne en « 1 / 2 ».
        Column(Modifier.padding(2.dp)) {
            Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Palette.text,
                maxLines = 1, softWrap = false,
                modifier = Modifier.background(Palette.background.copy(alpha = 0.7f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp))
            if (group == null) {
                Text(tr("ui.montagePlan.showcase"), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
            } else if (group.kills.size > 1) {
                Text(tr("ui.montagePlan.multi", "n" to group.kills.size), style = MaterialTheme.typography.labelSmall, color = Palette.cyan, fontWeight = FontWeight.Bold)
            }
            markLabel(mark)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.background, maxLines = 1,
                    modifier = Modifier.background(markColor(mark) ?: Palette.accent, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
            }
        }
    }
    // Les kills montrés, en traits : un clic écarte ce kill seul.
    if (group != null) {
        clip.kills.forEach { kill ->
            Box(
                Modifier.offset(x = x(kill.at) - 5.dp, y = 84.dp).size(10.dp, 18.dp)
                    .clickable { actions.editMontageKill(listOf(kill.ref.toString()), KillEdit.EXCLUDE) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(3.dp).height(18.dp).background(Palette.cyan))
            }
        }
    }
}

@Composable
private fun LeftCard(group: PlanGroup, mark: GroupMark, thumbnail: Path?, actions: UiActions, drag: (PlanGroup, () -> Offset) -> Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.width(150.dp)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .clip(shape)
            .background(Palette.surface)
            .border(BorderStroke(1.dp, markColor(mark) ?: Palette.outline), shape)
            .then(drag(group) { origin })
            // Clic : forcer le groupe dans le montage ; écarté par l'utilisateur ou déjà forcé : annuler.
            .clickable { actions.editMontageKill(group.refs.map { it.toString() }, if (mark == GroupMark.NONE) KillEdit.FORCE else KillEdit.CLEAR) }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp).background(Palette.surfaceHigh, RoundedCornerShape(4.dp))) {
            rememberImage(thumbnail)?.let {
                Image(it, contentDescription = group.key, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).alpha(if (mark == GroupMark.EXCLUDED) 0.35f else 1f))
            }
        }
        Text(group.capture, style = MaterialTheme.typography.labelSmall, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(KillRef.clock(group.kills.last()), style = MaterialTheme.typography.bodySmall, color = Palette.text)
            if (group.kills.size > 1) Text(tr("ui.montagePlan.multi", "n" to group.kills.size), style = MaterialTheme.typography.labelSmall, color = Palette.cyan)
            Spacer(Modifier.weight(1f))
        }
        Text(
            markLabel(mark) ?: tr("ui.montagePlan.leftByPlan"),
            style = MaterialTheme.typography.labelSmall,
            color = markColor(mark) ?: Palette.textMuted,
            // Deux lignes : « Ausgelassen · Klick zum Erzwingen » ne tient pas sur une carte de 150 dp.
            maxLines = 2,
            minLines = 2,
        )
    }
}
