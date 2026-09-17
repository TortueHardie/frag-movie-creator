package dev.highlights.scoring

import dev.highlights.core.model.Highlight

object HighlightMerge {
    /**
     * Après un recalcul de la sélection (seuil ou cible modifiés), garde décochés les nouveaux segments
     * qui recouvrent un segment que l'utilisateur avait décoché.
     */
    fun preserveDisabled(previous: List<Highlight>, fresh: List<Highlight>): List<Highlight> {
        val disabled = previous.filterNot { it.enabled }.map { it.range }
        if (disabled.isEmpty()) return fresh
        return fresh.map { h ->
            val overlapsDisabled = disabled.any { d -> d.start < h.range.end && h.range.start < d.end }
            if (overlapsDisabled) h.copy(enabled = false) else h
        }
    }
}
