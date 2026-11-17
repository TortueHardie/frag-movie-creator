package dev.highlights.scoring

import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MomentOrigin
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.TimeRange
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Retouches à la main des moments d'une capture : bornes déplacées, moment ajouté, ordre du montage. Fonctions pures,
 * appelées par l'interface ; la session les garde ([Highlight.origin], [Highlight.order]) et [preserveManual] les fait
 * survivre au recalcul de la sélection (seuil ou durée changés).
 */
object MomentEdits {

    /** Identifiant d'un moment retouché ou ajouté : « m001 »… ne croise jamais les « h001 » renumérotés à chaque recalcul. */
    fun nextManualId(highlights: List<Highlight>): String {
        val used = highlights.filter { it.id.startsWith("m") }.mapNotNull { it.id.drop(1).toIntOrNull() }
        return "m%03d".format((used.maxOrNull() ?: 0) + 1)
    }

    /**
     * Déplace le début de [delta] (ou la fin si [end]) de [target], borné à la capture ([duration]) et aux longueurs
     * du profil ([policy] minClip/maxClip). Un moment déjà plus long que maxClip (étendu pour ne pas couper une phrase)
     * peut raccourcir mais pas grandir. Renvoie la liste, triée par début ; le moment retouché prend un identifiant
     * « m… » s'il était détecté.
     */
    fun adjust(
        highlights: List<Highlight>,
        targetId: String,
        delta: Duration,
        end: Boolean,
        duration: Duration,
        policy: SelectionPolicy,
    ): List<Highlight> {
        val h = highlights.firstOrNull { it.id == targetId } ?: return highlights
        val r = h.range
        val longest = maxOf(policy.maxClip, r.length)
        val shortest = minOf(policy.minClip, r.length)
        val range = if (end) {
            val e = (r.end + delta).coerceIn(r.start + shortest, minOf(duration, r.start + longest))
            TimeRange(r.start, e)
        } else {
            val s = (r.start + delta).coerceIn(maxOf(Duration.ZERO, r.end - longest), r.end - shortest)
            TimeRange(s, r.end)
        }
        if (range == r) return highlights
        val changed = h.copy(
            range = range,
            // Le pic reste dans le moment : la vignette et l'aperçu 9:16 le montrent.
            peak = h.peak.coerceIn(range.start, range.end),
            origin = if (h.origin == MomentOrigin.DETECTED) MomentOrigin.ADJUSTED else h.origin,
            id = if (h.manual) h.id else nextManualId(highlights),
        )
        return highlights.map { if (it.id == targetId) changed else it }.sortedBy { it.range.start }
    }

    /**
     * Moment de [length] centré sur [at], décalé pour rester dans la capture, coché, marqué ajouté à la main. Score et
     * événements lus dans la [timeline] (pic = meilleure fenêtre du moment), pour que le classement et le montage kills
     * le traitent comme les autres. Rang manuel : à la fin si un ordre manuel existe déjà.
     */
    fun add(highlights: List<Highlight>, at: Duration, length: Duration, duration: Duration, timeline: ScoredTimeline, source: Path): List<Highlight> {
        val range = TimeRange.placed(at - length / 2, length, TimeRange(Duration.ZERO, duration))
        // Fenêtres dont le centre tombe dans le moment.
        val windows = timeline.total.indices.filter { timeline.grid.centerOf(it) in range }
        val best = windows.maxByOrNull { timeline.total[it] }
        val added = Highlight(
            id = nextManualId(highlights),
            source = source,
            range = range,
            peak = best?.let { timeline.grid.centerOf(it).coerceIn(range.start, range.end) } ?: at.coerceIn(range.start, range.end),
            score = best?.let { timeline.total[it] } ?: 0.0,
            contributions = best?.let { i -> timeline.contributions.mapValues { (_, v) -> v.getOrElse(i) { 0.0 } } } ?: emptyMap(),
            events = timeline.events.filter { it.at in range }.groupingBy { it.kind }.eachCount(),
            origin = MomentOrigin.ADDED,
            order = highlights.mapNotNull { it.order }.maxOrNull()?.plus(1),
        )
        return (highlights + added).sortedBy { it.range.start }
    }

    /**
     * Après un recalcul : les moments retouchés ou ajoutés restent tels quels, les moments détectés qui les recouvrent
     * disparaissent (la retouche l'emporte), les décochés restent décochés ([HighlightMerge.preserveDisabled]) et un
     * moment détecté reprend le rang manuel du moment d'avant qu'il recouvre le plus.
     */
    fun preserveManual(previous: List<Highlight>, fresh: List<Highlight>): List<Highlight> {
        val manual = previous.filter { it.manual }
        val detected = HighlightMerge.preserveDisabled(previous, fresh)
            .filter { f -> manual.none { m -> m.range.start < f.range.end && f.range.start < m.range.end } }
            .map { f ->
                val ranked = previous.filter { it.order != null && overlap(it.range, f.range) > Duration.ZERO }
                    .maxByOrNull { overlap(it.range, f.range) }
                if (ranked != null) f.copy(order = ranked.order) else f
            }
        return (manual + detected).sortedBy { it.range.start }
    }

    private fun overlap(a: TimeRange, b: TimeRange): Duration = (minOf(a.end, b.end) - maxOf(a.start, b.start)).coerceAtLeast(Duration.ZERO)

    /**
     * Déplace l'élément [index] de [delta] places dans [ordered] (l'ordre du montage tel qu'affiché) et renvoie la
     * nouvelle liste ; hors bornes, rien ne bouge. Le rang de chacun = sa position ensuite ([Highlight.order]).
     */
    fun <T> move(ordered: List<T>, index: Int, delta: Int): List<T> {
        val to = index + delta
        if (index !in ordered.indices || to !in ordered.indices || delta == 0) return ordered
        val list = ordered.toMutableList()
        val item = list.removeAt(index)
        list.add(to, item)
        return list
    }
}
