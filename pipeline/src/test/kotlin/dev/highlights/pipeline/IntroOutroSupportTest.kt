package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.model.CardBackground
import dev.highlights.core.model.IntroOutroSettings
import dev.highlights.core.profile.GameProfile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IntroOutroSupportTest : FunSpec({
    test("app.yaml : section intro lue, chemins relatifs au dossier de configuration") {
        val dir = tempdir().toPath()
        dir.resolve("images").createDirectories()
        dir.resolve("images/logo.png").writeText("png")
        dir.resolve("app.yaml").writeText(
            """
            intro:
              enabled: true
              logo: images/logo.png
              name: ZeroX
              background: color
              color: "#1B1035"
              introLength: 2s
            """.trimIndent(),
        )
        val config = ConfigYaml.load(dir.resolve("app.yaml"))
        val cards = IntroOutroSupport.resolve(config, null, null).shouldNotBeNull()
        cards.logo shouldBe dir.resolve("images/logo.png").toAbsolutePath().normalize().toString()
        cards.background shouldBe CardBackground.COLOR
        cards.introLength shouldBe 2.seconds
        // --no-intro l'emporte.
        IntroOutroSupport.resolve(config, null, false).shouldBeNull()
    }

    test("le profil remplace app.yaml, l'application remplace les deux") {
        val dir = tempdir().toPath()
        val config = LoadedConfig(AppConfig(intro = IntroOutroSettings(enabled = true, name = "Global")), dir, null)
        val profile = GameProfile(id = "jeu", detectors = emptyList(), intro = IntroOutroSettings(enabled = true, name = "Profil"))
        IntroOutroSupport.resolve(config, profile, null)?.name shouldBe "Profil"
        IntroOutroSupport.resolve(config, null, null)?.name shouldBe "Global"
        IntroOutroSupport.resolve(config, profile, true, IntroOutroSettings(name = "Appli"))?.name shouldBe "Appli"
        // Désactivées par défaut : rien sans --intro.
        IntroOutroSupport.resolve(LoadedConfig(AppConfig(), dir, null), null, null).shouldBeNull()
    }

    test("cartes demandées sans rien à montrer, ou logo absent : erreur claire") {
        val dir = tempdir().toPath()
        val config = LoadedConfig(AppConfig(), dir, null)
        shouldThrow<InputException> { IntroOutroSupport.resolve(config, null, true) }
        shouldThrow<InputException> { IntroOutroSupport.resolve(config, null, true, IntroOutroSettings(logo = "absent.png")) }
    }

    test("place réservée dans la durée maximale d'une plateforme") {
        val cards = IntroOutroSettings(enabled = true, name = "ZeroX", introLength = 1500.milliseconds, outroLength = 2.seconds)
        IntroOutroSupport.reserved(cards) shouldBe 3500.milliseconds
        IntroOutroSupport.reserved(cards, montage = true) shouldBe 4.seconds
        IntroOutroSupport.reserved(cards.copy(outro = false)) shouldBe 1500.milliseconds
        IntroOutroSupport.reserved(null) shouldBe 0.seconds
    }
})
