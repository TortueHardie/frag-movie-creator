package dev.highlights.pipeline

import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * Trouve le programme `whisper-cli` de whisper.cpp, comme FFmpeg : chemin configuré (`whisper.cli` d'app.yaml, relatif
 * au dossier de configuration), puis `whisper/whisper-cli.exe` du dossier de configuration (où l'on dépose aussi les
 * modèles), puis le PATH. Absent : null, les sous-titres passent par le filtre `whisper` de FFmpeg.
 */
object WhisperLocator {
    const val PROGRAM = "whisper-cli.exe"

    fun locate(configured: Path?, configDir: Path, env: Map<String, String> = System.getenv()): Path? {
        if (configured != null) return configured.takeIf { it.isRegularFile() }?.toAbsolutePath()
        configDir.resolve("whisper").resolve(PROGRAM).takeIf { it.isRegularFile() }?.let { return it.toAbsolutePath() }
        val pathVar = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value ?: return null
        return pathVar.split(File.pathSeparator).filter { it.isNotBlank() }
            .firstNotNullOfOrNull { dir -> runCatching { Path.of(dir.trim('"'), PROGRAM) }.getOrNull()?.takeIf { it.isRegularFile() } }
            ?.toAbsolutePath()
    }
}
