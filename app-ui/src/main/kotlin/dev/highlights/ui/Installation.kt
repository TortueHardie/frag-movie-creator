package dev.highlights.ui

import dev.highlights.ffmpeg.FfmpegLocator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

private val log = KotlinLogging.logger {}

/**
 * Application installée (MSI) : les ressources livrées sont dans le dossier donné par Compose
 * (`compose.application.resources.dir`), en lecture seule.
 *
 * - `ffmpeg/` : ffmpeg.exe et ffprobe.exe, utilisés en priorité ;
 * - `config/` : configuration par défaut, recopiée dans `%APPDATA%\Highlights\config` pour rester modifiable.
 *
 * Rien à faire quand la config est imposée (variable HIGHLIGHTS_CONFIG, ou `-Dhighlights.config` pendant le développement).
 */
object Installation {
    private const val MANIFEST = ".installed-files.properties"

    fun setup(
        resourcesDir: Path? = System.getProperty("compose.application.resources.dir")?.let(::Path),
        userConfigDir: Path = defaultUserConfigDir(),
    ) {
        if (resourcesDir == null) return
        val ffmpeg = resourcesDir.resolve("ffmpeg")
        if (ffmpeg.resolve("ffmpeg.exe").isRegularFile() && System.getProperty(FfmpegLocator.BUNDLED_DIR) == null) {
            System.setProperty(FfmpegLocator.BUNDLED_DIR, ffmpeg.toString())
        }
        val bundledConfig = resourcesDir.resolve("config")
        if (!bundledConfig.isDirectory()) return
        if (System.getenv("HIGHLIGHTS_CONFIG") != null || System.getProperty("highlights.config") != null) return
        runCatching { sync(bundledConfig, userConfigDir) }
            .onFailure { log.error(it) { "Copie de la configuration impossible dans $userConfigDir" } }
        System.setProperty("highlights.config", userConfigDir.resolve("app.yaml").toString())
    }

    /**
     * Recopie la config livrée sans écraser les modifications de l'utilisateur : un fichier est remplacé seulement s'il
     * est identique à celui installé la fois précédente (empreintes dans [MANIFEST]). Les nouveaux fichiers d'une mise à
     * jour (profils, modèles) arrivent donc, et les profils retouchés sont conservés.
     */
    fun sync(bundled: Path, target: Path) {
        target.createDirectories()
        val manifestFile = target.resolve(MANIFEST)
        val previous = Properties().apply { if (manifestFile.exists()) manifestFile.inputStream().use(::load) }
        val next = Properties()
        bundled.walk().filter { it.isRegularFile() }.forEach { src ->
            val rel = src.relativeTo(bundled).joinToString("/")
            val dst = target.resolve(rel)
            val hash = sha256(src)
            val untouched = dst.exists() && sha256(dst) == previous.getProperty(rel)
            when {
                !dst.exists() || (untouched && previous.getProperty(rel) != hash) -> {
                    dst.parent.createDirectories()
                    src.copyTo(dst, overwrite = true)
                    next.setProperty(rel, hash)
                }
                // Déjà à jour, ou modifié par l'utilisateur : on garde l'empreinte d'origine pour les mises à jour suivantes.
                else -> next.setProperty(rel, previous.getProperty(rel) ?: hash)
            }
        }
        manifestFile.outputStream().use { next.store(it, "Fichiers installés par Highlights — ne pas modifier") }
    }

    private fun defaultUserConfigDir(): Path =
        Path(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Highlights", "config")

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
