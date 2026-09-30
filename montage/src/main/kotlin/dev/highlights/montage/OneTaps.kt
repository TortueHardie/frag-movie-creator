package dev.highlights.montage

import dev.highlights.core.InputException
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.session.Session
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Kills d'un montage « onetaps » : une balle, à la tête. Les groupes viennent de [MontagePlanner.groups] avec les
 * réglages de [MontageSettings.forOneTaps] (un kill par groupe, sauf deux kills au même instant).
 */
object OneTaps {

    /** Ce qui a été gardé, et si les tirs à la tête étaient connus ([strict]) ou seulement le nombre de balles. */
    data class Selection(val groups: List<KillGroup>, val strict: Boolean)

    fun select(groups: List<KillGroup>, sessions: List<Session>, settings: MontageSettings): Selection {
        val style = settings.killStyle
        if (groups.none { g -> g.traits.any { it.shots != null } }) {
            throw InputException(
                "Aucun tir entendu dans ces parties : le montage onetaps compte les balles de chaque kill dans le son du " +
                    "jeu. Réanalysez-les avec un profil qui a le détecteur game-sounds (tirs).",
            )
        }
        // Une partie où aucun tir à la tête n'a été vu ni entendu : le profil n'en détecte pas (capture sans Outplayed,
        // gabarit du son absent). Une vraie partie sans aucun tir à la tête est rare.
        val strict = style.headshotEvent.isNotEmpty() && sessions.any { s -> s.timeline.events.any { it.kind == style.headshotEvent } }
        if (!strict && !settings.oneTaps.allowWithoutHeadshots) {
            throw InputException(
                "Tirs à la tête inconnus dans ces parties (ni Outplayed, ni gabarit du son de l'impact) : impossible de " +
                    "distinguer un one tap d'un kill au corps d'une balle.",
            )
        }
        fun oneTap(t: KillTraits) = t.shots == 1 && (t.headshot || !strict)
        val kept = groups.filter { g -> g.kills.isNotEmpty() && g.kills.all { oneTap(g.traitsOf(it)) } }
        val known = groups.sumOf { g -> g.traits.count { it.shots != null } }
        log.info {
            "Onetaps : ${kept.sumOf { it.kills.size }} kill(s) gardé(s) sur ${groups.sumOf { it.kills.size }} " +
                "($known avec leurs balles comptées)" +
                if (strict) "" else ", tirs à la tête inconnus : tout kill d'une balle compte"
        }
        if (kept.isEmpty()) throw InputException("Aucun one tap dans ces parties")
        return Selection(kept, strict)
    }
}
