package dev.highlights.core

import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.profile.ProfileRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class ProfileRepositoryTest : FunSpec({
    @Serializable
    data class FakeParams(val stream: Int = 0, val optional: Boolean = false)

    fun repoWith(vararg files: Pair<String, String>): ProfileRepository {
        val dir = tempdir().toPath()
        files.forEach { (name, content) -> dir.resolve(name).writeText(content) }
        return ProfileRepository.loadDirectory(dir)
    }

    val default = "default.yaml" to """
        id: default
        detectors:
          - id: game-audio
            type: audio-loudness
            params: { stream: 0 }
    """.trimIndent()

    val lol = "lol.yaml" to """
        id: lol
        match: { pathContains: ["League of Legends"], priority: 10 }
        window: { size: 3s, hop: 1500ms }
        detectors:
          - id: mic
            type: audio-loudness
            weight: 0.3
            params: { stream: 1, optional: true }
        selection:
          threshold: 0.7
          target: { topN: 5 }
        edit:
          formats: ["16:9", "9:16"]
    """.trimIndent()

    test("chargement YAML avec durées lisibles et paramètres libres") {
        val repo = repoWith(default, lol)
        val p = repo.byId("lol")
        p.window.size shouldBe 3.seconds
        p.selection.threshold shouldBe 0.7
        p.selection.target.topN shouldBe 5
        p.edit.formats shouldBe listOf(OutputFormat.LANDSCAPE, OutputFormat.VERTICAL)
        p.detectors.single().detectorParams().decode(FakeParams.serializer()) { FakeParams() } shouldBe FakeParams(1, true)
        DetectorParams.EMPTY.decode(FakeParams.serializer()) { FakeParams(7) } shouldBe FakeParams(7)
    }

    test("résolution par chemin, sinon profil par défaut, ou profil forcé") {
        val repo = repoWith(default, lol)
        repo.resolve(Path("C:/Videos/Outplayed/League of Legends/game.mp4")).id shouldBe "lol"
        repo.resolve(Path("C:/Videos/Outplayed/league of legends/game.mp4")).id shouldBe "lol"
        repo.resolve(Path("C:/Videos/other/game.mp4")).id shouldBe "default"
        repo.resolve(Path("C:/Videos/other/game.mp4"), forcedId = "lol").id shouldBe "lol"
    }

    test("erreur explicite sur une clé inconnue") {
        val e = shouldThrow<ConfigException> {
            repoWith("bad.yaml" to "id: bad\ndetectors: []\nthreshold: 0.5")
        }
        e.message shouldContain "bad.yaml"
    }

    test("erreur explicite sur une durée invalide") {
        val e = shouldThrow<ConfigException> {
            repoWith("bad.yaml" to "id: bad\ndetectors: []\nwindow: { size: deux secondes }")
        }
        e.message shouldContain "Durée invalide"
    }

    test("un BOM UTF-8 en tête de fichier est toléré") {
        repoWith("bom.yaml" to "﻿id: bom\ndetectors: []").byId("bom").id shouldBe "bom"
    }

    test("fallbackFor désigne un autre détecteur du profil, qui n'est pas lui-même de secours") {
        val ok = repoWith(
            "p.yaml" to """
                id: p
                detectors:
                  - { id: events, type: outplayed-events }
                  - { id: feed, type: killfeed, fallbackFor: events }
            """.trimIndent(),
        )
        ok.byId("p").detectors.last().fallbackFor shouldBe "events"

        shouldThrow<ConfigException> {
            repoWith("p.yaml" to "id: p\ndetectors:\n  - { id: feed, type: killfeed, fallbackFor: absent }")
        }.message shouldContain "absent"
        shouldThrow<ConfigException> {
            repoWith("p.yaml" to "id: p\ndetectors:\n  - { id: a, fallbackFor: b }\n  - { id: b, fallbackFor: c }\n  - { id: c }")
        }.message shouldContain "lui-même détecteur de secours"
    }

    test("les profils du dépôt sont valides") {
        val repo = ProfileRepository.loadDirectory(Path("../config/profiles"))
        repo.all().map { it.id }.toSet() shouldBe setOf("default", "lol", "valorant", "wardogs")
        // Drop en rafale : le style des edits TikTok, activé pour VALORANT seulement pour l'instant.
        repo.byId("valorant").montage.burst.enabled shouldBe true
        repo.byId("wardogs").montage.burst.enabled shouldBe false
    }
})
