package dev.highlights.ui

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.MontageStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MontageStyleUiTest : FunSpec({
    val tiktok = MontageStyle("tiktok", fast = true, colors = true, flash = true)
    val propre = MontageStyle("propre", fast = false, effectDensity = EffectDensity.SOBER, flash = false, zoom = false)
    val base = MontageUiState(styles = listOf(tiktok, propre), profileKnobs = StyleKnobs(zoom = true, flash = true))

    /** Ce que fait le contrôleur à chaque modification de la fenêtre. */
    fun MontageUiState.edit(change: (MontageUiState) -> MontageUiState) = change(this).trackedFrom(this)

    test("choisir un style pose ses cases sur celles du profil, « Aucun » les rend au profil") {
        val styled = base.chooseStyle("tiktok")
        styled.fast shouldBe true
        styled.boostColors shouldBe true
        styled.zoom shouldBe true
        val clean = styled.chooseStyle("propre")
        clean.fast shouldBe false
        clean.boostColors shouldBe false
        clean.flash shouldBe false
        clean.density shouldBe EffectDensity.SOBER
        clean.chooseStyle(null).knobs shouldBe StyleKnobs(zoom = true, flash = true)
    }

    test("une case modifiée à la main garde sa valeur quand on change de style, jusqu'au retour au style") {
        val touched = base.chooseStyle("tiktok").edit { it.copy(boostColors = false) }
        touched.touched shouldBe setOf("boostColors")
        val clean = touched.edit { it.chooseStyle("propre") }
        clean.touched shouldBe setOf("boostColors")
        clean.boostColors shouldBe false
        val back = clean.edit { it.chooseStyle("tiktok") }
        back.boostColors shouldBe false
        back.fast shouldBe true
        back.edit { it.resetToStyle() }.let {
            it.touched shouldBe emptySet()
            it.boostColors shouldBe true
        }
    }

    test("un style posé par une simple copie (pont de pilotage) pose aussi ses cases") {
        val styled = base.edit { it.copy(style = "tiktok") }
        styled.fast shouldBe true
        styled.touched shouldBe emptySet()
    }

    test("un style inconnu revient aux réglages du profil") {
        base.chooseStyle("absent").style shouldBe null
    }
})
