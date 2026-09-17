package dev.highlights.core.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import dev.highlights.core.ConfigException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

@Serializable
data class AppConfig(
    val ffmpeg: FfmpegSettings = FfmpegSettings(),
    /** Chemins relatifs résolus par rapport au dossier du fichier app.yaml. */
    val outputDir: String = "../output",
    val profilesDir: String = "profiles",
    /** Fichiers temporaires. null = %TEMP%\highlights. */
    val workDir: String? = null,
    val analysis: AnalysisSettings = AnalysisSettings(),
    val encoder: EncoderSettings = EncoderSettings(),
)

@Serializable
data class FfmpegSettings(
    /** null = recherche automatique (PATH puis installation winget). */
    val ffmpegPath: String? = null,
    val ffprobePath: String? = null,
    /** Décodage matériel pour le rendu (d3d11va sur Windows). null = logiciel. */
    val hwaccelDecode: String? = "d3d11va",
)

@Serializable
data class AnalysisSettings(
    /** Nombre de détecteurs exécutés en parallèle. */
    val parallelism: Int = 2,
    /** Si un détecteur échoue, continuer avec les autres signaux plutôt que d'abandonner. */
    val continueOnDetectorError: Boolean = true,
) {
    init {
        require(parallelism >= 1) { "analysis.parallelism doit être ≥ 1" }
    }
}

@Serializable
data class EncoderSettings(
    val preference: List<String> = listOf("h264_amf", "libx264"),
    /** Remplace les arguments vidéo par défaut d'un encodeur, ex. h264_amf: ["-c:v", "h264_amf", "-rc", "cqp", …]. */
    val videoArgs: Map<String, List<String>> = emptyMap(),
    val audioBitrate: String = "192k",
)

/** Configuration chargée et dossier de référence pour résoudre les chemins relatifs. */
data class LoadedConfig(val app: AppConfig, val baseDir: Path, val source: Path?) {
    fun resolve(path: String): Path = baseDir.resolve(path).normalize()

    val outputDir: Path get() = resolve(app.outputDir)
    val profilesDir: Path get() = resolve(app.profilesDir)
    val workDir: Path get() = app.workDir?.let(::resolve) ?: Path(System.getProperty("java.io.tmpdir"), "highlights")
}

object ConfigYaml {
    val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    fun <T> decodeFile(file: Path, deserializer: DeserializationStrategy<T>): T {
        val text = try {
            file.readText().removePrefix("﻿")
        } catch (e: Exception) {
            throw ConfigException("Lecture impossible de $file : ${e.message}", e)
        }
        return try {
            yaml.decodeFromString(deserializer, text)
        } catch (e: YamlException) {
            throw ConfigException("$file ligne ${e.line}, colonne ${e.column} : ${e.message}", e)
        } catch (e: SerializationException) {
            throw ConfigException("$file : ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ConfigException("$file : ${e.message}", e)
        }
    }

    fun load(file: Path): LoadedConfig {
        if (!file.isRegularFile()) throw ConfigException("Fichier de configuration introuvable : $file")
        val abs = file.toAbsolutePath().normalize()
        return LoadedConfig(decodeFile(abs, AppConfig.serializer()), abs.parent, abs)
    }
}
