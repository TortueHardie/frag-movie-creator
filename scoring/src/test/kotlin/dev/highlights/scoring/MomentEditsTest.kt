package dev.highlights.scoring

import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MomentOrigin
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.WindowGrid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MomentEditsTest : FunSpec({
    fun h(id: String, start: Int, end: Int, enabled: Boolean = true) =
        Highlight(id, Path("x.mp4"), TimeRange(start.seconds, end.seconds), start.seconds, 0.8, enabled = enabled)

    val policy = SelectionPolicy(minClip = 4.seconds, maxClip = 20.seconds)
    val duration = 10.minutes

    test("rallonger la fin d'une seconde marque le moment retouché sous un nouvel identifiant") {
        val result = MomentEdits.adjust(listOf(h("h001", 10, 20), h("h002", 50, 60)), "h001", 1.seconds, end = true, duration, policy)
        val adjusted = result.first()
        adjusted.range shouldBe TimeRange(10.seconds, 21.seconds)
        adjusted.origin shouldBe MomentOrigin.ADJUSTED
        adjusted.id shouldBe "m001"
        result[1].id shouldBe "h002"
    }

    test("les bornes de la capture et les longueurs du profil sont respectées") {
        // Début avancé avant 0 : arrêté à 0.
        MomentEdits.adjust(listOf(h("h001", 0, 10)), "h001", (-1).seconds, end = false, duration, policy).single().range.start shouldBe 0.seconds
        // Fin rapprochée sous minClip : arrêtée à 4 s de long.
        MomentEdits.adjust(listOf(h("h001", 10, 15)), "h001", (-2).seconds, end = true, duration, policy).single().range shouldBe TimeRange(10.seconds, 14.seconds)
        // Fin repoussée au-delà de maxClip : arrêtée à 20 s de long.
        MomentEdits.adjust(listOf(h("h001", 10, 29)), "h001", 5.seconds, end = true, duration, policy).single().range.end shouldBe 30.seconds
        // Fin de capture.
        MomentEdits.adjust(listOf(h("h001", 590, 599)), "h001", 5.seconds, end = true, duration, policy).single().range.end shouldBe duration
    }

    test("un moment retouché garde son identifiant à la retouche suivante") {
        val once = MomentEdits.adjust(listOf(h("h001", 10, 20)), "h001", 1.seconds, end = true, duration, policy)
        val twice = MomentEdits.adjust(once, "m001", (-500).milliseconds, end = false, duration, policy)
        twice.single().id shouldBe "m001"
        twice.single().range shouldBe TimeRange(9.5.seconds, 21.seconds)
    }

    test("ajouter un moment centré sur le clic, avec score et kills lus dans la timeline") {
        val grid = WindowGrid(1.seconds, 1.seconds, duration)
        val scores = List(grid.count) { if (it == 101) 0.9 else 0.1 }
        val timeline = ScoredTimeline(grid, scores, mapOf("audio" to scores), events = listOf(TimelineEvent(101.seconds, "kill", 1.0, "d")))
        val added = MomentEdits.add(listOf(h("h001", 10, 20)), 100.seconds, 8.seconds, duration, timeline, Path("x.mp4"))
            .single { it.origin == MomentOrigin.ADDED }
        added.range shouldBe TimeRange(96.seconds, 104.seconds)
        added.id shouldBe "m001"
        added.score shouldBe 0.9
        added.events shouldBe mapOf("kill" to 1)
        added.enabled shouldBe true
        // Près du bord : décalé pour rester dans la capture.
        MomentEdits.add(emptyList(), 2.seconds, 8.seconds, duration, timeline, Path("x.mp4")).single().range shouldBe TimeRange(0.seconds, 8.seconds)
    }

    test("une retouche manuelle survit au recalcul de la sélection et l'emporte sur les moments qui la recouvrent") {
        val previous = MomentEdits.adjust(listOf(h("h001", 10, 20), h("h002", 50, 60, enabled = false)), "h001", 3.seconds, end = true, duration, policy)
        // Seuil changé : la sélection propose d'autres moments, dont un qui recouvre le moment retouché.
        val fresh = listOf(h("h001", 15, 25), h("h002", 52, 58), h("h003", 200, 210))
        val merged = MomentEdits.preserveManual(previous, fresh)
        merged.map { it.id to it.range } shouldBe listOf(
            "m001" to TimeRange(10.seconds, 23.seconds),
            "h002" to TimeRange(52.seconds, 58.seconds),
            "h003" to TimeRange(200.seconds, 210.seconds),
        )
        merged[1].enabled shouldBe false
    }

    test("réordonner : un moment monte d'une place, rien ne bouge hors bornes") {
        MomentEdits.move(listOf("a", "b", "c"), 2, -1) shouldBe listOf("a", "c", "b")
        MomentEdits.move(listOf("a", "b", "c"), 0, -1) shouldBe listOf("a", "b", "c")
    }

    test("le rang manuel passe au moment recalculé qui recouvre le plus l'ancien") {
        val previous = listOf(h("h001", 10, 20).copy(order = 1), h("h002", 50, 60).copy(order = 0))
        val merged = MomentEdits.preserveManual(previous, listOf(h("h001", 12, 22), h("h002", 48, 58)))
        merged.map { it.order } shouldBe listOf(1, 0)
    }
})
