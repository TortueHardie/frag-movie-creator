package dev.highlights.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.WebcamChoice
import dev.highlights.core.model.WebcamSync
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Webcam en incrustation : éteinte, cherchée à côté de la capture, ou fichier choisi. */
enum class WebcamMode { OFF, AUTO, FILE }

/** Réglage de la webcam dans l'application (pour l'export et le montage kills). */
data class WebcamUiState(
    val mode: WebcamMode = WebcamMode.OFF,
    val file: Path? = null,
    /** Décalage ajouté au calage automatique, en secondes (virgule ou point, signé). */
    val offsetText: String = "0",
) {
    /** Décalage lu, null si le champ est illisible (le calage automatique seul s'applique alors). */
    val offset: Duration?
        get() = offsetText.trim().replace(',', '.').replace('−', '-').toDoubleOrNull()?.let { (it * 1000).roundToLong().milliseconds }

    /** Choix passé au pipeline ; null : pas de webcam. */
    val choice: WebcamChoice?
        get() = when (mode) {
            WebcamMode.OFF -> null
            WebcamMode.AUTO -> WebcamChoice.Auto
            WebcamMode.FILE -> file?.let { WebcamChoice.File(it) }
        }
}

/** Aperçu de la disposition : images 9:16 et 16:9, et la webcam trouvée (null : aucune). */
data class WebcamPreviewState(
    val images: List<Path>,
    val file: Path?,
    val sync: WebcamSync?,
    val offset: Duration?,
    val version: Long = System.nanoTime(),
)

/** Section « Webcam » du panneau des réglages. */
@Composable
fun WebcamSection(state: UiState, actions: UiActions) {
    val webcam = state.settings.webcam
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(2.dp))
        Text(tr("ui.webcam.title"), style = MaterialTheme.typography.labelLarge)
        listOf(
            WebcamMode.OFF to tr("ui.webcam.off"),
            WebcamMode.AUTO to tr("ui.webcam.auto"),
            WebcamMode.FILE to tr("ui.webcam.file"),
        ).forEach { (mode, label) ->
            Row(Modifier.fillMaxWidth().clickable { actions.setWebcamMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = webcam.mode == mode, onClick = { actions.setWebcamMode(mode) })
                Text(label)
            }
        }
        if (webcam.mode == WebcamMode.AUTO) {
            Text(tr("ui.webcam.autoHint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
        }
        if (webcam.mode == WebcamMode.FILE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    webcam.file?.name ?: tr("ui.webcam.noFile"),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = actions::chooseWebcamFile) { Text(tr("ui.webcam.choose")) }
            }
        }
        if (webcam.mode != WebcamMode.OFF) {
            OutlinedTextField(
                value = webcam.offsetText,
                onValueChange = actions::setWebcamOffset,
                label = { Text(tr("ui.webcam.offset")) },
                singleLine = true,
                isError = webcam.offset == null,
                supportingText = { Text(tr("ui.webcam.offsetHint")) },
                modifier = Modifier.fillMaxWidth(),
            )
            val busy = state.busyWebcamPreview
            OutlinedButton(onClick = actions::previewWebcam, enabled = !busy && state.source != null && webcam.choice != null) {
                Text(if (busy) tr("ui.webcam.previewing") else tr("ui.webcam.preview"))
            }
            if (state.source == null) {
                Text(tr("ui.webcam.noSource"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
        }
    }
}

/** Fenêtre de l'aperçu de la disposition de la webcam : 9:16 (bandeau) et 16:9 (coin) côte à côte. */
@Composable
fun WebcamPreviewWindow(preview: WebcamPreviewState, busy: Boolean, actions: UiActions) {
    DialogWindow(
        onCloseRequest = actions::dismissWebcamPreview,
        state = rememberDialogState(size = DpSize(1180.dp, 820.dp)),
        title = tr("ui.webcam.previewTitle"),
    ) {
        HighlightsTheme {
            Column(Modifier.fillMaxSize().background(Palette.background).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    preview.file?.let { tr("ui.webcam.found", "file" to it.name) } ?: tr("ui.webcam.notFound"),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (preview.file != null) Palette.text else Palette.danger,
                )
                if (preview.file != null && preview.sync != null) {
                    Text(
                        tr("ui.webcam.sync.${preview.sync.name.lowercase()}", "offset" to I18n.decimal((preview.offset ?: Duration.ZERO).inWholeMilliseconds / 1000.0, 2)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                    )
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    preview.images.forEach { path ->
                        // 9:16 étroit, 16:9 large : chacun prend une part de la largeur à sa mesure.
                        val portrait = path.name.contains("9x16")
                        Box(Modifier.weight(if (portrait) 0.36f else 1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val image = rememberImage(path, preview.version)
                            if (image != null) {
                                Image(image, contentDescription = path.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
                Text(tr("ui.webcam.previewHint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions::previewWebcam, enabled = !busy) { Text(if (busy) tr("ui.webcam.previewing") else tr("ui.preview.regenerate")) }
                    preview.images.firstOrNull()?.let { first -> TextButton(onClick = { actions.reveal(first) }) { Text(tr("ui.preview.file")) } }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}
