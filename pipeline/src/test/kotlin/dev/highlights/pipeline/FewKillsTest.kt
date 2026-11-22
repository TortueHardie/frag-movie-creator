package dev.highlights.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class FewKillsTest : FunSpec({
    test("peu de kills trouvés : avertissement avec le nombre, sauf sélection à la main ou seuil à zéro") {
        PlannedMontage.fewKillsNotice(1, 3, picked = false)!!.text() shouldBe
            "Seulement 1 kill trouvé dans ces parties : vérifie l’analyse (la détection des kills en a peut-être manqué)."
        PlannedMontage.fewKillsNotice(2, 3, picked = false)!!.english() shouldBe
            "Only 2 kills found in these games: check the analysis (kill detection may have missed some)."
        PlannedMontage.fewKillsNotice(3, 3, picked = false).shouldBeNull()
        PlannedMontage.fewKillsNotice(1, 3, picked = true).shouldBeNull()
        PlannedMontage.fewKillsNotice(0, 0, picked = false).shouldBeNull()
    }
})
