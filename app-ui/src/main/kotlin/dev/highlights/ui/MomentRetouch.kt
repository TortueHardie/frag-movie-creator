package dev.highlights.ui

import dev.highlights.core.model.ClipOrder
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.editing.DefaultEditPlanner
import dev.highlights.editing.PlannedClip
import dev.highlights.scoring.MomentEdits
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Retouches à la main des moments ouverts (bornes, ajout, ordre du montage), appliquées à [SessionState] par
 * [AppController]. La logique des moments vit dans [MomentEdits] ; ici, seulement le passage clé de la liste ↔ capture.
 */
internal object MomentRetouch {

    /** Longueur d'un moment ajouté d'un double-clic : un kill avec son approche et sa suite, bornée par le profil. */
    val ADDED_LENGTH = 8.seconds

    /** Moments cochés dans l'ordre où l'export les enchaînera (ordre manuel s'il y en a un, sinon chronologique). */
    fun montageOrder(session: SessionState): List<Segment> {
        val segments = session.segments.filter { it.highlight.enabled }
        val manual = segments.any { it.highlight.order != null }
        val clips = segments.map { PlannedClip(it.highlight, session.entries[it.entry].session.media) }
        val ordered = DefaultEditPlanner.order(clips, if (manual) ClipOrder.MANUAL else ClipOrder.CHRONOLOGICAL)
        // Un même objet Highlight par moment : l'identité retrouve le segment (clé comprise).
        return ordered.map { c -> segments.first { it.highlight === c.highlight } }
    }

    /** Déplace le début (ou la fin si [end]) du moment [key] de [delta] ; renvoie la session et la nouvelle clé du moment. */
    fun nudge(session: SessionState, key: String, end: Boolean, delta: Duration, policy: SelectionPolicy): Pair<SessionState, String> {
        val segment = session.segments.firstOrNull { it.key == key } ?: return session to key
        val entry = session.entries[segment.entry]
        val before = entry.session.highlights
        val after = MomentEdits.adjust(before, segment.highlight.id, delta, end, entry.session.media.duration, policy)
        val changed = after.firstOrNull { it !in before } ?: return session to key
        val updated = replace(session, segment.entry, after)
        return updated to updated.keyOf(segment.entry, changed)
    }

    /** Ajoute un moment centré sur [at] dans la capture [entry] ; renvoie la session et la clé du nouveau moment. */
    fun add(session: SessionState, entry: Int, at: Duration, policy: SelectionPolicy): Pair<SessionState, String>? {
        val e = session.entries.getOrNull(entry) ?: return null
        val length = ADDED_LENGTH.coerceIn(policy.minClip, policy.maxClip)
        val before = e.session.highlights
        val after = MomentEdits.add(before, at, length, e.session.media.duration, e.session.timeline, e.session.media.path)
        val added = after.first { it !in before }
        // Un ordre manuel compte tous les moments de la soirée : le nouveau passe après le dernier rang, toutes captures confondues.
        val last = session.highlights.mapNotNull { it.order }.maxOrNull()
        val placed = after.map { if (it === added) it.copy(order = last?.plus(1)) else it }
        val updated = replace(session, entry, placed)
        return updated to updated.keyOf(entry, placed.first { it.id == added.id })
    }

    /**
     * Fait monter ([delta] < 0) ou descendre le moment [key] dans l'ordre du montage. Chaque moment coché prend son rang ;
     * un moment décoché perd le sien (recoché, il passera à la fin).
     */
    fun move(session: SessionState, key: String, delta: Int): SessionState {
        val ordered = montageOrder(session)
        val index = ordered.indexOfFirst { it.key == key }
        val moved = MomentEdits.move(ordered, index, delta)
        if (moved == ordered) return session
        val rank = moved.withIndex().associate { (i, s) -> s.key to i }
        val entries = session.entries.mapIndexed { i, e ->
            e.copy(session = e.session.copy(highlights = e.session.highlights.map { h -> h.copy(order = rank[session.keyOf(i, h)]) }))
        }
        return session.copy(entries = entries)
    }

    private fun replace(session: SessionState, entry: Int, highlights: List<Highlight>): SessionState =
        session.copy(entries = session.entries.mapIndexed { i, e -> if (i == entry) e.copy(session = e.session.copy(highlights = highlights)) else e })
}
