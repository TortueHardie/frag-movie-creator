package dev.highlights.pipeline

import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.profile.GameProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class AnalysisLibraryTest : FunSpec({
    fun profile(yaml: String): GameProfile = ConfigYaml.yaml.decodeFromString(GameProfile.serializer(), yaml.trimIndent())

    val base = """
        id: test
        detectors:
          - id: game-audio
            type: loudness
            weight: 1.0
            params:
              track: game
              floor: -40
    """.trimIndent()

    test("l'empreinte ne dépend que de ce qui décide de la timeline") {
        val reference = AnalysisLibrary.fingerprint(profile(base))
        AnalysisLibrary.fingerprint(profile(base)) shouldBe reference
        // Sélection et montage se recalculent sans réanalyser.
        AnalysisLibrary.fingerprint(profile(base + "\nselection:\n  threshold: 0.3\n")) shouldBe reference
        // Paramètres déplacés dans le fichier ou réordonnés : même analyse.
        val moved = """
            # commentaire qui décale les lignes

            id: test
            detectors:
              - id: game-audio
                type: loudness
                weight: 1.0
                params:
                  floor: -40
                  track: game
        """
        AnalysisLibrary.fingerprint(profile(moved)) shouldBe reference
        // Un poids ou un paramètre de détecteur change la timeline.
        AnalysisLibrary.fingerprint(profile(base.replace("weight: 1.0", "weight: 0.5"))) shouldNotBe reference
        AnalysisLibrary.fingerprint(profile(base.replace("floor: -40", "floor: -30"))) shouldNotBe reference
    }

    test("une capture n'est reconnue que tant qu'elle n'a pas changé") {
        val root = tempdir().toPath()
        val video = root.resolve("partie.mp4").also { it.writeText("vidéo") }
        val session = root.resolve("partie.session.json").also { it.writeText("{}") }
        val library = AnalysisLibrary(root.resolve("library.json"))
        val (size, modified) = AnalysisLibrary.stamp(video).shouldNotBeNull()
        library.record(
            LibraryEntry(video, size, modified, "lol", "abc", session, Instant.now(), 30.minutes),
        )

        library.find(video, "lol", "abc").shouldNotBeNull()
        library.contains(video) shouldBe true
        library.find(video, "valorant", "abc").shouldBeNull()
        library.find(video, "lol", "autre").shouldBeNull()
        // Relue depuis le disque par une autre instance (la ligne de commande et l'application partagent l'index).
        AnalysisLibrary(root.resolve("library.json")).entries() shouldHaveSize 1

        Files.setLastModifiedTime(video, FileTime.fromMillis(modified + 5_000))
        library.find(video, "lol", "abc").shouldBeNull()
        library.contains(video) shouldBe false

        // Session supprimée : l'analyse est oubliée.
        Files.setLastModifiedTime(video, FileTime.fromMillis(modified))
        Files.delete(session)
        library.entries().shouldBeEmpty()
        library.find(video, "lol", "abc").shouldBeNull()
    }

    test("un index illisible est ignoré") {
        val file = tempdir().toPath().resolve("library.json").also { it.writeText("pas du json") }
        AnalysisLibrary(file).entries().shouldBeEmpty()
    }
})
