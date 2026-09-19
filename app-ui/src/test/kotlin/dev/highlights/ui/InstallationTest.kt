package dev.highlights.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class InstallationTest : FunSpec({
    test("la config livrée est recopiée, mise à jour sans écraser les fichiers retouchés") {
        val bundled = createTempDirectory("bundled")
        val user = createTempDirectory("user")
        bundled.resolve("profiles").createDirectories()
        bundled.resolve("app.yaml").writeText("v1")
        bundled.resolve("profiles/lol.yaml").writeText("lol v1")

        Installation.sync(bundled, user)
        user.resolve("app.yaml").readText() shouldBe "v1"
        user.resolve("profiles/lol.yaml").readText() shouldBe "lol v1"

        // L'utilisateur retouche un profil, puis une nouvelle version change les deux fichiers et en ajoute un.
        user.resolve("profiles/lol.yaml").writeText("lol perso")
        bundled.resolve("app.yaml").writeText("v2")
        bundled.resolve("profiles/lol.yaml").writeText("lol v2")
        bundled.resolve("profiles/wardogs.yaml").writeText("wardogs")
        Installation.sync(bundled, user)

        user.resolve("app.yaml").readText() shouldBe "v2"
        user.resolve("profiles/lol.yaml").readText() shouldBe "lol perso"
        user.resolve("profiles/wardogs.yaml").readText() shouldBe "wardogs"

        // Toujours conservé aux mises à jour suivantes.
        bundled.resolve("profiles/lol.yaml").writeText("lol v3")
        Installation.sync(bundled, user)
        user.resolve("profiles/lol.yaml").readText() shouldBe "lol perso"
    }
})
