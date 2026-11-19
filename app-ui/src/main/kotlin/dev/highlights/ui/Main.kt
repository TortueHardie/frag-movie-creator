package dev.highlights.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.highlights.ui.bridge.ControlBridge
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension

private val log = KotlinLogging.logger {}

/** Contrôleur une fois créé : une exception non gérée lui est signalée pour proposer tout de suite de la signaler. */
@Volatile
private var liveController: AppController? = null

fun main() {
    val reportEnv = ReportEnvironment.forThisApp()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        log.error(e) { "Exception non gérée dans ${t.name}" }
        // Notée pour être proposée au signalement : tout de suite si l'interface tient, sinon au prochain lancement.
        reportEnv.crashLog?.record(t.name, e)
        runCatching { liveController?.onUncaughtException() }
    }
    // La langue avant toute fenêtre : celle choisie dans l'application, sinon celle de Windows.
    dev.highlights.core.i18n.I18n.setLanguage(LanguagePrefsStore(LanguagePrefsStore.DEFAULT_FILE).load())
    Installation.setup()
    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        var windowRef: java.awt.Frame? = null
        // Pont de pilotage (développement seulement, -Dhighlights.bridge=true) : voir ControlBridge.
        val platform = remember { ControlBridge.platform(DesktopPlatform { windowRef }) }
        val controller = remember { AppController(scope, platform, updater = GitHubUpdater(), reportEnv = reportEnv).also { liveController = it } }
        DisposableEffect(controller) {
            val bridge = ControlBridge.startIfEnabled(controller.state, controller, platform)
            onDispose { bridge?.close() }
        }
        val state by controller.state.collectAsState()
        val quit = {
            controller.shutdown()
            scope.cancel()
            exitApplication()
        }
        // Mise à jour : l'installeur attend la fermeture de l'application pour remplacer ses fichiers.
        if (state.exitRequested) LaunchedEffect(Unit) { quit() }

        Window(
            // Des traitements en file : confirmation d'abord (« Quitter » passe par exitRequested).
            onCloseRequest = { if (controller.requestQuit()) quit() },
            title = "Highlights",
            state = rememberWindowState(width = 1440.dp, height = 920.dp, position = WindowPosition.PlatformDefault),
        ) {
            windowRef = window
            DisposableEffect(Unit) {
                window.minimumSize = Dimension(1100, 700)
                onDispose { }
            }
            App(state, controller)
            state.imagePreview?.let { VerticalPreviewWindow(it, state.busyVerticalPreview != null, controller) }
            state.webcamPreview?.let { WebcamPreviewWindow(it, state.busyWebcamPreview, controller) }
        }
    }
}
