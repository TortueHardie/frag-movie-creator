package dev.highlights.pipeline

import dev.highlights.core.ConfigException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile

/**
 * Trouve app.yaml, dans l'ordre : chemin explicite, variable HIGHLIGHTS_CONFIG, propriété système highlights.config
 * (renseignée par le lanceur de l'interface), ./config/app.yaml, puis le dossier config/ à côté de l'installation.
 */
object ConfigLocator {
    fun locate(explicit: Path? = null): Path {
        explicit?.let { return it }
        val candidates = listOfNotNull(
            System.getenv("HIGHLIGHTS_CONFIG")?.let { Path(it) },
            System.getProperty("highlights.config")?.let { Path(it) },
            Path("config", "app.yaml"),
            installDir()?.resolve("config")?.resolve("app.yaml"),
        )
        return candidates.firstOrNull { it.isRegularFile() }
            ?: throw ConfigException("app.yaml introuvable (cherché : ${candidates.joinToString()})")
    }

    /** Dossier d'installation : le jar est dans lib/ (distribution CLI). */
    private fun installDir(): Path? = runCatching {
        Path.of(ConfigLocator::class.java.protectionDomain.codeSource.location.toURI()).parent?.parent
    }.getOrNull()
}
