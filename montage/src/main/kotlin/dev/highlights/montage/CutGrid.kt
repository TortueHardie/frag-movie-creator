package dev.highlights.montage

import dev.highlights.core.model.CutSettings
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Emplacement d'un clip dans la musique : temps [startBeat] inclus à [endBeat] exclu, coupes sur les temps.
 * [dropBeat] : la drop tombe dans ce slot, le kill doit y atterrir.
 */
data class CutSlot(val startBeat: Int, val endBeat: Int, val section: Int, val dropBeat: Int? = null) {
    val beats: Int get() = endBeat - startBeat
}

/**
 * Grille de coupes dictée par la musique : chaque section est découpée en plans dont la longueur dépend de son
 * intensité (2, 4, 8 ou 16 temps), les frontières de sections sont toujours des coupes, et un plan spécial chevauche
 * la drop pour que le kill tombe dessus, la coupe suivant juste après.
 */
object CutGrid {
    /** Poids d'une drop qui arrive sans montée : plus que l'écart d'intensité d'une intro calme gardée (0,14 mesuré). */
    private const val DROP_LEAD_WEIGHT = 0.5

    /** Avance d'une fenêtre acceptée sur une refusée, bien au-delà des écarts de note entre échelles (au plus 2). */
    private const val REFUSED_GAP = 10.0

    /** Longueur de plan (puissance de 2 entre [minBeats] et [maxBeats]) la plus proche d'une durée visée. */
    fun beatsFor(target: Duration, period: Duration, minBeats: Int, maxBeats: Int): Int {
        val ratio = (target / period).coerceAtLeast(1.0)
        val power = 2.0.pow((ln(ratio) / ln(2.0)).roundToInt()).roundToInt()
        val floor = 2.0.pow(kotlin.math.ceil(ln(minBeats.toDouble()) / ln(2.0))).roundToInt()
        return power.coerceIn(floor, maxBeats).coerceAtLeast(minBeats)
    }

    fun sectionBeats(section: MusicSection, cuts: CutSettings, period: Duration, minBeats: Int, scale: Double): Int =
        beatsFor(cadence(section.intensity, cuts) * scale, period, minBeats, cuts.maxBeats)

    /**
     * Durée visée d'un plan pour une section d'intensité [intensity] (0 = la plus calme du morceau, 1 = la plus intense) :
     * de [CutSettings.low] à [CutSettings.mid] puis [CutSettings.high], en progression géométrique continue. Avec trois
     * paliers, une drop à 0,74 gardait des plans de section moyenne, et le rythme rapide les écrasait en un seul.
     */
    fun cadence(intensity: Double, cuts: CutSettings): Duration {
        val i = intensity.coerceIn(0.0, 1.0)
        fun between(a: Duration, b: Duration, t: Double) = (a.inWholeMicroseconds.toDouble().pow(1 - t) * b.inWholeMicroseconds.toDouble().pow(t)).toLong().microseconds
        return if (i <= 0.5) between(cuts.low, cuts.mid, i * 2) else between(cuts.mid, cuts.high, i * 2 - 1)
    }

    /** Grille sur toute la musique. [scale] multiplie les durées visées : grille plus grossière quand il y a peu de clips. */
    fun build(music: MusicAnalysis, cuts: CutSettings, minBeats: Int, scale: Double = 1.0): List<CutSlot> {
        val sections = music.sections
        val period = music.beatPeriod
        val lengths = sections.map { sectionBeats(it, cuts, period, minBeats, scale) }
        val dropSection = music.sectionIndexAt(music.dropBeat)
        val dropSlot = if (dropSection > 0 && sections[dropSection].startBeat == music.dropBeat) {
            val before = sections[dropSection - 1]
            val pre = lengths[dropSection - 1].coerceIn(4, 8).coerceAtMost(before.beats)
            val post = lengths[dropSection].coerceAtMost(8).coerceAtMost(sections[dropSection].beats)
            CutSlot(music.dropBeat - pre, music.dropBeat + post, dropSection, dropBeat = music.dropBeat)
        } else {
            null
        }

        val slots = mutableListOf<CutSlot>()
        val lead = leadBeats(cuts, period)
        sections.forEachIndexed { si, s ->
            val l = lengths[si]
            // Dans une montée, les plans raccourcissent au fur et à mesure : les coupes accélèrent avec la musique.
            val accelerate = cuts.accelerateBuildUp && s.kind == SectionKind.BUILD_UP
            // Une section qui a des notes marquantes (une phrase de piano, un riff) se découpe sur elles.
            fun onNotes(from: Int, to: Int) = tileOnNotes(slots, music, from, to, l, si, minBeats, lead)
            when {
                dropSlot != null && si == dropSection - 1 ->
                    if (!onNotes(s.startBeat, dropSlot.startBeat)) tileBackward(slots, s.startBeat, dropSlot.startBeat, l, si, minBeats, if (accelerate) cuts.maxBeats else null)
                dropSlot != null && si == dropSection -> {
                    slots += dropSlot
                    if (!onNotes(dropSlot.endBeat, s.endBeat)) tileForward(slots, dropSlot.endBeat, s.endBeat, l, si, minBeats)
                }
                onNotes(s.startBeat, s.endBeat) -> Unit
                accelerate -> tileAccelerating(slots, s.startBeat, s.endBeat, l, si, minBeats, cuts.maxBeats)
                else -> tileForward(slots, s.startBeat, s.endBeat, l, si, minBeats)
            }
        }
        return slots
    }

    /** Temps de contexte avant un kill ([CutSettings.minLead]), au moins un. */
    internal fun leadBeats(cuts: CutSettings, period: Duration): Int = kotlin.math.ceil(cuts.minLead / period).toInt().coerceAtLeast(1)

    /**
     * Découpe [from]..[to] à la cadence de la section ([length] temps par plan), chaque coupe calée sur une note qui
     * ressort ([MusicAnalysis.salience] au moins [MusicAnalyzer.SALIENT]) : la coupe se pose [lead] temps avant la note
     * la plus marquante à une demi-longueur de plan de la coupe régulière, de quoi voir le kill arriver, et le kill y
     * tombe (le choix du temps du kill préfère ces notes). Sans note à portée, la coupe régulière. La cadence vient de
     * l'intensité de la section, les notes ne font que placer les coupes : découpée sur ses notes seules, une drop où
     * quelques notes ressortent de loin en loin gardait un plan de 31 temps, un kill toutes les 3,8 s quand la musique
     * accélérait. Une intro au piano, elle, coupe sur sa phrase. Faux (rien n'est posé) si la section n'a aucune note.
     */
    internal fun tileOnNotes(
        slots: MutableList<CutSlot>, music: MusicAnalysis, from: Int, to: Int, length: Int, section: Int, minBeats: Int, lead: Int,
    ): Boolean {
        if (music.salience.isEmpty()) return false
        val cuts = (from + minBeats until to).filter { music.salience.getOrElse(it + lead) { 0.0 } >= MusicAnalyzer.SALIENT }
        if (cuts.isEmpty()) return false
        val reach = maxOf(1, length / 2)
        var k = from
        while (k < to) {
            val ideal = k + length
            val snapped = cuts.filter { it >= k + minBeats && it <= ideal + reach && it >= ideal - reach }
                .maxByOrNull { music.salience[it + lead] - abs(it - ideal).toDouble() / (2 * length) }
            var e = (snapped ?: ideal).coerceAtMost(to)
            // Reste trop court pour un plan : absorbé par le dernier plan.
            if (to - e in 1 until minBeats) e = to
            slots += CutSlot(k, e, section)
            k = e
        }
        return true
    }

    private fun tileForward(slots: MutableList<CutSlot>, from: Int, to: Int, length: Int, section: Int, minBeats: Int) {
        var k = from
        while (k < to) {
            var e = minOf(k + length, to)
            // Reste trop court pour un plan : absorbé par le dernier plan.
            if (to - e in 1 until minBeats) e = to
            if (e - k < minBeats && slots.isNotEmpty() && slots.last().endBeat == k && slots.last().dropBeat == null) {
                val last = slots.removeLast()
                slots += last.copy(endBeat = e)
            } else {
                slots += CutSlot(k, e, section)
            }
            k = e
        }
    }

    /**
     * Comme [tileForward], mais les plans raccourcissent : on part du double de la longueur nominale et on divise par
     * deux à la moitié de la section, puis aux trois quarts. Les longueurs restent des puissances de deux, donc les
     * coupes restent sur les mesures ; descendre en dessous de [minBeats] les rendrait trop courts pour un kill.
     */
    private fun tileAccelerating(slots: MutableList<CutSlot>, from: Int, to: Int, length: Int, section: Int, minBeats: Int, maxBeats: Int) {
        val span = to - from
        val start = minOf(length * 2, maxBeats)
        if (span < 2 * start) return tileForward(slots, from, to, length, section, minBeats)
        var k = from
        var len = start
        var halved = 0
        while (k < to) {
            var e = minOf(k + len, to)
            if (to - e in 1 until minBeats) e = to
            slots += CutSlot(k, e, section)
            k = e
            val wanted = when {
                (k - from) * 4 >= span * 3 -> 2
                (k - from) * 2 >= span -> 1
                else -> 0
            }
            while (halved < wanted) {
                if (len / 2 >= minBeats) len /= 2
                halved++
            }
        }
    }

    private fun tileBackward(
        slots: MutableList<CutSlot>,
        from: Int,
        to: Int,
        length: Int,
        section: Int,
        minBeats: Int,
        /** Non nul dans une montée : les plans accélèrent jusqu'au slot de la drop, donc on pose vers l'avant. */
        accelerateTo: Int? = null,
    ) {
        if (accelerateTo != null) return tileAccelerating(slots, from, to, length, section, minBeats, accelerateTo)
        val tiles = mutableListOf<CutSlot>()
        var k = to
        while (k > from) {
            var s = maxOf(k - length, from)
            if (s - from in 1 until minBeats) s = from
            tiles += CutSlot(s, k, section)
            k = s
        }
        tiles.reverse()
        if (tiles.isNotEmpty() && tiles.first().beats < minBeats && slots.isNotEmpty() && slots.last().endBeat == tiles.first().startBeat) {
            val last = slots.removeLast()
            slots += last.copy(endBeat = tiles.first().endBeat)
            tiles.removeAt(0)
        }
        slots += tiles
    }

    /** Grille retenue : facteur d'échelle, grille complète et fenêtre du montage. */
    data class Selection(val scale: Double, val slots: List<CutSlot>, val window: List<CutSlot>)

    /**
     * Choisit l'échelle de la grille (×1, ×2, ×4, ×8) d'après le nombre de clips disponibles : on cherche à utiliser tous
     * les clips (coupes plus rapides) avec une durée proche de [target] (par défaut [maxDuration], qui reste le plafond
     * dans tous les cas). Une durée visée courte garde les plans courts au lieu d'étirer peu de clips sur toute la
     * musique. [scales] restreint les échelles essayées (variantes de plan). [accept] : voir [window].
     */
    fun select(
        music: MusicAnalysis,
        cuts: CutSettings,
        minBeats: Int,
        maxDuration: Duration,
        clipCount: Int,
        scales: List<Double>? = null,
        target: Duration = maxDuration,
        accept: (List<CutSlot>) -> Boolean = { true },
    ): Selection {
        var best: Selection? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (scale in scales ?: listOf(1.0, 2.0, 4.0, 8.0)) {
            val slots = build(music, cuts, minBeats, scale)
            val window = window(slots, music, maxDuration, clipCount, cuts.dropPosition, target, cuts.fromStart, cuts.dropLead, accept)
            if (window.isNotEmpty()) {
                val length = music.beatTime(window.last().endBeat) - music.beatTime(window.first().startBeat)
                // Une fenêtre que [accept] refuse ne sert que faute de mieux, à toutes les échelles.
                val score = 1.5 * window.size / clipCount + 0.5 * closeness(length, target) + (if (accept(window)) REFUSED_GAP else 0.0)
                if (score > bestScore + 1e-9) {
                    bestScore = score
                    best = Selection(scale, slots, window)
                }
            }
        }
        return best ?: Selection(1.0, emptyList(), emptyList())
    }

    /** 1 quand [length] vaut [target], 0 à partir du double (ou de zéro) ; plus court n'est pas mieux que plus long. */
    private fun closeness(length: Duration, target: Duration): Double =
        (1.0 - (length - target).absoluteValue / target).coerceIn(0.0, 1.0)

    /**
     * Fenêtre du montage : suite de slots contigus, d'au plus [maxDuration] et [maxSlots], la mieux notée : sections
     * intenses, drop à la position voulue, début et fin sur une frontière de section, durée proche de [target].
     * [fromStart] : la fenêtre commence au premier slot, le premier temps de la musique. [dropLead] : montée minimale
     * avant la drop, au plus [dropPosition] de la fenêtre. [accept] : la mieux notée des fenêtres qu'il accepte, la mieux
     * notée tout court s'il n'en accepte aucune (montage chronologique : celles où le meilleur groupe tombe sur la drop).
     */
    fun window(
        slots: List<CutSlot>,
        music: MusicAnalysis,
        maxDuration: Duration,
        maxSlots: Int,
        dropPosition: Double,
        target: Duration = maxDuration,
        fromStart: Boolean = false,
        dropLead: Duration = Duration.ZERO,
        accept: (List<CutSlot>) -> Boolean = { true },
    ): List<CutSlot> {
        if (slots.isEmpty() || maxSlots <= 0) return emptyList()
        val sectionStarts = music.sections.map { it.startBeat }.toSet()
        val dropIndex = slots.indexOfFirst { it.dropBeat != null }
        val maxSeconds = maxDuration.inWholeMicroseconds / 1e6
        val leadSeconds = dropLead.inWholeMicroseconds / 1e6
        fun time(beat: Int) = music.beatTime(beat).inWholeMicroseconds / 1e6

        val candidates = mutableListOf<Pair<IntRange, Double>>()
        for (i in if (fromStart) 0..0 else slots.indices) {
            val startTime = time(slots[i].startBeat)
            var j = i
            while (j < slots.size && j - i < maxSlots && time(slots[j].endBeat) - startTime <= maxSeconds + 1e-6) j++
            if (j == i) continue
            val run = i until j
            val beats = run.sumOf { slots[it].beats }
            val intensity = run.sumOf { music.sections[slots[it].section].intensity * slots[it].beats } / beats
            val length = time(slots[j - 1].endBeat) - startTime
            var score = intensity + 0.05 * closeness(length.seconds, target)
            if (dropIndex in run) {
                val lead = time(music.dropBeat) - startTime
                score += 1.0 - 0.6 * abs(lead / length - dropPosition)
                // Une drop sans montée : plus pénalisée que l'intro calme qu'il faudrait garder pour y mener.
                val needed = minOf(leadSeconds, dropPosition * length)
                if (needed > 0 && lead < needed) score -= DROP_LEAD_WEIGHT * (1 - lead / needed)
            }
            if (slots[i].startBeat in sectionStarts) score += 0.15
            // Ouvrir sur une intro ou une montée donne au montage la rampe qui mène à la drop.
            if (music.sections[slots[i].section].kind in setOf(SectionKind.INTRO, SectionKind.BUILD_UP)) score += 0.12
            if (j == slots.size || slots[j].startBeat in sectionStarts) score += 0.1
            candidates += run to score
        }
        // À égalité, la première : le tri est stable.
        val ranked = candidates.sortedByDescending { it.second }.map { (run, _) -> run.map { slots[it] } }
        return ranked.firstOrNull(accept) ?: ranked.firstOrNull() ?: emptyList()
    }
}
