package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.ExportPreset
import dev.highlights.core.model.ExportPresets
import dev.highlights.core.model.MontageStyle
import dev.highlights.core.model.MontageStyles
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.PlatformProfile
import dev.highlights.core.model.SelectionTarget
import dev.highlights.montage.ThumbnailStyle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExportPresetsTest : FunSpec({
    val tiktok = ExportPreset("tiktok", platform = "tiktok", length = 30.seconds, highlightsStyle = EditStyle.STORY, montageStyle = "tiktok", thumbnail = false)
    val complet = ExportPreset("complet", format = OutputFormat.LANDSCAPE, length = 3.minutes, montageStyle = "cinematique", thumbnail = true)

    test("highlights : le préréglage remplit plateforme, style et miniature restés libres") {
        val o = ExportOptions().withPreset(tiktok)
        o.platform shouldBe "tiktok"
        o.formats shouldBe null
        o.style shouldBe EditStyle.STORY
        o.thumbnail shouldBe null
        val full = ExportOptions().withPreset(complet)
        full.platform shouldBe null
        full.formats shouldBe listOf(OutputFormat.LANDSCAPE)
        full.thumbnail shouldBe ThumbnailStyle.DEFAULT
    }

    test("highlights : un choix fait à la main passe devant ; un format choisi écarte la plateforme du préréglage") {
        val o = ExportOptions(formats = listOf(OutputFormat.SOURCE), style = EditStyle.SIMPLE, thumbnail = ThumbnailStyle.SOBER).withPreset(tiktok)
        o.platform shouldBe null
        o.formats shouldBe listOf(OutputFormat.SOURCE)
        o.style shouldBe EditStyle.SIMPLE
        o.thumbnail shouldBe ThumbnailStyle.SOBER
        // Une plateforme choisie : le format du préréglage ne s'y ajoute pas, elle impose le sien.
        ExportOptions(platform = "shorts").withPreset(complet).formats shouldBe null
    }

    test("analyse : la durée du préréglage devient la cible, sauf cible donnée") {
        AnalyzeOptions().withPreset(tiktok).target shouldBe SelectionTarget(totalDuration = 30.seconds)
        AnalyzeOptions(target = SelectionTarget(topN = 5)).withPreset(tiktok).target shouldBe SelectionTarget(topN = 5)
    }

    test("montage : durée maximale, style, plateforme ou format du préréglage, sauf ce qui est choisi") {
        val o = MontageOptions().withPreset(tiktok)
        o.platform shouldBe "tiktok"
        o.maxDuration shouldBe 30.seconds
        o.style shouldBe "tiktok"
        o.thumbnail shouldBe null
        val hand = MontageOptions(maxDuration = 45.seconds, style = "propre", formats = listOf(OutputFormat.VERTICAL)).withPreset(complet)
        hand.maxDuration shouldBe 45.seconds
        hand.style shouldBe "propre"
        hand.formats shouldBe listOf(OutputFormat.VERTICAL)
        hand.thumbnail shouldBe ThumbnailStyle.DEFAULT
    }

    test("un préréglage inconnu, ou qui vise une plateforme ou un style inconnus, est refusé avec la liste des connus") {
        val all = ExportPresets(listOf(tiktok, ExportPreset("faux", platform = "myspace"), ExportPreset("sans-style", montageStyle = "absent")))
        val styles = MontageStyles(styles = listOf(MontageStyle("tiktok")))
        ExportPresetRules.resolve("tiktok", all, PlatformProfile.DEFAULTS, styles) shouldBe tiktok
        shouldThrow<InputException> { ExportPresetRules.resolve("youtube", all, PlatformProfile.DEFAULTS, styles) }.message shouldContain "tiktok, faux"
        shouldThrow<InputException> { ExportPresetRules.resolve("faux", all, PlatformProfile.DEFAULTS, styles) }.message shouldContain "myspace"
        shouldThrow<InputException> { ExportPresetRules.resolve("sans-style", all, PlatformProfile.DEFAULTS, styles) }.message shouldContain "absent"
    }

    test("les préréglages livrés visent des plateformes et des styles livrés") {
        val config = LoadedConfig(AppConfig(), Path("../config").toAbsolutePath().normalize(), null)
        val presets = config.loadExportPresets()
        presets.presets.forEach { p -> ExportPresetRules.resolve(p.id, presets, config.platforms, config.loadMontageStyles()) shouldBe p }
    }
})
