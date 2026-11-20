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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.time.Duration.Companion.milliseconds
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
    /** Calage à l'image du kill ouvert d'un clic sur son trait ([KillTimingPanel]) ; null : aucun. */
    val timing: KillTimingUi? = null,
    /** Outil de la drop du morceau, ouvert d'un clic sur la marque « DROP » ([MusicDropPanel]) ; null : fermé. */
    val drop: MusicDropUi? = null,
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

/**
 * Épingle le groupe [kills] (désigné par son dernier kill, celui qui tombe sur le temps) sur le temps du morceau le plus
 * proche de [music] ([MontagePin.At] : instant dans le morceau, pas dans le montage, pour qu'un plan recalculé sur un
 * autre passage le garde au même endroit de la musique). Ses autres corrections sont retirées.
 */
fun MontageConstraints.pinAt(kills: List<KillRef>, music: Duration): MontageConstraints {
    if (kills.isEmpty()) return this
    return kills.fold(this) { c, k -> c.clear(k) }.pin(kills.last(), MontagePin.At(music))
}

/** Épingle sur un temps posée par l'utilisateur : [music] dans le morceau ; [at] dans le montage, null hors du passage retenu. */
data class PinMark(val ref: KillRef, val music: Duration, val at: Duration?)

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

/**
 * Kill montré : son instant dans le montage, sa désignation (instant détecté), [source] son instant dans la capture tel
 * que le plan l'utilise ; [retimed] : déplacé depuis la détection, à la main ([byHand]) ou par un décalage appris.
 */
data class PlanKill(val at: Duration, val ref: KillRef, val source: Duration = ref.at, val retimed: Boolean = false, val byHand: Boolean = false)

/**
 * Plan placé sur la musique : [start]/[end] dans le montage, [kills] ceux qu'il montre ; [group] null : plan vitrine ;
 * [beatShift] : décalage de son kill par rapport au temps (calage à la main ou appris pour la musique).
 */
data class PlanClip(val group: PlanGroup?, val start: Duration, val end: Duration, val kills: List<PlanKill>, val drop: Boolean, val beatShift: Duration = Duration.ZERO)

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
    /** Fichier de la musique (celle choisie dans un dossier), pour l'outil de la drop. */
    val musicPath: java.nio.file.Path? = null,
    /** La drop de ce plan a été posée à la main ([dev.highlights.montage.MusicDrops]). */
    val dropByHand: Boolean = false,
    /** La drop de ce plan vient de ce qui a été appris des drops posées sur d'autres morceaux. */
    val dropLearned: Boolean = false,
    /** Repères proposés pour l'étape 1 de « Montage sur la musique » (plan automatique). */
    val proposal: dev.highlights.montage.MusicIntents? = null,
) {
    /** Temps de la frise le plus proche de [t] (instant du montage) ; null sans temps. */
    fun beatNear(t: Duration): Int? = beats.indices.minByOrNull { (beats[it] - t).absoluteValue }

    /** Instant dans le morceau du temps [beat] de la frise : ce que [MontagePin.At] attend. */
    fun musicAt(beat: Int): Duration = musicStart + beats[beat]

    /**
     * Les épingles sur un temps de [c], placées sur le temps le plus proche de la frise (le planificateur arrondit de
     * même) ; une épingle hors du passage retenu (plan pas encore recalculé, ou impossible) garde son instant du morceau.
     */
    fun pinMarks(c: MontageConstraints): List<PinMark> = c.pins.mapNotNull { (ref, pin) ->
        val music = (pin as? MontagePin.At)?.music ?: return@mapNotNull null
        val t = music - musicStart
        val inside = beats.isNotEmpty() && t >= beats.first() - HALF_BEAT_MARGIN && t <= duration
        // À l'instant posé (le kill y tombe exactement, même entre deux temps).
        PinMark(ref, music, if (inside) t else null)
    }.sortedBy { it.music }

    companion object {
        /** Marge avant le premier temps : une épingle arrondie à lui reste dans la frise. */
        private val HALF_BEAT_MARGIN = 250.milliseconds

        fun of(planned: PlannedMontage): PlanView {
            val plan = planned.plan
            val music = plan.music
            val start = plan.musicStart
            val range = plan.startBeat until plan.endBeat
            val rawEnergy = range.map { music.beatEnergy.getOrElse(it) { 0.0 } }
            val top = rawEnergy.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            // Groupe d'avant les corrections : celui que l'utilisateur reconnaît (kills exclus compris).
            // Par l'instant détecté : un kill calé (à la main ou appris) n'a plus l'instant du candidat.
            fun detected(g: KillGroup) = g.kills.map(g::detectedOf)
            fun candidate(g: KillGroup) = planned.candidates.firstOrNull { c -> c.media.path == g.media.path && detected(g).any { it in c.kills } } ?: g
            val clips = plan.clips.zip(plan.clipOffsets()).map { (c, offset) ->
                val end = offset + (music.beatTime(c.slot.endBeat) - music.beatTime(c.slot.startBeat))
                if (c.showcase) PlanClip(null, offset, end, emptyList(), false)
                else PlanClip(
                    PlanGroup.of(candidate(c.group)), offset, end,
                    c.kills.map { k ->
                        val retime = c.group.retimed[k]
                        PlanKill(offset + c.toOutput(k), KillRef.of(c.group.media, c.group.detectedOf(k)), k, retime != null, retime?.byHand == true)
                    },
                    c.slot.dropBeat != null, c.beatShift,
                )
            }
            val placed = plan.clips.filterNot { it.showcase }.map { it.group.media.path to detected(it.group) }
            val left = planned.candidates.filter { g -> placed.none { (path, kills) -> path == g.media.path && kills.any { it in g.kills } } }
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
                musicPath = music.file,
                dropByHand = music.dropByHand != null,
                dropLearned = music.dropLearned != null,
                proposal = planned.proposal,
            )
        }
    }
}

/** Repères posés (bornes de plans, sections), chacun retiré d'un clic (le plan est recalculé sans lui). */
@Composable
internal fun MarkerChips(constraints: MontageConstraints, actions: UiActions) {
    val cuts = constraints.cuts.entries.flatMap { (ref, c) -> listOfNotNull(c.start?.let { Triple(ref, true, it) }, c.end?.let { Triple(ref, false, it) }) }
    if (cuts.isEmpty() && constraints.ranges.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("ui.montagePlan.markersHint"), style = MaterialTheme.typography.labelSmall, color = Palette.textMuted)
        cuts.forEach { (ref, start, at) ->
            Chip(tr(if (start) "ui.montagePlan.cutStartChip" else "ui.montagePlan.cutEndChip", "time" to KillRef.precise(at), "kill" to ref.toString()), Palette.accent) {
                actions.clearKillCut(ref.toString(), start)
            }
        }
        constraints.ranges.forEachIndexed { i, r ->
            val skip = r.kind == dev.highlights.montage.RangeKind.SKIP
            Chip(tr(if (skip) "ui.montagePlan.skipChip" else "ui.montagePlan.keepChip", "range" to r.toString()), if (skip) Palette.danger else Palette.cyan) {
                actions.removeMontageRange(i)
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, color), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
