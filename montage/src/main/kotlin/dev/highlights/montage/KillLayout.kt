package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.MontageSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * « Montage sur la musique », étape 1 puis 2 : l'utilisateur a posé chaque kill sur un instant du morceau
 * ([MontageConstraints.layout]) ; les plans se construisent ENSUITE autour de ces instants, sans jamais en refuser un
 * pour cause de plan déjà là (demande du 20/11 : l'algorithme place les plans en conséquence).
 */
object KillLayout {
    /**
     * Plan dont chaque groupe de [groups] (épinglé à son instant, [KillGroup.pin]) tombe exactement à son instant :
     * - chaque kill porte le temps le plus proche de son instant (deux kills sur le même temps : le second passe au
     *   suivant ; l'éditeur l'évite déjà en poussant) ;
     * - entre deux kills, la coupe va sur le temps qui frappe le plus, en laissant au kill d'avant sa fin
     *   ([CutSettings.minTail]) et au suivant son approche ([CutSettings.minLead]) ; trop proches, le second ouvre son
     *   plan (plan d'un temps, contexte pris sur le précédent) ;
     * - avant le premier kill, [LEAD_BEATS] temps (moins au début du morceau) ; après le dernier, [TAIL_BEATS] ;
     * - le plan de chaque kill se taille comme d'habitude ([MontagePlanner.clipFor] : contexte, ralenti s'il tient,
     *   rampes, repères de la capture).
     * Les longs écarts entre deux kills font des plans longs, que les coupes dans l'attente découpent ensuite.
     */
    fun plan(groups: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings): MontagePlan {
        val placed = groups.mapNotNull { g -> (g.pin as? MontagePin.At)?.let { g to it.music } }.sortedBy { it.second }
        if (placed.isEmpty() || music.beats.isEmpty()) throw HighlightsException(Message("error.montage.noKills"))
        val period = music.beatPeriod
        val cuts = settings.cuts
        val leadBeats = beats(cuts.minLead, period)
        val tailBeats = beats(cuts.minTail, period).coerceAtLeast(1)
        // Le temps de chaque kill, strictement croissant.
        val anchors = mutableListOf<Int>()
        for ((_, at) in placed) {
            val b = MontageEdits.nearestBeat(music, at)
            anchors += if (anchors.isNotEmpty() && b <= anchors.last()) anchors.last() + 1 else b
        }
        // Les coupes : avant le premier, entre chaque paire, après le dernier.
        val bounds = mutableListOf((anchors.first() - LEAD_BEATS).coerceAtLeast(0).coerceAtMost(anchors.first()))
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            val range = (a + tailBeats)..(b - leadBeats)
            // Trop proches pour l'approche et la fin habituelles : le second ouvre son plan, sauf s'il est posé avant
            // son temps (il ne peut pas tomber avant sa coupe) : la coupe recule alors d'un temps quand il y en a un.
            val before = placed[i + 1].second < music.beatTime(b)
            val cut = when {
                !range.isEmpty() -> range.maxBy { c -> cutScore(music, c, a, b) }
                before && b - 1 > a -> b - 1
                else -> b
            }
            bounds += cut.coerceIn(a + 1, b)
        }
        bounds += anchors.last() + TAIL_BEATS
        val clips = placed.mapIndexed { i, (g, _) ->
            val start = bounds[i]
            val end = bounds[i + 1]
            val section = music.sectionIndexAt(start)
            val drop = music.dropBeat.takeIf { it in start until end }
            MontagePlanner.clipFor(CutSlot(start, end, section, drop) to g, music, settings, minLeadBeats = 0, minTailBeats = 1)
        }
        log.info { "Plans construits autour de ${placed.size} kill(s) posés : ${clips.joinToString(" ") { "${it.beats}" }} temps" }
        return MontagePlan(clips, music, settings)
    }

    /** Temps de contexte avant le premier kill et après le dernier. */
    const val LEAD_BEATS = 2
    const val TAIL_BEATS = 2

    /** La coupe sur le temps qui frappe (premier de mesure en prime), plutôt vers la moitié de l'écart. */
    private fun cutScore(music: MusicAnalysis, c: Int, a: Int, b: Int): Double {
        val middle = (a + b) / 2.0
        return music.strength(c) + (if (music.isDownbeat(c)) 0.4 else 0.0) - 0.1 * abs(c - middle) / (b - a).coerceAtLeast(1)
    }

    private fun beats(d: Duration, period: Duration): Int = ceil(d / period - 1e-9).toInt().coerceAtLeast(0)
}
