package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.CardBackground
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.IntroOutroSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SafeArea
import dev.highlights.core.profile.GameProfile
import dev.highlights.editing.CardRequest
import dev.highlights.editing.CardStill
import dev.highlights.editing.IntroOutroCards
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cartes d'ouverture et de fin côté pipeline : quels réglages valent pour un export (application, profil, app.yaml),
 * chemins résolus comme les autres chemins de configuration, et l'aperçu PNG montré dans l'application.
 */
object IntroOutroSupport {
    /**
     * Réglages des cartes d'un export : [override] (choisis dans l'application), sinon ceux du profil, sinon ceux
     * d'app.yaml. [enabled] force leur présence (`--intro` / `--no-intro`) ; null : le réglage `enabled`. Rend null
     * sans carte ; refuse des cartes demandées sans rien à montrer ou dont une image manque.
     */
    fun resolve(config: LoadedConfig, profile: GameProfile?, enabled: Boolean?, override: IntroOutroSettings? = null): IntroOutroSettings? {
        val base = override ?: profile?.intro ?: config.app.intro ?: IntroOutroSettings()
        if (!(enabled ?: base.enabled) || !(base.intro || base.outro)) return null
        if (!base.hasContent) throw InputException(Message("error.intro.empty"))
        fun file(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val resolved = config.resolve(path)
            if (!Files.isRegularFile(resolved)) throw InputException(Message("error.intro.fileMissing", "file" to resolved))
            return resolved.toString()
        }
        return base.copy(
            enabled = true,
            logo = file(base.logo),
            image = if (base.background == CardBackground.IMAGE) file(base.image) else base.image?.let { config.resolve(it).toString() },
        )
    }

    /**
     * Temps à retirer d'une durée maximale (plateforme) pour que la vidéo, cartes comprises, y tienne : leurs durées,
     * plus un demi-temps de musique au plus pour l'ouverture d'un montage arrondie au temps (un temps à 60 BPM).
     */
    fun reserved(settings: IntroOutroSettings?, montage: Boolean = false): Duration {
        if (settings == null) return Duration.ZERO
        val slack = if (montage && settings.intro) 500.milliseconds else Duration.ZERO
        return settings.introDuration + settings.outroDuration + slack
    }

    /** Taille de l'aperçu : 720p dans le sens du format. */
    fun previewSize(format: OutputFormat): FrameSize = when (format) {
        OutputFormat.VERTICAL -> FrameSize(720, 1280)
        else -> FrameSize(1280, 720)
    }

    /**
     * Aperçu PNG de la carte d'ouverture ([intro]) ou de fin dans [format], sur [still] pour un fond flouté (sans
     * image, le fond uni est montré). [safe] : zone sûre de la plateforme visée.
     */
    suspend fun preview(
        ffmpeg: FfmpegService, settings: IntroOutroSettings, format: OutputFormat, intro: Boolean, output: Path, workDir: Path,
        still: CardStill? = null, safe: SafeArea = SafeArea.NONE,
    ): Path {
        val size = previewSize(format)
        workDir.createDirectories()
        output.parent?.createDirectories()
        val request = CardRequest(settings, size.width, size.height, 30, safe, introStill = still, outroStill = still)
        val script = workDir.resolve("intro_preview.txt")
        val render = IntroOutroCards.preview(request, intro, script, output, ffmpeg.filterScriptOption())
        script.writeText(render.filterGraph)
        ffmpeg.run(render.command)
        return output
    }
}
