package dev.highlights.ui

import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.aspectLabel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UiStateTest : FunSpec({
    test("cible de sélection selon le mode et la validité du champ") {
        SettingsState(durationText = "5m").target shouldBe SelectionTarget(totalDuration = 5.minutes)
        SettingsState(durationText = "n'importe quoi").target.shouldBeNull()
        SettingsState(targetMode = TargetMode.TOP_N, topNText = "8").target shouldBe SelectionTarget(topN = 8)
        SettingsState(targetMode = TargetMode.TOP_N, topNText = "0").target.shouldBeNull()
        SettingsState(targetMode = TargetMode.ALL, durationText = "invalide").target shouldBe SelectionTarget(all = true)
        MomentMode.KILLS.requiredEvent shouldBe "kill"
        MomentMode.BEST.requiredEvent.shouldBeNull()
    }

    test("libellé des événements") {
        eventsLabel(mapOf("assist" to 1, "kill" to 3, "revive" to 2)) shouldBe "3 kills · 1 assistance · 2 réanimations"
        eventsLabel(mapOf("death" to 1, "kill" to 2)) shouldBe "2 kills · 1 mort"
    }

    test("fichiers déposés : URI ou chemin brut") {
        droppedPath("file:/C:/Videos/partie%201.mp4") shouldBe java.nio.file.Path.of("C:/Videos/partie 1.mp4")
        droppedPath("""C:\Videos\partie.mp4""") shouldBe java.nio.file.Path.of("""C:\Videos\partie.mp4""")
    }

    test("formats dans un ordre stable") {
        SettingsState(formats = setOf(OutputFormat.VERTICAL, OutputFormat.SOURCE)).orderedFormats shouldBe
            listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)
    }

    test("libellés de ratio") {
        aspectLabel(3440, 1440) shouldBe "21:9"
        aspectLabel(2560, 1440) shouldBe "16:9"
        aspectLabel(1920, 1200) shouldBe "16:10"
        aspectLabel(1080, 1920) shouldBe "9:16"
        aspectLabel(1000, 100) shouldBe "1000x100"
    }

    test("montage kills : une musique, ou un dossier où la choisir") {
        val music = java.nio.file.Path.of("C:/Musiques/drop.mp3")
        val dir = java.nio.file.Path.of("C:/Musiques")
        MontageUiState(music = music).musicSource shouldBe music
        MontageUiState(music = music).canCreate shouldBe true
        // En mode dossier, la musique choisie à la main ne compte plus : sans dossier, rien à créer.
        MontageUiState(music = music, useLibrary = true).canCreate shouldBe false
        MontageUiState(music = music, useLibrary = true, musicLibrary = dir).musicSource shouldBe dir
        MontageUiState(useLibrary = true, musicLibrary = dir).canCreate shouldBe true
    }

    test("choix des musiques retenus : dossier, mode et réglage « depuis le début »") {
        val file = kotlin.io.path.createTempDirectory("prefs").resolve("music.json")
        val dir = java.nio.file.Path.of("C:/Musiques")
        val store = MusicPrefsStore(file)
        store.useLibrary() shouldBe false
        store.rememberLibrary(dir, useLibrary = true)
        store.remember(dir.resolve("intro.mp3"), fromStart = true)
        val reread = MusicPrefsStore(file)
        reread.library() shouldBe dir
        reread.useLibrary() shouldBe true
        reread.fromStartMusics().map { it.toString() } shouldBe listOf(dir.resolve("intro.mp3").toString())
        // Revenir à une musique précise garde le dossier pour la prochaine fois.
        reread.rememberLibrary(null, useLibrary = false)
        MusicPrefsStore(file).let { it.library() shouldBe dir; it.useLibrary() shouldBe false }
    }

    test("publication YouTube : champs proposés modifiables, envoi bloqué tant que YouTube refuserait") {
        val suggested = dev.highlights.publish.YouTubeMetadata(
            "TRIPLÉ — 9 kills en 17 s | VALORANT", "9 kills.", listOf("VALORANT", "gaming"), dev.highlights.core.config.YouTubePrivacy.PRIVATE,
        )
        // Envoi manuel (par défaut) : rien ne part d'ici, les textes se copient.
        PublishUiState(java.nio.file.Path.of("montage.mp4")).withSuggestion(suggested).canPublish shouldBe false
        val p = PublishUiState(java.nio.file.Path.of("montage.mp4"), api = true).withSuggestion(suggested)
        p.canPublish shouldBe true
        p.tagsText shouldBe "VALORANT, gaming"
        p.copy(tagsText = " fps ,, VALORANT ").metadata().tags shouldBe listOf("fps", "VALORANT")
        p.copy(title = " ").canPublish shouldBe false
        p.copy(publishAtText = "demain").problems.single() shouldBe "Heure de mise en ligne illisible (ex. 2026-10-01 18:00)"
        p.copy(publishAtText = "2999-01-01 18:00").metadata().publishAt shouldBe
            java.time.LocalDateTime.of(2999, 1, 1, 18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        PublishUiState(java.nio.file.Path.of("montage.mp4")).canPublish shouldBe false
        // Revenir au texte proposé après l'avoir modifié.
        p.copy(title = "autre").let { it.withSuggestion(it.suggested!!) }.title shouldBe suggested.title
    }

    test("les réglages reprennent les valeurs du profil") {
        val profile = GameProfile(
            id = "wardogs",
            detectors = emptyList(),
            selection = SelectionPolicy(threshold = 0.7, target = SelectionTarget(topN = 5)),
            edit = EditSettings(formats = listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)),
        )
        val s = SettingsState(durationText = "90s").withProfileDefaults(profile)
        s.threshold shouldBe 0.7
        s.targetMode shouldBe TargetMode.TOP_N
        s.topNText shouldBe "5"
        s.durationText shouldBe "90s"
        s.formats shouldBe setOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)

        val d = SettingsState().withProfileDefaults(profile.copy(selection = SelectionPolicy(target = SelectionTarget(totalDuration = 45.seconds))))
        d.targetMode shouldBe TargetMode.DURATION
        d.durationText shouldBe "45s"
    }
})
