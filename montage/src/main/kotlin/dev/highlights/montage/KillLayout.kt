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
     * - deux kills plus proches qu'un temps, sinon : le second a un plan court qui s'ouvre [SHORT_LEAD] avant lui (moins
     *   s'il faut laisser voir le premier) ; la grille du plan reçoit pour cela un temps de coupe à cet instant
     *   ([withCuts]), le rendu suit la grille ;
     * - sinon, chaque kill porte le temps le plus proche, et entre deux kills la coupe va sur le temps qui frappe le
     *   plus, en laissant au kill d'avant sa fin ([CutSettings.minTail]) et au suivant son approche
     *   ([CutSettings.minLead]) ; trop serrés pour cela, le second ouvre son plan (la coupe recule d'un temps s'il est
     *   posé avant le sien) ;
     * - avant le premier kill, [LEAD_BEATS] temps (moins au début du morceau) ; après le dernier, [TAIL_BEATS] ;
     * - le plan de chaque kill se taille comme d'habitude ([MontagePlanner.clipFor]).
     */
    fun plan(groups: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings): MontagePlan {
        val laid = groups.mapNotNull { g -> (g.pin as? MontagePin.At)?.let { g to it.music } }.sortedBy { it.second }
        if (laid.isEmpty() || music.beats.isEmpty()) throw HighlightsException(Message("error.montage.noKills"))
        val period = music.beatPeriod
        val placed = merged(laid, period)
        // Temps de coupe à ajouter : juste avant chaque kill trop proche du précédent.
        val extra = mutableMapOf<Int, Duration>()
        for (i in 1 until placed.size) {
            val before = placed[i - 1].second
            val at = placed[i].second
            if (at - before >= period) continue
            val cut = maxOf(at - SHORT_LEAD, before + MIN_SHOWN).coerceAtMost(at)
            // À quelques ms d'un vrai temps : la coupe se pose sur lui (deux temps confondus faisaient un plan vide).
            extra[i] = music.beats.firstOrNull { (it - cut).absoluteValue <= SNAP_TO_BEAT } ?: cut
        }
        val grid = withCuts(music, extra.values.toList())
        val cuts = settings.cuts
        val leadBeats = beats(cuts.minLead, period)
        val tailBeats = beats(cuts.minTail, period).coerceAtLeast(1)
        fun index(t: Duration) = grid.beats.indexOfFirst { abs((it - t).inWholeMicroseconds) < 1 }
        // Le temps de chaque kill : la coupe ajoutée pour lui, sinon le temps le plus proche ; strictement croissant.
        val anchors = mutableListOf<Int>()
        placed.forEachIndexed { i, (_, at) ->
            val low = (anchors.lastOrNull() ?: -1) + 1
            // Avant la coupe du plan court du kill suivant : ce temps-là appartient au suivant.
            val high = extra[i + 1]?.let(::index)?.minus(1) ?: grid.beats.lastIndex
            val b = extra[i]?.let(::index)
                ?: (low..high).minByOrNull { (grid.beats[it] - at).absoluteValue }
                ?: low
            anchors += b.coerceAtLeast(low)
        }
        // Les coupes : avant le premier, entre chaque paire, après le dernier.
        val bounds = mutableListOf((anchors.first() - LEAD_BEATS).coerceAtLeast(0).coerceAtMost(anchors.first()))
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            val cut = when {
                // Coupe ajoutée : le plan court s'ouvre sur elle.
                extra.containsKey(i + 1) -> b
                else -> {
                    val range = (a + tailBeats)..(b - leadBeats)
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
        bounds += anchors.last() + TAIL_BEATS
        val clips = placed.mapIndexed { i, (g, _) ->
            val start = bounds[i]
            val end = bounds[i + 1]
            val section = grid.sectionIndexAt(start)
            val drop = grid.dropBeat.takeIf { it in start until end }
            MontagePlanner.clipFor(CutSlot(start, end, section, drop) to g, grid, settings, minLeadBeats = 0, minTailBeats = 1)
        }
        log.info { "Plans construits autour de ${placed.size} kill(s) posés (${extra.size} plan(s) court(s), ${laid.size - placed.size} fusion(s)) : ${clips.joinToString(" ") { "${it.beats}" }} temps" }
        return MontagePlan(clips, grid, settings)
    }

    /** Temps de contexte avant le premier kill et après le dernier. */
    const val LEAD_BEATS = 2
    const val TAIL_BEATS = 2

    /** Plan court : la coupe vient 3 images (60 img/s) avant le kill. */
    val SHORT_LEAD = 50.milliseconds

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
