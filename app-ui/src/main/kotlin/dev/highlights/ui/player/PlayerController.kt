package dev.highlights.ui.player

import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.TimeRange
import dev.highlights.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Partie lecteur de [dev.highlights.ui.AppController] : crée le [VideoPlayer] sur le FFmpeg de la configuration
 * chargée, tient [UiState.player] à jour, et ouvre le lecteur du système quand il n'y a pas de FFmpeg ([fallback]).
 */
class PlayerController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val fallback: (Path) -> Unit,
    private val factory: (FfmpegService, CoroutineScope) -> VideoPlayer = { ffmpeg, s -> VideoPlayer(ffmpeg, s) },
) {
    private var engine: VideoPlayer? = null
    private var engineFfmpeg: FfmpegService? = null
    private var mirror: Job? = null

    /** Moteur en service, s'il y en a un (la fenêtre lit ses images). */
    val current: VideoPlayer? get() = engine

    /** Ouvre [file] dans le lecteur intégré, lecture lancée (ou image de [at] seule si [play] est faux). */
    fun open(ffmpeg: FfmpegService?, file: Path, at: Duration = Duration.ZERO, play: Boolean = true, entry: Int? = null, range: TimeRange? = null) {
        val e = engineFor(ffmpeg) ?: return fallback(file)
        state.update { it.copy(player = PlayerUiState(file, entry, range, position = at, playing = play)) }
        e.open(file, at, play)
    }

    /**
     * Glissement sur la frise d'une capture : ouvre la capture en pause à [at] si elle n'est pas déjà dans le lecteur,
     * sinon montre l'image de [at].
     */
    fun scrub(ffmpeg: FfmpegService?, file: Path, entry: Int, at: Duration) {
        val shown = state.value.player
        if (shown == null || shown.file != file || engine == null) {
            if (engineFor(ffmpeg) == null) return
            open(ffmpeg, file, at, play = false, entry = entry)
            return
        }
        state.update { s -> s.copy(player = s.player?.copy(position = at, entry = entry)) }
        engine?.scrub(at)
    }

    fun toggle() = engine?.toggle()
    fun seek(at: Duration) = engine?.seek(at)
    fun scrub(at: Duration) = engine?.scrub(at)
    fun endScrub() = engine?.endScrub()

    fun close() {
        engine?.stop()
        state.update { it.copy(player = null) }
    }

    fun dispose() {
        mirror?.cancel()
        engine?.dispose()
        engine = null
    }

    private fun engineFor(ffmpeg: FfmpegService?): VideoPlayer? {
        if (ffmpeg == null) return null
        if (engine != null && engineFfmpeg === ffmpeg) return engine
        dispose()
        val e = factory(ffmpeg, scope)
        engine = e
        engineFfmpeg = ffmpeg
        // Recopie dans l'état de l'interface, au demi-seconde près : assez pour la frise et le pont de pilotage, sans
        // recomposer toute la fenêtre principale à chaque image.
        mirror = scope.launch {
            e.view
                .map { v -> Mirror(v.position.inWholeMilliseconds / MIRROR_STEP.inWholeMilliseconds, v.playing, v.duration, v.sync.frames / 60, v) }
                .distinctUntilChanged { a, b -> a.step == b.step && a.playing == b.playing && a.duration == b.duration && a.frames == b.frames }
                .collect { m ->
                    state.update { s ->
                        s.copy(player = s.player?.copy(position = m.view.position, playing = m.view.playing, duration = m.view.duration, sync = m.view.sync))
                    }
                }
        }
        return e
    }

    private class Mirror(val step: Long, val playing: Boolean, val duration: Duration, val frames: Long, val view: PlayerView)

    private companion object {
        val MIRROR_STEP = 500.milliseconds
    }
}
