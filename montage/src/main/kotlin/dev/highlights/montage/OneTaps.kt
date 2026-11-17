package dev.highlights.montage

import dev.highlights.core.i18n.Message
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
            throw InputException(Message("error.onetaps.noShots"))
        }
        // Une partie où aucun tir à la tête n'a été vu ni entendu : le profil n'en détecte pas (capture sans Outplayed,
        // gabarit du son absent). Une vraie partie sans aucun tir à la tête est rare.
        val strict = style.headshotEvent.isNotEmpty() && sessions.any { s -> s.timeline.events.any { it.kind == style.headshotEvent } }
        if (!strict && !settings.oneTaps.allowWithoutHeadshots) {
            throw InputException(Message("error.onetaps.noHeadshots"))
        }
        fun oneTap(t: KillTraits) = t.shots == 1 && (t.headshot || !strict)
        val kept = groups.filter { g -> g.kills.isNotEmpty() && g.kills.all { oneTap(g.traitsOf(it)) } }
        val known = groups.sumOf { g -> g.traits.count { it.shots != null } }
        log.info {
            "Onetaps : ${kept.sumOf { it.kills.size }} kill(s) gardé(s) sur ${groups.sumOf { it.kills.size }} " +
                "($known avec leurs balles comptées)" +
                if (strict) "" else ", tirs à la tête inconnus : tout kill d'une balle compte"
        }
        if (kept.isEmpty()) throw InputException(Message("error.onetaps.none"))
        return Selection(kept, strict)
    }
}
