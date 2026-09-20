package dev.highlights.ffmpeg

import dev.highlights.core.ConfigException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Trouve ffmpeg/ffprobe : chemin configuré, puis copie livrée avec l'installeur (propriété [BUNDLED_DIR]), puis PATH,
 * puis installation winget (Gyan.FFmpeg).
 */
object FfmpegLocator {
    const val BUNDLED_DIR = "highlights.ffmpeg.dir"

    fun locate(
        tool: String,
        configured: String?,
        env: Map<String, String> = System.getenv(),
        bundledDir: String? = System.getProperty(BUNDLED_DIR),
    ): Path {
        if (!configured.isNullOrBlank()) {
            val p = Path(configured)
            if (p.isRegularFile()) return p.toRealPath()
            findOnPath(configured, env)?.let { return it }
            throw ConfigException("$tool introuvable au chemin configuré : $configured")
        }
        bundledDir?.let { Path(it, "$tool.exe") }?.takeIf { it.isRegularFile() }?.let { return it.toRealPath() }
        return findOnPath(tool, env)
            ?: wingetCandidates(tool, env).firstOrNull { it.isRegularFile() }?.toRealPath()
            ?: throw ConfigException(
                "$tool introuvable. Installe-le avec « winget install Gyan.FFmpeg » ou renseigne ffmpeg.${tool}Path dans app.yaml",
            )
    }

    private fun findOnPath(name: String, env: Map<String, String>): Path? {
        val pathVar = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value ?: return null
        val names = if (name.endsWith(".exe", ignoreCase = true)) listOf(name) else listOf("$name.exe", name)
        return pathVar.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .asSequence()
            .flatMap { dir -> names.asSequence().map { runCatching { Path(dir.trim('"'), it) }.getOrNull() } }
            .filterNotNull()
            .firstOrNull { it.isRegularFile() }
            ?.toRealPath()
    }

    private fun wingetCandidates(tool: String, env: Map<String, String>): List<Path> {
        val localAppData = env["LOCALAPPDATA"] ?: return emptyList()
        val winget = Path(localAppData, "Microsoft", "WinGet")
        val links = winget.resolve("Links").resolve("$tool.exe")
        val packages = winget.resolve("Packages")
        val fromPackages = if (packages.isDirectory()) {
            Files.newDirectoryStream(packages, "Gyan.FFmpeg*").use { pkgs ->
                pkgs.flatMap { pkg ->
                    Files.newDirectoryStream(pkg).use { builds -> builds.map { it.resolve("bin").resolve("$tool.exe") } }
                }
            }
        } else {
            emptyList()
        }
        return listOf(links) + fromPackages.sortedDescending()
    }
}
