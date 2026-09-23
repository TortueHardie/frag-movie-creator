package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.SessionStore
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.time.Duration.Companion.seconds

/** Une capture déjà analysée n'est pas réanalysée : la session est reprise, les moments décochés le restent. */
class AnalysisMemoryIT : FunSpec({
    test("reprise d'une analyse déjà faite").config(enabledIf = { TestMedia.available }, timeout = 5.seconds * 60) {
        val root = tempdir().toPath()
        val video = TestMedia.generate(
            Files.createDirectories(root.resolve("League of Legends")).resolve("partie.mp4"),
            durationSeconds = 40,
            audioTracks = listOf(TestMedia.AudioTrackSpec("Game", bursts = listOf(10.0..12.0, 28.0..30.0))),
        )
        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                workDir = root.resolve("work").toString(),
            ),
            baseDir = root,
            source = null,
        )
        val pipeline = Pipelines.create(config, TestMedia.requireFfmpeg())
        val all = AnalyzeOptions(target = SelectionTarget(all = true))

        val first = pipeline.analyze(video, all, ProgressReporter.NONE)
        first.reused shouldBe false
        first.session.highlights shouldHaveSize 2
        pipeline.library.entries() shouldHaveSize 1

        // L'utilisateur décoche un moment ; la session est enregistrée.
        val edited = first.session.copy(highlights = first.session.highlights.mapIndexed { i, h -> if (i == 0) h.copy(enabled = false) else h })
        SessionStore.save(edited, first.sessionFile)

        // Une nouvelle instance (application relancée) reprend l'analyse, avec la cible demandée cette fois.
        val again = Pipelines.create(config, TestMedia.requireFfmpeg()).analyze(video, AnalyzeOptions(target = SelectionTarget(topN = 1)), ProgressReporter.NONE)
        again.reused shouldBe true
        again.sessionFile shouldBe first.sessionFile.toAbsolutePath().normalize()
        again.session.timeline shouldBe first.session.timeline
        again.session.highlights shouldHaveSize 1
        // La cible « tout » redonne les deux moments, le premier toujours décoché.
        val back = pipeline.analyze(video, all, ProgressReporter.NONE)
        back.reused shouldBe true
        back.session.highlights.map { it.enabled } shouldBe listOf(false, true)

        // Forcé, autre profil ou capture modifiée : nouvelle analyse.
        pipeline.analyze(video, all.copy(reuse = false), ProgressReporter.NONE).reused shouldBe false
        pipeline.analyze(video, all.copy(profileId = "default"), ProgressReporter.NONE).reused shouldBe false
        pipeline.library.entries() shouldHaveSize 2
        Files.setLastModifiedTime(video, FileTime.fromMillis(video.getLastModifiedTime().toMillis() + 60_000))
        pipeline.analyze(video, all, ProgressReporter.NONE).reused shouldBe false
        pipeline.analyze(video, all, ProgressReporter.NONE).reused shouldBe true
    }
})
