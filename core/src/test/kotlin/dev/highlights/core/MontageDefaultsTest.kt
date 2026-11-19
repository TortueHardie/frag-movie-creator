package dev.highlights.core

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.model.MontageSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path

/** Défauts du montage kills choisis par l'utilisateur le 18/11, après les rendus de comparaison. */
class MontageDefaultsTest : FunSpec({
    test("couleurs boostées, ouverture vitrine, rythme rapide par partie ; whip pans muets") {
        val s = MontageSettings()
        s.colors.enabled shouldBe true
        s.showcase.enabled shouldBe true
        s.fastParts.enabled shouldBe true
        s.whip.whoosh shouldBe 0.0
        s.fast.enabled shouldBe false
    }

    test("style par défaut : Cinématique ; aucun style livré ne remet le souffle") {
        val styles = LoadedConfig(AppConfig(), Path("../config").toAbsolutePath().normalize(), null).loadMontageStyles()
        styles.default shouldBe "cinematique"
        styles.styles.none { it.whoosh == true } shouldBe true
        styles.styles.none { it.colors == false } shouldBe true
    }
})
