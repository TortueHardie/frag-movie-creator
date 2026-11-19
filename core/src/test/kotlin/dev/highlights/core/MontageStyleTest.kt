package dev.highlights.core

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.MontageStyles
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path
import kotlin.io.path.writeText

class MontageStyleTest : FunSpec({
    test("les styles livrés se lisent, chacun a un nom et une description traduits") {
        val styles = LoadedConfig(AppConfig(), Path("../config").toAbsolutePath().normalize(), null).loadMontageStyles()
        styles.styles.map { it.id } shouldBe listOf("tiktok", "propre", "onetaps", "cinematique")
        styles.byId("tiktok")!!.fast shouldBe true
        styles.byId("propre")!!.effectDensity shouldBe EffectDensity.SOBER
        I18n.withLanguage("fr") {
            styles.styles.forEach { s ->
                (I18n.textOrNull("style.${s.id}") != null) shouldBe true
                (I18n.textOrNull("style.${s.id}.description") != null) shouldBe true
            }
            styles.byId("cinematique")!!.name shouldBe "Cinématique"
        }
    }

    test("sans fichier de styles, aucun style ; un style inconnu par défaut est refusé") {
        val dir = tempdir().toPath()
        LoadedConfig(AppConfig(), dir, null).loadMontageStyles() shouldBe MontageStyles.NONE
        val file = dir.resolve("montage-styles.yaml")
        file.writeText("default: absent\nstyles:\n  - id: vif\n    colors: true\n")
        shouldThrow<ConfigException> { ConfigYaml.decodeFile(file, MontageStyles.serializer()) }.message shouldContain "absent"
    }

    test("un style sans traduction garde le nom et la description du fichier") {
        val file = tempdir().toPath().resolve("s.yaml")
        file.writeText("default: vif\nstyles:\n  - id: vif\n    displayName: Très vif\n    description: Tout à fond.\n    colors: true\n")
        val styles = ConfigYaml.decodeFile(file, MontageStyles.serializer())
        styles.defaultStyle!!.name shouldBe "Très vif"
        styles.defaultStyle!!.shownDescription shouldBe "Tout à fond."
        styles.defaultStyle!!.colors shouldBe true
        styles.defaultStyle!!.fast shouldBe null
    }
})
