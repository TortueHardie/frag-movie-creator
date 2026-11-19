package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.CropRegion
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.math.abs
import kotlin.math.min

/** Kill marqué dans l'écran « Nouveau jeu » : instant (texte tel que saisi) et cadre du repère. */
data class NewGameMarkUi(val time: String, val box: CropRegion)

/** Résultat d'une méthode, et le jugement de l'utilisateur sur chaque vignette de sa planche (vrai par défaut). */
data class NewGameResultUi(
    /** Nom de [dev.highlights.assistant.CueMethod]. */
    val method: String,
    val kills: Int,
    val deaths: Int,
    val marksFound: Int,
    val sheet: Path?,
    val verdicts: List<Boolean>,
    val detail: String,
)

/** Écran « Nouveau jeu » : capture, nom, kills marqués, puis résultats des méthodes et profil écrit. */
data class NewGameState(
    val video: Path? = null,
    val game: String = "",
    val time: String = "",
    /** Image affichée pour tracer le cadre (instant [time]) et le cadre en cours. */
    val frame: Path? = null,
    val box: CropRegion? = null,
    val marks: List<NewGameMarkUi> = emptyList(),
    val results: List<NewGameResultUi> = emptyList(),
    /** Méthodes écartées avant le balayage : nom → raison. */
    val skipped: Map<String, dev.highlights.core.i18n.Message> = emptyMap(),
    val written: Path? = null,
    val chosen: String? = null,
) {
    val canAdd: Boolean get() = box != null && time.isNotBlank() && marks.size < MAX_MARKS
    val canScan: Boolean get() = video != null && game.isNotBlank() && marks.isNotEmpty()

    companion object {
        const val MAX_MARKS = 3
    }
}

@Composable
fun NewGameView(state: NewGameState, actions: UiActions, busy: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("ui.newgame.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::closeNewGame) { Text(tr("ui.library.title")) }
        }
        Text(tr("ui.newgame.intro"), color = Palette.textMuted, style = MaterialTheme.typography.bodyMedium)

        // 1. Capture et nom du jeu.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = actions::chooseNewGameVideo, enabled = !busy) { Text(tr("ui.newgame.chooseVideo")) }
            Text(state.video?.name ?: tr("ui.newgame.noVideo"), modifier = Modifier.weight(1f), color = if (state.video == null) Palette.textMuted else Color.Unspecified)
            OutlinedTextField(
                value = state.game,
                onValueChange = { actions.updateNewGame(game = it) },
                label = { Text(tr("ui.newgame.game")) },
                singleLine = true,
                modifier = Modifier.width(260.dp),
            )
        }

        // 2. Kills marqués : instant, image, cadre tracé à la souris.
        Text(tr("ui.newgame.marks", "max" to NewGameState.MAX_MARKS), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.time,
                onValueChange = { actions.updateNewGame(time = it) },
                label = { Text(tr("ui.newgame.time")) },
                singleLine = true,
                modifier = Modifier.width(160.dp),
            )
            OutlinedButton(onClick = actions::showNewGameFrame, enabled = !busy && state.video != null && state.time.isNotBlank()) { Text(tr("ui.newgame.showFrame")) }
            Button(onClick = actions::addNewGameMark, enabled = state.canAdd) { Text(tr("ui.newgame.addMark")) }
        }
        state.frame?.let { frame ->
            Text(tr("ui.newgame.drawHint"), color = Palette.textMuted, style = MaterialTheme.typography.bodySmall)
            FrameBox(frame, state.box) { actions.setNewGameBox(it.x, it.y, it.width, it.height) }
        }
        state.marks.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("ui.newgame.mark", "index" to i + 1, "time" to m.time, "box" to "%.3f, %.3f, %.3f, %.3f".format(java.util.Locale.ROOT, m.box.x, m.box.y, m.box.width, m.box.height)),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.removeNewGameMark(i) }, enabled = !busy) { Text(tr("ui.newgame.remove")) }
            }
        }
        Button(onClick = actions::scanNewGame, enabled = !busy && state.canScan) { Text(tr("ui.newgame.scan")) }

        // 3. Résultats : une planche par méthode, chaque vignette jugée vraie ou fausse.
        if (state.results.isNotEmpty()) {
            Text(tr("ui.newgame.results"), style = MaterialTheme.typography.titleMedium)
            Text(tr("ui.newgame.resultsHint"), color = Palette.textMuted, style = MaterialTheme.typography.bodySmall)
            state.results.forEach { r -> ResultCard(r, state.marks.size, actions) }
            state.skipped.forEach { (m, why) ->
                Text(tr("ui.newgame.skipped", "method" to tr("ui.newgame.method.$m"), "reason" to why.text()), color = Palette.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = actions::createNewGameProfile, enabled = !busy && state.written == null) { Text(tr("ui.newgame.create")) }
        }
        state.written?.let {
            Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, Palette.outline)) {
                Column(Modifier.padding(12.dp)) {
                    Text(tr("ui.newgame.written", "method" to tr("ui.newgame.method.${state.chosen}")), style = MaterialTheme.typography.titleSmall)
                    Text(it.toString(), color = Palette.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: NewGameResultUi, marks: Int, actions: UiActions) {
    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ui.newgame.method.${r.method}"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("ui.newgame.found", "kills" to r.kills, "deaths" to r.deaths, "marked" to r.marksFound, "marks" to marks),
                style = MaterialTheme.typography.bodyMedium,
            )
            rememberImage(r.sheet)?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
            if (r.verdicts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.verdicts.forEachIndexed { i, ok ->
                        FilterChip(selected = ok, onClick = { actions.toggleNewGameVerdict(r.method, i) }, label = { Text("${i + 1}") })
                    }
                }
            }
        }
    }
}

/** Image de la capture où l'on trace le cadre du repère à la souris (coordonnées normalisées). */
@Composable
private fun FrameBox(frame: Path, box: CropRegion?, onBox: (CropRegion) -> Unit) {
    val image = rememberImage(frame) ?: return
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
        Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize().pointerInput(frame) {
                detectDragGestures(
                    onDragStart = { start = it; current = it },
                    onDrag = { change, _ -> current = change.position },
                    onDragEnd = {
                        val a = start
                        val b = current
                        if (a != null && b != null && abs(a.x - b.x) > 3 && abs(a.y - b.y) > 3) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            onBox(CropRegion((min(a.x, b.x) / w).toDouble(), (min(a.y, b.y) / h).toDouble(), (abs(a.x - b.x) / w).toDouble(), (abs(a.y - b.y) / h).toDouble()))
                        }
                        start = null
                        current = null
                    },
                )
            },
        ) {
            val a = start
            val b = current
            val (topLeft, sz) = if (a != null && b != null) {
                Offset(min(a.x, b.x), min(a.y, b.y)) to Size(abs(a.x - b.x), abs(a.y - b.y))
            } else if (box != null) {
                Offset((box.x * size.width).toFloat(), (box.y * size.height).toFloat()) to Size((box.width * size.width).toFloat(), (box.height * size.height).toFloat())
            } else {
                return@Canvas
            }
            drawRect(Color(0xFFFFD200), topLeft, sz, style = Stroke(width = 2f))
        }
    }
}
