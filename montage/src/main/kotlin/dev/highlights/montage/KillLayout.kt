package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.MontageSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * « Montage sur la musique », étape 1 puis 2 : l'utilisateur a posé chaque kill sur un instant du morceau
 * ([MontageConstraints.layout]) ; les plans se construisent ENSUITE autour de ces instants, sans jamais en refuser un
 * (demandes du 20/11 : l'algorithme place les plans en conséquence ; deux kills proches donnent un plan court ou un
 * seul plan, jamais une erreur, et chaque kill reste à son instant).
 */
object KillLayout {
    /**
     * Plan dont chaque groupe de [groups] (épinglé à son instant, [KillGroup.pin]) tombe exactement à son instant :
     * - deux kills de la même capture, plus proches qu'un temps, dont l'écart dans la partie correspond à peu près à
     *   celui sur la musique ([MERGE_TOLERANCE]) : un seul plan qui montre les deux (comme un multi-kill) ;
     * - deux kills trop proches pour couper sur un temps en laissant au second son approche ([CutSettings.minLead]) et
     *   au premier sa fin ([CutSettings.minTail]) : la coupe tombe entre deux temps, [LEAD_SHARE] de l'écart avant le
     *   second (au plus son approche, au moins [MIN_SHOWN] après le premier) ; la grille du plan reçoit pour cela un
     *   temps de coupe à cet instant ([withCuts]), le rendu suit la grille. Chaque kill garde son instant exact ;
     * - sinon, chaque kill porte le temps le plus proche, et entre deux kills la coupe va sur le temps qui frappe le
     *   plus, en laissant au kill d'avant sa fin et au suivant son approche ;
     * - avant le premier kill, [LEAD_BEATS] temps (moins au début du morceau) ; après le dernier, [TAIL_BEATS] ;
     * - le plan de chaque kill se taille comme d'habitude ([MontagePlanner.clipFor]).
     */
    fun plan(groups: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings, intents: Intents? = null): MontagePlan {
        val laid = groups.mapNotNull { g -> (g.pin as? MontagePin.At)?.let { g to it.music } }.sortedBy { it.second }
        if (laid.isEmpty() || music.beats.isEmpty()) throw HighlightsException(Message("error.montage.noKills"))
        val period = music.beatPeriod
        val placed = merged(laid, period)
        // Repères de l'étape 1 : coupes et bords des plages de vitrine (sans kill dedans) deviennent des temps de coupe.
        val fillerRanges = intents?.fillers.orEmpty().filter { (a, b) -> b > a && placed.none { (_, at) -> at > a && at < b } }
        val forced = (intents?.cuts.orEmpty() + fillerRanges.flatMap { listOf(it.first, it.second) }).filter { it >= Duration.ZERO }
            .map { c -> music.beats.firstOrNull { (it - c).absoluteValue <= SNAP_TO_BEAT } ?: c }
        val cuts = settings.cuts
        val leadBeats = beats(cuts.minLead, period)
        val tailBeats = beats(cuts.minTail, period).coerceAtLeast(1)
        // Temps de coupe à ajouter entre deux kills trop proches pour couper sur un temps en laissant au suivant son
        // approche ([CutSettings.minLead]) et au précédent sa fin ([CutSettings.minTail]) : la coupe tombe entre deux
        // temps, [LEAD_SHARE] de l'écart pour le kill suivant (au plus son approche), le reste pour le précédent. Avant
        // (20/11) la coupe tombait sur le temps du kill : sur une suite de kills à un temps d'écart, chaque plan
        // s'ouvrait sur son kill, qu'on ne voyait jamais venir.
        val extra = mutableMapOf<Int, Duration>()
        // Un plan court doit montrer le tir ET la chute de l'ennemi (demande du 20/11 : un plan d'un temps coupait entre
        // les deux). [KillTraits.shift] = instant recalé − instant détecté : positif (recalé sur la chute, cas mesuré :
        // +216 ms), le tir est |shift| AVANT le kill ; négatif (recalé sur le tir), l'ennemi tombe |shift| APRÈS.
        // Après le kill : la chute puis [DEATH_TAIL] ; avant : le tir puis [SHOT_LEAD].
        fun deathAfter(g: KillGroup): Duration = (-g.traitsOf(g.kills.last()).shift).coerceAtLeast(Duration.ZERO) + DEATH_TAIL
        fun shotBefore(g: KillGroup): Duration = g.traitsOf(g.kills.last()).shift.coerceAtLeast(Duration.ZERO) + SHOT_LEAD
        fun tailBeatsOf(g: KillGroup) = maxOf(tailBeats, beats(deathAfter(g), period))
        for (i in 1 until placed.size) {
            val before = placed[i - 1].second
            val at = placed[i].second
            val gap = at - before
            val wholeBeats = MontageEdits.nearestBeat(music, at) - MontageEdits.nearestBeat(music, before)
            val previous = placed[i - 1].first
            if (wholeBeats >= leadBeats + tailBeatsOf(previous) && gap >= cuts.minLead + maxOf(cuts.minTail, deathAfter(previous))) continue
            val lead = maxOf(minOf(gap * LEAD_SHARE, cuts.minLead.coerceAtLeast(Duration.ZERO)), shotBefore(placed[i].first))
            // Jamais avant la mort du kill d'avant ; le suivant garde le reste (son tir reste sur son temps).
            val cut = maxOf(at - lead, before + maxOf(MIN_SHOWN, deathAfter(previous))).coerceAtMost(at)
            // À quelques ms d'un vrai temps : la coupe se pose sur lui (deux temps confondus faisaient un plan vide).
            extra[i] = music.beats.firstOrNull { (it - cut).absoluteValue <= SNAP_TO_BEAT } ?: cut
        }
        val grid = withCuts(music, extra.values.toList() + forced)
        fun index(t: Duration) = grid.beats.indexOfFirst { abs((it - t).inWholeMicroseconds) < 1 }
        // Le temps de chaque kill : la coupe ajoutée pour lui, sinon le temps le plus proche ; strictement croissant.
        val anchors = mutableListOf<Int>()
        placed.forEachIndexed { i, (_, at) ->
            val low = (anchors.lastOrNull() ?: -1) + 1
            // Avant la coupe du plan court du kill suivant : ce temps-là appartient au suivant.
            val high = extra[i + 1]?.let(::index)?.minus(1) ?: grid.beats.lastIndex
            // Après la coupe ajoutée pour lui : le kill porte le temps le plus proche de son instant (son temps à lui
            // s'il tombe dessus, la coupe elle-même s'il est entre deux), comme le planificateur arrondit son épingle.
            val from = maxOf(low, extra[i]?.let(::index) ?: low)
            val b = (from..high).minByOrNull { (grid.beats[it] - at).absoluteValue } ?: from
            anchors += b.coerceAtLeast(low)
        }
        val forcedBeats = forced.map(::index).filter { it >= 0 }.distinct().sorted()
        // Les coupes : avant le premier, entre chaque paire, après le dernier. Les repères « coupe » s'imposent : entre
        // deux kills, le premier finit sur le premier repère, le second commence au dernier, entre eux de la vitrine.
        val bounds = mutableListOf<Int>()
        val before = forcedBeats.filter { it <= anchors.first() }
        if (before.isNotEmpty()) bounds += before else bounds += (anchors.first() - LEAD_BEATS).coerceAtLeast(0).coerceAtMost(anchors.first())
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            val imposed = forcedBeats.filter { it > a && it <= b }
            if (imposed.isNotEmpty() && !extra.containsKey(i + 1)) {
                bounds += imposed
                continue
            }
            val cut = when {
                // Coupe ajoutée : le plan court s'ouvre sur elle.
                extra.containsKey(i + 1) -> index(extra.getValue(i + 1))
                else -> {
                    val range = (a + tailBeatsOf(placed[i].first))..(b - leadBeats)
                    val beforeBeat = placed[i + 1].second < grid.beatTime(b)
                    when {
                        !range.isEmpty() -> range.maxBy { c -> cutScore(grid, c, a, b) }
                        beforeBeat && b - 1 > a -> b - 1
                        else -> b
                    }
                }
            }
            bounds += cut.coerceIn(a + 1, b)
        }
        val after = forcedBeats.filter { it > anchors.last() }
        if (after.isNotEmpty()) bounds += after else bounds += anchors.last() + TAIL_BEATS
        // Chaque segment entre deux coupes : le plan d'un kill (celui qui y tombe) ou de la vitrine.
        val slows = intents?.slows.orEmpty()
        val used = mutableSetOf<String>()
        val clips = mutableListOf<MontageClip>()
        var k = 0
        for (s in 0 until bounds.size - 1) {
            val start = bounds[s]
            val end = bounds[s + 1]
            if (end <= start) continue
            val section = grid.sectionIndexAt(start)
            val drop = grid.dropBeat.takeIf { it in start until end }
            if (k < anchors.size && anchors[k] in start until end) {
                val g = placed[k].first
                k++
                val from = grid.beatTime(start)
                val to = grid.beatTime(end)
                // Ralenti : à ses repères seulement (repères posés) ; sinon comme le planificateur le permet.
                val wantsSlow = intents == null || slows.any { it >= from && it < to }
                val own = if (intents != null && wantsSlow) settings.copy(slowMotion = settings.slowMotion.copy(enabled = true)) else settings
                clips += MontagePlanner.clipFor(CutSlot(start, end, section, drop) to g, grid, own, minLeadBeats = 0, minTailBeats = 1, allowSlow = wantsSlow)
            } else {
                // Vitrine : un plan de partie sans kill par [ShowcaseOpening.shotBeats] temps.
                val shot = settings.showcase.shotBeats.coerceAtLeast(1)
                var b = start
                while (b < end) {
                    val e = if (end - b < 2 * shot) end else b + shot
                    val filler = fillerClip(intents?.pool.orEmpty().ifEmpty { placed.map { it.first } }, CutSlot(b, e, grid.sectionIndexAt(b), grid.dropBeat.takeIf { it in b until e }), grid, settings, used)
                    if (filler != null) clips += filler
                    else if (clips.isNotEmpty()) clips[clips.lastIndex] = stretch(clips.last(), e, grid, settings)
                    else return plan(groups, music, settings, intents?.copy(cuts = intents.cuts.filter { it > grid.beatTime(e) }, fillers = intents.fillers.filter { it.first >= grid.beatTime(e) }))
                    b = e
                }
            }
        }
        val transitions = intents?.transitions.orEmpty()
        val withTransitions = if (transitions.isEmpty()) clips else clips.mapIndexed { i, c ->
            val at = grid.beatTime(c.slot.startBeat)
            val wanted = i > 0 && transitions.any { t -> clips.indices.drop(1).minBy { j -> (grid.beatTime(clips[j].slot.startBeat) - t).absoluteValue } == i }
            if (wanted) c.copy(transitionIn = true) else c
        }
        log.info { "Plans construits autour de ${placed.size} kill(s) posés (${extra.size} plan(s) court(s), ${laid.size - placed.size} fusion(s), ${clips.count { it.showcase }} plan(s) de vitrine) : ${clips.joinToString(" ") { "${it.beats}" }} temps" }
        return MontagePlan(withTransitions, grid, settings, byIntents = intents != null)
    }

    /**
     * Repères de l'étape 1 qui ne sont pas des kills : [cuts] (un plan commence ici), [fillers] (plages de vitrine : la
     * vitrine posée et les repères « kill » en trop), [slows] et [transitions] (seuls endroits où il y en aura), [pool]
     * les groupes où prendre les passages de vitrine.
     */
    data class Intents(
        val cuts: List<Duration> = emptyList(),
        val fillers: List<Pair<Duration, Duration>> = emptyList(),
        val slows: List<Duration> = emptyList(),
        val transitions: List<Duration> = emptyList(),
        val pool: List<KillGroup> = emptyList(),
    )

    /**
     * Plan de vitrine pour [slot] : un passage calme d'une partie ([KillGroup.calm]), avant un kill, d'un groupe pas
     * encore pris ([used]) ; sinon le passage juste avant le premier kill d'un groupe. Null : aucune source.
     */
    private fun fillerClip(pool: List<KillGroup>, slot: CutSlot, music: MusicAnalysis, settings: MontageSettings, used: MutableSet<String>): MontageClip? {
        val s = settings.showcase
        val length = music.beatTime(slot.endBeat) - music.beatTime(slot.startBeat)
        fun keyOf(g: KillGroup) = "${g.media.path}|${g.kills.first()}"
        fun window(g: KillGroup, calmOnly: Boolean): Pair<Duration, Duration>? {
            val end = g.kills.first() - s.beforeClip
            val low = if (calmOnly) maxOf((g.calm ?: return null).start + s.afterEvent, g.media.bounds.start) else g.media.bounds.start
            return (end - length to end).takeIf { it.first >= low }
        }
        val g = pool.firstOrNull { keyOf(it) !in used && window(it, true) != null }
            ?: pool.firstOrNull { keyOf(it) !in used && window(it, false) != null }
            ?: pool.firstOrNull { window(it, false) != null }
            ?: return null
        used += keyOf(g)
        val (start, end) = window(g, true) ?: window(g, false)!!
        return MontageClip(
            g, slot, slot.startBeat, g.kills.last(), start, end, Duration.ZERO, Duration.ZERO, emptyList(), length, showcase = true,
        )
    }

    /** Le plan [c] prolongé jusqu'au temps [end] (pas de source pour la vitrine qui devait suivre). */
    private fun stretch(c: MontageClip, end: Int, music: MusicAnalysis, settings: MontageSettings): MontageClip =
        MontagePlanner.clipFor(c.slot.copy(endBeat = end) to c.group, music, settings, minLeadBeats = 0, minTailBeats = 1, allowSlow = c.slow != null)

    /** Temps de contexte avant le premier kill et après le dernier. */
    const val LEAD_BEATS = 2
    const val TAIL_BEATS = 2

    /** Part de l'écart entre deux kills trop proches donnée à l'approche du second (le reste : fin du premier). */
    const val LEAD_SHARE = 0.6

    /** Après la chute de l'ennemi, de quoi la voir avant la coupe. */
    val DEATH_TAIL = 150.milliseconds

    /** Avant le tir, de quoi le voir partir. */
    val SHOT_LEAD = 50.milliseconds

    /** Au moins ce temps du kill d'avant avant la coupe d'un plan court (une image et demie à 60 img/s). */
    val MIN_SHOWN = 25.milliseconds

    /** Une coupe de plan court à moins de cet écart d'un vrai temps se pose sur ce temps. */
    val SNAP_TO_BEAT = 5.milliseconds

    /** Écart toléré entre l'écart dans la partie et l'écart sur la musique pour montrer deux kills dans un seul plan. */
    val MERGE_TOLERANCE = 150.milliseconds

    /**
     * Fusionne deux kills consécutifs de la même capture, plus proches qu'un temps, dont l'écart réel correspond à peu
     * près à l'écart voulu : un seul plan (le dernier kill porte l'épingle, le premier tombe à son écart réel).
     */
    internal fun merged(laid: List<Pair<KillGroup, Duration>>, period: Duration): List<Pair<KillGroup, Duration>> {
        val out = mutableListOf<Pair<KillGroup, Duration>>()
        for ((g, at) in laid) {
            val last = out.lastOrNull()
            if (last != null) {
                val (pg, pat) = last
                val musicGap = at - pat
                val captureGap = g.kills.first() - pg.kills.last()
                if (pg.media.path == g.media.path && musicGap < period && captureGap.isPositive() && (captureGap - musicGap).absoluteValue <= MERGE_TOLERANCE) {
                    out[out.lastIndex] = pg.copy(
                        kills = pg.kills + g.kills, traits = if (pg.traits.isEmpty() || g.traits.isEmpty()) emptyList() else pg.traits + g.traits,
                        pin = g.pin, retimed = pg.retimed + g.retimed, forced = true,
                    ) to at
                    continue
                }
            }
            out += g to at
        }
        return out
    }

    /**
     * La même analyse avec des temps de coupe en plus ([cuts], instants du morceau) : la grille du plan les compte comme
     * des temps (coupes et ancrages s'y posent), les sections, la drop et les mesures par temps suivent leurs temps.
     * Un temps ajouté n'a ni attaque ni saillance (rien ne le fait passer pour un temps fort).
     */
    fun withCuts(music: MusicAnalysis, cuts: List<Duration>): MusicAnalysis {
        val added = cuts.filter { c -> music.beats.none { abs((it - c).inWholeMicroseconds) < 1 } }.distinct().sorted()
        if (added.isEmpty()) return music
        val all = (music.beats.map { it to true } + added.map { it to false }).sortedBy { it.first }
        val newIndex = IntArray(music.beats.size)
        var k = 0
        all.forEachIndexed { i, (_, real) -> if (real) newIndex[k++] = i }
        fun remap(old: Int): Int = when {
            old < 0 -> old
            old < newIndex.size -> newIndex[old]
            else -> newIndex.last() + (old - newIndex.lastIndex)
        }
        fun array(src: DoubleArray, fill: Double): DoubleArray {
            if (src.isEmpty()) return src
            var j = 0
            return DoubleArray(all.size) { i -> if (all[i].second) src.getOrElse(j++) { fill } else fill }
        }
        return music.copy(
            beats = all.map { it.first },
            beatEnergy = array(music.beatEnergy, 0.0).let { e -> if (e.isEmpty()) e else DoubleArray(e.size) { i -> if (all[i].second) e[i] else e.getOrElse(i - 1) { 0.0 } } },
            beatAccent = array(music.beatAccent, 0.0),
            halfAccent = array(music.halfAccent, 0.0),
            salience = array(music.salience, 0.0),
            sections = music.sections.map { it.copy(startBeat = remap(it.startBeat), endBeat = remap(it.endBeat)) },
            dropBeat = remap(music.dropBeat),
            virtualBeats = all.indices.filter { !all[it].second }.toSet(),
        )
    }

    /** La coupe sur le temps qui frappe (premier de mesure en prime), plutôt vers la moitié de l'écart. */
    private fun cutScore(music: MusicAnalysis, c: Int, a: Int, b: Int): Double {
        val middle = (a + b) / 2.0
        return music.strength(c) + (if (music.isDownbeat(c)) 0.4 else 0.0) - 0.1 * abs(c - middle) / (b - a).coerceAtLeast(1)
    }

    private fun beats(d: Duration, period: Duration): Int = ceil(d / period - 1e-9).toInt().coerceAtLeast(0)
}
