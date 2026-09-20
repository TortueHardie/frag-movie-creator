package dev.highlights.vision

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.video.FrameSpec
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Images clés si leur intervalle (mesuré sur 30 s au milieu de la vidéo) est assez court, sinon [fps] images par seconde. */
internal suspend fun chooseSampling(
    ctx: AnalysisContext,
    id: String,
    sampling: Sampling,
    fps: Double,
    hwaccel: String?,
    maxKeyframeInterval: Duration,
): FrameSpec {
    if (sampling == Sampling.FPS) return FrameSpec.fps(fps, hwaccel)
    val measured = ctx.frames.keyframeInterval(id)
    if (sampling == Sampling.KEYFRAMES) return FrameSpec.keyframes(measured ?: 1.seconds)
    return if (measured != null && measured <= maxKeyframeInterval) {
        FrameSpec.keyframes(measured)
    } else {
        log.warn { "$id : images clés espacées de ${measured ?: "?"}, échantillonnage à $fps img/s (plus lent)" }
        FrameSpec.fps(fps, hwaccel)
    }
}
