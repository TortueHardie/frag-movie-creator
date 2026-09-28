package dev.highlights.pipeline

import dev.highlights.core.model.MontageSettings
import dev.highlights.core.session.Session
import dev.highlights.montage.MontagePlanner
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Bilan d'une partie, tiré des événements de sa session. Rounds, multi-kills, aces et clutchs suivent les règles du
 * montage kills ([MontagePlanner.groups], [MontagePlanner.rounds]) : les chiffres concordent avec ce qu'il monte.
 * [deaths] et [headshots] : null quand le profil n'en détecte pas (sinon 0 laisserait croire à une partie parfaite).
 */
data class GameStats(
    val sessionFile: Path,
    val source: Path,
    val profileId: String,
    val game: String,
    val playedAt: Instant?,
    val duration: Duration,
    val kills: Int,
    val deaths: Int?,
    val headshots: Int?,
    /** Groupes de kills par taille : 2 → doublés, 3 → triplés… */
    val multiKills: Map<Int, Int>,
    val aces: Int,
    val clutches: Int,
    /** Le plus de kills dans un même round ; null sans rounds connus (pas d'événement de mort). */
    val bestRound: Int?,
    /** Rounds déduits des morts ; null sans événement de mort. */
    val rounds: Int?,
) {
    /** Kills par mort (les kills seuls sans mort). */
    val kd: Double? get() = deaths?.let { kills.toDouble() / it.coerceAtLeast(1) }
    val headshotRate: Double? get() = headshots?.takeIf { kills > 0 }?.let { it.toDouble() / kills }
    val killsPerHour: Double get() = if (duration.isPositive()) kills / (duration.inWholeSeconds / 3600.0) else 0.0
}

/**
 * Soirée de jeu d'un jeu ([game]) : ses parties d'une même date, une partie après minuit comptant pour la veille
 * ([Statistics.EVENING_END]). Deux jeux ne se mélangent pas : 18 kills en VALORANT et 2 en Wardogs ne font pas 10
 * par partie. K/D et tirs à la tête ne comptent que les parties où morts et tirs à la tête sont connus.
 */
data class EveningStats(val date: LocalDate, val game: String, val games: List<GameStats>) {
    val kills: Int get() = games.sumOf { it.kills }
    val deaths: Int? get() = games.mapNotNull { it.deaths }.takeIf { it.isNotEmpty() }?.sum()
    val headshots: Int? get() = games.mapNotNull { it.headshots }.takeIf { it.isNotEmpty() }?.sum()
    val kd: Double?
        get() = games.filter { it.deaths != null }.takeIf { it.isNotEmpty() }?.let { known ->
            known.sumOf { it.kills }.toDouble() / known.sumOf { it.deaths!! }.coerceAtLeast(1)
        }
    val headshotRate: Double?
        get() = games.filter { it.headshots != null }.takeIf { k -> k.sumOf { it.kills } > 0 }?.let { known ->
            known.sumOf { it.headshots!! }.toDouble() / known.sumOf { it.kills }
        }
    val killsPerGame: Double get() = if (games.isEmpty()) 0.0 else kills.toDouble() / games.size
    val bestRound: Int? get() = games.mapNotNull { it.bestRound }.maxOrNull()
    val multiKills: Map<Int, Int> get() = games.flatMap { it.multiKills.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    val aces: Int get() = games.sumOf { it.aces }
    val clutches: Int get() = games.sumOf { it.clutches }
    val playTime: Duration get() = games.fold(Duration.ZERO) { acc, g -> acc + g.duration }
    /** La partie au plus de kills. */
    val bestGame: GameStats? get() = games.maxByOrNull { it.kills }
}

object Statistics {
    /** Une soirée finit à 6 h du matin : la partie de 1 h appartient à celle de la veille. */
    val EVENING_END = 6.hours

    /** Capture plus courte : un clip (Outplayed en garde de 1 min 30), pas une partie (30 à 45 min en VALORANT). */
    val MIN_GAME: Duration = 5.minutes

    /**
     * Bilan d'une partie selon les réglages de montage de son profil ([settings] : kills, morts, tirs à la tête, rounds).
     * [deathSources], [headshotSources] : détecteurs qui voient morts et tirs à la tête (voir [sources]). Une partie dont
     * les kills viennent d'un autre détecteur (le killfeed de secours ne voit pas les tirs à la tête) les laisse inconnus :
     * sinon 23 kills sans tir à la tête passeraient pour 0 %.
     */
    fun game(
        session: Session,
        settings: MontageSettings,
        sessionFile: Path,
        game: String,
        deathSources: Set<String>? = null,
        headshotSources: Set<String>? = null,
    ): GameStats {
        val style = settings.killStyle
        val events = session.timeline.events
        val killEvents = events.filter { it.kind == settings.killEvent }
        val kills = killEvents.map { it.at }.sorted()
        val killSources = killEvents.map { it.detectorId }.toSet()
        // Sans kill, la partie ne dit rien de son détecteur : on se fie à ce que le profil sait voir.
        fun known(sources: Set<String>?) = sources == null || (sources.isNotEmpty() && (killSources.isEmpty() || killSources.any { it in sources }))
        val deaths = if (style.deathEvent.isEmpty() || !known(deathSources)) null else events.filter { it.kind == style.deathEvent }.map { it.at }.sorted()
        val headshots = if (style.headshotEvent.isEmpty() || !known(headshotSources)) null else events.count { it.kind == style.headshotEvent }
        val groups = MontagePlanner.groups(listOf(session), settings)
        val rounds = MontagePlanner.rounds(kills, deaths.orEmpty(), style.roundGap)
        return GameStats(
            sessionFile = sessionFile,
            source = session.media.path,
            profileId = session.profileId,
            game = game,
            playedAt = session.media.recordedAt,
            duration = session.media.duration,
            kills = kills.size,
            deaths = deaths?.size,
            headshots = headshots,
            multiKills = groups.map { it.kills.size }.filter { it > 1 }.groupingBy { it }.eachCount(),
            aces = groups.count { it.outcome.ace },
            clutches = groups.count { it.outcome.clutch },
            bestRound = if (deaths != null) rounds.maxOfOrNull { it.kills.size } ?: 0 else null,
            rounds = if (deaths != null) rounds.size else null,
        )
    }

    /**
     * Détecteurs qui produisent cet événement dans au moins une partie du profil : une partie sans mort vaut alors 0
     * mort, et un profil qui n'en voit jamais (pas de killfeed, pas d'Outplayed) laisse le chiffre inconnu.
     */
    fun sources(sessions: List<Session>, kind: String): Set<String> =
        if (kind.isEmpty()) emptySet() else sessions.flatMap { s -> s.timeline.events.filter { it.kind == kind }.map { it.detectorId } }.toSet()

    /**
     * Parties regroupées par soirée et par jeu, la plus récente d'abord ; celles sans date d'enregistrement sont
     * écartées, et une même partie enregistrée deux fois n'est comptée qu'une ([distinctGames]).
     */
    fun evenings(games: List<GameStats>, zone: ZoneId = ZoneId.systemDefault()): List<EveningStats> =
        distinctGames(games.filter { it.playedAt != null })
            .groupBy { eveningOf(it.playedAt!!, zone) to it.game }
            .map { (key, list) -> EveningStats(key.first, key.second, list.sortedBy { it.playedAt }) }
            .sortedWith(compareByDescending<EveningStats> { it.date }.thenBy { it.game })

    /**
     * On ne joue pas deux parties d'un même jeu à la fois : deux captures qui se chevauchent sur plus de la moitié de
     * la plus courte sont la même partie, vue par deux enregistreurs (18:07, 44 min, et 18:08, 40 min). La plus riche
     * en kills reste.
     */
    fun distinctGames(games: List<GameStats>): List<GameStats> {
        val kept = mutableListOf<GameStats>()
        for (g in games.sortedByDescending { it.kills }) {
            val start = g.playedAt ?: continue
            val same = kept.any { k ->
                val kStart = k.playedAt!!
                val overlap = minOf(start + g.duration.toJavaDuration(), kStart + k.duration.toJavaDuration()).epochSecond -
                    maxOf(start, kStart).epochSecond
                k.game == g.game && overlap > minOf(g.duration, k.duration).inWholeSeconds / 2
            }
            if (!same) kept += g
        }
        return kept.sortedBy { it.playedAt }
    }

    fun eveningOf(at: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        at.atZone(zone).minusHours(EVENING_END.inWholeHours).toLocalDate()
}
