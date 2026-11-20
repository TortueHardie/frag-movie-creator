package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.KillStyle
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.serialization.Durations
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Un kill désigné par l'utilisateur : le nom de fichier de sa capture et son instant dans la capture,
 * « partie.mp4@3:12.450 ». Lisible et stable d'un plan à l'autre, contrairement à une place dans le montage. Une
 * désignation vise **un seul** kill : le plus proche de son instant dans sa capture ([MontageEdits.designated]), à
 * [TOLERANCE] au plus (une désignation tapée à la main n'a pas besoin d'être exacte : le recalage sur le son du tir
 * déplace les kills de 0,6 s au plus). Deux kills recalés à 0,1 s l'un de l'autre restent deux désignations distinctes :
 * avant, chacune les attrapait tous les deux, et « écarter ce kill seul » en écartait deux (QA finale).
 */
data class KillRef(val capture: String, val at: Duration) {
    fun matches(media: MediaInfo, kill: Duration): Boolean =
        media.path.fileName.toString().equals(capture, ignoreCase = true) && (kill - at).absoluteValue <= TOLERANCE

    /** Le même kill exactement (à la milliseconde, la précision de [toString]) : ce que la frise relit de ses désignations. */
    fun exactly(media: MediaInfo, kill: Duration): Boolean =
        media.path.fileName.toString().equals(capture, ignoreCase = true) && (kill - at).absoluteValue < EXACT

    /** « capture@3:12.450 » : à la milliseconde, pour qu'une désignation relue vise le même kill qu'à l'écriture. */
    override fun toString() = "$capture@${precise(at)}"

    companion object {
        /** Écart toléré entre l'instant écrit et celui du kill (même marge que [dev.highlights.montage.KillInspector] et la recherche). */
        val TOLERANCE = 1.seconds

        /** Écart en deçà duquel deux instants sont le même (arrondi de [toString] à la milliseconde). */
        private val EXACT = 1.5.milliseconds

        /** « 3:12.450 ». */
        fun precise(d: Duration): String {
            val ms = d.inWholeMilliseconds.coerceAtLeast(0)
            return String.format(Locale.ROOT, "%d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)
        }

        fun of(media: MediaInfo, kill: Duration) = KillRef(media.path.fileName.toString(), kill)

        /** « capture@instant » : instant en « 3:12.4 », « 192.4 » ou « 3m12.4s ». */
        fun parse(text: String): KillRef {
            val at = text.lastIndexOf('@')
            val time = if (at > 0) Durations.parseOrNull(text.substring(at + 1)) else null
            if (time == null || at <= 0) throw InputException(Message("error.montage.edit.badKill", "text" to text))
            return KillRef(text.substring(0, at).trim(), time)
        }

        /** « 3:12.4 » : à la seconde près, plus le dixième qui sépare deux kills rapprochés. */
        fun clock(d: Duration): String {
            val tenths = (d.inWholeMilliseconds + 50) / 100
            return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
        }
    }
}

/** Place imposée à un groupe : sur la drop, ou son kill d'ancrage sur le temps le plus proche de [At.music]. */
sealed interface MontagePin {
    data object Drop : MontagePin

    /** [music] : instant dans le morceau (pas dans le montage), arrondi au temps le plus proche. */
    data class At(val music: Duration) : MontagePin

    companion object {
        /** « drop » ou un instant du morceau. */
        fun parse(text: String): MontagePin? =
            if (text.trim().equals("drop", ignoreCase = true)) Drop else Durations.parseOrNull(text)?.let(::At)
    }
}

/**
 * Corrections de l'utilisateur sur le plan automatique : kills [excluded] (retirés de leur groupe, le groupe disparaît
 * s'il n'en reste aucun), groupes [forced] (gardés même si le plan les aurait écartés), groupes épinglés ([pins]). Un
 * groupe forcé ou épinglé est désigné par n'importe lequel de ses kills.
 *
 * Calage à l'image : [anchors], l'instant de la capture où le kill a vraiment lieu, choisi image par image (il remplace
 * celui de la détection et du recalage, c'est lui qui tombe sur le temps) ; [beatShifts], le kill posé un peu après (+)
 * ou avant (−) son temps dans le montage, quand le temps détecté de la musique est lui-même un peu décalé. Les coupes
 * restent sur les temps : la longueur de chaque plan ne bouge pas. Un décalage de temps désigne le groupe par n'importe
 * lequel de ses kills. Ces deux réglages s'ajoutent aux autres corrections (un kill épinglé peut aussi être calé).
 */
data class MontageConstraints(
    val excluded: Set<KillRef> = emptySet(),
    val forced: Set<KillRef> = emptySet(),
    val pins: Map<KillRef, MontagePin> = emptyMap(),
    val anchors: Map<KillRef, Duration> = emptyMap(),
    val beatShifts: Map<KillRef, Duration> = emptyMap(),
    /** « Couper ici » : début et fin du plan d'un kill dans sa capture ([ClipCut]). */
    val cuts: Map<KillRef, ClipCut> = emptyMap(),
    /** Sections de capture marquées à la main : sans kill, ou à garder entières ([CaptureRange]). */
    val ranges: List<CaptureRange> = emptyList(),
    /**
     * « Montage sur la musique », kills posés ([KillLayout]) : TOUS les kills du montage, chacun (désigné par le dernier
     * kill de son groupe) à son instant du morceau ; les plans se construisent ensuite autour. Null : placement
     * automatique.
     */
    val layout: Map<KillRef, Duration>? = null,
) {
    val isEmpty: Boolean get() = excluded.isEmpty() && forced.isEmpty() && pins.isEmpty() && anchors.isEmpty() && beatShifts.isEmpty() && cuts.isEmpty() && ranges.isEmpty() && layout == null

    /** Nombre de corrections posées (ce que la ligne du dialogue de montage annonce). */
    val count: Int get() = excluded.size + forced.size + pins.size + anchors.size + beatShifts.size + cuts.size + ranges.size + (layout?.size ?: 0)

    /** Début ([start]) ou fin ([end]) du plan du kill [ref] posés à la main ; les bornes déjà posées restent. */
    fun cut(ref: KillRef, start: Duration? = null, end: Duration? = null): MontageConstraints {
        val next = (cuts[ref] ?: ClipCut()).with(ClipCut(start, end))
        return copy(cuts = cuts - ref + (ref to next))
    }

    /** Retire le début ([start]) ou la fin du plan du kill [ref] posés à la main. */
    fun clearCut(ref: KillRef, start: Boolean): MontageConstraints {
        val c = cuts[ref] ?: return this
        val next = if (start) c.copy(start = null) else c.copy(end = null)
        return copy(cuts = if (next.isEmpty) cuts - ref else cuts - ref + (ref to next))
    }

    fun addRange(range: CaptureRange) = copy(ranges = ranges.filterNot { it == range } + range)

    fun removeRange(index: Int) = if (index in ranges.indices) copy(ranges = ranges.filterIndexed { i, _ -> i != index }) else this

    /** Le kill n'a plus ni exclusion, ni forçage, ni épingle (avant de lui en donner une autre : elles s'excluent). Son calage reste. */
    fun clear(ref: KillRef) = copy(excluded = excluded - ref, forced = forced - ref, pins = pins - ref)

    /** Un kill écarté perd aussi son calage : il ne serait plus montré. */
    fun exclude(ref: KillRef) = clear(ref).clearTiming(ref).let { it.copy(excluded = it.excluded + ref) }

    fun force(ref: KillRef) = clear(ref).let { it.copy(forced = it.forced + ref) }

    /** Une seule drop : l'épingler ailleurs la retire au groupe qui l'avait. */
    fun pin(ref: KillRef, pin: MontagePin): MontageConstraints {
        val others = if (pin == MontagePin.Drop) pins.filterValues { it != MontagePin.Drop } else pins
        return clear(ref).copy(pins = others - ref + (ref to pin))
    }

    /** Le kill [ref] a lieu à [at] dans sa capture (voir [anchors]). */
    fun anchor(ref: KillRef, at: Duration) = copy(anchors = anchors - ref + (ref to at))

    /** Kill de [ref] posé à [shift] de son temps (voir [beatShifts]) ; zéro retire le décalage. */
    fun shiftBeat(ref: KillRef, shift: Duration) =
        copy(beatShifts = if (shift == Duration.ZERO) beatShifts - ref else beatShifts - ref + (ref to shift))

    /** Retire le calage du kill (instant choisi et décalage du temps) : la détection reprend la main. */
    fun clearTiming(ref: KillRef) = copy(anchors = anchors - ref, beatShifts = beatShifts - ref)

    companion object {
        val NONE = MontageConstraints()
    }
}

/** Applique les [MontageConstraints] aux groupes avant la planification, puis vérifie que le plan les respecte. */
object MontageEdits {
    /**
     * Retire les kills exclus et marque les groupes forcés ou épinglés. Une contrainte qui ne désigne aucun kill, ou deux
     * contraintes qui se contredisent, arrêtent tout : un montage qui ignore une correction en silence ferait croire
     * qu'elle a été appliquée.
     */
    fun apply(groups: List<KillGroup>, constraints: MontageConstraints, style: KillStyle): List<KillGroup> {
        if (constraints.isEmpty) return groups
        val all = constraints.excluded + constraints.forced + constraints.pins.keys + constraints.anchors.keys + constraints.beatShifts.keys + constraints.cuts.keys + constraints.layout?.keys.orEmpty()
        // Chaque désignation vise un seul kill, le plus proche : deux kills recalés à 0,1 s ne sont pas écartés ensemble.
        val target = all.associateWith { designated(it, groups) }
        target.entries.firstOrNull { it.value == null }?.let {
            throw InputException(Message("error.montage.edit.notFound", "kill" to it.key.toString()))
        }
        fun hit(refs: Collection<KillRef>, g: KillGroup, kill: Duration) = refs.any { r -> target[r]?.let { (p, k) -> p == g.media.path && k == kill } == true }
        (constraints.excluded + constraints.forced + constraints.pins.keys).firstOrNull { ref -> listOf(ref in constraints.excluded, ref in constraints.forced, ref in constraints.pins).count { it } > 1 }?.let {
            throw InputException(Message("error.montage.edit.conflict", "kill" to it.toString()))
        }
        if (constraints.pins.values.count { it == MontagePin.Drop } > 1) throw InputException(Message("error.montage.edit.twoDrops"))
        // Un calage loin de la détection désigne sans doute un autre instant que le kill : mieux vaut le dire.
        constraints.anchors.entries.firstOrNull { (ref, at) -> target[ref]?.let { (_, k) -> (at - k).absoluteValue > MAX_ANCHOR } == true }?.let {
            throw InputException(Message("error.montage.edit.anchorFar", "kill" to it.key.toString(), "time" to KillRef.precise(it.value)))
        }
        constraints.beatShifts.entries.firstOrNull { it.value.absoluteValue > MAX_BEAT_SHIFT }?.let {
            throw InputException(Message("error.montage.edit.beatShiftFar", "kill" to it.key.toString(), "ms" to it.value.inWholeMilliseconds))
        }
        // Une coupe doit laisser le kill dans son plan : début avant lui, fin après.
        constraints.cuts.entries.firstOrNull { (ref, c) ->
            target[ref]?.let { (_, k) -> (c.start != null && c.start >= k) || (c.end != null && c.end <= k) || (c.start != null && c.end != null && c.end <= c.start) } == true
        }?.let { throw InputException(Message("error.montage.edit.cutAroundKill", "kill" to it.key.toString())) }
        fun rangesOf(g: KillGroup, kind: RangeKind) = constraints.ranges.filter { it.kind == kind && it.matches(g.media) }.map { it.range }
        val layout = constraints.layout
        return groups.mapNotNull { g ->
            // Kills posés sur la musique : seuls les groupes posés restent, chacun épinglé à son instant.
            val laid = layout?.entries?.firstOrNull { (ref, _) -> g.kills.any { hit(listOf(ref), g, it) } }?.value
            if (layout != null && laid == null) return@mapNotNull null
            val skips = rangesOf(g, RangeKind.SKIP)
            // Un kill détecté dans une section sans kill est écarté, comme un kill exclu.
            val kept = g.kills.indices.filter { i -> !hit(constraints.excluded, g, g.kills[i]) && skips.none { g.kills[i] in it } }
            if (kept.isEmpty()) return@mapNotNull null
            val trimmed = when {
                kept.size == g.kills.size -> g
                g.traits.isEmpty() -> g.copy(kills = kept.map { g.kills[it] })
                else -> MontagePlanner.withTraits(g, kept.map { g.kills[it] }, kept.map { g.traits[it] }, style)
            }
            fun designates(ref: KillRef) = trimmed.kills.any { hit(listOf(ref), g, it) }
            val pins = constraints.pins.filterKeys(::designates).values.distinct()
            if (pins.size > 1) throw InputException(Message("error.montage.edit.conflict", "kill" to label(trimmed)))
            val shifts = constraints.beatShifts.filterKeys(::designates).values.distinct()
            if (shifts.size > 1) throw InputException(Message("error.montage.edit.conflict", "kill" to label(trimmed)))
            val clipCuts = constraints.cuts.filterKeys(::designates).values.distinct()
            if (clipCuts.size > 1) throw InputException(Message("error.montage.edit.conflict", "kill" to label(trimmed)))
            val marked = trimmed.copy(
                forced = constraints.forced.any(::designates) || laid != null, pin = laid?.let { MontagePin.At(it) } ?: pins.firstOrNull(), beatShift = shifts.firstOrNull() ?: trimmed.beatShift,
                cut = clipCuts.firstOrNull(), skips = skips, keeps = rangesOf(g, RangeKind.KEEP),
            )
            anchored(marked, g, constraints, ::hit)
        }
    }

    /**
     * Les kills calés à la main prennent l'instant choisi (dans la capture), ordre des kills et particularités suivant ;
     * l'instant d'avant est gardé ([KillGroup.retimed]) : la frise désigne toujours le kill par lui, et un décalage appris
     * ne touche jamais un kill calé à la main.
     */
    private fun anchored(
        group: KillGroup,
        original: KillGroup,
        constraints: MontageConstraints,
        hit: (Collection<KillRef>, KillGroup, Duration) -> Boolean,
    ): KillGroup {
        if (constraints.anchors.isEmpty()) return group
        val moved = group.kills.associateWith { k -> constraints.anchors.entries.firstOrNull { (r, _) -> hit(listOf(r), original, k) }?.value }
        if (moved.values.all { it == null }) return group
        val bounds = group.media.bounds
        val pairs = group.kills.mapIndexed { i, k ->
            val at = moved[k]?.coerceIn(bounds.start, bounds.end) ?: k
            Triple(at, group.traits.getOrNull(i), k)
        }.sortedBy { it.first }
        val kept = pairs.map { it.first }.toSet()
        val retimed = group.retimed + pairs.filter { moved[it.third] != null }.associate { (at, _, k) -> at to KillRetime(group.detectedOf(k), byHand = true) }
        return group.copy(
            kills = pairs.map { it.first },
            traits = if (group.traits.isEmpty()) group.traits else pairs.map { it.second ?: KillTraits.NONE },
            retimed = retimed.filterKeys { it in kept },
        )
    }

    /** Écart au kill détecté au-delà duquel un calage est refusé : la frise propose ±0,5 s, la désignation tolère 1 s. */
    val MAX_ANCHOR = 1.seconds

    /** Écart permis entre l'instant d'une épingle et celui où le kill tombe (arrondis des instants). */
    val PIN_TOLERANCE = 2.milliseconds

    /** Décalage du temps au-delà duquel c'est un autre temps qu'on vise (un temps fait 0,4 s à 150 BPM) : l'épingler plutôt. */
    val MAX_BEAT_SHIFT = 250.milliseconds

    /**
     * Le kill que vise [ref] : le plus proche de son instant parmi ceux de sa capture, à [KillRef.TOLERANCE] au plus ;
     * null s'il n'y en a pas. (capture, instant du kill).
     */
    fun designated(ref: KillRef, groups: List<KillGroup>): Pair<java.nio.file.Path, Duration>? =
        groups.flatMap { g -> g.kills.filter { ref.matches(g.media, it) }.map { g.media.path to it } }
            .minByOrNull { (_, k) -> (k - ref.at).absoluteValue }

    /** Désignation lisible d'un groupe : son dernier kill (celui qui tombe sur le temps). */
    fun label(g: KillGroup): String = KillRef.of(g.media, g.kills.last()).toString()

    /**
     * Le plan respecte-t-il les contraintes de [groups] ? Sinon une erreur qui dit laquelle et pourquoi, plutôt qu'un
     * montage qui l'ignore.
     */
    fun check(plan: MontagePlan, groups: List<KillGroup>) {
        val music = plan.music
        // La raison la plus parlante d'abord : pas de drop du tout, un temps hors du passage retenu, puis le groupe absent.
        for (g in groups.filter { it.forced || it.pin != null }) {
            val clip = plan.clips.firstOrNull { it.group === g }
            when (val pin = g.pin) {
                MontagePin.Drop -> {
                    if (plan.dropAt == null) throw HighlightsException(Message("error.montage.edit.noDrop", "kill" to label(g)))
                    if (clip?.slot?.dropBeat == null) throw HighlightsException(Message("error.montage.edit.dropMissed", "kill" to label(g)))
                }
                is MontagePin.At -> {
                    val beat = nearestBeat(music, pin.music)
                    if (beat !in plan.startBeat until plan.endBeat) {
                        throw HighlightsException(Message("error.montage.edit.pinOutside", "kill" to label(g), "time" to KillRef.clock(pin.music)))
                    }
                    if (clip == null || clip.anchorBeat != beat || clip.anchorHalf) {
                        throw HighlightsException(Message("error.montage.edit.pinMissed", "kill" to label(g), "time" to KillRef.clock(pin.music)))
                    }
                    // Le kill tombe à l'instant posé, pas seulement sur son temps : un plan qui le commence trop tard
                    // (le kill ne peut pas passer avant sa coupe) ne le tient pas.
                    if ((music.beatTime(beat) + clip.beatShift - pin.music).absoluteValue > PIN_TOLERANCE) {
                        throw HighlightsException(Message("error.montage.edit.pinMissed", "kill" to label(g), "time" to KillRef.precise(pin.music)))
                    }
                }
                null -> if (clip == null) throw HighlightsException(Message("error.montage.edit.notPlaced", "kill" to label(g)))
            }
        }
        // Repères (coupes, sections) que le plan retenu n'a pas pu tenir : la raison exacte, plutôt qu'une coupe silencieuse.
        plan.clips.firstNotNullOfOrNull { it.markerMiss }?.let { throw InputException(it) }
        // Décalage du temps posé à la main : un plan d'un temps (le kill l'ouvre) ne laisse pas le kill passer avant sa coupe.
        for (clip in plan.clips) {
            val wanted = clip.group.beatShift ?: continue
            if ((clip.beatShift - wanted).absoluteValue > 1.milliseconds) {
                throw HighlightsException(Message("error.montage.edit.beatShiftMissed", "kill" to label(clip.group), "ms" to wanted.inWholeMilliseconds))
            }
        }
    }

    /** Temps du morceau le plus proche de [t]. */
    fun nearestBeat(music: MusicAnalysis, t: Duration): Int =
        music.beats.indices.minByOrNull { (music.beats[it] - t).absoluteValue } ?: 0
}
