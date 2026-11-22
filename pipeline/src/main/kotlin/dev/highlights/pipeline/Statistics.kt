package dev.highlights.pipeline

import dev.highlights.core.model.MontageSettings
import dev.highlights.core.session.Session
import dev.highlights.montage.MontagePlanner
import dev.highlights.montage.TeamRounds
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
    /**
     * null : inconnus. Clutchs annoncés par le jeu, mais partie analysée avant de les lire ([outdated]), ou kills venus
     * d'un détecteur qui ne les annonce pas (le killfeed d'une capture OBS).
     */
    val clutches: Int?,
    /** Le plus de kills dans un même round ; null sans rounds connus (pas d'événement de mort). */
    val bestRound: Int?,
    /** Rounds déduits des morts ; null sans événement de mort. */
    val rounds: Int?,
    /** Analysée avec d'autres réglages de détection que ceux du profil aujourd'hui : à réanalyser pour être à jour. */
    val outdated: Boolean = false,
    /** [playedAt] vient de l'enregistrement, pas de la date du fichier (voir [dev.highlights.core.model.MediaInfo.reliableTime]). */
    val reliableTime: Boolean = true,
    /** Taille de la capture (octets) : deux copies d'une même capture ont la même taille et la même durée. */
    val sizeBytes: Long = 0,
    /** Un clip (voir [dev.highlights.core.model.MediaInfo.isClip]) : compte dans une séance ([PlaySession]), pas seul. */
    val clip: Boolean = false,
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
    /** Séances de jeu ([PlaySessions.of]) : chaque partie seule, les clips sauvegardés à la suite ensemble. */
    val parts: List<PlaySession> get() = PlaySessions.of(games)
    val clips: Int get() = games.count { it.clip }
    /** Kills par séance : par partie quand il n'y a que des parties, une séance de clips comptant pour une. */
    val killsPerGame: Double get() = parts.size.let { n -> if (n == 0) 0.0 else kills.toDouble() / n }
    val bestRound: Int? get() = games.mapNotNull { it.bestRound }.maxOrNull()
    val multiKills: Map<Int, Int> get() = games.flatMap { it.multiKills.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    val aces: Int get() = games.sumOf { it.aces }
    /** Clutchs connus (ceux des parties à réanalyser n'y sont pas). */
    val clutches: Int get() = games.sumOf { it.clutches ?: 0 }
    val playTime: Duration get() = games.fold(Duration.ZERO) { acc, g -> acc + g.duration }
    /** La partie au plus de kills. */
    val bestGame: GameStats? get() = games.maxByOrNull { it.kills }
}

object Statistics {
    /** Une soirée finit à 6 h du matin : la partie de 1 h appartient à celle de la veille. */
    val EVENING_END = 6.hours

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
        outdated: Boolean = false,
        clutchSources: Set<String>? = null,
    ): GameStats {
        val style = settings.killStyle
        // Morts de Phoenix pendant son ultime (« Run it Back ») retirées, comme au montage : le joueur revient.
        val events = TeamRounds.withoutRevived(session.timeline.events, style)
        val killEvents = events.filter { it.kind == settings.killEvent }
        val kills = killEvents.map { it.at }.sorted()
        val killSources = killEvents.map { it.detectorId }.toSet()
        // Sans kill, la partie ne dit rien de son détecteur : on se fie à ce que le profil sait voir.
        fun known(sources: Set<String>?) = sources == null || (sources.isNotEmpty() && (killSources.isEmpty() || killSources.any { it in sources }))
        val deaths = if (style.deathEvent.isEmpty() || !known(deathSources)) null else events.filter { it.kind in style.deathKinds }.map { it.at }.sorted()
        val groups = MontagePlanner.groups(listOf(session), settings)
        // Les tirs à la tête peuvent venir d'un autre détecteur que les kills (le son du jeu, avec le killfeed) : il
        // suffit qu'il ait entendu quelque chose dans cette partie. On compte les kills à la tête, pas les événements :
        // un tir à la tête qui ne tue pas s'entend aussi.
        val heard = headshotSources != null && events.any { it.kind == style.headshotEvent && it.detectorId in headshotSources }
        val headshots = if (style.headshotEvent.isEmpty() || !(known(headshotSources) || heard)) null
        else groups.sumOf { g -> g.kills.count { g.traitsOf(it).headshot } }
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
            // Clutchs annoncés par le jeu : connus seulement d'une analyse à jour dont les kills viennent d'un détecteur
            // qui les annonce ; sinon inconnus plutôt que 0. Clutchs déduits : toujours connus. Clutchs réels, comptés sur les
            // morts des deux équipes lues dans le killfeed : connus dès que la partie en a.
            clutches = if (style.clutchEvent.isEmpty() || (!outdated && (known(clutchSources) || events.any { it.kind.isNotEmpty() && (it.kind == style.allyDeathEvent || it.kind == style.clutchBannerEvent) }))) {
                groups.count { it.outcome.clutch }
            } else {
                null
            },
            bestRound = if (deaths != null) rounds.maxOfOrNull { it.kills.size } ?: 0 else null,
            rounds = if (deaths != null) rounds.size else null,
            outdated = outdated,
            reliableTime = session.media.reliableTime,
            sizeBytes = session.media.sizeBytes,
            clip = session.media.isClip,
        )
    }

    /**
     * Détecteurs qui produisent cet événement dans au moins une partie du profil : une partie sans mort vaut alors 0
     * mort, et un profil qui n'en voit jamais (pas de killfeed, pas d'Outplayed) laisse le chiffre inconnu.
     */
    fun sources(sessions: List<Session>, kind: String): Set<String> = sources(sessions, setOf(kind))

    /** Détecteurs qui ont émis l'une des sortes [kinds] (ex. mort et, si elle compte, mise à terre). */
    fun sources(sessions: List<Session>, kinds: Set<String>): Set<String> =
        sessions.flatMap { s -> s.timeline.events.filter { it.kind.isNotEmpty() && it.kind in kinds }.map { it.detectorId } }.toSet()

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
     * la plus courte sont la même partie, vue par deux enregistreurs (18:07, 44 min, et 18:08, 40 min). Seulement si
     * les deux horaires viennent de l'enregistrement ([GameStats.reliableTime]) : deux parties VALORANT sans date dans
     * leurs métadonnées, copiées à une minute d'écart, passaient pour une seule (« 1 partie, 46 kills » au lieu de
     * 2 parties, 61 kills). La même capture, elle, reste une partie quels que soient ses horaires ([sameCapture]). La
     * plus riche en kills reste.
     */
    fun distinctGames(games: List<GameStats>): List<GameStats> {
        val kept = mutableListOf<GameStats>()
        // Parties entières d'abord : un clip qui tombe dans une partie gardée en est un morceau (Outplayed garde la partie
        // et ses clips), la partie reste même si le clip a plus de kills qu'elle n'en a lu.
        for (g in games.sortedWith(compareBy<GameStats> { it.clip }.thenByDescending { it.kills })) {
            val start = g.playedAt ?: continue
            val same = kept.any { k ->
                val kStart = k.playedAt!!
                val margin = if (g.clip != k.clip) CLIP_MARGIN.toJavaDuration() else java.time.Duration.ZERO
                val overlap = minOf(start + g.duration.toJavaDuration(), kStart + k.duration.toJavaDuration() + margin).epochSecond -
                    maxOf(start, kStart - margin).epochSecond
                val timed = k.reliableTime && g.reliableTime
                k.game == g.game && (
                    sameCapture(k, g) ||
                        // Deux clips qui se chevauchent sont deux appuis sur la touche, pas une partie vue deux fois : leurs
                        // kills en double sont retirés avant ([ClipRepeats.remove]).
                        (timed && !(g.clip && k.clip) && overlap > minOf(g.duration, k.duration).inWholeSeconds / 2)
                    )
            }
            if (!same) kept += g
        }
        return kept.sortedBy { it.playedAt }
    }

    /**
     * Marge autour d'une partie entière où un clip du même jeu en est un morceau : l'heure d'un clip Outplayed est celle de
     * l'événement ou de la sauvegarde selon les versions, et une capture OBS commence après le lancement de la partie.
     */
    val CLIP_MARGIN: Duration = 2.minutes

    /** La même capture : même fichier, ou une copie (même taille à l'octet près, même durée à la seconde). */
    internal fun sameCapture(a: GameStats, b: GameStats): Boolean =
        a.source.toAbsolutePath().normalize() == b.source.toAbsolutePath().normalize() ||
            (a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes && (a.duration - b.duration).absoluteValue < kotlin.time.Duration.parse("1s"))

    fun eveningOf(at: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        at.atZone(zone).minusHours(EVENING_END.inWholeHours).toLocalDate()
}
