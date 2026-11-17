package dev.highlights.ui.bridge

import dev.highlights.ui.Platform
import java.net.URI
import java.nio.file.Path

/**
 * [Platform] du pont de pilotage : pendant une action lancée par le pont ([scripted]), les boîtes de dialogue de
 * fichiers répondent avec les chemins passés dans la requête au lieu de s'ouvrir, et ce qui ouvrirait une autre
 * application (Explorateur, navigateur, éditeur, presse-papiers) est seulement noté et renvoyé dans la réponse : piloter
 * l'application ne doit jamais faire apparaître de fenêtre devant l'utilisateur. Hors du pont (clics de l'utilisateur),
 * tout passe à la vraie plateforme.
 */
class BridgePlatform(private val real: Platform) : Platform {
    /** Réponses d'une action pilotée ; n'existe que le temps de l'appel, sur le thread de l'interface. */
    class Script(val paths: List<Path>) {
        val calls = mutableListOf<String>()
        fun record(call: String) { calls += call }
    }

    @Volatile
    private var script: Script? = null

    /** Exécute [block] (une action) avec ces réponses aux boîtes de dialogue ; rend les appels interceptés. */
    fun <T> scripted(paths: List<Path>, block: () -> T): Pair<T, List<String>> {
        val s = Script(paths)
        script = s
        try {
            return block() to s.calls.toList()
        } finally {
            script = null
        }
    }

    override fun chooseVideos(initialDir: Path?): List<Path> =
        script?.let { it.record("chooseVideos → ${it.paths}"); it.paths } ?: real.chooseVideos(initialDir)

    override fun chooseAudio(initialDir: Path?): Path? =
        script?.let { it.record("chooseAudio → ${it.paths.firstOrNull()}"); it.paths.firstOrNull() } ?: real.chooseAudio(initialDir)

    override fun chooseSessions(initialDir: Path?): List<Path> =
        script?.let { it.record("chooseSessions → ${it.paths}"); it.paths } ?: real.chooseSessions(initialDir)

    override fun chooseDirectory(initialDir: Path?, title: String): Path? =
        script?.let { it.record("chooseDirectory($title) → ${it.paths.firstOrNull()}"); it.paths.firstOrNull() }
            ?: real.chooseDirectory(initialDir, title)

    override fun open(path: Path) = script?.record("open $path") ?: real.open(path)
    override fun reveal(path: Path) = script?.record("reveal $path") ?: real.reveal(path)
    override fun edit(path: Path) = script?.record("edit $path") ?: real.edit(path)
    override fun browse(uri: URI) = script?.record("browse $uri") ?: real.browse(uri)
    override fun copy(text: String) = script?.record("copy ${text.take(200)}") ?: real.copy(text)
}
