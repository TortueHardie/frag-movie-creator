package dev.highlights.ui

import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionPolicy
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.model.EditSettings
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
