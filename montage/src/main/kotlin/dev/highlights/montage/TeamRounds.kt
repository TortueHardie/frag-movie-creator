package dev.highlights.montage

import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.KillStyle
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Clutchs réels, comptés à partir des morts des deux équipes lues dans le killfeed (détecteur `killfeed-teams`) :
 * dans un round, le joueur se retrouve seul de son équipe en vie face à au moins [KillStyle.clutchMinEnemies]
 * adversaires, puis l'équipe adverse est éliminée sans qu'il meure. C'est la règle du jeu, qui affiche alors
 * « CLUTCH » en fin de round. Sans Outplayed, c'était jusqu'ici déduit des seuls kills et morts du joueur (round
 * survécu fini sur un multi-kill), ce qui en comptait trois pour un vrai.
 *
 * Limite : un round gagné au désamorçage du spike (ou à son explosion) ne passe pas par le killfeed, ses clutchs sont
 * manqués. Une ligne de mort alliée manquée en fait manquer un aussi ; une ligne en trop peut en inventer un.
 */
object TeamRounds {
    /**
     * Ligne de mort alliée à moins de ça d'une mort du joueur : c'est la sienne. Les deux viennent de la même ligne du
     * killfeed (au même décalage) ou d'Outplayed (0,4 s d'écart au plus mesuré).
     */
    val SAME_DEATH: Duration = 1500.milliseconds

    /**
     * Le clutch est daté juste après la mort du dernier adversaire : le kill du joueur qui la cause est alors le dernier
     * avant lui, à la façon d'un clutch annoncé ([MontagePlanner.announcedClutch]).
     */
    val AFTER_LAST: Duration = 300.milliseconds

    /**
     * [events] sans les morts du joueur pendant l'ultime de Phoenix : chaque événement [KillStyle.revivedEvent] (ligne
     * du killfeed marquée du cercle de « Run it Back ») retire la mort la plus proche à moins de [SAME_DEATH], qu'elle
     * vienne du détecteur `killfeed` ou d'Outplayed. Les morts sont celles de [KillStyle.deathEvent], « death » quand il
     * est vide (montage des morts, qui les vide pour les monter comme des kills).
     */
    fun withoutRevived(events: List<TimelineEvent>, style: KillStyle): List<TimelineEvent> {
        if (style.revivedEvent.isEmpty()) return events
        val revived = events.filter { it.kind == style.revivedEvent }
        if (revived.isEmpty()) return events
        val kind = style.deathEvent.ifEmpty { "death" }
        val dropped = HashSet<TimelineEvent>()
        for (r in revived) {
            events.filter { it.kind == kind && it !in dropped && (it.at - r.at).absoluteValue <= SAME_DEATH }
                .minByOrNull { (it.at - r.at).absoluteValue }
                ?.let { dropped += it }
        }
        return if (dropped.isEmpty()) events else events.filter { it !in dropped }
    }

    /** Un clutch : quand le joueur est resté seul, face à combien d'adversaires, et quand le dernier est tombé. */
    data class Clutch(val alone: Duration, val enemies: Int, val won: Duration)

    /**
     * Instants des clutchs d'une partie (voir [Clutch.won] et [AFTER_LAST]), ou null si elle n'a aucune mort lue dans
     * le killfeed : analysée sans `killfeed-teams`, son clutch reste déduit. [offset] : décalage ajouté aux événements
     * (celui des kills, `killOffset`).
     */
    fun clutchTimes(events: List<TimelineEvent>, playerDeaths: List<Duration>, style: KillStyle, offset: Duration): List<Duration>? {
        if (style.allyDeathEvent.isEmpty()) return null
        val allies = events.filter { it.kind == style.allyDeathEvent }.map { it.at + offset }
        if (allies.isEmpty()) return null
        val enemies = if (style.enemyDeathEvent.isEmpty()) emptyList() else events.filter { it.kind == style.enemyDeathEvent }.map { it.at + offset }
        val starts = if (style.roundStartEvent.isEmpty()) emptyList() else events.filter { it.kind == style.roundStartEvent }.map { it.at + offset }
        return clutches(allies, enemies, playerDeaths, style.roundGap, style.teamSize, style.clutchMinEnemies, starts).map { it.won + AFTER_LAST }
    }

    private enum class Kind { ALLY, ENEMY, PLAYER }

    /**
     * Rejoue les morts dans l'ordre, round par round. Un round finit quand une équipe est éliminée ([teamSize] morts),
     * après un silence de plus de [gap] (phase d'achat : un round perdu ou gagné au spike n'élimine personne), ou à un
     * début de round lu à l'image ([roundStarts]). Une mort alliée proche d'une mort du joueur ([SAME_DEATH]) est la sienne.
     */
    fun clutches(
        allyDeaths: List<Duration>,
        enemyDeaths: List<Duration>,
        playerDeaths: List<Duration>,
        gap: Duration,
        teamSize: Int,
        minEnemies: Int,
        roundStarts: List<Duration> = emptyList(),
    ): List<Clutch> {
        val starts = roundStarts.sorted()
        val players = playerDeaths.sorted()
        val events = mutableListOf<Pair<Duration, Kind>>()
        val matched = HashSet<Int>()
        for (a in allyDeaths) {
            val p = players.indices.filter { it !in matched && (players[it] - a).absoluteValue <= SAME_DEATH }.minByOrNull { (players[it] - a).absoluteValue }
            if (p == null) events += a to Kind.ALLY else matched += p
        }
        // Mort du joueur : la sienne, même si sa ligne du killfeed n'a pas été lue.
        events += players.map { it to Kind.PLAYER }
        events += enemyDeaths.map { it to Kind.ENEMY }
        events.sortBy { it.first }

        val out = mutableListOf<Clutch>()
        var allies = 0
        var foes = 0
        var dead = false
        var alone: Pair<Duration, Int>? = null
        var previous: Duration? = null
        fun reset() {
            allies = 0
            foes = 0
            dead = false
            alone = null
        }
        for ((at, kind) in events) {
            val last = previous
            // Équipe éliminée au round précédent, ou silence de la phase d'achat : nouveau round.
            val started = last != null && starts.any { it > last && it <= at }
            if (last != null && (started || at - last > gap || foes >= teamSize || allies + (if (dead) 1 else 0) >= teamSize)) reset()
            previous = at
            when (kind) {
                Kind.ALLY -> allies++
                Kind.PLAYER -> dead = true
                Kind.ENEMY -> foes++
            }
            if (dead) continue
            if (alone == null && allies >= teamSize - 1) alone = at to (teamSize - foes).coerceAtLeast(0)
            val a = alone
            if (kind == Kind.ENEMY && foes == teamSize && a != null && a.second >= minEnemies) out += Clutch(a.first, a.second, at)
        }
        return out
    }
}
