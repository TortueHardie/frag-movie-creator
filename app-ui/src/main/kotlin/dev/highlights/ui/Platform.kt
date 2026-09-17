package dev.highlights.ui

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
    fun chooseVideo(initialDir: Path?): Path?
    fun chooseAudio(initialDir: Path?): Path?
    fun chooseSession(initialDir: Path?): Path?
    fun chooseDirectory(initialDir: Path?): Path?
    fun open(path: Path)
    fun reveal(path: Path)
    fun edit(path: Path)
}

class DesktopPlatform(private val owner: () -> Frame?) : Platform {
    init {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    override fun chooseVideo(initialDir: Path?): Path? =
        fileDialog("Choisir une capture", initialDir) { name -> name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") } }

    override fun chooseAudio(initialDir: Path?): Path? =
        fileDialog("Choisir une musique", initialDir) { name ->
            name.lowercase().let { n -> listOf(".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".opus").any { n.endsWith(it) } }
        }

    override fun chooseSession(initialDir: Path?): Path? =
        fileDialog("Ouvrir une session", initialDir) { it.lowercase().endsWith(".session.json") }

    override fun chooseDirectory(initialDir: Path?): Path? {
        val chooser = JFileChooser(initialDir?.takeIf { it.isDirectory() }?.toFile()).apply {
            dialogTitle = "Dossier de sortie"
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
}
