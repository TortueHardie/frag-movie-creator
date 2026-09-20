package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import dev.highlights.editing.RenderCommand
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.SourceCut
import dev.highlights.editing.SourceCuts
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

data class MontageRenderRequest(
    val plan: MontagePlan,
    val format: OutputFormat,
    val edit: EditSettings,
    val encoder: EncoderProfile,
    val output: Path,
    val filterScript: Path,
    val audioBitrate: String = "192k",
    val hwaccel: String? = null,
    /** Pré-découpes des extraits, lues à la place des sources. Voir [SourceCuts]. */
    val cuts: SourceCuts = SourceCuts.NONE,
    /** Indices de pistes imposés par le profil ; vide = rôles déduits de la capture. */
    val audioLayout: AudioLayout = AudioLayout(),
    /** Option de lecture du graphe depuis un fichier, selon la version de FFmpeg (voir [FfmpegService.filterScriptOption]). */
    val filterScriptOption: String = FfmpegService.FILTER_COMPLEX_FROM_FILE,
)

/**
 * Graphe FFmpeg du montage : une entrée par clip (seek rapide), ralenti et rampes de vitesse par découpe + étirement des
 * horodatages, zoom « punch » et textes aux kills, flash blanc à chaque coupe, image gelée si la source ne couvre pas
 * le slot, concaténation vidéo et fondu au noir final ; le son du jeu de chaque clip est posé à son décalage et mixé
 * (il peut déborder un peu sur le plan suivant pour finir un kill ou une phrase), puis mixé avec la musique (jeu au
 * premier plan pendant les kills et les réactions, musique baissée sous la voix). Les frontières suivent les temps réels
 * de la musique, arrondis à l'image.
 */
object MontageRenderBuilder {
    private val KILL_AUDIO_BEFORE = 150.milliseconds
    private val KILL_AUDIO_AFTER = 600.milliseconds
    private val TEXT_DURATION = 900.milliseconds

    fun build(request: MontageRenderRequest): RenderCommand {
        val plan = request.plan
        val settings = plan.settings
        val edit = request.edit.copy(fps = request.edit.fps)
        val clips = plan.clips
        val fps = request.edit.fps
        val boundaries = boundaries(plan, fps)
        val offsets = boundaries.dropLast(1).map { (it * 1_000_000 / fps).microseconds }
        val total = (boundaries.last() * 1_000_000 / fps).microseconds
        val audio = settings.audio
        val bleeds = bleeds(plan, fps)

        val args = mutableListOf<String>()
        clips.forEachIndexed { i, clip ->
            request.hwaccel?.let { args += listOf("-hwaccel", it) }
            val input = request.cuts.input(clip.group.media, sourceRange(clip, bleeds[i]))
            args += listOf("-ss", Durations.ffmpegSecondsPrecise(input.start), "-t", sec(clip.sourceLength + bleeds[i]), "-i", input.path.toString())
        }
        val musicInput = clips.size
        args += listOf("-ss", sec(plan.musicStart), "-t", sec(total + plan.period), "-i", plan.music.file.toString())

        val graph = mutableListOf<String>()
        var killCounter = 0
        val musicDuck = mutableListOf<TimeRange>()

        clips.forEachIndexed { i, clip ->
            val media = clip.group.media
            val (w, h) = RenderCommandBuilder.outputSize(request.format, media, edit)
            val length = ((boundaries[i + 1] - boundaries[i]) * 1_000_000 / fps).microseconds
            val outKills = clip.outputKills()
            val parts = speedParts(clip)

            // --- vidéo : géométrie du format (recadrage 9:16 + HUD), puis ralenti et rampes
            graph += RenderCommandBuilder.videoChain(i, media, request.format, edit, "g$i")
            val base = if (parts.size > 1) {
                graph += "[g$i]split=${parts.size}" + parts.indices.joinToString("") { "[g${i}s$it]" }
                parts.forEachIndexed { p, part ->
                    val pts = if (part.factor == 1.0) "setpts=PTS-STARTPTS" else "setpts=(PTS-STARTPTS)/${num(part.factor)},fps=${edit.fps}"
                    graph += "[g${i}s$p]trim=start=${sec(part.from)}:end=${sec(part.to)},$pts[g${i}p$p]"
                }
                graph += parts.indices.joinToString("") { "[g${i}p$it]" } + "concat=n=${parts.size}:v=1:a=0[s$i]"
                "s$i"
            } else {
                "g$i"
            }

            val effects = mutableListOf<String>()
            // Source trop courte pour le slot : image gelée avant / après.
            if (clip.padBefore.isPositive()) effects += "tpad=start_mode=clone:start_duration=${sec(clip.padBefore)}"
            if (settings.zoom.enabled && outKills.isNotEmpty()) {
                val decay = settings.zoom.decay.inWholeMicroseconds / 1e6
                val z = "(1+${num(settings.zoom.amount)}*(" + outKills.joinToString("+") { k ->
                    val tk = sec(k)
                    "if(gte(t\\,$tk)\\,exp(-(t-$tk)/${num(decay)})\\,0)"
                } + "))"
                effects += "scale=w='trunc($w*$z/2)*2':h='trunc($h*$z/2)*2':eval=frame:flags=bilinear"
                effects += "crop=$w:$h:(iw-$w)/2:(ih-$h)/2"
            }
            if (settings.flash.enabled) {
                effects += "fade=t=in:st=0:d=${sec(settings.flash.duration)}:color=white"
            }
            if (settings.text.enabled) {
                // Vertical : entre le kill feed replacé en haut et le réticule ; sinon sous la boussole.
                val (labelY, counterY) = if (request.format == OutputFormat.VERTICAL) "h*0.25" to "h*0.35" else "h*0.20" to "h*0.11"
                val font = settings.text.font.replace("\\", "/").replace(":", "\\:")
                outKills.forEachIndexed { j, k ->
                    killCounter++
                    if (j >= 1 && settings.text.multiKillLabels.isNotEmpty()) {
                        val label = settings.text.multiKillLabels[minOf(j - 1, settings.text.multiKillLabels.lastIndex)]
                        effects += drawText(font, label, k, (h * 0.10).roundToInt(), labelY, border = (h * 0.006).roundToInt().coerceAtLeast(3))
                    }
                    if (settings.text.killCounter) {
                        effects += drawText(font, "KILL $killCounter", k, (h * 0.045).roundToInt(), counterY, border = (h * 0.003).roundToInt().coerceAtLeast(2))
                    }
                }
            }
            effects += "tpad=stop_mode=clone:stop_duration=${sec(clip.padAfter + 500.milliseconds)}"
            effects += "trim=duration=${sec(length)}"
            effects += "setpts=PTS-STARTPTS"
            effects += "format=yuv420p"
            effects += "settb=AVTB"
            graph += "[$base]${effects.joinToString(",")}[v$i]"

            // --- audio du jeu : même découpe, volume adaptatif, posé à son décalage (peut déborder sur le clip suivant)
            // Les pistes sont choisies par leur rôle : une capture à pistes séparées (OBS) est mixée ici même.
            val streams = edit.audioIndices(AudioTracks.of(media.audio, request.audioLayout))
            val source = when {
                streams.isEmpty() -> null
                streams.size == 1 -> "[$i:a:${streams.first()}]"
                else -> "[xa$i]".also {
                    graph += streams.joinToString("") { s -> "[$i:a:$s]" } +
                        "amix=inputs=${streams.size}:normalize=0:duration=longest$it"
                }
            }
            val voice = clip.group.voiceSegments.mapNotNull { seg ->
                val s = maxOf(seg.start, clip.start)
                val e = minOf(seg.end, clip.end + bleeds[i])
                if (e > s) TimeRange(clip.toOutput(s), clip.toOutput(e)) else null
            }
            voice.forEach { musicDuck += TimeRange(offsets[i] + it.start, minOf(offsets[i] + it.end, total)) }
            val volume = volumeExpression(audio.gameVolume, audio.killVolume, audio.voiceVolume, outKills, voice)
            val format = "aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"
            val delay = if (clip.padBefore.isPositive()) "adelay=${clip.padBefore.inWholeMilliseconds}|${clip.padBefore.inWholeMilliseconds}," else ""
            val bleed = bleeds[i]
            val tail = if (bleed.isPositive()) "afade=t=out:st=${sec(length)}:d=${sec(bleed)}," else ""
            val cut = "apad=whole_dur=${sec(length + bleed)},atrim=duration=${sec(length + bleed)}"
            val place = "adelay=${offsets[i].inWholeMilliseconds}|${offsets[i].inWholeMilliseconds}"
            if (source == null) {
                graph += "anullsrc=r=48000:cl=stereo,atrim=duration=${sec(length)},$place[a$i]"
            } else if (parts.size > 1) {
                graph += "${source}asetpts=PTS-STARTPTS,$format,asplit=${parts.size}" + parts.indices.joinToString("") { "[x${i}s$it]" }
                parts.forEachIndexed { p, part ->
                    val tempo = if (part.factor == 1.0) "" else ",atempo=${num(part.factor)}"
                    val to = if (p == parts.lastIndex) sec(part.to + bleed) else sec(part.to)
                    graph += "[x${i}s$p]atrim=start=${sec(part.from)}:end=$to,asetpts=PTS-STARTPTS$tempo[x${i}p$p]"
                }
                graph += parts.indices.joinToString("") { "[x${i}p$it]" } + "concat=n=${parts.size}:v=0:a=1,${delay}volume='$volume':eval=frame,$tail$cut,$place[a$i]"
            } else {
                graph += "${source}asetpts=PTS-STARTPTS,$format,${delay}volume='$volume':eval=frame,$tail$cut,$place[a$i]"
            }
        }

        val fadeOut = minOf(plan.period * 2, total / 4)
        graph += clips.indices.joinToString("") { "[v$it]" } + "concat=n=${clips.size}:v=1:a=0[vcat]"
        // Format imposé juste avant l'encodeur : concat et xfade peuvent sinon négocier du 4:4:4 (selon la version
        // de FFmpeg), que le profil « high » de x264 refuse.
        graph += "[vcat]fade=t=out:st=${sec(total - fadeOut)}:d=${sec(fadeOut)},format=yuv420p[vout]"
        graph += clips.indices.joinToString("") { "[a$it]" } +
            "amix=inputs=${clips.size}:normalize=0:duration=longest,apad=whole_dur=${sec(total)},atrim=duration=${sec(total)}[game]"

        val duck = if (musicDuck.isEmpty()) {
            num(audio.musicVolume)
        } else {
            val any = musicDuck.joinToString("+") { "between(t\\,${sec(it.start)}\\,${sec(it.end)})" }
            "if(gt($any\\,0)\\,${num(audio.musicVolume * audio.musicUnderVoice)}\\,${num(audio.musicVolume)})"
        }
        graph += "[$musicInput:a]asetpts=PTS-STARTPTS,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo," +
            "volume='$duck':eval=frame,afade=t=out:st=${sec(total - fadeOut)}:d=${sec(fadeOut)},apad=whole_dur=${sec(total)},atrim=duration=${sec(total)}[music]"
        graph += "[game][music]amix=inputs=2:normalize=0:duration=first,loudnorm=I=${num(audio.loudnessLufs)}:TP=-1.5:LRA=11,aresample=48000[aout]"

        args += listOf(request.filterScriptOption, request.filterScript.toString(), "-map", "[vout]", "-map", "[aout]")
        args += request.encoder.videoArgs
        args += listOf("-c:a", "aac", "-b:a", request.audioBitrate, "-ar", "48000", "-movflags", "+faststart", "-progress", "pipe:1", "-y", request.output.toString())
        return RenderCommand(
            FfmpegCommand(args, "montage kills ${request.format.label} (${clips.size} clips, ${"%.0f".format(plan.music.bpm)} BPM)"),
            graph.joinToString(";\n") + "\n",
            total,
        )
    }

    /** Extraits lus par le rendu de [plan] : ce que [SourceCutter] doit pré-découper. */
    fun sourceCuts(plan: MontagePlan, fps: Int): List<SourceCut> =
        bleeds(plan, fps).mapIndexed { i, bleed -> SourceCut(plan.clips[i].group.media, sourceRange(plan.clips[i], bleed)) }

    /** Intervalle lu dans la source pour un clip, débordement sonore compris. */
    private fun sourceRange(clip: MontageClip, bleed: Duration) = TimeRange(clip.start, clip.start + clip.sourceLength + bleed)

    /** Frontières des clips en images, sur la position cumulée : vidéo et audio de même longueur, sans dérive vis-à-vis du beat. */
    private fun boundaries(plan: MontagePlan, fps: Int): List<Long> =
        (plan.clipOffsets() + plan.duration).map { (it.inWholeMicroseconds * fps / 1_000_000.0).roundToLong() }

    /** Débordement sonore de chaque clip sur le suivant : jusqu'à audio.bleed pour finir un kill ou une phrase. */
    private fun bleeds(plan: MontagePlan, fps: Int): List<Duration> {
        val boundaries = boundaries(plan, fps)
        val bleed = plan.settings.audio.bleed
        return plan.clips.mapIndexed { i, clip ->
            if (i == plan.clips.lastIndex) return@mapIndexed Duration.ZERO
            val length = ((boundaries[i + 1] - boundaries[i]) * 1_000_000 / fps).microseconds
            val killTail = clip.outputKills().maxOfOrNull { it + KILL_AUDIO_AFTER - length } ?: Duration.ZERO
            val voiceTail = clip.group.voiceSegments.filter { it.start < clip.end && it.end > clip.end }.maxOfOrNull { it.end - clip.end } ?: Duration.ZERO
            maxOf(killTail, voiceTail, Duration.ZERO).coerceAtMost(bleed).coerceAtMost(clip.group.media.duration - clip.end)
        }
    }

    /** Portion de l'extrait (relative à son début) jouée à une vitesse donnée. */
    internal data class SpeedPart(val from: Duration, val to: Duration, val factor: Double)

    /** Découpe de l'extrait en portions à vitesse constante (1 entre les segments), sans portion vide. */
    internal fun speedParts(clip: MontageClip): List<SpeedPart> {
        val parts = mutableListOf<SpeedPart>()
        var pos = clip.start
        for (seg in clip.speeds) {
            val from = maxOf(seg.range.start, clip.start)
            val to = minOf(seg.range.end, clip.end)
            if (to <= from) continue
            if (from > pos) parts += SpeedPart(pos - clip.start, from - clip.start, 1.0)
            parts += SpeedPart(from - clip.start, to - clip.start, seg.factor)
            pos = to
        }
        if (clip.end > pos || parts.isEmpty()) parts += SpeedPart(pos - clip.start, clip.end - clip.start, 1.0)
        return parts
    }

    /** Volume du jeu : base faible, fort autour des kills, voix et rires bien audibles. */
    internal fun volumeExpression(base: Double, kill: Double, voice: Double, kills: List<Duration>, voiceRanges: List<TimeRange>): String {
        val terms = mutableListOf(num(base))
        kills.forEach { k ->
            terms += "${num(kill)}*between(t\\,${sec((k - KILL_AUDIO_BEFORE).coerceAtLeast(Duration.ZERO))}\\,${sec(k + KILL_AUDIO_AFTER)})"
        }
        voiceRanges.forEach { r -> terms += "${num(voice)}*between(t\\,${sec(r.start)}\\,${sec(r.end)})" }
        return terms.reduce { acc, term -> "max($acc\\,$term)" }
    }

    private fun drawText(font: String, text: String, at: Duration, size: Int, y: String, border: Int): String {
        val t0 = sec(at)
        val t1 = sec(at + TEXT_DURATION)
        val fadeIn = sec(at + 80.milliseconds)
        val fadeOutStart = sec(at + TEXT_DURATION - 250.milliseconds)
        val alpha = "if(lt(t\\,$fadeIn)\\,(t-$t0)/0.08\\,if(gt(t\\,$fadeOutStart)\\,($t1-t)/0.25\\,1))"
        return "drawtext=fontfile='$font':text='$text':fontsize=$size:fontcolor=white:borderw=$border:bordercolor=black" +
            ":x=(w-text_w)/2:y=$y:enable='between(t\\,$t0\\,$t1)':alpha='$alpha'"
    }

    private fun sec(d: Duration) = Durations.ffmpegSeconds(d)

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}
