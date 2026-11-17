package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.KillStyle
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.serialization.Durations
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Un kill désigné par l'utilisateur : le nom de fichier de sa capture et son instant dans la capture, « partie.mp4@3:12.4 ».
 * Lisible et stable d'un plan à l'autre, contrairement à une place dans le montage. L'instant n'a pas besoin d'être exact
 * ([TOLERANCE]) : le recalage sur le son du tir ([KillInspector]) déplace les kills de 0,6 s au plus.
 */
data class KillRef(val capture: String, val at: Duration) {
    fun matches(media: MediaInfo, kill: Duration): Boolean =
        media.path.fileName.toString().equals(capture, ignoreCase = true) && (kill - at).absoluteValue <= TOLERANCE

    override fun toString() = "$capture@${clock(at)}"

    companion object {
        /** Écart toléré entre l'instant écrit et celui du kill (même marge que [dev.highlights.montage.KillInspector] et la recherche). */
        val TOLERANCE = 1.seconds

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
 */
data class MontageConstraints(
    val excluded: Set<KillRef> = emptySet(),
    val forced: Set<KillRef> = emptySet(),
    val pins: Map<KillRef, MontagePin> = emptyMap(),
) {
    val isEmpty: Boolean get() = excluded.isEmpty() && forced.isEmpty() && pins.isEmpty()

    /** Le kill n'a plus aucune contrainte (avant de lui en donner une autre : elles s'excluent). */
    fun clear(ref: KillRef) = MontageConstraints(excluded - ref, forced - ref, pins - ref)

    fun exclude(ref: KillRef) = clear(ref).copy(excluded = excluded + ref)

    fun force(ref: KillRef) = clear(ref).copy(forced = forced + ref)

    /** Une seule drop : l'épingler ailleurs la retire au groupe qui l'avait. */
    fun pin(ref: KillRef, pin: MontagePin): MontageConstraints {
        val others = if (pin == MontagePin.Drop) pins.filterValues { it != MontagePin.Drop } else pins
        return clear(ref).copy(pins = others - ref + (ref to pin))
    }

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
        val all = constraints.excluded + constraints.forced + constraints.pins.keys
        all.firstOrNull { ref -> groups.none { g -> g.kills.any { ref.matches(g.media, it) } } }?.let {
            throw InputException(Message("error.montage.edit.notFound", "kill" to it.toString()))
        }
        all.firstOrNull { ref -> listOf(ref in constraints.excluded, ref in constraints.forced, ref in constraints.pins).count { it } > 1 }?.let {
            throw InputException(Message("error.montage.edit.conflict", "kill" to it.toString()))
        }
        if (constraints.pins.values.count { it == MontagePin.Drop } > 1) throw InputException(Message("error.montage.edit.twoDrops"))
        return groups.mapNotNull { g ->
            val kept = g.kills.indices.filter { i -> constraints.excluded.none { it.matches(g.media, g.kills[i]) } }
            if (kept.isEmpty()) return@mapNotNull null
            val trimmed = when {
                kept.size == g.kills.size -> g
                g.traits.isEmpty() -> g.copy(kills = kept.map { g.kills[it] })
                else -> MontagePlanner.withTraits(g, kept.map { g.kills[it] }, kept.map { g.traits[it] }, style)
            }
            fun designates(ref: KillRef) = trimmed.kills.any { ref.matches(g.media, it) }
            val pins = constraints.pins.filterKeys(::designates).values.distinct()
            if (pins.size > 1) throw InputException(Message("error.montage.edit.conflict", "kill" to label(trimmed)))
            trimmed.copy(forced = constraints.forced.any(::designates), pin = pins.firstOrNull())
        }
    }

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
                }
                null -> if (clip == null) throw HighlightsException(Message("error.montage.edit.notPlaced", "kill" to label(g)))
            }
        }
    }

    /** Temps du morceau le plus proche de [t]. */
    fun nearestBeat(music: MusicAnalysis, t: Duration): Int =
        music.beats.indices.minByOrNull { (music.beats[it] - t).absoluteValue } ?: 0
}
