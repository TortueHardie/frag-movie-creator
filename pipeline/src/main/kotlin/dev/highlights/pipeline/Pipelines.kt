package dev.highlights.pipeline

import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.editing.DefaultEditPlanner
import dev.highlights.export.Exporter
import dev.highlights.montage.KillMontageExporter
import dev.highlights.ffmpeg.FfmpegEncoderSelector
import dev.highlights.ffmpeg.FfmpegLocator
import dev.highlights.ffmpeg.ProcessFfmpegService
import dev.highlights.scoring.ScoringEngine
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/** Câblage par défaut, sans framework d'injection. */
object Pipelines {
    fun ffmpeg(config: LoadedConfig): ProcessFfmpegService {
        val settings = config.app.ffmpeg
        val service = ProcessFfmpegService(
            FfmpegLocator.locate("ffmpeg", settings.ffmpegPath),
            FfmpegLocator.locate("ffprobe", settings.ffprobePath),
        )
        log.info { "ffmpeg : ${service.ffmpegPath}, ffprobe : ${service.ffprobePath}" }
        return service
    }

    fun create(config: LoadedConfig, ffmpeg: ProcessFfmpegService = ffmpeg(config)): HighlightPipeline {
        val detectors = DetectorRegistry.fromServiceLoader()
        log.info { "Détecteurs disponibles : ${detectors.types.sorted().joinToString()}" }
        val encoders = FfmpegEncoderSelector(ffmpeg, config.app.encoder)
        return HighlightPipeline(
            config = config,
            ffmpeg = ffmpeg,
            profiles = ProfileRepository.loadDirectory(config.profilesDir),
            detectors = detectors,
            scoring = ScoringEngine(),
            exporter = Exporter(ffmpeg, encoders, DefaultEditPlanner),
            montageExporter = KillMontageExporter(ffmpeg, encoders),
        )
    }
}
