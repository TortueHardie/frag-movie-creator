package dev.highlights.core

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.ExportPresets
import dev.highlights.core.model.OutputFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExportPresetTest : FunSpec({
    test("les préréglages livrés se lisent, chacun a un nom et une description traduits dans toutes les langues") {
        val presets = LoadedConfig(AppConfig(), Path("../config").toAbsolutePath().normalize(), null).loadExportPresets()
        presets.presets.map { it.id } shouldBe listOf("tiktok", "shorts", "complet")
        presets.byId("tiktok")!!.let { p ->
            p.platform shouldBe "tiktok"
            p.length shouldBe 30.seconds
            p.montageStyle shouldBe "tiktok"
            p.thumbnail shouldBe false
        }
        presets.byId("complet")!!.let { p ->
            p.platform shouldBe null
            p.format shouldBe OutputFormat.LANDSCAPE
            p.length shouldBe 3.minutes
            p.highlightsStyle shouldBe EditStyle.STORY
            p.thumbnail shouldBe true
        }
        I18n.available().forEach { lang ->
            I18n.withLanguage(lang.tag) {
                presets.presets.forEach { p ->
                    (I18n.textOrNull("preset.${p.id}") != null) shouldBe true
                    (I18n.textOrNull("preset.${p.id}.description") != null) shouldBe true
                }
            }
        }
        I18n.withLanguage("fr") { presets.byId("shorts")!!.name shouldBe "YouTube court" }
    }

    test("sans fichier, aucun préréglage ; une clé inconnue ou une plateforme avec un format sont refusées") {
        val dir = tempdir().toPath()
        LoadedConfig(AppConfig(), dir, null).loadExportPresets() shouldBe ExportPresets.NONE
        val file = dir.resolve("export-presets.yaml")
        file.writeText("presets:\n  - id: court\n    duree: 30s\n")
        shouldThrow<ConfigException> { ConfigYaml.decodeFile(file, ExportPresets.serializer()) }
        file.writeText("presets:\n  - id: court\n    platform: tiktok\n    format: \"16:9\"\n")
        shouldThrow<ConfigException> { ConfigYaml.decodeFile(file, ExportPresets.serializer()) }.message shouldContain "court"
    }

    test("un préréglage sans traduction garde le nom et la description du fichier") {
        val file = tempdir().toPath().resolve("p.yaml")
        file.writeText("presets:\n  - id: reels-court\n    displayName: Reels court\n    description: Quinze secondes.\n    platform: reels\n    length: 15s\n")
        val preset = ConfigYaml.decodeFile(file, ExportPresets.serializer()).presets.single()
        preset.name shouldBe "Reels court"
        preset.shownDescription shouldBe "Quinze secondes."
        preset.length shouldBe 15.seconds
        preset.thumbnail shouldBe null
    }
})
