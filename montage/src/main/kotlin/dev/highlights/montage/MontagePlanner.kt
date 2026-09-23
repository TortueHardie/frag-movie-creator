package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.KillStyle
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.TimeRange
import dev.highlights.core.session.Session
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/** Kills rapprochés d'une même partie (un clip). Les instants sont dans la vidéo source, décalage du tir appliqué. */
data class KillGroup(
    val media: MediaInfo,
    val kills: List<Duration>,
    /** Meilleur score de la timeline autour des kills : départage les clips de même nombre de kills. */
    val score: Double,
    /** Segments à ne pas couper (réactions), dans la vidéo source. */
    val protectedSegments: List<TimeRange>,
    /** Réactions audibles (voix, rires) à mettre en avant au mixage, dans la vidéo source. */
    val voiceSegments: List<TimeRange>,
    /** Ce qu'on sait de chaque kill (même ordre que [kills]) ; vide : rien de particulier. */
    val traits: List<KillTraits> = emptyList(),
    /** Bonus de spectacle des kills (tête, flick, enchaînement), en kills : voir [KillStyle]. */
    val style: Double = 0.0,
) {
    val rank: Double get() = kills.size + score / 10 + style
    val span: Duration get() = kills.last() - kills.first()

    fun traitsOf(kill: Duration): KillTraits = traits.getOrNull(kills.indexOf(kill)) ?: KillTraits.NONE
}

/**
 * Particularités d'un kill : tir à la tête (événement du jeu), [flick] de 0 (visée posée) à 1 (balayage franc juste
 * avant le kill), [shift] : correction appliquée à son instant par le recalage sur le son du tir.
 */
data class KillTraits(val headshot: Boolean = false, val flick: Double = 0.0, val shift: Duration = Duration.ZERO) {
    companion object {
        val NONE = KillTraits()

        /** Flick assez net pour faire d'un plan un plan fort (ralenti, pour qu'on le voie). */
        const val STRONG_FLICK = 0.5
    }
}

/** Origine d'un changement de vitesse : ralenti du kill d'ancrage, ou rampe qui ramène un kill sur un temps. */
enum class SpeedKind { SLOW, RAMP }

/** Portion de la source jouée à une vitesse différente de 1 : 0,5 = ralenti ×2, 1,1 = légèrement accéléré (speed ramp). */
data class SpeedSegment(val range: TimeRange, val factor: Double, val kind: SpeedKind = SpeedKind.RAMP) {
    /** Durée de sortie de la portion. */
    val outputLength: Duration get() = range.length / factor
}

/**
 * Un clip du montage, taillé pour remplir exactement son [slot] musical : [start]/[end] est l'extrait de la source,
 * le kill d'ancrage ([anchor], dernier kill du groupe) tombe sur le temps [anchorBeat] ; [speeds] : ralenti autour du
 * kill et rampes de vitesse entre les kills d'un multi-kill (chaque kill sur un temps) ;
 * [padBefore]/[padAfter] : gel d'image quand la vidéo source ne couvre pas tout le slot.
 */
data class MontageClip(
    val group: KillGroup,
    val slot: CutSlot,
    val anchorBeat: Int,
    val anchor: Duration,
    val start: Duration,
    val end: Duration,
    val padBefore: Duration,
    val padAfter: Duration,
    val speeds: List<SpeedSegment>,
    val outputLength: Duration,
) {
    val beats: Int get() = slot.beats
    val beatsPre: Int get() = anchorBeat - slot.startBeat
    val beatsPost: Int get() = slot.endBeat - anchorBeat
    val sourceLength: Duration get() = end - start
    /** Kills visibles dans l'extrait (les premiers d'un multi-kill peuvent être coupés quand le slot est court). */
    val kills: List<Duration> get() = group.kills.filter { it >= start && it <= end }
    /** Un kill visible est un flick franc. */
    val flick: Boolean get() = kills.any { group.traitsOf(it).flick >= KillTraits.STRONG_FLICK }
    val rank: Double get() = group.rank
    /** Paliers du ralenti du kill d'ancrage : décélération avant le kill, puis vitesse pleine du ralenti. */
    val slowSteps: List<SpeedSegment> get() = speeds.filter { it.kind == SpeedKind.SLOW }
    /** Palier le plus lent du ralenti (celui qui porte le kill), s'il a été gardé. */
    val slow: SpeedSegment? get() = slowSteps.lastOrNull()
    val ramps: List<SpeedSegment> get() = speeds.filter { it.kind == SpeedKind.RAMP }

    /** Instant de sortie (relatif au début du clip) d'un instant de la source. */
    fun toOutput(t: Duration): Duration {
        var out = padBefore
        var pos = start
        for (seg in speeds) {
            if (t <= seg.range.start) return out + (t - pos)
            out += seg.range.start - pos
            if (t <= seg.range.end) return out + (t - seg.range.start) / seg.factor
            out += seg.outputLength
            pos = seg.range.end
        }
        return out + (t - pos)
    }

    fun outputKills(): List<Duration> = kills.map(::toOutput)
}

/** Variante d'un plan : échelle de grille imposée (null : choisie par [CutGrid.select]) et décalage de la place de la drop. */
data class PlanVariant(val scale: Double? = null, val dropShift: Double = 0.0) {
    override fun toString() = "grille ×${scale?.let { "%.0f".format(it) } ?: "auto"}, drop ${"%+.2f".format(dropShift)}"

    companion object {
        val BASE = PlanVariant()
    }
}

data class MontagePlan(
    val clips: List<MontageClip>,
    val music: MusicAnalysis,
    val settings: MontageSettings,
    val variant: PlanVariant = PlanVariant.BASE,
) {
    init {
        require(clips.isNotEmpty()) { "montage vide" }
        clips.zipWithNext().forEach { (a, b) -> require(a.slot.endBeat == b.slot.startBeat) { "slots non contigus : ${a.slot} puis ${b.slot}" } }
    }

    val startBeat: Int get() = clips.first().slot.startBeat
    val endBeat: Int get() = clips.last().slot.endBeat
    val period: Duration get() = music.beatPeriod
    val totalBeats: Int get() = endBeat - startBeat
    val musicStart: Duration get() = music.beatTime(startBeat)
    val duration: Duration get() = music.beatTime(endBeat) - musicStart
    /** Instant de la drop dans le montage, si elle en fait partie. */
    val dropAt: Duration? get() = music.dropBeat.takeIf { it in startBeat until endBeat }?.let { music.beatTime(it) - musicStart }

    /** Décalage de sortie de chaque clip dans le montage : sur les temps réels de la musique, sans dérive de tempo. */
    fun clipOffsets(): List<Duration> = clips.map { music.beatTime(it.slot.startBeat) - musicStart }
}

/**
 * Planifie le montage : la musique dicte la grille de coupes ([CutGrid]), les clips sont étendus ou coupés pour remplir
 * exactement leur slot, le meilleur groupe atterrit sur la drop, les multi-kills fusionnent des slots voisins.
 */
object MontagePlanner {
    /** Écart minimal entre deux kills pour tenter une rampe de vitesse. */
    private val MIN_RAMP_GAP = 200.milliseconds

    /** Groupes gardés malgré le plancher de qualité, pour ne jamais rendre un montage vide. */
    private const val MIN_KEPT = 3

    /** Recul d'importance d'un slot dont un voisin porte déjà un clip qui se ressemble. */
    private const val MONOTONY_PENALTY = 0.5

    /** Écart maximal entre un kill et l'événement « tir à la tête » qui l'accompagne. */
    private val HEADSHOT_MATCH = 250.milliseconds

    /**
     * Prix d'un changement de vitesse dans une rampe, en force de frappe : 10 % de vitesse en plus ou en moins valent
     * 0,3 d'accent. Une frappe franchement plus forte justifie d'aller la chercher un peu plus loin.
     */
    private const val RAMP_COST = 3.0

    /** Un contretemps vaut un peu moins qu'un temps de même force : le temps reste le repère du spectateur. */
    private const val OFF_BEAT_DISCOUNT = 0.85

    /**
     * Variantes : écarts essayés sur la place de la drop, et part minimale des clips du plan de base qu'une variante
     * doit garder (une grille plus lâche serait sinon mieux notée en se contentant de moins de clips).
     */
    private val DROP_SHIFTS = listOf(-0.15, 0.0, 0.15)
    private val SCALES = listOf(1.0, 2.0, 4.0, 8.0)
    private const val MIN_CLIP_SHARE = 0.85

    /** Gain de note en dessous duquel le plan de base est gardé : on ne change pas pour du bruit. */
    private const val MIN_GAIN = 0.005

    /** Regroupe les kills de chaque session (multi-kills) avec leurs segments de réaction. */
    fun groups(sessions: List<Session>, settings: MontageSettings): List<KillGroup> = sessions.flatMap { session ->
        val timeline = session.timeline
        val bounds = session.media.bounds
        val kills = timeline.events.filter { it.kind == settings.killEvent }.map { (it.at + settings.killOffset).coerceIn(bounds.start, bounds.end) }.sorted()
        // Tirs à la tête : l'événement du jeu tombe au même instant que le kill (même retard de notification).
        val style = settings.killStyle
        val headshots = if (style.headshotEvent.isEmpty()) emptyList()
        else timeline.events.filter { it.kind == style.headshotEvent }.map { it.at + settings.killOffset }
        fun headshot(k: Duration) = headshots.any { (it - k).absoluteValue <= HEADSHOT_MATCH }
        val grouped = mutableListOf<MutableList<Duration>>()
        for (k in kills) {
            val last = grouped.lastOrNull()
            if (last != null && k - last.last() <= settings.mergeGap) last += k else grouped += mutableListOf(k)
        }
        // Sans mise en avant des réactions, la voix ne décide de rien : ni durée des plans, ni volume, ni débordement.
        val protectedSegments = if (settings.reactions) timeline.segments.filter { it.kind in settings.keepWhole }.map { it.range } else emptyList()
        val voice = if (settings.reactions) timeline.segments.filter { it.kind in setOf("speech", "laughter", "shout") }.map { it.range } else emptyList()
        grouped.map { ks ->
            val window = TimeRange(ks.first() - settings.preRoll, ks.last() + settings.postRoll)
            val indices = timeline.grid.let { g -> (0 until g.count).filter { g.rangeOf(it).isWithin(window) } }
            val traits = ks.map { KillTraits(headshot = headshot(it)) }
            KillGroup(
                media = session.media,
                kills = ks,
                score = indices.maxOfOrNull { timeline.total[it] } ?: 0.0,
                protectedSegments = protectedSegments.filter { it.isWithin(window, settings.postRoll * 4) },
                voiceSegments = voice.filter { it.isWithin(window, settings.postRoll * 4) },
                traits = traits,
                style = style(ks, traits, style),
            )
        }
    }

    /** Bonus de spectacle d'un groupe, en kills : tirs à la tête, flicks, kills enchaînés. */
    fun style(kills: List<Duration>, traits: List<KillTraits>, style: KillStyle): Double =
        traits.sumOf { (if (it.headshot) style.headshotBonus else 0.0) + style.flickBonus * it.flick } +
            style.quickBonus * kills.zipWithNext().count { (a, b) -> b - a <= style.quickGap }

    /** Même groupe, kills recalés et particularités mises à jour (voir [KillInspector]) ; le bonus est recalculé. */
    fun withTraits(group: KillGroup, kills: List<Duration>, traits: List<KillTraits>, style: KillStyle): KillGroup {
        val sorted = kills.zip(traits).sortedBy { it.first }
        return group.copy(kills = sorted.map { it.first }, traits = sorted.map { it.second }, style = style(sorted.map { it.first }, sorted.map { it.second }, style))
    }

    /**
     * Meilleur plan parmi des variantes (échelle de la grille, place de la drop), d'après [MontageScorer]. Sans
     * [MontageSettings.variants], le plan de base. Une variante doit garder presque tous les clips du plan de base et le
     * battre nettement : sinon le plan de base, dont les choix sont les plus prévisibles, reste.
     */
    fun best(all: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings): MontagePlan {
        val base = plan(all, music, settings)
        if (!settings.variants) return base
        val baseScore = MontageScorer.score(base).total
        val minClips = ceil(base.clips.size * MIN_CLIP_SHARE).toInt()
        val candidates = SCALES.flatMap { scale -> DROP_SHIFTS.map { PlanVariant(scale, it) } }.mapNotNull { variant ->
            runCatching { plan(all, music, settings, variant) }.getOrNull()
                ?.takeIf { it.clips.size >= minClips }
                ?.let { it to MontageScorer.score(it).total }
        }
        val (winner, score) = candidates.maxByOrNull { it.second } ?: return base
        if (score < baseScore + MIN_GAIN) {
            log.info { "Variantes : ${candidates.size} essayées, plan de base gardé (note ${"%.3f".format(baseScore)})" }
            return base
        }
        log.info {
            "Variantes : ${candidates.size} essayées, retenue ${winner.variant} (note ${"%.3f".format(score)} contre ${"%.3f".format(baseScore)}, " +
                "${winner.clips.size} clips contre ${base.clips.size})"
        }
        return winner
    }

    fun plan(all: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings, variant: PlanVariant = PlanVariant.BASE): MontagePlan {
        if (all.isEmpty()) throw HighlightsException("Aucun kill à monter : lance l'analyse avec un profil qui détecte les kills")
        // Plancher de qualité : mieux vaut un montage plus court qu'un plan sans intérêt. Les meilleurs passent toujours.
        val groups = if (settings.minScore > 0.0) {
            all.filter { it.score >= settings.minScore }
                .ifEmpty { all.sortedByDescending { it.rank }.take(MIN_KEPT) }
                .also { if (it.size < all.size) log.info { "Plancher de qualité : ${all.size - it.size} groupe(s) écarté(s) sur ${all.size}" } }
        } else {
            all
        }
        val cuts = settings.cuts.let { it.copy(dropPosition = (it.dropPosition + variant.dropShift).coerceIn(0.0, 1.0)) }
        val period = music.beatPeriod
        val minLeadBeats = beatsCeil(cuts.minLead, period).coerceAtLeast(1)
        val minTailBeats = beatsCeil(cuts.minTail, period).coerceAtLeast(1)
        val minBeats = maxOf(2, minLeadBeats + minTailBeats)

        // Grille : plus grossière quand il y a moins de clips que de plans dans la durée demandée.
        val (scale, _, window) = CutGrid.select(music, cuts, minBeats, settings.maxDuration, groups.size, variant.scale?.let(::listOf))
        if (window.isEmpty()) throw HighlightsException("Musique trop courte pour un seul clip (${music.duration.inWholeSeconds} s)")

        val cells = assign(window, groups, music, settings, minLeadBeats, minTailBeats)
        val preferred = preferredOffsets(cells.map { it.first }, music, minLeadBeats, minTailBeats)
        val clips = cells.mapIndexed { i, cell ->
            val offset = preferred[cell.first.section to cell.first.beats]
            // Le plan sans ralenti d'abord : c'est lui qui dit combien de kills seront réellement à l'écran, donc si le
            // plan mérite le ralenti. Un multi-kill dont tout le début serait coupé n'en est pas un pour le spectateur.
            val plain = clipFor(cell, music, settings, minLeadBeats, minTailBeats, offset, allowSlow = false)
            if (allowsSlow(settings, i, cell.first, plain.kills.size, plain.flick)) {
                clipFor(cell, music, settings, minLeadBeats, minTailBeats, offset, allowSlow = true)
            } else {
                plain
            }
        }
        // Les variantes essayées par [best] ne méritent pas une ligne chacune : seul le plan de base est détaillé.
        val describe = {
            "Grille ×${"%.0f".format(scale)} : ${window.size} slots, ${clips.size} clips, ${clips.sumOf { it.beats }} temps, " +
                clips.joinToString(" ") { "${it.beats}${if (it.slot.dropBeat != null) "*" else ""}" }
        }
        if (variant == PlanVariant.BASE) log.info(describe) else log.debug(describe)
        return MontagePlan(clips, music, settings, variant)
    }

    private class Cell(var startBeat: Int, var endBeat: Int, val section: Int, val dropBeat: Int?) {
        var group: KillGroup? = null
        val beats: Int get() = endBeat - startBeat
        fun toSlot() = CutSlot(startBeat, endBeat, section, dropBeat)
    }

    /** Attribue un groupe à chaque slot ; les multi-kills et réactions fusionnent des slots libres voisins. */
    private fun assign(
        window: List<CutSlot>,
        groups: List<KillGroup>,
        music: MusicAnalysis,
        settings: MontageSettings,
        minLeadBeats: Int,
        minTailBeats: Int,
    ): List<Pair<CutSlot, KillGroup>> {
        val cuts = settings.cuts
        val period = music.beatPeriod
        val cells = window.map { Cell(it.startBeat, it.endBeat, it.section, it.dropBeat) }.toMutableList()
        val slowTail = if (settings.slowMotion.enabled) settings.slowMotion.after / settings.slowMotion.factor else Duration.ZERO

        /** Temps nécessaires pour montrer tous les kills du groupe (et finir une réaction) sans couper. */
        fun need(g: KillGroup): Int {
            val reaction = g.protectedSegments.filter { it.end > g.kills.last() }.maxOfOrNull { it.end - g.kills.last() } ?: Duration.ZERO
            val post = maxOf(minTailBeats, beatsCeil(maxOf(reaction + cuts.minTail, slowTail + cuts.minTail), period))
            return beatsCeil(g.span + cuts.minLead, period).coerceAtLeast(minLeadBeats) + post
        }

        fun importance(c: Cell) = (if (c.dropBeat != null) 10.0 else 0.0) + music.sections[c.section].intensity + 1e-4 * c.startBeat

        /**
         * Recul d'un slot pour ce groupe : ses voisins déjà pourvus viennent-ils de la même capture, au même moment de
         * la partie ? Deux plans consécutifs du même endroit se ressemblent et cassent l'impression de variété.
         */
        fun monotony(index: Int, g: KillGroup): Double {
            if (!settings.varietyGap.isPositive()) return 0.0
            val similar = listOf(index - 1, index + 1).count { i ->
                val neighbour = cells.getOrNull(i)?.group ?: return@count false
                neighbour.media.path == g.media.path && (neighbour.kills.first() - g.kills.first()).absoluteValue < settings.varietyGap
            }
            return MONOTONY_PENALTY * similar
        }

        /** Meilleur slot libre pour un groupe : le plus important, à variété égale. */
        fun bestFree(g: KillGroup): Int? =
            cells.indices.filter { cells[it].group == null }.maxByOrNull { importance(cells[it]) - monotony(it, g) }

        /**
         * Fusionne des cellules libres voisines jusqu'à [needed] temps, sans dépasser maxBeats : les suivantes (le clip
         * s'allonge après le kill), ou d'abord les précédentes pour la drop (les kills d'un multi-kill la précèdent).
         */
        fun grow(index: Int, needed: Int): Int {
            var i = index
            val cell = cells[i]
            val directions = if (cell.dropBeat != null) listOf(-1, 1) else listOf(1)
            for (dir in directions) {
                while (cell.beats < needed) {
                    val next = i + dir
                    if (next !in cells.indices || cells[next].group != null || cells[next].dropBeat != null) break
                    if (cell.beats + cells[next].beats > cuts.maxBeats) break
                    if (dir < 0) cell.startBeat = cells[next].startBeat else cell.endBeat = cells[next].endBeat
                    cells.removeAt(next)
                    if (dir < 0) i--
                }
            }
            return i
        }

        /** Vrai si la cellule peut atteindre [needed] temps en fusionnant des cellules libres. */
        fun canGrow(index: Int, needed: Int): Boolean {
            val cell = cells[index]
            if (cell.beats >= needed) return true
            var total = cell.beats
            for (dir in if (cell.dropBeat != null) listOf(-1, 1) else listOf(1)) {
                var next = index + dir
                while (next in cells.indices && cells[next].group == null && cells[next].dropBeat == null && total + cells[next].beats <= cuts.maxBeats) {
                    total += cells[next].beats
                    if (total >= needed) return true
                    next += dir
                }
            }
            return false
        }

        fun place(index: Int, g: KillGroup) {
            val i = grow(index, need(g))
            cells[i].group = g
        }

        when (settings.order) {
            MontageOrder.BUILD_UP -> {
                val remaining = groups.sortedWith(compareByDescending<KillGroup> { it.rank }.thenBy { it.kills.first() }).toMutableList()
                val dropIndex = cells.indexOfFirst { it.dropBeat != null }
                if (dropIndex >= 0) place(dropIndex, remaining.removeAt(0))
                // Accroche : le meilleur groupe restant ouvre le montage, là où le spectateur décide de rester.
                if (settings.hook && cells.size >= 3 && remaining.size >= 2 && cells[0].group == null) {
                    place(0, remaining.removeAt(0))
                }
                // Pas de traitement particulier pour le dernier plan : l'importance croît avec la position dans la
                // section, si bien que les meilleurs groupes restants finissent déjà le montage.
                // Multi-kills : le slot le plus important qui peut les contenir entièrement.
                for (g in remaining.filter { it.kills.size > 1 }) {
                    val free = cells.indices.filter { cells[it].group == null }.sortedByDescending { importance(cells[it]) - monotony(it, g) }
                    val index = free.firstOrNull { canGrow(it, need(g)) } ?: free.firstOrNull() ?: break
                    place(index, g)
                    remaining -= g
                }
                remaining.removeAll { it.kills.size > 1 }
                for (g in remaining) {
                    place(bestFree(g) ?: break, g)
                }
            }
            MontageOrder.CHRONOLOGICAL -> {
                val kept = groups.sortedByDescending { it.rank }.take(cells.size)
                // Les parties dans l'ordre où elles ont été jouées, pas dans celui des noms de fichiers.
                val ordered = kept.sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { g: KillGroup -> g.media }.thenBy { it.kills.first() })
                var i = 0
                for (g in ordered) {
                    if (i >= cells.size) break
                    i = grow(i, need(g))
                    cells[i].group = g
                    i++
                }
            }
        }

        // Slots restés vides (plus de clips) : absorbés par le voisin.
        var i = 0
        while (i < cells.size) {
            if (cells[i].group == null) {
                if (i > 0) {
                    cells[i - 1].endBeat = cells[i].endBeat
                    cells.removeAt(i)
                    continue
                } else if (cells.size > 1) {
                    cells[1].startBeat = cells[0].startBeat
                    cells.removeAt(0)
                    continue
                }
            }
            i++
        }
        if (cells.isEmpty() || cells.any { it.group == null }) throw HighlightsException("Aucun clip ne tient dans la musique")
        return cells.map { it.toSlot() to it.group!! }
    }

    /**
     * Plan « fort » : celui de la drop, un multi-kill, un flick, ou l'accroche qui ouvre le montage. Ce sont les seuls
     * à mériter un ralenti au rythme normal ; les autres reçoivent un zoom, et jamais les deux. Un flick passe trop vite
     * pour être vu à vitesse normale : c'est le plan qui a le plus besoin du ralenti.
     */
    internal fun isStrong(index: Int, slot: CutSlot, visibleKills: Int, flick: Boolean = false): Boolean =
        slot.dropBeat != null || visibleKills > 1 || index == 0 || flick

    /** Le plan a-t-il droit au ralenti, vu la quantité d'effets demandée ? */
    internal fun allowsSlow(settings: MontageSettings, index: Int, slot: CutSlot, visibleKills: Int, flick: Boolean = false): Boolean =
        when (settings.effectDensity) {
            EffectDensity.SOBER -> slot.dropBeat != null
            EffectDensity.BALANCED -> isStrong(index, slot, visibleKills, flick)
            EffectDensity.HEAVY -> true
        }

    /** Score musical d'un temps d'ancrage dans un slot : attaque, premier temps de mesure, un peu tard dans le plan. */
    private fun anchorScore(music: MusicAnalysis, slot: CutSlot, beat: Int): Double =
        music.beatAccent[beat.coerceIn(0, music.beatAccent.lastIndex)] +
            (if (music.isDownbeat(beat)) 0.4 else 0.0) +
            0.15 * (beat - slot.startBeat).toDouble() / slot.beats

    /**
     * Position du kill commune à tous les plans de même longueur d'une section : des coupes qui se suivent avec le même
     * timing donnent de l'élan (le kill tombe toujours au même endroit du plan).
     */
    internal fun preferredOffsets(slots: List<CutSlot>, music: MusicAnalysis, minLeadBeats: Int, minTailBeats: Int): Map<Pair<Int, Int>, Int> =
        slots.filter { it.dropBeat == null }.groupBy { it.section to it.beats }.mapNotNull { (key, group) ->
            val offsets = minLeadBeats..(key.second - minTailBeats)
            if (offsets.isEmpty()) return@mapNotNull null
            key to offsets.maxBy { offset -> group.sumOf { anchorScore(music, it, it.startBeat + offset) } }
        }.toMap()

    /**
     * Taille un clip pour remplir exactement son slot : le kill d'ancrage tombe sur le temps le plus accentué du slot
     * (ou sur la drop), le ralenti n'est gardé que s'il tient, les kills précédents sont ramenés sur un temps par une
     * légère rampe de vitesse, la source est étendue ou coupée, gelée si elle manque.
     */
    internal fun clipFor(
        assignment: Pair<CutSlot, KillGroup>,
        music: MusicAnalysis,
        settings: MontageSettings,
        minLeadBeats: Int,
        minTailBeats: Int,
        preferredOffset: Int? = null,
        allowSlow: Boolean = true,
    ): MontageClip {
        val (slot, group) = assignment
        val media = group.media
        val cuts = settings.cuts
        val slotStart = music.beatTime(slot.startBeat)
        val slotEnd = music.beatTime(slot.endBeat)
        val anchor = group.kills.last()

        // Réaction (phrase, rire) après le dernier kill : le clip doit durer jusqu'à sa fin.
        val reaction = group.protectedSegments.filter { it.end > anchor }.maxOfOrNull { it.end - anchor } ?: Duration.ZERO
        val candidates = (slot.startBeat + minLeadBeats)..(slot.endBeat - minTailBeats)
        fun fits(b: Int) = music.beatTime(b) - slotStart >= group.span + cuts.minLead && slotEnd - music.beatTime(b) >= reaction + cuts.minTail
        val preferred = preferredOffset?.let { slot.startBeat + it }
        val anchorBeat = when {
            slot.dropBeat != null && slot.dropBeat in candidates -> slot.dropBeat
            candidates.isEmpty() -> slot.startBeat + maxOf(1, slot.beats / 2)
            preferred != null && preferred in candidates && fits(preferred) -> preferred
            else -> candidates.maxBy { b ->
                val lead = music.beatTime(b) - slotStart
                val tail = slotEnd - music.beatTime(b)
                anchorScore(music, slot, b) +
                    (if (lead >= group.span + cuts.minLead) 0.3 else 0.0) +
                    (if (tail >= reaction + cuts.minTail) 0.3 else 0.0)
            }
        }
        val anchorTime = music.beatTime(anchorBeat)
        val preOut = anchorTime - slotStart
        val postOut = slotEnd - anchorTime

        // Ralenti autour du kill d'ancrage, seulement s'il tient dans le slot : la vitesse descend par paliers avant le
        // kill, et le plein régime revient sur un temps (la relance tombe avec la musique).
        val slowSettings = settings.slowMotion
        val speeds = mutableListOf<SpeedSegment>()
        var sb = Duration.ZERO
        var sa = Duration.ZERO
        var outBefore = Duration.ZERO
        var outAfter = Duration.ZERO
        if (slowSettings.enabled && allowSlow) {
            val f = slowSettings.factor
            val before = minOf(slowSettings.before, anchor - media.bounds.start).coerceAtLeast(Duration.ZERO)
            val maxAfter = minOf(slowSettings.after, media.duration - anchor).coerceAtLeast(Duration.ZERO)
            // Le plus long ralenti dont la sortie finit sur un temps, sans dépasser le budget ni déborder du slot.
            val snapped = if (slowSettings.snapToBeat) {
                (1..(slot.endBeat - anchorBeat)).lastOrNull { k ->
                    val out = music.beatTime(anchorBeat + k) - anchorTime
                    out * f <= maxAfter && postOut - out >= cuts.minTail
                }?.let { (music.beatTime(anchorBeat + it) - anchorTime) * f }
            } else {
                null
            }
            val after = snapped ?: maxAfter
            val steps = slowCurve(anchor, before, after, f, slowSettings.rampSteps)
            val pre = steps.fold(Duration.ZERO) { acc, s ->
                acc + (minOf(s.range.end, anchor) - s.range.start).coerceAtLeast(Duration.ZERO) / s.factor
            }
            if (steps.isNotEmpty() && preOut >= pre + cuts.minLead && postOut >= after / f + cuts.minTail) {
                speeds += steps
                sb = before
                sa = after
                outBefore = pre
                outAfter = after / f
            }
        }

        // Sans rampe : début de la source, kills visibles.
        val plainStart = anchor - sb - (preOut - outBefore)
        val visible = group.kills.filter { it >= plainStart }

        // Rampes de vitesse : chaque kill visible précédent est ramené sur le temps le plus proche (vitesse ±maxChange).
        var firstOut = preOut
        val ramp = settings.speedRamp
        if (ramp.enabled && visible.size > 1) {
            var nextKill = anchor
            var nextOut = preOut
            for (j in visible.size - 2 downTo 0) {
                val k = visible[j]
                val last = j == visible.size - 2
                val segEnd = if (last) anchor - sb else nextKill
                val segEndOut = if (last) nextOut - outBefore else nextOut
                val gap = segEnd - k
                val wanted = segEndOut - gap
                var out = wanted
                if (gap >= MIN_RAMP_GAP) {
                    // Frappes à portée d'une rampe : la plus forte, au moindre changement de vitesse. Sans les
                    // contretemps, simplement le temps le plus proche.
                    val hits = if (ramp.onHits) music.hits(slot.startBeat, anchorBeat)
                    else music.hits(slot.startBeat, anchorBeat).filter { it.onBeat }
                    val best = hits.mapNotNull { hit ->
                        val at = hit.at - slotStart
                        if (at < cuts.minLead / 2 || at >= segEndOut) return@mapNotNull null
                        val factor = gap / (segEndOut - at)
                        if (abs(factor - 1) > ramp.maxChange) return@mapNotNull null
                        val value = if (ramp.onHits) hit.strength * (if (hit.onBeat) 1.0 else OFF_BEAT_DISCOUNT) - RAMP_COST * abs(factor - 1)
                        else -abs(factor - 1)
                        Triple(at, factor, value)
                    }.maxByOrNull { it.third }
                    if (best != null) {
                        val (at, factor) = best
                        if (abs(factor - 1) > 1e-3) speeds += SpeedSegment(TimeRange(k, segEnd), factor)
                        out = at
                    }
                }
                nextKill = k
                nextOut = out
            }
            firstOut = nextOut
        }
        speeds.sortBy { it.range.start }

        var start = (if (ramp.enabled && visible.size > 1) visible.first() - firstOut else plainStart)
        var end = anchor + sa + (postOut - outAfter)
        val padBefore = (media.bounds.start - start).coerceAtLeast(Duration.ZERO)
        val padAfter = (end - media.duration).coerceAtLeast(Duration.ZERO)
        start = start.coerceAtLeast(media.bounds.start)
        end = end.coerceAtMost(media.duration)
        return MontageClip(group, slot, anchorBeat, anchor, start, end, padBefore, padAfter, speeds, slotEnd - slotStart)
    }

    /**
     * Paliers du ralenti : la vitesse descend de 1 vers [factor] sur [before] (durée source, juste avant le kill), puis
     * reste au plus lent sur [after]. Un seul palier redonne l'ancien changement de vitesse net. Les paliers voisins de
     * même vitesse sont fusionnés : le dernier de la descente est déjà au plus lent.
     */
    internal fun slowCurve(anchor: Duration, before: Duration, after: Duration, factor: Double, steps: Int): List<SpeedSegment> {
        val raw = mutableListOf<SpeedSegment>()
        val n = steps.coerceAtLeast(1)
        if (before.isPositive()) {
            val slice = before / n
            for (i in 0 until n) {
                val f = 1.0 - (1.0 - factor) * (i + 1) / n
                raw += SpeedSegment(TimeRange(anchor - before + slice * i, anchor - before + slice * (i + 1)), f, SpeedKind.SLOW)
            }
        }
        if (after.isPositive()) raw += SpeedSegment(TimeRange(anchor, anchor + after), factor, SpeedKind.SLOW)
        val merged = mutableListOf<SpeedSegment>()
        for (seg in raw) {
            val last = merged.lastOrNull()
            if (last != null && abs(last.factor - seg.factor) < 1e-9 && last.range.end == seg.range.start) {
                merged[merged.lastIndex] = last.copy(range = TimeRange(last.range.start, seg.range.end))
            } else {
                merged += seg
            }
        }
        return merged.filter { it.range.length.isPositive() && abs(it.factor - 1.0) > 1e-9 }
    }

    private fun beatsCeil(d: Duration, period: Duration): Int = ceil((d / period) - 1e-9).toInt().coerceAtLeast(0)
}
