package dev.highlights.ffmpeg

import dev.highlights.core.HighlightsException
import dev.highlights.core.config.EncoderSettings
import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.EncoderSelector
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = KotlinLogging.logger {}

data class EncoderCheck(val name: String, val usable: Boolean, val reason: String?)

/**
 * Choisit le premier encodeur utilisable. Être listé par `ffmpeg -encoders` ne suffit pas (pilote absent, GPU non compatible) :
 * chaque candidat est validé par un encodage réel de quelques frames.
 */
class FfmpegEncoderSelector(
    private val ffmpeg: FfmpegService,
    private val settings: EncoderSettings,
) : EncoderSelector {
    private val mutex = Mutex()
    private var cached: EncoderProfile? = null

    override suspend fun select(): EncoderProfile = mutex.withLock {
        cached ?: detect().also {
            cached = it
            log.info { "Encodeur retenu : ${it.name}" }
        }
    }

    suspend fun checkAll(): List<EncoderCheck> {
        val available = listEncoders()
        return settings.preference.map { check(it, available) }
    }

    private suspend fun detect(): EncoderProfile {
        val available = listEncoders()
        val failures = mutableListOf<String>()
        for (name in settings.preference) {
            val check = check(name, available)
            if (check.usable) return profileFor(name)
            log.warn { "Encodeur $name inutilisable : ${check.reason}" }
            failures += "$name (${check.reason})"
        }
        throw HighlightsException("Aucun encodeur utilisable : ${failures.joinToString("; ")}")
    }

    private suspend fun check(name: String, available: Set<String>): EncoderCheck {
        if (name !in available) return EncoderCheck(name, false, "absent de ce build ffmpeg")
        return try {
            ffmpeg.run(
                FfmpegCommand(
                    listOf("-v", "error", "-f", "lavfi", "-i", "color=black:s=1920x1080:r=60", "-frames:v", "10") +
                        profileFor(name).videoArgs + listOf("-f", "null", "-"),
                    "test encodeur $name",
                ),
            )
            EncoderCheck(name, true, null)
        } catch (e: FfmpegException) {
            EncoderCheck(name, false, e.stderrTail.lastOrNull { it.isNotBlank() } ?: "code retour ${e.exitCode}")
        }
    }

    private suspend fun listEncoders(): Set<String> {
        val names = mutableSetOf<String>()
        val pattern = Regex("""^\s*[VAS][A-Z.]{5}\s+(\S+)""")
        ffmpeg.run(FfmpegCommand(listOf("-encoders"), "liste des encodeurs"), StdoutHandler.Lines { line ->
            pattern.find(line)?.let { names += it.groupValues[1] }
        })
        return names
    }

    private fun profileFor(name: String) = EncoderProfile(
        name = name,
        videoArgs = settings.videoArgs[name] ?: EncoderPresets.defaultVideoArgs(name),
        hardware = EncoderPresets.isHardware(name),
    )
}

object EncoderPresets {
    fun defaultVideoArgs(name: String): List<String> = when (name) {
        // VBR plafonné : bon compromis qualité/poids pour YouTube/TikTok en 1080p60.
        "h264_amf" -> listOf(
            "-c:v", "h264_amf", "-usage", "transcoding", "-quality", "quality",
            "-rc", "vbr_peak", "-b:v", "12M", "-maxrate", "20M", "-bufsize", "24M", "-profile:v", "high",
        )
        "libx264" -> listOf("-c:v", "libx264", "-preset", "medium", "-crf", "20", "-profile:v", "high")
        else -> listOf("-c:v", name)
    }

    fun isHardware(name: String) = listOf("_amf", "_nvenc", "_qsv", "_vaapi", "_mf").any { name.endsWith(it) }
}
