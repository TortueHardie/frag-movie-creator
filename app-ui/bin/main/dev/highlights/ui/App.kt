package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.net.URI
import java.nio.file.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun App(state: UiState, actions: UiActions) {
    // Glisser-déposer géré par Compose : une DropTarget AWT posée sur la fenêtre ne reçoit rien, le canevas Compose intercepte.
    var dragging by remember { mutableStateOf(false) }
    val dropTarget = remember(actions) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty().mapNotNull(::droppedPath)
                if (files.isEmpty()) return false
                actions.dropFiles(files)
                return true
            }
        }
    }
    HighlightsTheme {
        // Surface racine : fournit la couleur de texte par défaut (sinon noire sur fond sombre).
        Surface(
            Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = dropTarget),
            color = Palette.background,
            contentColor = Palette.text,
        ) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(380.dp).fillMaxHeight().background(Palette.surface).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                AppHeader(state.config, actions)
                SourceSection(state, actions)
                SettingsSection(state, actions)
            }
            VerticalDivider(color = Palette.outline)
            Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.job?.let { JobCard(it, actions) }
                val session = state.session
                if (session == null) {
                    if (state.job == null) EmptyState(state)
                } else {
                    SessionHeader(state, session, actions)
                    TimelineCard(session, state.settings.threshold, actions)
                    SegmentList(session, state, actions, Modifier.weight(1f))
                    ExportBar(state, session, actions)
                }
            }
        }
        if (dragging) {
            Box(
                Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.85f)).border(BorderStroke(3.dp, Palette.accent)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Dépose la vidéo (ou la session) ici", style = MaterialTheme.typography.titleLarge, color = Palette.accent)
            }
        }
        }
        state.montage?.let { MontageDialog(it, state, actions) }
        state.error?.let { ErrorDialog(it, actions) }
    }
}

@Composable
private fun EmptyState(state: UiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aucune analyse", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            val hint = when {
                state.config is ConfigStatus.Failed -> "Corrige la configuration à gauche pour commencer."
                state.source == null -> "Choisis une capture (ou glisse-la dans la fenêtre), puis lance l'analyse."
                else -> "Vérifie les réglages puis clique sur « Analyser »."
            }
            Text(
                hint,
                color = Palette.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(420.dp),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleSmall, color = Palette.textMuted, modifier = Modifier.fillMaxWidth())
}

/** Les fichiers déposés arrivent sous forme d'URI ("file:/C:/…") ou, selon la source, de chemin brut. */
internal fun droppedPath(value: String): Path? =
    runCatching { if (value.startsWith("file:")) Path.of(URI(value)) else Path.of(value) }.getOrNull()
