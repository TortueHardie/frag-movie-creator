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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension

private val log = KotlinLogging.logger {}

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { t, e -> log.error(e) { "Exception non gérée dans ${t.name}" } }
    Installation.setup()
    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        var windowRef: java.awt.Frame? = null
        val controller = remember { AppController(scope, DesktopPlatform { windowRef }, updater = GitHubUpdater()) }
        val state by controller.state.collectAsState()
        val quit = {
            controller.shutdown()
            scope.cancel()
            exitApplication()
        }
        // Mise à jour : l'installeur attend la fermeture de l'application pour remplacer ses fichiers.
        if (state.exitRequested) LaunchedEffect(Unit) { quit() }

        Window(
            onCloseRequest = quit,
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
        }
    }
}
