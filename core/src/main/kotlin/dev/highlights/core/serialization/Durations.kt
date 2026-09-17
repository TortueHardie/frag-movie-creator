package dev.highlights.core.serialization

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object Durations {
    private val units = Regex("""^(?:(\d+(?:\.\d+)?)h)?(?:(\d+(?:\.\d+)?)m(?!s))?(?:(\d+(?:\.\d+)?)s)?(?:(\d+(?:\.\d+)?)ms)?$""")
    private val timecode = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{1,2}(?:\.\d+)?)$""")
    private val plainNumber = Regex("""^\d+(?:\.\d+)?$""")

    fun parseOrNull(text: String): Duration? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty()) return null
        if (t.startsWith("P") || t.startsWith("-P")) return Duration.parseIsoStringOrNull(t)
        if (plainNumber.matches(t)) return t.toDouble().seconds
        timecode.matchEntire(t)?.let { m ->
            val (h, min, s) = m.destructured
            return h.ifEmpty { "0" }.toLong().hours + min.toLong().minutes + s.toDouble().seconds
        }
        val m = units.matchEntire(t) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        fun g(i: Int) = m.groupValues[i].ifEmpty { "0" }.toDouble()
        return g(1).hours + g(2).minutes + g(3).seconds + g(4).milliseconds
    }

    /** Format stable pour la sérialisation : secondes à la milliseconde, ex. "12.5s". */
    fun format(d: Duration): String {
        val ms = d.inWholeMilliseconds
        val sign = if (ms < 0) "-" else ""
        val abs = abs(ms)
        val frac = abs % 1000
        val secs = abs / 1000
        return if (frac == 0L) "$sign${secs}s" else "$sign$secs.${"%03d".format(frac).trimEnd('0')}s"
    }

    /** Secondes décimales pour les arguments FFmpeg ("12.345"), indépendamment de la locale. */
    fun ffmpegSeconds(d: Duration): String = String.format(Locale.ROOT, "%.3f", d.inWholeMicroseconds / 1_000_000.0)
}

/** "01:23.450" ou "1:02:03.450". */
fun Duration.toTimecode(): String {
    val totalMs = inWholeMilliseconds.coerceAtLeast(0)
    val h = totalMs / 3_600_000
    val m = totalMs / 60_000 % 60
    val s = totalMs / 1000 % 60
    val ms = totalMs % 1000
    return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, ms) else "%02d:%02d.%03d".format(m, s, ms)
}

/** Durée compacte pour l'affichage : "1h02m", "4m05s", "12s". */
fun Duration.toShortText(): String {
    val total = inWholeSeconds.coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return when {
        h > 0 -> "%dh%02dm".format(h, m)
        m > 0 -> "%dm%02ds".format(m, s)
        else -> "${s}s"
    }
}

fun Double.roundTo(decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return (this * factor).roundToLong() / factor
}
