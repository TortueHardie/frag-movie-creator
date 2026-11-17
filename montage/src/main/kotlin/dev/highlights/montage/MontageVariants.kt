package dev.highlights.montage

import dev.highlights.core.model.MontageSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

private val log = KotlinLogging.logger {}

/**
 * Plusieurs montages à montrer en aperçu, au lieu du seul que [MontagePlanner.best] retient : l'utilisateur juge à
 * l'image et choisit celui qui part au rendu final.
 *
 * Les variantes sont celles que [MontagePlanner.best] note déjà (échelle de la grille × place de la drop), plus le plan
 * de base. Classées comme lui les classe : la première est exactement le montage que `best` aurait rendu. Les suivantes
 * doivent s'écarter de toutes celles déjà retenues d'au moins [MIN_DISTANCE] (voir [distance]) : trois fois le même
 * montage à un temps près n'apprend rien à celui qui choisit.
 */
object MontageVariants {
    /** Nombre de variantes proposées par défaut : assez pour comparer, assez peu pour les regarder toutes. */
    const val DEFAULT_COUNT = 3

    /**
     * Écart minimal entre deux variantes proposées (0 : le même montage, 1 : rien en commun). 0,25 : un quart des plans
     * changent de clip ou de place, ou un quart des coupes tombent ailleurs, ou la musique part d'un quart du montage
     * plus loin. En dessous, deux aperçus se regardent comme le même montage.
     */
    const val MIN_DISTANCE = 0.25

    /** Variante classée : le plan, sa note ([MontageScorer]) et son rang dans le classement de [MontagePlanner.best]. */
    data class Ranked(val plan: MontagePlan, val score: Double)

    /**
     * Jusqu'à [count] plans assez différents ([MIN_DISTANCE]), du mieux noté au moins bien noté. Choix glouton dans
     * l'ordre du classement : la liste pour n variantes commence par celle pour n − 1, ce qui permet de retrouver la
     * variante n sans connaître le nombre demandé à l'aperçu. Moins de [count] quand les variantes se ressemblent.
     */
    fun distinct(all: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings, count: Int = DEFAULT_COUNT): List<Ranked> {
        require(count >= 1) { "au moins une variante" }
        val ranked = ranked(all, music, settings)
        val kept = mutableListOf<Ranked>()
        for (candidate in ranked) {
            if (kept.size == count) break
            if (kept.all { distance(it.plan, candidate.plan) >= MIN_DISTANCE }) kept += candidate
        }
        log.info {
            "Variantes proposées : ${kept.size} sur ${ranked.size} notées (" +
                kept.joinToString(", ") { "${it.plan.variant} note ${"%.3f".format(it.score)}" } + ")"
        }
        return kept
    }

    /**
     * Plan de base et variantes de [MontagePlanner.best], dans l'ordre où il les préfère : la note, le plan de base
     * d'abord à [MontagePlanner.MIN_GAIN] près (il ne cède que battu nettement). Les variantes qui perdent trop de clips
     * face au plan de base sont écartées comme dans `best`.
     */
    fun ranked(all: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings): List<Ranked> {
        val base = MontagePlanner.plan(all, music, settings)
        val minClips = ceil(base.clips.size * MontagePlanner.MIN_CLIP_SHARE).toInt()
        val variants = MontagePlanner.SCALES.flatMap { scale -> MontagePlanner.DROP_SHIFTS.map { PlanVariant(scale, it) } }.mapNotNull { variant ->
            runCatching { MontagePlanner.plan(all, music, settings, variant) }.getOrNull()?.takeIf { it.clips.size >= minClips }
        }
        val scored = (listOf(base) + variants).map { Ranked(it, MontageScorer.score(it).total) }
        // Tri stable : à note égale, l'ordre d'essai de `best` (base, puis grilles croissantes, puis drop avancée).
        return scored.sortedByDescending { it.score + if (it.plan === base) MontagePlanner.MIN_GAIN else 0.0 }
    }

    /**
     * Écart entre deux montages sur la même musique, de 0 (le même) à 1 : le plus grand de trois écarts, chacun ce que
     * l'œil ou l'oreille remarque en regardant les deux à la suite.
     * - **Clips** : part des plans qui ne se retrouvent pas dans l'autre montage dans le même ordre (plus longue
     *   sous-suite commune des groupes de kills) ; insérer un plan au début ne change pas tous les autres.
     * - **Coupes** : part des coupes (en temps depuis le début du montage) sans coupe de l'autre à un temps près.
     * - **Passage** : départ de la musique déplacé, en part de la longueur du montage, au-delà d'un temps.
     * Un montage décalé d'un temps en entier (mêmes clips, mêmes coupes) vaut 0.
     */
    fun distance(a: MontagePlan, b: MontagePlan): Double = max(clips(a, b), max(cuts(a, b), passage(a, b)))

    internal fun clips(a: MontagePlan, b: MontagePlan): Double {
        val x = a.clips.map(::key)
        val y = b.clips.map(::key)
        return 1.0 - lcs(x, y).toDouble() / max(x.size, y.size)
    }

    internal fun cuts(a: MontagePlan, b: MontagePlan): Double {
        val x = cutBeats(a)
        val y = cutBeats(b)
        if (x.isEmpty() && y.isEmpty()) return 0.0
        fun unmatched(from: List<Int>, to: List<Int>) = if (from.isEmpty()) 0.0 else from.count { c -> to.none { abs(it - c) <= 1 } }.toDouble() / from.size
        return (unmatched(x, y) + unmatched(y, x)) / 2
    }

    internal fun passage(a: MontagePlan, b: MontagePlan): Double =
        ((abs(a.startBeat - b.startBeat) - 1).coerceAtLeast(0).toDouble() / max(a.totalBeats, b.totalBeats)).coerceAtMost(1.0)

    /** Coupes intérieures, en temps depuis le début du montage. */
    private fun cutBeats(plan: MontagePlan): List<Int> = plan.clips.drop(1).map { it.slot.startBeat - plan.startBeat }

    /** Un plan se reconnaît à ce qu'il montre : la capture et ses kills (un plan sans kill, à son début). */
    private fun key(clip: MontageClip): String =
        clip.group.media.path.toString().lowercase() + "@" + (clip.group.kills.ifEmpty { listOf(clip.start) }).joinToString(",") { it.inWholeMilliseconds.toString() }

    private fun <T> lcs(x: List<T>, y: List<T>): Int {
        val row = IntArray(y.size + 1)
        for (i in x.indices) {
            var diagonal = 0
            for (j in y.indices) {
                val above = row[j + 1]
                row[j + 1] = if (x[i] == y[j]) diagonal + 1 else max(row[j + 1], row[j])
                diagonal = above
            }
        }
        return row[y.size]
    }

    /**
     * Empreinte d'un montage fini : ce qui se voit et s'entend (musique et passage, chaque plan, sa source, ses temps,
     * ses changements de vitesse). Deux calculs de la même variante donnent la même empreinte : c'est ce qui garantit que
     * le rendu final choisi après l'aperçu est bien le montage regardé, sinon il est refusé.
     */
    fun signature(plan: MontagePlan): String {
        val text = buildString {
            append(plan.music.file.fileName).append('|').append(plan.startBeat).append('|').append(plan.endBeat).append('\n')
            plan.clips.forEach { c ->
                append(c.group.media.path.fileName).append('|').append(c.start.inWholeMicroseconds).append('|').append(c.end.inWholeMicroseconds)
                append('|').append(c.slot.startBeat).append('|').append(c.slot.endBeat).append('|').append(c.anchorBeat).append('|').append(c.anchorHalf)
                append('|').append(c.leadIn.inWholeMicroseconds).append('|').append(c.showcase)
                c.speeds.forEach { s -> append('|').append(s.kind).append(':').append(s.range.start.inWholeMicroseconds).append(':').append(s.range.end.inWholeMicroseconds).append(':').append(s.factor) }
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
