package dev.highlights.pipeline

import dev.highlights.montage.KillGroup
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Critères d'une recherche de moments dans les parties analysées. Un moment est un groupe de kills tel que le monte le
 * montage kills (kills rapprochés, dans un même round). L'arme n'y figure pas : ni VALORANT ni Outplayed ne la
 * transmettent (le champ « data » des événements n'est qu'un numéro d'ordre).
 */
data class MomentQuery(
    /** Profil (valorant, wardogs…) ; null : tous. */
    val game: String? = null,
    /** Soirées incluses (voir [Statistics.eveningOf]). */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Kills au moins dans le groupe : 2 pour les doublés et plus, 3 pour les triplés… */
    val minKills: Int = 1,
    val ace: Boolean = false,
    val clutch: Boolean = false,
    /** Tous les kills du groupe tirés à la tête. */
    val allHeadshots: Boolean = false,
    /** Au moins un kill d'une balle à la tête dans le groupe (voir [dev.highlights.montage.KillTraits.oneTap]). */
    val oneTaps: Boolean = false,
) {
    init {
        require(minKills >= 1) { "minKills doit être ≥ 1" }
    }

    fun matches(m: FoundMoment, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val evening = m.playedAt?.let { Statistics.eveningOf(it, zone) }
        return (game == null || m.profileId == game) &&
            (from == null || (evening != null && evening >= from)) &&
            (to == null || (evening != null && evening <= to)) &&
            m.kills.size >= minKills &&
            (!ace || m.ace) && (!clutch || m.clutch) &&
            (!allHeadshots || m.headshots == m.kills.size) &&
            (!oneTaps || m.oneTaps > 0)
    }
}

/** Moment trouvé : un groupe de kills d'une partie analysée. [kills] : instants dans la capture [source]. */
data class FoundMoment(
    val sessionFile: Path,
    val source: Path,
    val profileId: String,
    val game: String,
    val playedAt: Instant?,
    val kills: List<Duration>,
    val headshots: Int,
    val ace: Boolean,
    val clutch: Boolean,
    /** Importance (celle du montage kills : kills, score, style). */
    val rank: Double,
    /** Kills d'une balle à la tête (0 si les balles n'ont pas été comptées). */
    val oneTaps: Int = 0,
) {
    companion object {
        fun of(sessionFile: Path, game: String, profileId: String, playedAt: Instant?, g: KillGroup) = FoundMoment(
            sessionFile, g.media.path, profileId, game, playedAt, g.kills, g.kills.count { g.traitsOf(it).headshot },
            g.outcome.ace, g.outcome.clutch, g.rank, g.kills.count { g.traitsOf(it).oneTap },
        )
    }
}

/**
 * Moments choisis pour un montage kills : les kills retenus, capture par capture. Un groupe du montage est gardé s'il
 * contient l'un d'eux (à [TOLERANCE] près : le montage peut recaler un kill sur le son du tir, ou regrouper autrement
 * quand on lui retire le classement par round).
 */
data class MomentPick(val kills: Map<Path, List<Duration>>) {
    val size: Int get() = kills.values.sumOf { it.size }

    fun keeps(g: KillGroup): Boolean {
        val wanted = kills[g.media.path.toAbsolutePath().normalize()] ?: return false
        return g.kills.any { k -> wanted.any { (it - k).absoluteValue <= TOLERANCE } }
    }

    companion object {
        val TOLERANCE = 1.seconds

        fun of(moments: List<FoundMoment>) = MomentPick(
            moments.groupBy { it.source.toAbsolutePath().normalize() }.mapValues { (_, list) -> list.flatMap { it.kills } },
        )
    }
}
