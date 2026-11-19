package dev.highlights.montage

import dev.highlights.core.model.MontageSettings
import dev.highlights.core.session.Session
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Kills faits avec une capacité (molly, flèche, drone, ultime…) écartés du montage ([MontageSettings.skipAbilityKills]) :
 * la cible meurt hors du réticule, souvent hors champ, et le plan ne montre rien de lisible.
 *
 * Reconnus à ce que le compteur de munitions du HUD ne voit **aucune balle** autour du kill. Deux conditions :
 * - [KillTraits.shots] = 0 : le compteur est lu dans cette partie ([MontagePlanner.hudShots] est seul à donner 0 ; les
 *   tirs entendus laissent un kill sans tir « inconnu ») et aucune balle n'est attribuée au kill ;
 * - aucune balle brute du compteur de [BEFORE] avant le kill à [AFTER] après. L'attribution seule ne suffit pas : dans
 *   un doublé à 0,4 s (« Valorant- 44 kills », 25:04), les deux kills étaient rapprochés de la même ligne du killfeed,
 *   toutes les balles allaient au premier et le second, au Vandal, passait pour une capacité.
 *
 * Mesuré sur 61 kills à l'arme de deux vidéos VALORANT 854x480 (arme vérifiée dans le killfeed image par image :
 * Vandal, Phantom, Sheriff) : au moins une balle brute dans la fenêtre pour chacun, aucun écarté. Le son seul en aurait
 * écarté 1 (fenêtre de 1 s), 5 avec 0,4 s : il ne sert pas ici. Le couteau ne tire pas non plus : un kill au couteau
 * est écarté aussi. Aucun kill à la capacité du joueur dans ces vidéos : ce qui est vraiment reconnu reste à mesurer.
 *
 * Pas d'icône de l'arme lue dans le killfeed : Outplayed n'enregistre pas l'arme (le « data » de ses kills n'est qu'un
 * compteur), et en 480p l'icône fait 10 à 25 px de large, sans un seul kill à la capacité pour la régler.
 */
object AbilityKills {
    /** Fenêtre où une balle du compteur prouve un tir : celle où [MontagePlanner.hudShots] cherche la balle qui tue. */
    val BEFORE = 1.seconds
    val AFTER = 300.milliseconds

    /** Kills à la capacité de ces groupes, par capture : seulement là où le compteur de munitions est lu. */
    fun find(groups: List<KillGroup>, sessions: List<Session>, settings: MontageSettings): Set<Pair<Path, Duration>> {
        val event = settings.killStyle.hudShotEvent
        if (event.isEmpty()) return emptySet()
        val balls = sessions.associate { s -> s.media.path to s.timeline.events.filter { it.kind == event }.map { it.at }.sorted() }
        return groups.flatMap { g ->
            val seen = balls[g.media.path].orEmpty()
            g.kills.filter { k -> g.traitsOf(k).shots == 0 && seen.none { it >= k - BEFORE && it <= k + AFTER } }.map { g.media.path to it }
        }.toSet()
    }

    /**
     * Groupes sans leurs kills à la capacité ([ability]). Un groupe qui n'a que ça disparaît ; un multi-kill qui en
     * contient garde son importance (le combat entier était un triplé), son issue (ace, clutch) et ses autres kills.
     * [keep] : kills que l'utilisateur a choisis (forcés, épinglés), montés quoi qu'il arrive.
     */
    fun remove(
        groups: List<KillGroup>,
        ability: Set<Pair<Path, Duration>>,
        keep: (KillGroup, Duration) -> Boolean = { _, _ -> false },
    ): List<KillGroup> = groups.mapNotNull { g ->
        val kept = g.kills.indices.filter { i -> (g.media.path to g.kills[i]) !in ability || keep(g, g.kills[i]) }
        when {
            kept.size == g.kills.size -> g
            kept.isEmpty() -> null
            else -> g.copy(
                kills = kept.map { g.kills[it] },
                traits = if (g.traits.isEmpty()) emptyList() else kept.map { g.traits[it] },
                // Même rang que le combat entier : rank = kills + score / 10 + style.
                style = g.style + (g.kills.size - kept.size),
            )
        }
    }
}
