package dev.highlights.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.highlights.core.HighlightsException
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.userMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.InputStream
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Taille des images décodées : la vidéo réduite pour tenir dans la boîte du lecteur, dimensions paires. */
data class DecodeSize(val width: Int, val height: Int) {
    val bytes: Int get() = width * height * 4

    companion object {
        /**
         * Plus grande taille qui tient dans [maxWidth]×[maxHeight] sans agrandir ni déformer. 720p par défaut : un
         * montage 1080×1920 se décode en 404×720, quatre fois moins de pixels à copier et à convertir que la source,
         * et c'est déjà plus que la fenêtre du lecteur n'en montre sur un écran 1080p.
         */
        fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): DecodeSize {
            if (width <= 0 || height <= 0) return DecodeSize(maxWidth.even(), maxHeight.even())
            val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
            return DecodeSize((width * scale).toInt().even().coerceAtLeast(2), (height * scale).toInt().even().coerceAtLeast(2))
        }

        private fun Int.even() = this - this % 2
    }
}

/** Image affichée : [index] change à chaque image, même quand le tampon de pixels est réutilisé. */
class PlayerFrame(val image: ImageBitmap, val pts: Duration, val index: Long)

/**
 * Mesure de la synchro pendant la lecture : écart entre l'horloge du son et l'heure de l'image au moment où elle est
 * montrée (positif = image en retard sur le son). [dropped] : images sautées parce qu'elles arrivaient trop tard.
 */
data class SyncStats(
    val frames: Long = 0,
    val dropped: Long = 0,
    val meanMs: Double = 0.0,
    val p95Ms: Double = 0.0,
    val maxMs: Double = 0.0,
)

/** État du lecteur, mis à jour une dizaine de fois par seconde (la position) ou à chaque changement. */
data class PlayerView(
    val file: Path? = null,
    val duration: Duration = Duration.ZERO,
    val position: Duration = Duration.ZERO,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val ended: Boolean = false,
    val hasAudio: Boolean = false,
    val size: DecodeSize? = null,
    val error: Message? = null,
    val sync: SyncStats = SyncStats(),
)

/**
 * Lecteur vidéo de l'application, sans dépendance native nouvelle : le FFmpeg déjà livré décode, en deux process,
 * les images (réduites à [DecodeSize], RGBA brut sur stdout) et le son (PCM 48 kHz stéréo vers [AudioSink]).
 * Le son est l'horloge maîtresse ([PlaybackClock]) : chaque image attend son heure, et une image qui arrive plus d'une
 * trame et demie trop tard est sautée s'il en existe déjà une plus récente. Pause = arrêt de la sortie son : FFmpeg se
 * bloque de lui-même sur le tuyau plein, sans être relancé. Déplacement = relance des deux process à `-ss` (recherche
 * exacte de FFmpeg) ; pendant un glissement de la barre, une seule image est décodée par position ([scrub]), les
 * positions intermédiaires sont fusionnées.
 *
 * Toutes les commandes passent par une file traitée dans l'ordre par une seule coroutine : aucune course entre un clic
 * sur lecture et un glissement en cours.
 */
class VideoPlayer(
    private val ffmpeg: FfmpegService,
    parent: CoroutineScope,
    private val sinks: () -> AudioSink? = JavaSoundSink::create,
    private val maxWidth: Int = 1280,
    private val maxHeight: Int = 720,
    hwaccel: String? = Hwaccel.AUTO,
    private val now: () -> Long = System::nanoTime,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default)
    private val _view = MutableStateFlow(PlayerView())
    private val _frame = MutableStateFlow<PlayerFrame?>(null)
    val view: StateFlow<PlayerView> = _view.asStateFlow()
    val frame: StateFlow<PlayerFrame?> = _frame.asStateFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** Décodage matériel demandé à FFmpeg ; abandonné pour de bon au premier échec (repli logiciel). */
    @Volatile private var hw: String? = Hwaccel.resolve(hwaccel)

    // État de la boucle de commandes : lu et écrit seulement par elle.
    private var media: MediaInfo? = null
    private var size = DecodeSize(2, 2)
    private var fps = DEFAULT_FPS
    private var position = Duration.ZERO
    private var scrubbing = false
    private var resumeAfterScrub = false
    private var still: Job? = null

    /** Index des images seules : négatifs et toujours nouveaux, pour que l'écran se redessine. */
    @Volatile private var stills = 0L

    /** Lecture en cours ; lu aussi par ses propres coroutines pour ne plus publier une fois remplacée. */
    @Volatile private var session: Session? = null
    private var bitmaps = BitmapRing(size)
    private val meter = SyncMeter()

    init {
        scope.launch { loop() }
    }

    fun open(file: Path, at: Duration = Duration.ZERO, play: Boolean = true) = send(Command.Open(file, at, play))
    fun play() = send(Command.Play)
    fun pause() = send(Command.Pause)
    fun toggle() = send(Command.Toggle)

    /** Va à [at] : la lecture continue si elle était en cours, sinon l'image de [at] est montrée. */
    fun seek(at: Duration) = send(Command.Seek(at))

    /** Glissement de la barre (ou de la frise) : met en pause et montre l'image de [at] ; [endScrub] reprend. */
    fun scrub(at: Duration) = send(Command.Scrub(at))
    fun endScrub() = send(Command.EndScrub)

    /** Arrête la lecture et oublie la vidéo ; le lecteur reste utilisable. */
    fun stop() = send(Command.Stop)

    /** Libère tout pour de bon (fermeture de l'application). */
    fun dispose() {
        session?.stop()
        scope.cancel()
    }

    private fun send(command: Command) {
        commands.trySend(command)
    }

    private sealed interface Command {
        data class Open(val file: Path, val at: Duration, val play: Boolean) : Command
        data object Play : Command
        data object Pause : Command
        data object Toggle : Command
        data class Seek(val at: Duration) : Command
        data class Scrub(val at: Duration) : Command
        data object EndScrub : Command
        data object Stop : Command
        class Ended(val session: Session) : Command
        class Lagging(val session: Session) : Command
        class Failed(val session: Session, val error: Exception) : Command
    }

    private suspend fun loop() {
        var next: Command? = null
        while (true) {
            var command = next ?: commands.receive()
            next = null
            // Positions fusionnées : seule la dernière d'une rafale de glissements compte.
            if (command is Command.Scrub || command is Command.Seek) {
                while (true) {
                    val more = commands.tryReceive().getOrNull() ?: break
                    if (more is Command.Scrub || more is Command.Seek) command = more else { next = more; break }
                }
            }
            try {
                handle(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Lecteur : échec de $command" }
                stopSession()
                _view.update { it.copy(playing = false, loading = false, error = e.userMessage()) }
            }
        }
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Open -> {
                stopAll()
                meter.reset()
                _frame.value = null
                _view.value = PlayerView(file = command.file, loading = true)
                val info = ffmpeg.probe(command.file)
                val video = info.video ?: throw HighlightsException(Message("error.player.noVideo", "file" to command.file.fileName.toString()))
                media = info
                size = DecodeSize.fit(video.width, video.height, maxWidth, maxHeight)
                fps = playbackFps(video.fps, size)
                if (bitmaps.size != size) bitmaps = BitmapRing(size)
                position = clamp(command.at)
                _view.update { it.copy(loading = false, duration = info.duration, position = position, hasAudio = info.audio.isNotEmpty(), size = size) }
                if (command.play) startSession(position) else showStill(position)
            }
            Command.Play -> play(fromEnd = true)
            Command.Pause -> session?.let { s ->
                s.userPaused = true
                s.clock.pause()
                _view.update { it.copy(playing = false) }
            }
            Command.Toggle -> if (session?.userPaused == false) handle(Command.Pause) else play(fromEnd = true)
            is Command.Seek -> {
                val playing = session?.userPaused == false
                stopAll()
                position = clamp(command.at)
                _view.update { it.copy(position = position, ended = false) }
                if (playing) startSession(position) else showStill(position)
            }
            is Command.Scrub -> {
                if (!scrubbing) resumeAfterScrub = session?.userPaused == false
                scrubbing = true
                stopAll()
                position = clamp(command.at)
                _view.update { it.copy(position = position, playing = false, ended = false) }
                showStill(position)
            }
            Command.EndScrub -> {
                scrubbing = false
                if (resumeAfterScrub) startSession(position)
                resumeAfterScrub = false
            }
            Command.Stop -> {
                stopAll()
                media = null
                _frame.value = null
                _view.value = PlayerView()
            }
            is Command.Ended -> if (session === command.session) {
                session = null
                position = media?.duration ?: position
                _view.update { it.copy(playing = false, ended = true, position = position, sync = meter.snapshot()) }
                log.info { "Lecture finie : synchro ${meter.snapshot()}" }
            }
            is Command.Lagging -> if (session === command.session && fps > MIN_FPS) {
                val at = clamp(command.session.clock.position())
                fps = (fps / 2).coerceAtLeast(MIN_FPS)
                log.warn { "Lecteur : l'image ne suit pas le son, reprise à $at en $fps img/s" }
                stopSession()
                position = at
                startSession(at)
            }
            is Command.Failed -> if (session === command.session) {
                val at = clamp(command.session.clock.position())
                session = null
                if (hw != null) {
                    log.warn(command.error) { "Lecteur : décodage matériel ($hw) en échec, repli logiciel" }
                    hw = null
                    startSession(at)
                } else {
                    throw command.error
                }
            }
        }
    }

    private fun play(fromEnd: Boolean) {
        if (media == null) return
        session?.let { s ->
            if (s.userPaused) {
                s.userPaused = false
                if (s.started) s.clock.resume()
                _view.update { it.copy(playing = true) }
            }
            return
        }
        still?.cancel()
        val total = media?.duration ?: Duration.ZERO
        if (fromEnd && position >= total - END_MARGIN) position = Duration.ZERO
        startSession(position)
    }

    private fun clamp(at: Duration): Duration = at.coerceIn(Duration.ZERO, media?.duration ?: at.coerceAtLeast(Duration.ZERO))

    private fun stopSession() {
        val s = session ?: return
        position = clamp(s.clock.position())
        session = null
        s.stop()
    }

    private fun stopAll() {
        still?.cancel()
        still = null
        stopSession()
    }

    private fun startSession(at: Duration) {
        val m = media ?: return
        val sink = if (m.audio.isEmpty()) null else sinks()
        val s = Session(m.path, at, size, fps, sink, bitmaps)
        session = s
        _view.update { it.copy(playing = true, ended = false, error = null, position = at) }
        // Son retenu jusqu'à la première image : FFmpeg sort le son avant l'image (décodeur vidéo plus long à ouvrir),
        // et la première image arrivait 210 ms après son heure sur un montage 1080×1920.
        s.clock.pause()
        s.job = scope.launch { s.run() }
    }

    /** Image de [at] seule (pause, glissement) : un FFmpeg qui s'arrête après une image. */
    private fun showStill(at: Duration) {
        val m = media ?: return
        val target = at.coerceAtMost((m.duration - STILL_MARGIN).coerceAtLeast(Duration.ZERO))
        val ring = bitmaps
        val frameSize = size
        still = scope.launch {
            val bytes = ByteArray(frameSize.bytes)
            var read = false
            suspend fun decode(accel: String?) = ffmpeg.run(
                videoCommand(m.path, target, frameSize, fps, accel, single = true),
                StdoutHandler.Binary { input -> read = input.readFully(bytes) },
            )
            try {
                try {
                    decode(hw)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (hw == null) throw e
                    log.warn(e) { "Lecteur : image seule en décodage matériel ($hw) en échec, repli logiciel" }
                    hw = null
                    decode(null)
                }
                if (read) _frame.value = ring.publish(bytes, at, --stills)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Lecteur : image de $at illisible" }
            }
        }
    }

    /** Une lecture depuis [start] : deux FFmpeg, une sortie son, une horloge. Remplacée à chaque déplacement. */
    private inner class Session(
        val file: Path,
        val start: Duration,
        val size: DecodeSize,
        val fps: Double,
        val sink: AudioSink?,
        val ring: BitmapRing,
    ) {
        val clock = PlaybackClock(start, sink, now)

        /** Pause demandée par l'utilisateur (l'horloge est aussi arrêtée avant la première image, sans elle). */
        @Volatile var userPaused = false

        /** Première image arrivée : l'horloge peut partir. */
        @Volatile var started = false
        private var lagging = false
        lateinit var job: Job

        fun stop() {
            job.cancel()
            // Débloque un write ou un drain en attente dans la sortie son : sans ça l'annulation attendrait la fin du tampon.
            sink?.close()
        }

        suspend fun run() {
            try {
                coroutineScope {
                    val frames = Channel<RawFrame>(QUEUE)
                    launch(Dispatchers.IO) { decodeVideo(frames) }
                    if (sink != null) launch(Dispatchers.IO) { feedAudio(sink) }
                    present(frames)
                }
                commands.send(Command.Ended(this))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commands.send(Command.Failed(this, e))
            } finally {
                sink?.close()
            }
        }

        private suspend fun decodeVideo(frames: SendChannel<RawFrame>) {
            // Tampons recyclés : QUEUE dans la file + celui que montre le présentateur + celui qu'on remplit, plus une marge.
            val buffers = Array(QUEUE + 3) { ByteArray(size.bytes) }
            var n = 0L
            try {
                ffmpeg.run(
                    videoCommand(file, start, size, fps, hw, single = false),
                    StdoutHandler.Binary { input ->
                        while (true) {
                            val buffer = buffers[(n % buffers.size).toInt()]
                            if (!input.readFully(buffer)) break
                            frames.send(RawFrame(buffer, start + (n / fps).seconds, n))
                            n++
                        }
                    },
                )
            } finally {
                frames.close()
            }
        }

        private suspend fun feedAudio(sink: AudioSink) {
            val chunk = ByteArray(AUDIO_CHUNK)
            ffmpeg.run(
                audioCommand(file, start),
                StdoutHandler.Binary { input ->
                    while (true) {
                        val read = input.readUpTo(chunk)
                        val whole = read - read % AudioSink.BYTES_PER_FRAME
                        if (whole > 0) sink.write(chunk, whole)
                        if (read < chunk.size) break
                    }
                },
            )
            sink.drain()
            clock.audioFinished()
        }

        private suspend fun present(frames: ReceiveChannel<RawFrame>) {
            val frameTime = (1.0 / fps).seconds
            val first = frames.receiveCatching().getOrNull()
            started = true
            if (!userPaused) clock.resume()
            var current = first ?: return
            var lastPosition: Duration? = null
            while (true) {
                while (true) {
                    val wait = current.pts - clock.position()
                    if (wait <= ON_TIME) break
                    delay(if (clock.paused) PAUSED_POLL else wait.coerceAtMost(MAX_WAIT))
                }
                val late = clock.position() - current.pts
                if (late > frameTime * 1.5) {
                    val newer = frames.tryReceive().getOrNull()
                    if (newer != null) {
                        meter.drop()
                        current = newer
                        continue
                    }
                }
                if (session !== this) return
                _frame.value = ring.publish(current.bytes, current.pts, current.index)
                meter.record(late)
                // Retard qui ne se rattrape pas (machine trop lente pour cette taille) : une seule alerte par lecture.
                if (late > LAG_LIMIT && !lagging) {
                    lagging = true
                    commands.send(Command.Lagging(this))
                }
                if (lastPosition == null || (current.pts - lastPosition).absoluteValue >= POSITION_STEP) {
                    lastPosition = current.pts
                    _view.update { it.copy(position = current.pts, sync = meter.snapshot()) }
                }
                current = frames.receiveCatching().getOrNull() ?: break
            }
        }
    }

    private class RawFrame(val bytes: ByteArray, val pts: Duration, val index: Long)

    companion object {
        const val DEFAULT_FPS = 30.0

        /** Au-delà, FFmpeg retire des images ([videoCommand], filtre fps) : l'écran n'en montre pas plus. */
        const val MAX_FPS = 60.0
        private const val MIN_FPS = 15.0

        /**
         * Pixels par seconde que l'interface affiche sans prendre de retard. Mesuré : un montage 404×720 à 60 img/s
         * (17 Mpx/s) coûte un demi-cœur à la JVM ; une capture 21:9 réduite à 1280×534 à 60 img/s (41 Mpx/s) en
         * coûtait 1,3 et l'image finissait 3,5 s derrière le son, alors que FFmpeg seul la décode trois fois plus vite
         * que le temps réel : la copie et le dessin de 2,7 Mo par image sont le goulot.
         */
        private const val PIXEL_BUDGET = 24_000_000.0
        private val LAG_LIMIT = 1.seconds

        /** Cadence de lecture : celle de la source, au plus 60, divisée par deux tant que [PIXEL_BUDGET] est dépassé. */
        fun playbackFps(source: Double, size: DecodeSize): Double {
            var fps = source.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(MAX_FPS) ?: DEFAULT_FPS
            while (fps > MIN_FPS && size.width.toDouble() * size.height * fps > PIXEL_BUDGET) fps /= 2
            return fps
        }
        private const val QUEUE = 3

        /** 20 ms de son par écriture. */
        private const val AUDIO_CHUNK = AudioSink.RATE / 50 * AudioSink.BYTES_PER_FRAME
        private val ON_TIME = 2.milliseconds
        private val MAX_WAIT = 8.milliseconds
        private val PAUSED_POLL = 30.milliseconds
        private val POSITION_STEP = 100.milliseconds
        private val END_MARGIN = 100.milliseconds
        private val STILL_MARGIN = 200.milliseconds

        /** Images RGBA à la taille [size] et à cadence fixe (heure de l'image n = début + n / fps, même pour une capture à cadence variable). */
        fun videoCommand(file: Path, at: Duration, size: DecodeSize, fps: Double, hwaccel: String?, single: Boolean): FfmpegCommand {
            val args = mutableListOf<String>()
            if (hwaccel != null) args += listOf("-hwaccel", hwaccel)
            args += listOf("-ss", seconds(at), "-i", file.toString(), "-map", "0:v:0", "-an", "-sn")
            args += listOf("-vf", "fps=${String.format(Locale.ROOT, "%.3f", fps)},scale=${size.width}:${size.height}:flags=bilinear")
            if (single) args += listOf("-frames:v", "1")
            args += listOf("-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1")
            return FfmpegCommand(args, if (single) "lecteur-image" else "lecteur-images")
        }

        /** Son en PCM 16 bits stéréo 48 kHz : première piste audio (le mélange des captures, la seule piste des exports). */
        fun audioCommand(file: Path, at: Duration): FfmpegCommand = FfmpegCommand(
            listOf(
                "-ss", seconds(at), "-i", file.toString(), "-map", "0:a:0", "-vn", "-sn",
                "-ac", AudioSink.CHANNELS.toString(), "-ar", AudioSink.RATE.toString(), "-f", "s16le", "pipe:1",
            ),
            "lecteur-son",
        )

        private fun seconds(d: Duration) = String.format(Locale.ROOT, "%.3f", d.inWholeMicroseconds / 1e6)
    }
}

/**
 * Horloge d'une lecture : la position de la sortie son tant qu'elle joue, puis une horloge murale (vidéo sans son, ou
 * son plus court que l'image) qui repart de là où le son s'est arrêté.
 */
class PlaybackClock(private val start: Duration, private val sink: AudioSink?, private val now: () -> Long = System::nanoTime) {
    @Volatile private var audioDone = sink == null
    @Volatile var paused = false
        private set
    @Volatile private var wallBase = start
    @Volatile private var wallMark = now()

    fun position(): Duration = when {
        !audioDone && sink != null -> start + (sink.playedFrames * 1_000_000_000L / sink.rate).nanoseconds
        paused -> wallBase
        else -> wallBase + (now() - wallMark).nanoseconds
    }

    fun audioFinished() {
        val at = position()
        wallBase = at
        wallMark = now()
        audioDone = true
    }

    fun pause() {
        if (paused) return
        if (audioDone) wallBase = position()
        paused = true
        sink?.pause()
    }

    fun resume() {
        if (!paused) return
        wallMark = now()
        paused = false
        sink?.resume()
    }
}

/** Mesures de [SyncStats] : moyenne, maximum, 95e centile (par demi-milliseconde jusqu'à 200 ms). */
internal class SyncMeter {
    private val buckets = LongArray(400)
    private var frames = 0L
    private var dropped = 0L
    private var sum = 0.0
    private var max = 0.0

    @Synchronized fun reset() {
        buckets.fill(0)
        frames = 0
        dropped = 0
        sum = 0.0
        max = 0.0
    }

    @Synchronized fun record(late: Duration) {
        val ms = late.inWholeMicroseconds / 1000.0
        frames++
        sum += abs(ms)
        max = maxOf(max, abs(ms))
        buckets[(abs(ms) * 2).toInt().coerceIn(0, buckets.size - 1)]++
    }

    @Synchronized fun drop() {
        dropped++
    }

    @Synchronized fun snapshot(): SyncStats {
        if (frames == 0L) return SyncStats(dropped = dropped)
        var seen = 0L
        var p95 = buckets.size - 1
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= frames * 0.95) { p95 = i; break }
        }
        return SyncStats(frames, dropped, sum / frames, (p95 + 1) / 2.0, max)
    }
}

/**
 * Images Skia recyclées : huit, pour qu'un tampon ne soit jamais réécrit pendant que l'écran dessine encore l'image
 * qu'il portait (le rendu a au plus une image de retard). Allouer une image native de 1 Mo soixante fois par seconde
 * laissait le ramasse-miettes en retard sur la mémoire native.
 */
internal class BitmapRing(val size: DecodeSize) {
    private val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
    private val slots = Array(8) { Bitmap().apply { allocPixels(info) } }
    private val images = slots.map { it.asComposeImageBitmap() }
    private var next = 0

    @Synchronized fun publish(bytes: ByteArray, pts: Duration, index: Long): PlayerFrame {
        val i = next
        next = (next + 1) % slots.size
        slots[i].installPixels(info, bytes, size.width * 4)
        return PlayerFrame(images[i], pts, index)
    }
}

/** Remplit [buffer] ; faux si le flux finit avant (image incomplète en fin de fichier : ignorée). */
internal fun InputStream.readFully(buffer: ByteArray): Boolean = readUpTo(buffer) == buffer.size

/** Lit jusqu'à remplir [buffer] ou atteindre la fin du flux ; renvoie le nombre d'octets lus. */
internal fun InputStream.readUpTo(buffer: ByteArray): Int {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) break
        offset += read
    }
    return offset
}
