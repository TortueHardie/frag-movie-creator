package dev.highlights.core

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.PlatformProfile
import dev.highlights.core.model.SafeArea
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

class PlatformTest : FunSpec({
    test("zone sûre : un texte reste entre les onglets du haut et la légende du bas") {
        val tiktok = SafeArea(top = 0.10, bottom = 0.22, left = 0.05, right = 0.14)
        tiktok.width shouldBe (0.81 plusOrMinus 1e-9)
        tiktok.centerY(0.66, 0.06) shouldBe (0.66 plusOrMinus 1e-9)
        // Trop bas : remonté juste au-dessus de la légende ; trop haut : descendu sous les onglets.
        tiktok.centerY(0.90, 0.06) shouldBe (0.75 plusOrMinus 1e-9)
        tiktok.centerY(0.02, 0.06) shouldBe (0.13 plusOrMinus 1e-9)
        SafeArea.NONE.centerY(0.9, 0.06) shouldBe (0.9 plusOrMinus 1e-9)
        shouldThrow<IllegalArgumentException> { SafeArea(bottom = 0.6) }
    }

    test("une plateforme impose son format, son volume et sa zone sûre, et ne relève jamais les images par seconde") {
        val shorts = PlatformProfile.DEFAULTS.getValue("shorts")
        val edit = shorts.applyTo(EditSettings(formats = listOf(OutputFormat.SOURCE, OutputFormat.LANDSCAPE), loudnessLufs = -16.0, fps = 30))
        edit.formats shouldBe listOf(OutputFormat.VERTICAL)
        edit.loudnessLufs shouldBe -14.0
        edit.fps shouldBe 30
        edit.safeArea shouldBe shorts.safeArea
        edit.maxBitrate shouldBe "16M"
        shorts.maxDuration shouldBe 3.minutes
    }

    test("débit plafonné : l'encodeur matériel vise 75 % du plafond, le logiciel garde sa qualité constante") {
        PlatformProfile.parseBitrate("16M") shouldBe 16_000_000L
        PlatformProfile.parseBitrate("8000k") shouldBe 8_000_000L
        PlatformProfile.parseBitrate("vite") shouldBe null
        val amf = EncoderProfile("h264_amf", listOf("-c:v", "h264_amf", "-b:v", "12M"), hardware = true)
        amf.withMaxBitrate("8M").videoArgs.takeLast(6) shouldBe listOf("-b:v", "6000000", "-maxrate", "8000000", "-bufsize", "8000000")
        val x264 = EncoderProfile("libx264", listOf("-c:v", "libx264", "-crf", "20"), hardware = false)
        x264.withMaxBitrate("16M").videoArgs shouldBe listOf("-c:v", "libx264", "-crf", "20", "-maxrate", "16000000", "-bufsize", "16000000")
        x264.withMaxBitrate(null) shouldBe x264
    }

    test("plateformes d'app.yaml : ajoutées aux connues ou les remplaçant") {
        val app = ConfigYaml.yaml.decodeFromString(
            AppConfig.serializer(),
            """
            platforms:
              tiktok:
                name: TikTok
                format: "9:16"
                maxDuration: 60s
                safeArea: { top: 0.12, bottom: 0.25, right: 0.15 }
              twitch:
                name: Twitch (clips)
                format: "16:9"
                loudnessLufs: -16
            """.trimIndent(),
        )
        val platforms = LoadedConfig(app, Path("."), null).platforms
        platforms.getValue("tiktok").maxDuration shouldBe 1.minutes
        platforms.getValue("tiktok").safeArea.bottom shouldBe 0.25
        platforms.getValue("twitch").format shouldBe OutputFormat.LANDSCAPE
        platforms.getValue("shorts") shouldBe PlatformProfile.DEFAULTS.getValue("shorts")
    }
})
