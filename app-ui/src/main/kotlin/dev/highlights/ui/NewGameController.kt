package dev.highlights.ui

import dev.highlights.assistant.CueMethod
import dev.highlights.assistant.KillMark
import dev.highlights.assistant.NewGameAssistant
import dev.highlights.assistant.NewGameRequest
import dev.highlights.assistant.NewGameScan
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.CropRegion
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import dev.highlights.pipeline.Pipelines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Logique de l'écran « Nouveau jeu » (voir [NewGameAssistant]) : l'état vit dans [UiState.newGame], le balayage passe
 * par la tâche longue de l'application ([run]), le dernier balayage est gardé ici pour écrire le profil.
 */
internal class NewGameController(
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
    private val platform: Platform,
    private val configFile: () -> Path?,
    private val run: (title: String, block: suspend (ProgressReporter) -> Unit) -> Unit,
    private val fail: (title: String, e: Exception) -> Unit,
) {
    private var scan: NewGameScan? = null

    private fun assistant(): NewGameAssistant? {
        val file = configFile() ?: return null
        val config = ConfigYaml.load(file)
        return NewGameAssistant(config, Pipelines.ffmpeg(config))
    }

    private fun workDir(): Path? = configFile()?.let { ConfigYaml.load(it).workDir.resolve("nouveau-jeu") }

    private fun edit(block: (NewGameState) -> NewGameState) = state.update { s -> s.copy(newGame = s.newGame?.let(block)) }

    fun show() {
        scan = null
        state.update { it.copy(newGame = NewGameState(), stats = null, search = null) }
    }

    fun close() = state.update { it.copy(newGame = null) }

    fun chooseVideo() {
        val video = platform.chooseVideos(null).firstOrNull() ?: return
        edit { it.copy(video = video, frame = null, box = null, marks = emptyList(), results = emptyList(), written = null) }
    }

    fun update(game: String?, time: String?) = edit { s -> s.copy(game = game ?: s.game, time = time ?: s.time) }

    fun showFrame() {
        val s = state.value.newGame ?: return
        val video = s.video ?: return
        val at = parseTime(s.time) ?: return
        val assistant = assistant() ?: return
        val out = workDir()?.resolve("image_${at.inWholeMilliseconds}.png") ?: return
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { assistant.preview(video, at, out) }
                edit { it.copy(frame = file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(tr("ui.newgame.showFrame"), e)
            }
        }
    }

    fun setBox(x: Double, y: Double, width: Double, height: Double) {
        val box = runCatching { CropRegion(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0), width, height) }.getOrNull() ?: return
        edit { it.copy(box = box) }
    }

    fun addMark() = edit { s ->
        val box = s.box
        if (!s.canAdd || box == null || parseTime(s.time) == null) s else s.copy(marks = s.marks + NewGameMarkUi(s.time.trim(), box), results = emptyList(), written = null)
    }

    fun removeMark(index: Int) = edit { s -> s.copy(marks = s.marks.filterIndexed { i, _ -> i != index }, results = emptyList(), written = null) }

    fun scan() {
        val s = state.value.newGame ?: return
        val video = s.video ?: return
        if (!s.canScan) return
        val assistant = assistant() ?: return
        val request = NewGameRequest(video, s.game.trim(), s.marks.mapNotNull { m -> parseTime(m.time)?.let { KillMark(it, m.box) } })
        val dir = workDir()?.resolve(request.profileId) ?: return
        run(tr("ui.newgame.scan")) { progress ->
            val result = assistant.scan(request, dir, progress)
            scan = result
            edit { st ->
                st.copy(
                    results = result.results.map { r ->
                        NewGameResultUi(
                            r.candidate.method.name, r.kills.size, r.hits.count { it.kind == "death" }, r.marksFound, r.sheet,
                            r.sample.map { false }, r.candidate.detail,
                        )
                    },
                    skipped = result.skipped.mapKeys { it.key.name },
                    written = null,
                )
            }
        }
    }

    fun toggleVerdict(method: String, index: Int) = edit { s ->
        s.copy(results = s.results.map { r -> if (r.method != method) r else r.copy(verdicts = r.verdicts.mapIndexed { i, v -> if (i == index) !v else v }) })
    }

    fun create() {
        val s = state.value.newGame ?: return
        val result = scan ?: return
        val assistant = assistant() ?: return
        try {
            val verdicts = s.results.associate { CueMethod.valueOf(it.method) to it.verdicts }
            val choice = assistant.choose(result, verdicts)
            if (choice == null) {
                fail(tr("ui.newgame.create"), dev.highlights.core.InputException(dev.highlights.core.i18n.Message("error.newgame.noMethod")))
                return
            }
            val file = assistant.write(result, choice)
            edit { it.copy(written = file, chosen = choice.result.candidate.method.name) }
        } catch (e: Exception) {
            fail(tr("ui.newgame.create"), e)
        }
    }

    companion object {
        /** « 1:32.5 », « 92.5 », « 1m32s ». */
        fun parseTime(text: String): Duration? {
            val parts = text.trim().split(':')
            if (parts.size in 2..3 && parts.all { it.toDoubleOrNull() != null }) {
                return (parts.fold(0.0) { acc, p -> acc * 60 + p.toDouble() } * 1000).toLong().milliseconds
            }
            return text.trim().toDoubleOrNull()?.let { (it * 1000).toLong().milliseconds } ?: Durations.parseOrNull(text)
        }
    }
}
