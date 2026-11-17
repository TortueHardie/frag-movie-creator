package dev.highlights.pipeline

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.MontageStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MontageStylesTest : FunSpec({
    val tiktok = MontageStyle("tiktok", fast = true, colors = true, flash = true, effectDensity = EffectDensity.HEAVY)

    test("le style remplit les réglages restés libres") {
        val styled = MontageOptions().withStyle(tiktok)
        styled.fast shouldBe true
        styled.boostColors shouldBe true
        styled.flash shouldBe true
        styled.effectDensity shouldBe EffectDensity.HEAVY
        // Ce que le style ne dit pas reste au profil.
        styled.zoom shouldBe null
        styled.oneTaps shouldBe null
    }

    test("un réglage choisi passe devant le style") {
        val styled = MontageOptions(flash = false, boostColors = false, oneTaps = false).withStyle(tiktok.copy(oneTaps = true))
        styled.flash shouldBe false
        styled.boostColors shouldBe false
        styled.oneTaps shouldBe false
        styled.fast shouldBe true
    }
})
