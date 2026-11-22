package dev.highlights.pipeline

import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.session.Session
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Clips enregistrés deux fois. Un tampon de rejeu (FTHRClips, ShadowPlay, Medal) garde les dernières secondes jouées :
 * appuyer deux fois sur la touche à 20 s d'écart donne deux clips qui partagent 40 s de jeu, et les mêmes kills. On ne
 * jette pas le second clip (ses 20 dernières secondes sont nouvelles) : on retire de lui les événements déjà vus dans un
 * clip plus ancien qui couvre le même instant.
 */
object ClipRepeats {
    /**
     * Écart toléré entre deux instants d'un même événement vus par deux clips : l'heure d'un nom de clip est à la seconde
     * et elle date l'appui sur la touche, pas l'image exacte où le clip finit.
     */
    val TOLERANCE: Duration = 1500.milliseconds

    /**
     * [sessions] sans les événements déjà vus par un clip plus ancien du même profil (même sorte, à [TOLERANCE] près,
     * appariés un à un : un doublé vu par les deux clips retire deux kills, pas un). Seuls les clips dont l'heure vient
     * de l'enregistrement ([dev.highlights.core.model.MediaInfo.reliableTime]) sont rapprochés ; les autres sessions,
     * et les parties entières, sont rendues telles quelles, dans le même ordre.
     */
    fun remove(sessions: List<Session>): List<Session> {
        val clips = sessions.withIndex()
            .filter { (_, s) -> s.media.isClip && s.media.reliableTime && s.media.recordedAt != null }
            .sortedBy { it.value.media.recordedAt }
        if (clips.size < 2) return sessions
        val result = sessions.toMutableList()
        clips.forEachIndexed { k, (index, clip) ->
            val start = clip.media.recordedAt!!
            val earlier = clips.subList(0, k).map { it.value }.filter { it.profileId == clip.profileId && overlap(it, clip) }
            if (earlier.isEmpty()) return@forEachIndexed
            // Événements déjà vus, en heure absolue, chacun ne pouvant effacer qu'un événement du clip.
            val seen = earlier.flatMap { e -> e.timeline.events.map { it.kind to absolute(e, it) } }.toMutableList()
            val kept = clip.timeline.events.filter { ev ->
                val at = start + ev.at.toJavaDuration()
                val match = seen.withIndex()
                    .filter { (_, s) -> s.first == ev.kind && distance(s.second, at) <= TOLERANCE }
                    .minByOrNull { (_, s) -> distance(s.second, at) }
                if (match != null) seen.removeAt(match.index)
                match == null
            }
            if (kept.size != clip.timeline.events.size) result[index] = clip.copy(timeline = clip.timeline.copy(events = kept))
        }
        return result
    }

    /**
     * [sessions] dont les moments cochés d'un clip qui montrent un passage déjà montré par un moment coché d'un clip plus
     * ancien sont décochés : un récap ne repasse pas deux fois le même kill.
     */
    fun withoutRepeatedMoments(sessions: List<Session>): List<Session> {
        val order = sessions.withIndex().sortedWith(compareBy(nullsLast()) { it.value.media.recordedAt })
        val shown = mutableListOf<Pair<Instant, Instant>>()
        val result = sessions.toMutableList()
        for ((index, s) in order) {
            val start = s.media.recordedAt
            val timed = start != null && s.media.isClip && s.media.reliableTime
            val highlights = s.highlights.map { h ->
                if (!h.enabled || !timed) return@map h
                val from = start!! + h.range.start.toJavaDuration()
                val to = start + h.range.end.toJavaDuration()
                val repeated = shown.any { (a, b) -> minOf(b, to).toEpochMilli() - maxOf(a, from).toEpochMilli() > (to.toEpochMilli() - from.toEpochMilli()) / 2 }
                if (repeated) h.copy(enabled = false) else h.also { shown += from to to }
            }
            if (highlights != s.highlights) result[index] = s.copy(highlights = highlights)
        }
        return result
    }

    private fun absolute(s: Session, e: TimelineEvent): Instant = s.media.recordedAt!! + e.at.toJavaDuration()

    private fun distance(a: Instant, b: Instant): Duration = (a.toEpochMilli() - b.toEpochMilli()).let { kotlin.math.abs(it) }.milliseconds

    private fun overlap(a: Session, b: Session): Boolean {
        val tol = TOLERANCE.toJavaDuration()
        val aStart = a.media.recordedAt!!
        val bStart = b.media.recordedAt!!
        return aStart.minus(tol) < bStart + b.media.duration.toJavaDuration() && bStart.minus(tol) < aStart + a.media.duration.toJavaDuration()
    }
}

/**
 * Séance de jeu : une partie entière, ou des clips d'un même jeu sauvegardés à la suite (moins de
 * [PlaySessions.CLIP_GAP] entre la fin d'un clip et le début du suivant). Un joueur qui ne garde que des clips n'a pas de
 * « partie » : ses clips d'une heure de jeu font une séance, comptée comme une partie dans les statistiques.
 */
data class PlaySession(val games: List<GameStats>) {
    init {
        require(games.isNotEmpty())
    }

    val clips: Boolean get() = games.first().clip
    val game: String get() = games.first().game
    val start: Instant? get() = games.first().playedAt
    val end: Instant? get() = games.mapNotNull { g -> g.playedAt?.plus(g.duration.toJavaDuration()) }.maxOrNull()
    val kills: Int get() = games.sumOf { it.kills }
}

object PlaySessions {
    /**
     * Plus long écart entre deux clips d'une même séance. Un clip tous les quarts d'heure reste une séance (une partie
     * de VALORANT dure 40 min, on ne garde pas un clip par round) ; une pause d'une heure en ouvre une autre.
     */
    val CLIP_GAP: Duration = 45.minutes

    /**
     * [games] (triées par heure, celles sans heure à la fin) en séances : chaque partie entière seule, les clips d'un
     * même jeu à moins de [gap] d'écart ensemble. Une partie entière entre deux clips coupe la séance.
     */
    fun of(games: List<GameStats>, gap: Duration = CLIP_GAP): List<PlaySession> {
        val sessions = mutableListOf<MutableList<GameStats>>()
        for (g in games.sortedWith(compareBy(nullsLast()) { it.playedAt })) {
            val current = sessions.lastOrNull()
            val last = current?.last()
            val joins = g.clip && last != null && last.clip && last.game == g.game && g.playedAt != null && last.playedAt != null &&
                current.mapNotNull { c -> c.playedAt?.plus(c.duration.toJavaDuration()) }.max().plus(gap.toJavaDuration()) >= g.playedAt
            if (joins) current!!.add(g) else sessions.add(mutableListOf(g))
        }
        return sessions.map { PlaySession(it) }
    }
}
