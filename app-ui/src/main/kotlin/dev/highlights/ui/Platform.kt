package dev.highlights.ui

import dev.highlights.pipeline.HighlightPipeline
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.io.path.isDirectory

private val log = KotlinLogging.logger {}

/** Interactions avec le système (boîtes de dialogue, ouverture de fichiers), remplaçables en test. */
interface Platform {
    /** Une ou plusieurs captures (sélection multiple) ; liste vide si annulé. */
    fun chooseVideos(initialDir: Path?): List<Path>
    fun chooseAudio(initialDir: Path?): Path?
    fun chooseSessions(initialDir: Path?): List<Path>
    fun chooseDirectory(initialDir: Path?, title: String = "Dossier de sortie"): Path?
    fun open(path: Path)
    fun reveal(path: Path)
    fun edit(path: Path)
}

class DesktopPlatform(private val owner: () -> Frame?) : Platform {
    init {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    override fun chooseVideos(initialDir: Path?): List<Path> =
        filesDialog("Choisir une ou plusieurs captures", initialDir) { name ->
            name.lowercase().substringAfterLast('.', "") in HighlightPipeline.SUPPORTED_EXTENSIONS
        }

    override fun chooseAudio(initialDir: Path?): Path? =
        fileDialog("Choisir une musique", initialDir) { name ->
            name.lowercase().let { n -> listOf(".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".opus").any { n.endsWith(it) } }
        }

    override fun chooseSessions(initialDir: Path?): List<Path> =
        filesDialog("Ouvrir une ou plusieurs sessions", initialDir) { it.lowercase().endsWith(".session.json") }

    override fun chooseDirectory(initialDir: Path?, title: String): Path? {
        val chooser = JFileChooser(initialDir?.takeIf { it.isDirectory() }?.toFile()).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    override fun open(path: Path) {
        runCatching { Desktop.getDesktop().open(path.toFile()) }
            .onFailure { log.warn { "Ouverture impossible de $path : ${it.message}" }; reveal(path) }
    }

    override fun reveal(path: Path) {
        val args = if (path.isDirectory()) listOf("explorer.exe", path.toString()) else listOf("explorer.exe", "/select,$path")
        runCatching { ProcessBuilder(args).start() }.onFailure { log.warn { "Explorateur indisponible : ${it.message}" } }
    }

    /** Fichiers texte (YAML) : éditeur associé, sinon Bloc-notes. */
    override fun edit(path: Path) {
        runCatching { Desktop.getDesktop().edit(path.toFile()) }
            .recoverCatching { Desktop.getDesktop().open(path.toFile()) }
            .recoverCatching { ProcessBuilder("notepad.exe", path.toString()).start() }
            .onFailure { log.warn { "Édition impossible de $path : ${it.message}" } }
    }

    private fun fileDialog(title: String, initialDir: Path?, accept: (String) -> Boolean): Path? {
        val dialog = FileDialog(owner(), title, FileDialog.LOAD).apply {
            initialDir?.takeIf { it.isDirectory() }?.let { directory = it.toString() }
            setFilenameFilter { _, name -> accept(name) }
            isVisible = true
        }
        val file = dialog.file ?: return null
        return File(dialog.directory, file).toPath()
    }

    /** Sélection multiple (Ctrl / Maj + clic dans la boîte de dialogue de Windows). */
    private fun filesDialog(title: String, initialDir: Path?, accept: (String) -> Boolean): List<Path> {
        val dialog = FileDialog(owner(), title, FileDialog.LOAD).apply {
            initialDir?.takeIf { it.isDirectory() }?.let { directory = it.toString() }
            setFilenameFilter { _, name -> accept(name) }
            isMultipleMode = true
            isVisible = true
        }
        return dialog.files.map { it.toPath() }
    }
}
