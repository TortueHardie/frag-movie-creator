package dev.highlights.ui

import dev.highlights.core.i18n.tr
import dev.highlights.montage.MusicLibrary
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
    fun chooseDirectory(initialDir: Path?, title: String = tr("ui.dialog.outputFolder")): Path?
    fun open(path: Path)
    fun reveal(path: Path)
    fun edit(path: Path)
    /** Page web dans le navigateur (connexion à YouTube, vidéo mise en ligne). */
    fun browse(uri: java.net.URI) = Unit
    /** Texte dans le presse-papiers. */
    fun copy(text: String) = Unit
    /** Où enregistrer un fichier ([fileName] proposé dans [initialDir]) ; null si annulé. */
    fun chooseSaveFile(initialDir: Path?, fileName: String, title: String): Path? = null
    /** Une image (logo, fond des cartes d'ouverture et de fin) ; null si annulé. */
    fun chooseImage(initialDir: Path?): Path? = null
    /** Notification Windows (bulle de la zone de notification) ; [onClick] : clic sur la notification. */
    fun notify(title: String, text: String, onClick: () -> Unit) = Unit
}

class DesktopPlatform(private val owner: () -> Frame?) : Platform {
    init {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    private var tray: java.awt.TrayIcon? = null

    @Volatile
    private var onNotificationClick: (() -> Unit)? = null

    /**
     * Par l'icône de la zone de notification, posée à la première notification seulement : Windows en fait une
     * notification du centre de notifications. Le clic sur la bulle déclenche l'action de l'icône.
     */
    override fun notify(title: String, text: String, onClick: () -> Unit) {
        if (!java.awt.SystemTray.isSupported()) {
            log.info { "Notification impossible (pas de zone de notification) : $title — $text" }
            return
        }
        runCatching {
            onNotificationClick = onClick
            val icon = tray ?: java.awt.TrayIcon(trayImage(), "Highlights").also { icon ->
                icon.isImageAutoSize = true
                icon.addActionListener { onNotificationClick?.invoke() }
                java.awt.SystemTray.getSystemTray().add(icon)
                tray = icon
            }
            icon.displayMessage(title, text, java.awt.TrayIcon.MessageType.INFO)
        }.onFailure { log.warn(it) { "Notification non affichée : $title" } }
    }

    /** Point orange de la marque, dessiné : l'application n'a pas d'image d'icône à charger. */
    private fun trayImage(): java.awt.Image {
        val size = 32
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color(0xFF, 0x7A, 0x2F)
        g.fillOval(4, 4, size - 8, size - 8)
        g.dispose()
        return image
    }

    override fun chooseVideos(initialDir: Path?): List<Path> =
        filesDialog(tr("ui.dialog.chooseVideos"), initialDir) { name ->
            name.lowercase().substringAfterLast('.', "") in HighlightPipeline.SUPPORTED_EXTENSIONS
        }

    override fun chooseAudio(initialDir: Path?): Path? =
        fileDialog(tr("ui.dialog.chooseTrack"), initialDir) { name ->
            name.lowercase().substringAfterLast('.', "") in MusicLibrary.AUDIO_EXTENSIONS
        }

    override fun chooseImage(initialDir: Path?): Path? =
        fileDialog(tr("ui.dialog.chooseImage"), initialDir) { name ->
            name.lowercase().substringAfterLast('.', "") in IMAGE_EXTENSIONS
        }

    override fun chooseSessions(initialDir: Path?): List<Path> =
        filesDialog(tr("ui.dialog.chooseSessions"), initialDir) { it.lowercase().endsWith(".session.json") }

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

    override fun browse(uri: java.net.URI) {
        runCatching { Desktop.getDesktop().browse(uri) }
            .recoverCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start() }
            .onFailure { log.warn { "Navigateur indisponible pour $uri : ${it.message}" } }
    }

    override fun copy(text: String) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
            .onFailure { log.warn { "Presse-papiers indisponible : ${it.message}" } }
    }

    /** Boîte « Enregistrer sous » de Windows (elle demande elle-même avant de remplacer un fichier existant). */
    override fun chooseSaveFile(initialDir: Path?, fileName: String, title: String): Path? {
        val dialog = FileDialog(owner(), title, FileDialog.SAVE).apply {
            initialDir?.takeIf { it.isDirectory() }?.let { directory = it.toString() }
            file = fileName
            isVisible = true
        }
        val file = dialog.file ?: return null
        return File(dialog.directory, file).toPath()
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

/** Images acceptées pour le logo et le fond des cartes (lues par FFmpeg et, pour leur taille, par Java). */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp")
