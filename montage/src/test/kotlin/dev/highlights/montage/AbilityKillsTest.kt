package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AbilityKillsTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 854, 480, 30.0))
    val settings = MontageSettings(killOffset = Duration.ZERO, mergeGap = 5.seconds, splitGap = Duration.ZERO)

    fun event(at: Double, kind: String) = TimelineEvent(at.seconds, kind, 1.0, "n")

    /** Kills aux instants donnés ; balles du compteur aux instants [hud], tirs entendus aux instants [heard]. */
    fun session(kills: List<Double>, hud: List<Double> = emptyList(), heard: List<Double> = emptyList()): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        val events = kills.map { event(it, "kill") } + hud.map { event(it, "hud-shot") } + heard.map { event(it, "shot") }
        return Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "valorant",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = events.sortedBy { it.at }),
            highlights = emptyList(),
        )
    }

    fun groups(s: Session) = MontagePlanner.groups(listOf(s), settings)
    fun ability(s: Session, g: List<KillGroup> = groups(s)) = AbilityKills.find(g, listOf(s), settings).map { it.second }

    test("un kill sans balle au compteur est une capacité, pas un kill au fusil") {
        // 100 : deux balles avant le kill ; 300 : aucune balle (molly) ; 500 : une balle.
        val s = session(listOf(100.0, 300.0, 500.0), hud = listOf(99.6, 99.8, 499.7))
        val g = groups(s)
        ability(s, g) shouldBe listOf(300.seconds)
        AbilityKills.remove(g, AbilityKills.find(g, listOf(s), settings)).map { it.kills.single() } shouldBe listOf(100.seconds, 500.seconds)
    }

    test("doublé au fusil à 0,4 s : le second kill, sans balle attribuée, n'est pas une capacité") {
        // Mesuré sur « Valorant- 44 kills » (25:04) : toutes les balles de la rafale vont au premier kill.
        val s = session(listOf(1504.45, 1504.85), hud = listOf(1504.1, 1504.2, 1504.3, 1504.4, 1504.5, 1504.6, 1504.7))
        val g = groups(s)
        g.single().let { d -> d.kills.map { d.traitsOf(it).shots } } shouldBe listOf(7, 0)
        ability(s, g) shouldBe emptyList()
    }

    test("sans compteur de munitions, rien n'est écarté : un tir non entendu reste inconnu") {
        // Le son seul : le kill de 300 n'a aucun tir entendu, mais ce n'est pas une preuve (1 kill au fusil sur 61 mesurés).
        val s = session(listOf(100.0, 300.0), heard = listOf(99.9))
        groups(s).map { g -> g.traitsOf(g.kills.single()).shots } shouldBe listOf(1, null)
        ability(s) shouldBe emptyList()
    }

    test("un multi-kill garde ses kills au fusil et son importance") {
        // Triplé : 199 et 203 au fusil, 201 à la capacité (aucune balle de 200 à 201,3).
        val s = session(listOf(199.0, 201.0, 203.0), hud = listOf(198.8, 202.9))
        val g = groups(s)
        val kept = AbilityKills.remove(g, AbilityKills.find(g, listOf(s), settings)).single()
        kept.kills shouldBe listOf(199.seconds, 203.seconds)
        kept.traits.map { it.shots } shouldBe listOf(1, 1)
        kept.rank shouldBe g.single().rank
    }

    test("un kill choisi par l'utilisateur est monté même à la capacité") {
        val s = session(listOf(100.0, 300.0), hud = listOf(99.8))
        val g = groups(s)
        val ref = KillRef.of(media, 300.seconds)
        AbilityKills.remove(g, AbilityKills.find(g, listOf(s), settings)) { grp, kill -> ref.matches(grp.media, kill) }.map { it.kills.single() } shouldBe
            listOf(100.seconds, 300.seconds)
    }

    test("le montage des morts garde toutes les morts, même sans balle tirée") {
        settings.copy(skipAbilityKills = true).forFails().skipAbilityKills shouldBe false
    }
})
