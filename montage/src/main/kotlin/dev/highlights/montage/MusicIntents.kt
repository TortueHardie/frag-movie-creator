package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.MontageSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Sortes de repères d'intention de l'étape 1 de « Montage sur la musique » (demande du 20/11 : l'étape 1 dit seulement
 * « je veux un kill ici, une coupe ici, la vitrine ici… », sans dire quel kill) :
 * - [KILL] : un kill tombe ici ;
 * - [MULTI] : un multi-kill tombe ici (son dernier kill), un repère qui vaut plusieurs kills ;
 * - [CUT] : un plan commence ici ;
 * - [SLOW] : le kill du plan qui contient cet instant passe au ralenti ;
 * - [TRANSITION] : la coupe la plus proche de cet instant se fait par une transition (whip pan).
 */
enum class IntentKind {
    KILL, MULTI, CUT, SLOW, TRANSITION;

    /** Repère qui reçoit un kill à l'étape 2. */
    val takesKill: Boolean get() = this == KILL || this == MULTI
}

/** Un repère posé sur le morceau : [at] instant du morceau ; [byHand] : posé ou touché par l'utilisateur. */
data class IntentMark(val id: Int, val kind: IntentKind, val at: Duration, val byHand: Boolean = false)

/** Plage de la vitrine (instants du morceau) : plans de partie sans kill. */
data class IntentRange(val start: Duration, val end: Duration, val byHand: Boolean = false)

/** Repères « kill » en trop (plus que de kills) : en plans de vitrine ([SHOWCASE], choix du 20/11) ou laissés vides. */
enum class ExtraMarks { SHOWCASE, EMPTY }

/**
 * Les intentions de l'étape 1 ([marks], [showcase], [extras]) et les choix faits à l'étape 2 ([fixed] : repère → kill
 * mis là à la main, échangé ou déplacé). L'étape 2 ([IntentLayout.plan]) choisit les kills des autres repères.
 */
data class MusicIntents(
    val marks: List<IntentMark>,
    val showcase: IntentRange? = null,
    val extras: ExtraMarks = ExtraMarks.SHOWCASE,
    val fixed: Map<Int, KillRef> = emptyMap(),
) {
    val killMarks: List<IntentMark> get() = marks.filter { it.kind.takesKill }.sortedBy { it.at }

    fun of(kind: IntentKind): List<IntentMark> = marks.filter { it.kind == kind }.sortedBy { it.at }

    val nextId: Int get() = (marks.maxOfOrNull { it.id } ?: 0) + 1
}

/**
 * Qui va sur quel repère : [byMark] (un multi-kill sur des repères rapprochés les prend tous), [laid] les groupes et
 * l'instant de leur dernier kill, [extras] les repères « kill » restés sans kill.
 */
data class IntentAssignment(
    val byMark: Map<Int, KillGroup>,
    val laid: List<Pair<KillGroup, Duration>>,
    val extras: List<IntentMark>,
)

/**
 * Étape 2 de « Montage sur la musique » : quel kill va sur quel repère, puis les plans construits autour
 * ([KillLayout.plan]) avec coupes, vitrine, ralentis et transitions là où les repères les demandent.
 */
object IntentLayout {
    /** Repères « kill » à ce nombre de temps ou moins : un groupe de repères rapprochés, un multi-kill de préférence. */
    const val CLUSTER_BEATS = 1.0

    /** Marge pour des repères aimantés à un temps d'écart exactement. */
    val CLUSTER_SLACK = 5.milliseconds

    /** Valeur d'un repère sur la drop (ou à un temps d'elle) en plus de la force de son temps. */
    const val DROP_BONUS = 1.0

    /**
     * Les kills de [groups] répartis sur les repères de [intents] :
     * - d'abord les repères où l'utilisateur a mis un kill à l'étape 2 ([MusicIntents.fixed]) ;
     * - puis les multi-kills, les meilleurs d'abord, sur les repères « multi-kill » et les groupes de repères
     *   rapprochés ([CLUSTER_BEATS]) les plus forts ; sans multi-kill, ces repères prennent chacun un kill ;
     * - enfin les meilleurs groupes restants sur les repères les plus forts (force du temps, la drop en prime).
     */
    fun assign(groups: List<KillGroup>, music: MusicAnalysis, intents: MusicIntents): IntentAssignment {
        val period = music.beatPeriod
        val free = groups.sortedByDescending { it.rank }.toMutableList()
        val byMark = mutableMapOf<Int, KillGroup>()
        val laid = mutableListOf<Pair<KillGroup, Duration>>()
        val marks = intents.killMarks
        fun value(m: IntentMark): Double {
            val b = music.beats.indices.minByOrNull { (music.beats[it] - m.at).absoluteValue } ?: return 0.0
            return music.strength(b) + (if (kotlin.math.abs(b - music.dropBeat) <= 1) DROP_BONUS else 0.0)
        }
        fun take(g: KillGroup, target: List<IntentMark>) {
            free.remove(g)
            target.forEach { byMark[it.id] = g }
            laid += g to target.last().at
        }
        // Les choix de l'étape 2 d'abord.
        for (m in marks) {
            val ref = intents.fixed[m.id] ?: continue
            val g = free.firstOrNull { g -> g.kills.any { ref.matches(g.media, g.detectedOf(it)) } } ?: continue
            take(g, listOf(m))
        }
        val open = marks.filter { it.id !in byMark }
        // Groupes de repères : un repère « multi-kill », ou des repères « kill » rapprochés.
        val targets = mutableListOf<List<IntentMark>>()
        var run = mutableListOf<IntentMark>()
        for (m in open) {
            val last = run.lastOrNull()
            if (m.kind == IntentKind.KILL && last != null && last.kind == IntentKind.KILL && (m.at - last.at) <= period * CLUSTER_BEATS + CLUSTER_SLACK) {
                run += m
            } else {
                if (run.isNotEmpty()) targets += run
                run = mutableListOf(m)
            }
        }
        if (run.isNotEmpty()) targets += run
        val wantsMulti = targets.filter { it.size >= 2 || it.single().kind == IntentKind.MULTI }.sortedByDescending { t -> t.maxOf(::value) }
        val singles = targets.filter { it.size == 1 && it.single().kind == IntentKind.KILL }.toMutableList()
        for (t in wantsMulti) {
            val g = free.firstOrNull { it.kills.size >= 2 }
            if (g != null) take(g, t) else singles += t.map { listOf(it) }
        }
        for (t in singles.sortedByDescending { t -> t.maxOf(::value) }) {
            val g = free.firstOrNull() ?: break
            take(g, t)
        }
        val extras = marks.filter { it.id !in byMark }
        log.info { "Repères : ${marks.size} « kill », ${laid.size} groupe(s) placés, ${extras.size} repère(s) sans kill" }
        return IntentAssignment(byMark, laid.sortedBy { it.second }, extras)
    }

    /**
     * Le plan de l'étape 2 : chaque groupe placé épinglé à son repère, coupes aux repères « coupe », vitrine sur sa
     * plage et sur les repères en trop ([ExtraMarks.SHOWCASE]), ralentis et transitions à leurs repères seulement.
     */
    fun plan(groups: List<KillGroup>, music: MusicAnalysis, settings: MontageSettings, intents: MusicIntents): MontagePlan {
        val a = assign(groups, music, intents)
        if (a.laid.isEmpty()) throw HighlightsException(Message("error.montage.noKills"))
        val pinned = a.laid.map { (g, at) -> g.copy(pin = MontagePin.At(at), forced = true) }
        val period = music.beatPeriod
        val fillers = buildList {
            intents.showcase?.let { add(it.start to it.end) }
            if (intents.extras == ExtraMarks.SHOWCASE) a.extras.forEach { add(it.at - period to it.at + period) }
        }
        return KillLayout.plan(
            pinned, music, settings,
            KillLayout.Intents(
                cuts = intents.of(IntentKind.CUT).map { it.at }, fillers = fillers,
                slows = intents.of(IntentKind.SLOW).map { it.at }, transitions = intents.of(IntentKind.TRANSITION).map { it.at },
                pool = groups,
            ),
        )
    }

    /**
     * La proposition de départ de l'étape 1, tirée du plan automatique : un repère « kill » (« multi-kill » pour un
     * multi-kill) à l'instant de chaque kill d'ancrage, un ralenti où il y en a un, une transition à chaque whip pan, la
     * plage de la vitrine.
     */
    fun propose(plan: MontagePlan): MusicIntents {
        val music = plan.music
        val marks = mutableListOf<IntentMark>()
        var id = 1
        val whips = MontageRenderBuilder.whips(plan)
        plan.clips.forEachIndexed { i, c ->
            if (c.showcase) return@forEachIndexed
            val at = music.beatTime(c.slot.startBeat) + c.anchorOutput(music)
            marks += IntentMark(id++, if (c.kills.size >= 2) IntentKind.MULTI else IntentKind.KILL, at)
            if (c.slow != null) marks += IntentMark(id++, IntentKind.SLOW, at)
            if (i > 0 && whips[i] != null) marks += IntentMark(id++, IntentKind.TRANSITION, music.beatTime(c.slot.startBeat))
        }
        val shots = plan.clips.filter { it.showcase }
        val showcase = shots.takeIf { it.isNotEmpty() }?.let { IntentRange(music.beatTime(it.first().slot.startBeat), music.beatTime(it.last().slot.endBeat)) }
        return MusicIntents(marks.sortedBy { it.at }, showcase)
    }
}
