package dev.highlights.vision

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.Serializable
import java.text.Normalizer
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

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

/**
 * Notification qui crédite des kills d'un coup, en retard sur l'action (Apex : « SQUAD WIPE ») : le dernier ennemi
 * d'une escouade tombe, et tous ceux que le joueur avait mis à terre meurent avec lui. Une ligne lue dont le texte
 * correspond à l'un des libellés [match] (comparés comme ceux de [LogRule]) est :
 * - datée de la dernière ligne [to] (« knock ») lue au plus [within] avant elle : la notification peut arriver
 *   jusqu'à 4,5 s après la mise à terre, cachée par un menu (death box ouverte, « Apex 37 kills revenant » à 3:43) ;
 * - comptée autant de fois qu'il y a eu de lignes [to] depuis l'événement précédent de son type, sans remonter plus
 *   loin que [countSince] (une au moins) ; avec [countSince] null, une seule fois ;
 * - écartée si aucune ligne [to] ne la précède depuis l'événement précédent et qu'une même notification a été comptée
 *   moins de [within] avant : c'est la même, relue (deux « SQUAD WIPE » à 1,5 s, 7:29 de la même capture).
 * Le compte est une estimation : sur les deux captures mesurées, juste pour 15 « SQUAD WIPE » sur 24, un de moins
 * pour 5 (mise à terre non lue), un de plus pour 1.
 */
@Serializable
data class LogCredit(
    val match: List<String>,
    val to: String,
    val within: SerialDuration,
    val countSince: SerialDuration? = null,
) {
    init {
        require(match.isNotEmpty() && match.all { RewardsLog.letters(it) == it && it.isNotEmpty() }) {
            "libellés en majuscules sans accents ni ponctuation attendus : $match"
        }
        require(within.isPositive() && (countSince == null || countSince.isPositive())) { "durées positives attendues : $this" }
    }
}

/**
 * Coups de grâce (Apex : « ELIMINATED », « SQUAD WIPE ») qui ne comptent pas en plus du coup qui a mis l'ennemi à terre
 * (« KNOCKED DOWN »). Le joueur l'a voulu ainsi : le kill, c'est la mise à terre, pas l'achèvement (souvent 2 à 10 s plus
 * tard, ou jamais montré). Les lignes [finish] sont rapprochées des lignes [hit] encore ouvertes (lues au plus [within]
 * avant elles) :
 * - une ligne [finish] referme la plus ancienne mise à terre ouverte, et ne donne rien de plus ;
 * - une ligne dont le texte correspond à l'un des libellés [all] (« SQUAD WIPE » : toute l'escouade tombe) les referme
 *   toutes ;
 * - sans mise à terre ouverte (achèvement d'un ennemi mis à terre hors champ, tiers qui passe), elle devient un [hit] à
 *   son propre instant, sauf si la même notification vient d'être lue moins de [repeat] avant (relecture : deux
 *   « SQUAD WIPE » à 1,5 s, « Apex 37 kills revenant » à 7:29).
 * Les lignes [finish] ne sortent pas du détecteur. Mesuré sur deux captures : 53 kills pour 56 crédits du compteur du
 * HUD, 37 pour 36 (une mise à terre relevée par les alliés compte ; un ennemi achevé sans mise à terre lue aussi).
 */
@Serializable
data class LogFinish(
    val finish: String,
    val hit: String,
    val within: SerialDuration,
    val all: List<String> = emptyList(),
    val repeat: SerialDuration = 3.seconds,
) {
    init {
        require(finish.isNotEmpty() && hit.isNotEmpty() && finish != hit) { "finish et hit distincts attendus : $this" }
        require(all.all { RewardsLog.letters(it) == it && it.isNotEmpty() }) { "libellés en majuscules sans accents ni ponctuation attendus : $all" }
        require(within.isPositive() && !repeat.isNegative()) { "durées positives attendues : $this" }
    }
}

/** Application d'un [LogFinish] aux événements lus. */
object LogFinishes {
    fun apply(events: List<LogEvent>, finish: LogFinish?): List<LogEvent> {
        if (finish == null) return events
        // À instant égal, la mise à terre d'abord : « SQUAD WIPE » et « KNOCKED DOWN » du dernier ennemi s'affichent ensemble.
        val sorted = events.sortedWith(compareBy({ it.at }, { if (it.kind == finish.hit) 0 else 1 }))
        val open = ArrayDeque<Duration>()
        val out = mutableListOf<LogEvent>()
        val lastRead = mutableMapOf<Boolean, Duration>()
        for (e in sorted) {
            while (open.isNotEmpty() && e.at - open.first() > finish.within) open.removeFirst()
            when (e.kind) {
                finish.hit -> {
                    open.addLast(e.at)
                    out += e
                }
                finish.finish -> {
                    val wipe = finish.all.isNotEmpty() && RewardsLog.classify(e.text, listOf(LogRule(e.kind, finish.all))) == e.kind
                    val previous = lastRead[wipe]
                    lastRead[wipe] = e.at
                    when {
                        open.isNotEmpty() -> if (wipe) open.clear() else open.removeFirst()
                        previous != null && e.at - previous <= finish.repeat -> Unit
                        else -> out += e.copy(kind = finish.hit)
                    }
                }
                else -> out += e
            }
        }
        return out.sortedBy { it.at }
    }
}

/** Application des [LogCredit] aux événements lus (dans l'ordre du temps). */
object LogCredits {
    fun apply(events: List<LogEvent>, credits: List<LogCredit>): List<LogEvent> {
        if (credits.isEmpty()) return events
        val sorted = events.sortedBy { it.at }
        val out = mutableListOf<LogEvent>()
        val previous = mutableMapOf<String, Duration>()
        val lastCredited = mutableMapOf<LogCredit, Duration>()
        for (e in sorted) {
            val credit = credits.firstOrNull { c -> e.kind != c.to && RewardsLog.classify(e.text, listOf(LogRule(e.kind, c.match))) == e.kind }
            if (credit == null) {
                out += e
                if (credits.none { it.to == e.kind }) previous[e.kind] = e.at
                continue
            }
            val marks = sorted.filter { it.kind == credit.to && it.at <= e.at }.map { it.at }
            val since = maxOf(previous[e.kind] ?: -Duration.INFINITE, credit.countSince?.let { e.at - it } ?: -Duration.INFINITE)
            val count = marks.count { it > since }
            previous[e.kind] = e.at
            val last = lastCredited[credit]
            if (count == 0 && last != null && e.at - last <= credit.within) continue
            lastCredited[credit] = e.at
            val at = marks.filter { it >= e.at - credit.within }.maxOrNull() ?: e.at
            repeat(if (credit.countSince == null) 1 else maxOf(1, count)) { out += e.copy(at = at) }
        }
        return out.sortedBy { it.at }
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
 *
 * [maxDrop] : lignes dont une entrée peut aussi descendre, pour les notifications empilées où la nouvelle s'insère
 * au-dessus et pousse les anciennes vers le bas (Apex : « KNOCKED DOWN » posé sur « ELIMINATED », 1:32 de la capture
 * « Apex 53 kills octane » : l'élimination poussée d'une ligne était comptée deux fois). 0 : un journal qui ne fait
 * que remonter (Wardogs).
 */
class LogTracker(private val hold: Duration, private val rowPitch: Int, private val minSightings: Int, private val maxDrop: Int = 0) {
    private class Entry(val kind: String, var y: Int, var seen: Duration, val first: Duration, var count: Int, var emitted: Boolean, val text: String)

    private var entries = listOf<Entry>()

    /** Lignes classées ([kind] → y) de l'image à [at] ; renvoie les événements confirmés à cette image. */
    fun update(at: Duration, rows: List<Triple<Int, String, String>>): List<LogEvent> {
        val alive = entries.filter { at - it.seen <= hold }.toMutableList()
        val next = mutableListOf<Entry>()
        val confirmed = mutableListOf<LogEvent>()
        for ((y, kind, text) in rows.sortedBy { it.first }) {
            val match = alive
                .filter { it.kind == kind && y >= it.y - MAX_RISE * rowPitch - rowPitch / 2 && y <= it.y + rowPitch * 6 / 10 + maxDrop * rowPitch }
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

/** Libellé de la notification centrale lu à [at] : [kind] selon les règles, "" = notification à écarter. */
data class NoticeRead(val at: Duration, val kind: String, val text: String)

/**
 * Type des icônes de notification d'après le libellé écrit sous elles (« ÉLIMINATION CONFIRMÉE », « AIDE :
 * ÉLIMINATION », « UN PASSAGER A SURVÉCU »…). L'icône seule se trompe quand le journal n'a rien lu : la tête de mort
 * sert aussi aux aides au kill (22-55-31 à 38:37, « AIDE : ÉLIMINATION +$387 » compté en kill), la poignée de main
 * aussi aux passagers déposés (18-23-47 à 15:14, « UN PASSAGER A SURVÉCU » compté en aide).
 *
 * Pour chaque icône, le libellé lu le plus près dans [before, after] l'emporte : son type remplace celui de l'icône,
 * "" l'écarte. Sans libellé lisible, l'icône garde son type.
 */
object NoticeLabels {
    fun relabel(icons: List<TimedKind>, reads: List<NoticeRead>, before: Duration, after: Duration): List<TimedKind> =
        icons.mapNotNull { icon ->
            val read = reads.filter { it.at >= icon.at - before && it.at <= icon.at + after }.minByOrNull { (it.at - icon.at).absoluteValue }
            when {
                read == null -> icon
                read.kind.isEmpty() -> null
                else -> icon.copy(kind = read.kind)
            }
        }
}

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
