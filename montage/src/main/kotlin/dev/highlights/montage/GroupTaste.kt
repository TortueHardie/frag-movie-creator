package dev.highlights.montage

import dev.highlights.core.serialization.roundTo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Sortes de groupes de kills dont l'utilisateur peut montrer qu'il les aime ou non dans l'éditeur de plan. [key] : nom
 * gardé dans `taste-history.json` et clé de traduction (`ui.taste.trait.<key>`). La distance du kill n'en fait pas
 * partie : aucun détecteur ne la mesure aujourd'hui.
 */
enum class GroupTrait(val key: String) {
    /** Deux kills ou plus enchaînés. */
    MULTI_KILL("multiKill"),

    /** Tous les kills du groupe à la tête. */
    HEADSHOT("headshot"),

    /** Au moins un kill d'une balle à la tête ([KillTraits.oneTap]). */
    ONE_TAP("oneTap"),

    /** Au moins un flick net ([KillTraits.STRONG_FLICK]). */
    FLICK("flick"),

    ACE("ace"),
    CLUTCH("clutch"),

    /** Kill aussitôt payé ([RoundOutcome.traded]) : le joueur meurt juste après. */
    TRADED("traded"),
    ;

    companion object {
        fun byKey(key: String): GroupTrait? = entries.firstOrNull { it.key == key }

        /** Sortes de [group], d'après ce que l'analyse et l'inspection en savent ([KillGroup.traits], [KillGroup.outcome]). */
        fun of(group: KillGroup): Set<GroupTrait> = buildSet {
            if (group.kills.size >= 2) add(MULTI_KILL)
            if (group.traits.size == group.kills.size && group.traits.isNotEmpty() && group.traits.all { it.headshot }) add(HEADSHOT)
            if (group.traits.any { it.oneTap }) add(ONE_TAP)
            if (group.traits.any { it.flick >= KillTraits.STRONG_FLICK }) add(FLICK)
            if (group.outcome.ace) add(ACE)
            if (group.outcome.clutch) add(CLUTCH)
            if (group.outcome.traded || group.trade) add(TRADED)
        }
    }
}

/** Ce que l'utilisateur a fait d'un groupe dans l'éditeur : rien, écarté (un de ses kills au moins), gardé de force ou épinglé. */
@Serializable
enum class GroupAction { NONE, EXCLUDED, FORCED }

/** Un groupe proposé au moment d'une correction : ses sortes ([GroupTrait.key]) et ce que l'utilisateur en a fait. */
@Serializable
data class TasteGroup(val traits: List<String>, val action: GroupAction, val kills: Int = 1)

/**
 * Préférences de groupes apprises ([GroupTasteLearner]) : [bonus] par sorte, en kills (ajouté à [KillGroup.rank]) ;
 * [actions] : nombre de groupes de cette sorte écartés ou gardés par l'utilisateur ; [edits] corrections utilisables.
 * [active] : assez de corrections pour que les préférences servent. Une sorte sans assez d'actions n'a pas de bonus.
 */
data class LearnedGroupTaste(
    val bonus: Map<GroupTrait, Double>,
    val actions: Map<GroupTrait, Int>,
    val edits: Int,
    val active: Boolean,
) {
    /** Bonus d'un groupe : la somme des bonus de ses sortes, bornée à ±[GroupTasteLearner.MAX_TOTAL]. Zéro si inactif. */
    fun of(group: KillGroup): Double {
        if (!active) return 0.0
        return GroupTrait.of(group).sumOf { bonus[it] ?: 0.0 }.coerceIn(-GroupTasteLearner.MAX_TOTAL, GroupTasteLearner.MAX_TOTAL)
    }

    /** [groups] avec leur bonus ([KillGroup.taste]) ; inchangés si inactif (même plan qu'avant l'apprentissage). */
    fun applyTo(groups: List<KillGroup>): List<KillGroup> = if (!active) groups else groups.map { it.copy(taste = of(it)) }

    companion object {
        val NONE = LearnedGroupTaste(emptyMap(), emptyMap(), 0, active = false)
    }
}

/**
 * Apprend des corrections de l'éditeur ([TasteEdit.groups]) quelles sortes de groupes l'utilisateur préfère. Chaque
 * groupe proposé vaut +1 s'il l'a gardé de force ou épinglé, −1 s'il en a écarté un kill, 0 s'il n'y a pas touché. Pour
 * une sorte, la moyenne de ces valeurs sur les groupes de cette sorte (rappelée vers 0 par [PRIOR] groupes neutres), moins
 * la même moyenne sur tous les groupes : un utilisateur qui écarte beaucoup n'en déteste pas pour autant toutes les
 * sortes, seule compte la différence. Le bonus est cette différence × [GAIN], borné à ±[MAX_BONUS] kill, nul sous
 * [MIN_BONUS] (bruit). Pas de
 * descente de gradient : un comptage, donc déterministe et explicable (« 4 flicks gardés, aucun écarté »).
 */
object GroupTasteLearner {
    /** En dessous, aucune préférence : deux ou trois corrections ne disent pas un goût. */
    const val MIN_EDITS = 5

    /** Groupes écartés ou gardés d'une sorte en dessous desquels elle n'a pas de bonus. */
    const val MIN_ACTIONS = 3

    /** Groupes neutres ajoutés à chaque moyenne : avec peu de groupes d'une sorte, sa préférence reste modeste. */
    const val PRIOR = 4.0

    /**
     * Un écart de moyenne de 1 (toujours gardé face à toujours ignoré) vaut 1,5 kill : un flick préféré passe devant
     * un kill seul sans flick, pas devant un triple kill.
     */
    const val GAIN = 1.5

    /** Bonus d'une sorte, en kills : une préférence ne fait jamais passer un kill seul devant un double kill. */
    const val MAX_BONUS = 0.75

    /**
     * En deçà, pas de bonus : c'est du bruit. Sur la démonstration synthétique (goût caché : flicks gardés, échanges
     * écartés, 10 % d'actions au hasard), une sorte neutre (tête, multi-kill) sortait entre −0,18 et +0,12 kill de 5 à
     * 100 corrections, quand les vraies préférences atteignaient ±0,3 dès 7 corrections et ±0,5 à 0,7 ensuite.
     */
    const val MIN_BONUS = 0.2

    /** Bonus d'un groupe (somme de ses sortes), en kills. */
    const val MAX_TOTAL = 1.0

    fun learn(edits: List<TasteEdit>, minEdits: Int = MIN_EDITS): LearnedGroupTaste {
        val usable = edits.filter { e -> e.groups.isNotEmpty() && e.groups.any { it.action != GroupAction.NONE } }
        val groups = usable.flatMap { it.groups }
        val actions = GroupTrait.entries.associateWith { t -> groups.count { t.key in it.traits && it.action != GroupAction.NONE } }
        if (usable.size < minEdits) return LearnedGroupTaste(emptyMap(), actions, usable.size, active = false)
        fun mean(of: List<TasteGroup>) = of.sumOf { value(it.action) } / (of.size + PRIOR)
        val baseline = mean(groups)
        val bonus = GroupTrait.entries.filter { actions.getValue(it) >= MIN_ACTIONS }.associateWith { t ->
            (GAIN * (mean(groups.filter { t.key in it.traits }) - baseline)).coerceIn(-MAX_BONUS, MAX_BONUS).roundTo(3)
        }.filterValues { abs(it) >= MIN_BONUS }
        log.info { "Préférences de groupes apprises sur ${usable.size} corrections : $bonus" }
        return LearnedGroupTaste(bonus, actions, usable.size, active = true)
    }

    private fun value(action: GroupAction) = when (action) {
        GroupAction.FORCED -> 1.0
        GroupAction.EXCLUDED -> -1.0
        GroupAction.NONE -> 0.0
    }

    /**
     * Les groupes proposés ([candidates], avant corrections) et ce que [constraints] en fait, à garder dans
     * [TasteEdit.groups]. Un groupe dont un kill est écarté et un autre gardé compte comme gardé : l'utilisateur tient
     * au groupe, il en retire un kill.
     */
    fun record(candidates: List<KillGroup>, constraints: MontageConstraints): List<TasteGroup> {
        fun hits(refs: Collection<KillRef>): Set<Pair<java.nio.file.Path, Duration>> = refs.mapNotNull { MontageEdits.designated(it, candidates) }.toSet()
        val excluded = hits(constraints.excluded)
        val kept = hits(constraints.forced + constraints.pins.keys)
        return candidates.map { g ->
            val keys = g.kills.map { g.media.path to it }
            val action = when {
                keys.any { it in kept } -> GroupAction.FORCED
                keys.any { it in excluded } -> GroupAction.EXCLUDED
                else -> GroupAction.NONE
            }
            TasteGroup(GroupTrait.of(g).map { it.key }.sorted(), action, g.kills.size)
        }
    }
}
