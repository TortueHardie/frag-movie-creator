package dev.highlights.vision

import kotlinx.serialization.Serializable
import java.text.Normalizer
import kotlin.math.abs
import kotlin.time.Duration

/** Ligne de texte reconnue sur une image, position en pixels de la zone. */
data class OcrLine(val text: String, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Règle de lecture d'une ligne du journal : si l'un des libellés [match] y figure (à quelques erreurs de lecture près),
 * la ligne est un événement [kind] ; [kind] absent = ligne ignorée (à placer avant les règles plus larges qu'elle
 * contredit, ex. « AIDE : JOUEUR RÉANIMÉ » avant « RÉANIMÉ »). Libellés comparés en lettres majuscules sans accents
 * ni ponctuation : « AIDE : ÉLIMINATION » s'écrit AIDEELIMINATION.
 */
@Serializable
data class LogRule(val kind: String? = null, val match: List<String>) {
    init {
        require(match.isNotEmpty() && match.all { RewardsLog.letters(it) == it && it.isNotEmpty() }) {
            "libellés en majuscules sans accents ni ponctuation attendus : $match"
        }
    }
}

/** Événement lu dans le journal : [at] = première lecture, [text] = ce qui a été lu (diagnostic). */
data class LogEvent(val at: Duration, val kind: String, val text: String)

/**
 * Journal des gains (liste des actions récompensées, en haut à droite dans Wardogs) : chaque action y a sa ligne et son
 * libellé (« ÉLIMINATION +$1500 », « COÉQUIPIER RÉANIMÉ 250XP »…). Une nouvelle ligne s'ajoute en bas, les anciennes
 * remontent puis disparaissent en haut après une dizaine de secondes.
 */
object RewardsLog {

    /** En dessous, une ligne est un fragment illisible. */
    private const val MIN_LETTERS = 5

    /** Confusions fréquentes de l'OCR entre chiffres, signes et lettres. */
    private val confusions = mapOf('0' to 'O', '1' to 'I', '|' to 'I', '!' to 'I', '5' to 'S', '8' to 'B', '$' to 'S')

    /** Lettres majuscules sans accents : « Coéquipier réanimé » → COEQUIPIERREANIME. */
    fun letters(text: String): String {
        val plain = Normalizer.normalize(text.uppercase(), Normalizer.Form.NFD)
        return buildString {
            for (c in plain) {
                val m = confusions[c] ?: c
                if (m in 'A'..'Z') append(m)
            }
        }
    }

    /** Plus petite distance d'édition entre [needle] et une sous-chaîne de [haystack] (algorithme de Sellers). */
    fun fuzzyDistance(needle: String, haystack: String): Int {
        var prev = IntArray(haystack.length + 1)
        for (i in 1..needle.length) {
            val cur = IntArray(haystack.length + 1)
            cur[0] = i
            for (j in 1..haystack.length) {
                val substitution = prev[j - 1] + if (needle[i - 1] == haystack[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, substitution)
            }
            prev = cur
        }
        return prev.minOrNull() ?: needle.length
    }

    /** Type de la ligne selon la première règle qui correspond ; "" = ignorée, null = inconnue. Une erreur tolérée pour 5 lettres. */
    fun classify(text: String, rules: List<LogRule>): String? {
        val t = letters(text)
        if (t.length < MIN_LETTERS) return null
        for (rule in rules) {
            if (rule.match.any { fuzzyDistance(it, t) <= maxOf(1, it.length / 5) }) return rule.kind ?: ""
        }
        return null
    }

    /** Regroupe les morceaux d'une même ligne du journal (libellé et montant lus séparément), de haut en bas. */
    fun rows(lines: List<OcrLine>, tolerance: Int): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, MutableList<OcrLine>>>()
        for (line in lines.sortedBy { it.y }) {
            val last = out.lastOrNull()
            if (last != null && abs(last.first - line.y) <= tolerance) last.second += line else out += line.y to mutableListOf(line)
        }
        return out.map { (y, parts) -> y to parts.sortedBy { it.x }.joinToString(" ") { it.text } }
    }
}

/**
 * Suivi des lignes d'une image à l'autre, pour ne compter chaque action qu'une fois malgré les images où l'OCR ne lit
 * rien ou lit mal (texte blanc sur ciel clair). Une ligne ne fait que remonter : une ligne du même type vue au même
 * endroit ou plus bas depuis moins de [hold] est la même. Une ligne n'est comptée qu'après [minSightings] lectures
 * (une lecture isolée est souvent une confusion, ex. « SOIGNÉ » lu « SOJON ») et datée de sa première lecture.
 */
class LogTracker(private val hold: Duration, private val rowPitch: Int, private val minSightings: Int) {
    private class Entry(val kind: String, var y: Int, var seen: Duration, val first: Duration, var count: Int, var emitted: Boolean, val text: String)

    private var entries = listOf<Entry>()

    /** Lignes classées ([kind] → y) de l'image à [at] ; renvoie les événements confirmés à cette image. */
    fun update(at: Duration, rows: List<Triple<Int, String, String>>): List<LogEvent> {
        val alive = entries.filter { at - it.seen <= hold }.toMutableList()
        val next = mutableListOf<Entry>()
        val confirmed = mutableListOf<LogEvent>()
        for ((y, kind, text) in rows.sortedBy { it.first }) {
            val match = alive
                .filter { it.kind == kind && y >= it.y - MAX_RISE * rowPitch - rowPitch / 2 && y <= it.y + rowPitch * 6 / 10 }
                .minByOrNull { abs(it.y - y) }
            val entry = if (match != null) {
                alive -= match
                match.also {
                    it.y = y
                    it.seen = at
                    it.count++
                }
            } else {
                Entry(kind, y, at, at, 1, false, text)
            }
            if (!entry.emitted && entry.count >= minSightings) {
                entry.emitted = true
                confirmed += LogEvent(entry.first, kind, entry.text)
            }
            next += entry
        }
        entries = next + alive
        return confirmed
    }

    private companion object {
        /** Lignes dont une entrée peut remonter entre deux images (plusieurs lignes expirées d'un coup). */
        const val MAX_RISE = 4
    }
}

/** Événement d'une source, avant fusion. */
data class TimedKind(val at: Duration, val kind: String)

/**
 * Fusion du journal avec les icônes de notification (centre de l'écran), qui ont des défauts opposés : l'icône est
 * toujours bien vue mais ne distingue pas une aide d'un kill (même tête de mort pour « Aide : Élimination ») et ne se
 * réaffiche pas entre deux kills enchaînés ; le journal distingue tout mais son texte blanc est parfois illisible.
 *
 * Pour chaque icône, on cherche une ligne du journal apparue entre [before] avant et [after] après :
 * - même type → un seul événement, à l'instant de l'icône (le journal est lu parfois quelques secondes plus tard) ;
 * - autre type → l'icône est écartée, le journal fait foi ;
 * - rien → l'icône compte seule (ligne illisible).
 * Les lignes restantes comptent seules (kills enchaînés, actions sans icône).
 */
object EventFusion {
    fun fuse(log: List<LogEvent>, icons: List<TimedKind>, before: Duration, after: Duration): List<TimedKind> {
        val used = BooleanArray(log.size)
        val out = mutableListOf<TimedKind>()
        for (icon in icons.sortedBy { it.at }) {
            val near = log.indices.filter { !used[it] && log[it].at >= icon.at - before && log[it].at <= icon.at + after }
            val same = near.filter { log[it].kind == icon.kind }.minByOrNull { log[it].at }
            when {
                same != null -> {
                    used[same] = true
                    out += icon
                }
                near.isEmpty() -> out += icon
            }
        }
        log.forEachIndexed { i, e -> if (!used[i]) out += TimedKind(e.at, e.kind) }
        return out.sortedBy { it.at }
    }
}
