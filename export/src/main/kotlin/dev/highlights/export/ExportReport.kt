package dev.highlights.export

import dev.highlights.core.model.Highlight
import dev.highlights.core.serialization.roundTo
import dev.highlights.core.serialization.toTimecode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExportReport(
    val generatedAt: String,
    /** Première capture (la seule, le plus souvent). */
    val source: String,
    /** Toutes les captures, quand le montage en réunit plusieurs. */
    val sources: List<String> = emptyList(),
    val profile: String,
    val encoder: String,
    val outputs: List<ReportOutput>,
    val totalDurationSeconds: Double,
    val highlights: List<ReportHighlight>,
    val skippedHighlights: List<ReportHighlight>,
)

@Serializable
data class ReportOutput(val format: String, val path: String)

@Serializable
data class ReportHighlight(
    val id: String,
    /** Position dans le montage (1 = premier), null si non exporté. */
    val order: Int?,
    val start: String,
    val end: String,
    val peak: String,
    val startSeconds: Double,
    val endSeconds: Double,
    val durationSeconds: Double,
    val score: Double,
    val contributions: Map<String, Double>,
    /** Capture d'où vient le moment, quand le montage en réunit plusieurs. */
    val source: String? = null,
) {
    companion object {
        fun of(h: Highlight, order: Int?, withSource: Boolean = false) = ReportHighlight(
            id = h.id,
            order = order,
            start = h.range.start.toTimecode(),
            end = h.range.end.toTimecode(),
            peak = h.peak.toTimecode(),
            startSeconds = h.range.start.inWholeMilliseconds / 1000.0,
            endSeconds = h.range.end.inWholeMilliseconds / 1000.0,
            durationSeconds = h.range.length.inWholeMilliseconds / 1000.0,
            score = h.score.roundTo(3),
            contributions = h.contributions.mapValues { it.value.roundTo(3) },
            source = if (withSource) h.source.toString() else null,
        )
    }
}

internal val reportJson = Json {
    prettyPrint = true
    encodeDefaults = true
}
