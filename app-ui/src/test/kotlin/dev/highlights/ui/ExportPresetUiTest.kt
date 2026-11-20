package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.ExportPreset
import dev.highlights.core.model.MontageStyle
import dev.highlights.core.model.OutputFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExportPresetUiTest : FunSpec({
    val tiktok = ExportPreset("tiktok", platform = "tiktok", length = 30.seconds, highlightsStyle = EditStyle.STORY, montageStyle = "tiktok", thumbnail = false)
    val complet = ExportPreset("complet", format = OutputFormat.LANDSCAPE, length = 3.minutes, highlightsStyle = EditStyle.SIMPLE, montageStyle = "cinematique", thumbnail = true)
    val presets = listOf(tiktok, complet)
    val settings = SettingsState(formats = setOf(OutputFormat.SOURCE), style = EditStyle.STORY, durationText = I18n.duration(60.seconds))

    /** Ce que fait le contrôleur à chaque modification des réglages d'export. */
    fun SettingsState.edit(change: (SettingsState) -> SettingsState) = change(this).presetTrackedFrom(this, presets)

    test("highlights : un préréglage pose plateforme, durée, style et miniature ; un autre avec un format retire la plateforme") {
        val t = settings.edit { it.choosePreset("tiktok", presets) }
        t.preset shouldBe "tiktok"
        t.platform shouldBe "tiktok"
        t.targetMode shouldBe TargetMode.DURATION
        t.targetDuration shouldBe 30.seconds
        t.thumbnail shouldBe false
        val c = t.edit { it.choosePreset("complet", presets) }
        c.platform shouldBe null
        c.formats shouldBe setOf(OutputFormat.LANDSCAPE)
        c.targetDuration shouldBe 3.minutes
        c.style shouldBe EditStyle.SIMPLE
        c.thumbnail shouldBe true
        // « Personnalisé » ne touche à rien.
        c.edit { it.choosePreset(null, presets) }.copy(preset = "complet") shouldBe c
    }

    test("highlights : un réglage changé à la main reste quand on change de préréglage, jusqu'au retour au préréglage") {
        val touched = settings.edit { it.choosePreset("tiktok", presets) }.edit { it.copy(durationText = I18n.duration(45.seconds)) }
        touched.presetTouched shouldBe setOf(PresetKnob.LENGTH)
        val other = touched.edit { it.choosePreset("complet", presets) }
        other.targetDuration shouldBe 45.seconds
        other.formats shouldBe setOf(OutputFormat.LANDSCAPE)
        val reset = other.edit { it.resetToPreset(presets) }
        reset.presetTouched shouldBe emptySet()
        reset.targetDuration shouldBe 3.minutes
    }

    test("highlights : un préréglage posé par simple copie (pont de pilotage) pose aussi ses réglages") {
        settings.edit { it.copy(preset = "tiktok") }.platform shouldBe "tiktok"
    }

    test("montage : le préréglage pose plateforme, durée maximale, style de montage et miniature ; le style suit ses cases") {
        val montage = MontageUiState(styles = listOf(MontageStyle("tiktok", fast = true), MontageStyle("cinematique", fast = false, showcase = true)))
        fun MontageUiState.edit(change: (MontageUiState) -> MontageUiState) = change(this).trackedFrom(this).presetTrackedFrom(this, presets)
        val t = montage.edit { it.choosePreset("tiktok", presets) }
        t.platform shouldBe "tiktok"
        t.maxDuration shouldBe 30.seconds
        t.style shouldBe "tiktok"
        t.thumbnail shouldBe false
        t.presetTouched shouldBe emptySet()
        // Style choisi à la main sous le préréglage : noté, et gardé au changement de préréglage.
        val hand = t.edit { it.chooseStyle("cinematique") }
        hand.presetTouched shouldBe setOf(PresetKnob.STYLE)
        val c = hand.edit { it.choosePreset("complet", presets) }
        c.style shouldBe "cinematique"
        c.platform shouldBe null
        c.formats shouldBe setOf(OutputFormat.LANDSCAPE)
        c.maxDuration shouldBe 3.minutes
        c.thumbnail shouldBe true
    }
})
