package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Détecteurs de secours : celui qui remplace un signal absent (base Outplayed introuvable) tourne, celui qui double
 * un signal présent ne tourne pas.
 */
class FallbackDetectorIT : FunSpec({
    test("un détecteur de secours ne tourne que si le signal qu'il remplace est absent").config(
        enabledIf = { TestMedia.available },
        timeout = 3.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val profiles = root.resolve("profiles").createDirectories()
        profiles.resolve("default.yaml").writeText(
            """
            id: default
            detectors:
              - id: game-events
                type: outplayed-events
                params: { database: "${root.resolve("pas-de-base").toString().replace('\\', '/')}" }
              - id: relais
                type: audio-loudness
                fallbackFor: game-events
                params: { role: game }
              - id: game-audio
                type: audio-loudness
                params: { role: game }
              - id: doublon
                type: audio-loudness
                fallbackFor: game-audio
                params: { role: game }
            """.trimIndent(),
        )
        val source = TestMedia.generate(root.resolve("partie.mp4"), durationSeconds = 30)
        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = profiles.toString(),
                workDir = root.resolve("work").toString(),
            ),
            baseDir = root,
            source = null,
        )
        val outcome = Pipelines.create(config, TestMedia.requireFfmpeg()).analyze(source, AnalyzeOptions(), ProgressReporter.NONE)

        val contributions = outcome.session.timeline.contributions
        contributions shouldContainKey "relais"
        contributions shouldContainKey "game-audio"
        contributions shouldNotContainKey "doublon"
    }
})
