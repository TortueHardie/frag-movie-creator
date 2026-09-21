package dev.highlights.editing.story

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.serialization.Durations
import dev.highlights.editing.RenderCommand
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.SourceCut
import dev.highlights.editing.SourceCuts
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class StoryRenderRequest(
    val plan: StoryPlan,
    val format: OutputFormat,
    val encoder: EncoderProfile,
    val output: Path,
    val filterScript: Path,
    val audioBitrate: String = "192k",
    val hwaccel: String? = null,
    val cuts: SourceCuts = SourceCuts.NONE,
    val audioLayout: AudioLayout = AudioLayout(),
    val filterScriptOption: String = FfmpegService.FILTER_COMPLEX_FROM_FILE,
)

/**
 * Graphe FFmpeg du montage « story » : une entrée par plan (seek rapide), cadrage fixe alterné, punch-in et secousses
 * par une seule paire scale/crop évaluée à chaque image, flash blanc à l'ouverture de chaque moment ; le son de chaque
 * plan est adouci à ses bords (une coupe au milieu d'un son claque), les plans sont concaténés, puis viennent les
 * bruitages générés (whoosh sur les changements de moment, impact sous les secousses), la musique de fond facultative
 * (baissée sous la voix, coupée sur le pic), la normalisation et le fondu final.
 */
object StoryRenderBuilder {
    /** Le whoosh culmine sur la coupe : il démarre un peu avant. */
    private val WHOOSH_LENGTH = 450.milliseconds
    private val WHOOSH_PEAK = 300.milliseconds
    private val IMPACT_LENGTH = 600.milliseconds

    /** Voir MontageRenderBuilder : trames courtes pour qu'une enveloppe de volume ne fasse pas d'escalier. */
    private const val GAIN_FRAMES = "asetnsamples=n=64:p=0"
    private const val AUDIO_FORMAT = "aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo"

    fun build(request: StoryRenderRequest): RenderCommand {
        val plan = request.plan
        val edit = plan.settings
        val story = edit.story
        val shots = plan.shots
        val offsets = plan.offsets()
        val total = plan.outputDuration

        val args = mutableListOf<String>()
        shots.forEach { shot ->
            val input = request.cuts.input(shot.media, shot.range)
            request.hwaccel?.let { args += listOf("-hwaccel", it) }
            args += listOf("-ss", Durations.ffmpegSecondsPrecise(input.start), "-t", sec(shot.length), "-i", input.path.toString())
        }
        var nextInput = shots.size
        fun input(vararg extra: String): Int {
            args += extra
            return nextInput++
        }
        val sfx = story.sfx
        val whooshInput = sfx.whooshFile?.takeIf { sfx.enabled }?.let { input("-i", it) }
        val impactInput = sfx.impactFile?.takeIf { sfx.enabled }?.let { input("-i", it) }
        // La musique tourne en boucle : un montage plus long qu'elle n'y perd pas son fond sonore.
        val musicInput = story.music.file?.let { input("-stream_loop", "-1", "-i", it) }

        val (w, h) = RenderCommandBuilder.outputSize(request.format, shots.first().media, edit)
        val graph = mutableListOf<String>()
        shots.forEachIndexed { i, shot ->
            graph += RenderCommandBuilder.videoChain(i, shot.media, request.format, edit, "g$i")
            val sized = if (RenderCommandBuilder.outputSize(request.format, shot.media, edit) == w to h) {
                "g$i"
            } else {
                graph += "[g$i]scale=$w:$h:force_original_aspect_ratio=decrease:flags=lanczos,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1[z$i]"
                "z$i"
            }
            val effects = mutableListOf<String>()
            effects += framing(shot, w, h, story.punchIn.amount, story.punchIn.ramp, story.shake.amplitude, story.shake.duration, story.shake.frequency)
            // Après le cadrage : le texte ne suit ni le zoom ni la secousse.
            val captionY = if (request.format == OutputFormat.VERTICAL) story.captions.verticalY else story.captions.y
            shot.captions.forEach { effects += Captions.drawText(it, story.captions, h, captionY) }
            val labels = story.labels
            val labelY = if (request.format == OutputFormat.VERTICAL) labels.verticalY else labels.y
            shot.labels.forEach { (at, text) ->
                val end = minOf(at + labels.duration, shot.length)
                effects += Captions.drawLabel(text, TimeRange(at, end), story.captions.font, labels.size, labels.color, h, labelY, story.captions.pop)
            }
            if (story.transition.flash && shot.role == ShotRole.OPENING && i > 0) {
                effects += "fade=t=in:st=0:d=${sec(story.transition.flashDuration)}:color=white"
            }
            effects += "trim=duration=${sec(shot.length)}"
            effects += "setpts=PTS-STARTPTS"
            effects += "format=yuv420p"
            effects += "settb=AVTB"
            graph += "[$sized]${effects.joinToString(",")}[v$i]"
            graph += audioChain(i, shot, edit.audioIndices(AudioTracks.of(shot.media.audio, request.audioLayout)), story.transition.audioFade)
        }
        graph += shots.indices.joinToString("") { "[v$it][a$it]" } + "concat=n=${shots.size}:v=1:a=1[vcat][acat]"

        val fadeOut = minOf(story.fadeOut, total / 4)
        graph += "[vcat]" + (if (fadeOut.isPositive()) "fade=t=out:st=${sec(total - fadeOut)}:d=${sec(fadeOut)}," else "") + "format=yuv420p[vout]"

        // --- bruitages : whoosh sur chaque changement de moment, impact sous chaque secousse
        val mixInputs = mutableListOf("[acat]")
        if (sfx.enabled) {
            val whooshes = shots.indices.filter { shots[it].role == ShotRole.OPENING && it > 0 }
                .map { (offsets[it] - WHOOSH_PEAK).coerceAtLeast(Duration.ZERO) }
            val impacts = shots.indices.flatMap { i -> shots[i].shakes.map { offsets[i] + it } }
            mixInputs += placeSfx(graph, "wh", whooshes, sfx.whooshVolume, whooshInput?.let { "[$it:a]" } ?: WHOOSH)
            mixInputs += placeSfx(graph, "im", impacts, sfx.impactVolume, impactInput?.let { "[$it:a]" } ?: IMPACT)
        }

        // --- musique de fond : baissée sous la voix, coupée sur le pic de chaque moment
        if (musicInput != null) {
            val music = story.music
            val voice = shots.indices.flatMap { i -> shots[i].voice.map { TimeRange(offsets[i] + it.start, offsets[i] + it.end) } }
            val drops = if (music.dropOut) shots.indices.flatMap { i -> shots[i].drops.map { offsets[i] + it } } else emptyList()
            graph += "[$musicInput:a]asetpts=PTS-STARTPTS,$AUDIO_FORMAT,atrim=duration=${sec(total)},$GAIN_FRAMES," +
                "volume='${musicVolume(music.volume, music.underVoice, voice, drops, music.dropLength)}':eval=frame[music]"
            mixInputs += "[music]"
        }

        val audioTail = (if (fadeOut.isPositive()) "afade=t=out:st=${sec(total - fadeOut)}:d=${sec(fadeOut)}," else "") +
            (edit.loudnessLufs?.let { "loudnorm=I=${fmt(it, 1)}:TP=-1.5:LRA=11," } ?: "") + "aresample=48000[aout]"
        graph += if (mixInputs.size == 1) {
            "[acat]$audioTail"
        } else {
            mixInputs.joinToString("") + "amix=inputs=${mixInputs.size}:normalize=0:duration=first,$audioTail"
        }

        args += listOf(request.filterScriptOption, request.filterScript.toString(), "-map", "[vout]", "-map", "[aout]")
        args += request.encoder.videoArgs
        args += listOf("-c:a", "aac", "-b:a", request.audioBitrate, "-ar", "48000", "-movflags", "+faststart", "-progress", "pipe:1", "-y", request.output.toString())
        return RenderCommand(
            FfmpegCommand(args, "rendu story ${request.format.label} (${plan.moments.size} moments, ${shots.size} plans, ${request.encoder.name})"),
            graph.joinToString(";\n") + "\n",
            total,
        )
    }

    /** Extraits lus par le rendu : ce que le pré-découpage doit préparer. */
    fun sourceCuts(plan: StoryPlan): List<SourceCut> = plan.shots.map { SourceCut(it.media, it.range) }

    /** Whoosh : bruit rose filtré, qui enfle jusqu'à la coupe puis retombe vite. */
    internal const val WHOOSH = "anoisesrc=d=0.45:c=pink:r=48000:a=0.8,bandpass=f=1400:t=q:w=0.7," +
        "afade=t=in:d=0.3:curve=exp,afade=t=out:st=0.3:d=0.15"

    /** Impact : une sinusoïde grave dont la hauteur plonge, qui s'éteint en un peu plus d'une demi-seconde. */
    internal const val IMPACT = "aevalsrc=exprs='sin(2*PI*(45+90*exp(-t*28))*t)*exp(-t*7)':s=48000:d=0.6"

    /**
     * Pose un bruitage à chaque instant de [at] et renvoie les labels à mixer. [source] est soit un label d'entrée
     * (fichier fourni), soit la description d'une source générée.
     */
    private fun placeSfx(graph: MutableList<String>, prefix: String, at: List<Duration>, volume: Double, source: String): List<String> {
        if (at.isEmpty() || volume <= 0.0) return emptyList()
        val head = if (source.startsWith("[")) source else "$source,"
        val chain = if (source.startsWith("[")) "${head}asetpts=PTS-STARTPTS," else head
        val max = if (source == IMPACT) IMPACT_LENGTH else WHOOSH_LENGTH
        val trim = if (source.startsWith("[")) "atrim=duration=${sec(max * 3)}," else ""
        graph += "${chain}$trim$AUDIO_FORMAT,volume=${fmt(volume, 3)},asplit=${at.size}" + at.indices.joinToString("") { "[${prefix}s$it]" }
        return at.mapIndexed { k, t ->
            val ms = t.inWholeMilliseconds
            graph += "[${prefix}s$k]adelay=$ms|$ms[${prefix}$k]"
            "[${prefix}$k]"
        }
    }

    /**
     * Cadrage d'un plan, en un seul scale + crop évalués à chaque image : zoom fixe (jump cut alterné), punch-in
     * pendant les réactions (montée de [ramp], retour à la fin de la réaction), secousse amortie sur les impacts. La
     * secousse agrandit l'image juste assez pour que le déplacement ne découvre jamais de bord noir. Chaîne vide quand
     * le plan est en plein cadre, sans effet.
     */
    internal fun framing(shot: StoryShot, w: Int, h: Int, punch: Double, ramp: Duration, amplitude: Double, shakeLength: Duration, frequency: Double): List<String> {
        val punches = if (punch > 0) shot.punchIns else emptyList()
        val shakes = if (amplitude > 0) shot.shakes else emptyList()
        if (shot.zoom == 1.0 && punches.isEmpty() && shakes.isEmpty()) return emptyList()

        val r = num(secs(ramp).coerceAtLeast(0.001))
        val punchTerms = punches.map { p ->
            val s = num(secs(p.start))
            val e = num(secs(p.end))
            // Monte en [ramp] au début de la réaction, redescend en [ramp] à sa fin (sans effet si le plan coupe avant).
            "min(max((t-$s)/$r\\,0)\\,1)*min(max(($e+$r-t)/$r\\,0)\\,1)"
        }
        val d = secs(shakeLength).coerceAtLeast(0.001)
        val shakeEnv = shakes.map { k ->
            val tk = num(secs(k))
            "if(between(t\\,$tk\\,$tk+${num(d)})\\,exp(-(t-$tk)*${num(5 / d)})\\,0)"
        }
        val punchExpr = if (punchTerms.isEmpty()) "0" else punchTerms.reduce { a, b -> "max($a\\,$b)" }
        val shakeExpr = if (shakeEnv.isEmpty()) "0" else shakeEnv.joinToString("+")
        // Marge de la secousse : 2,5 amplitudes, de quoi absorber le déplacement dans les deux sens.
        val zoom = "(${num(shot.zoom)}*(1+${num(punch)}*$punchExpr)*(1+${num(amplitude * 2.5)}*min($shakeExpr\\,1)))"
        val filters = mutableListOf("scale=w='trunc($w*$zoom/2)*2':h='trunc($h*$zoom/2)*2':eval=frame:flags=bicubic")
        if (shakes.isEmpty()) {
            filters += "crop=$w:$h:(iw-$w)/2:(ih-$h)/2"
        } else {
            val amp = num(amplitude * h)
            val f = num(2 * Math.PI * frequency)
            val dx = "$amp*sin($f*t)*($shakeExpr)"
            val dy = "$amp*cos(${num(2 * Math.PI * frequency * 1.3)}*t)*($shakeExpr)"
            filters += "crop=$w:$h:x='clip((iw-$w)/2+$dx\\,0\\,iw-$w)':y='clip((ih-$h)/2+$dy\\,0\\,ih-$h)'"
        }
        return filters
    }

    /**
     * Son d'un plan : pistes choisies par leur rôle, fondu très court à chaque bord (une coupe au milieu d'un son
     * claque), longueur forcée à celle du plan pour que la synchro ne dérive pas au fil des coupes.
     */
    private fun audioChain(i: Int, shot: StoryShot, streams: List<Int>, fade: Duration): String {
        val length = shot.length
        val f = minOf(fade, length / 4)
        val fades = if (f.isPositive()) "afade=t=in:d=${sec(f)},afade=t=out:st=${sec(length - f)}:d=${sec(f)}," else ""
        val tail = "$AUDIO_FORMAT,apad,atrim=duration=${sec(length)},${fades}asetpts=PTS-STARTPTS[a$i]"
        return when (streams.size) {
            0 -> "anullsrc=r=48000:cl=stereo,$tail"
            1 -> "[$i:a:${streams[0]}]asetpts=PTS-STARTPTS,$tail"
            else -> streams.joinToString("") { "[$i:a:$it]" } + "amix=inputs=${streams.size}:normalize=0:duration=longest,$tail"
        }
    }

    /**
     * Volume de la musique de fond : [base], baissé à [under] pendant la voix, et à zéro sur chaque pic ([drops]) le
     * temps de [dropLength] — la coupe est franche (c'est elle qui fait l'effet), la reprise se fait en fondu.
     */
    internal fun musicVolume(base: Double, under: Double, voice: List<TimeRange>, drops: List<Duration>, dropLength: Duration): String {
        val attack = 80.milliseconds
        val release = 250.milliseconds
        val duck = if (voice.isEmpty()) num(base) else "${num(base)}-${num(base - under)}*(" +
            voice.map { envelope(it, attack, release) }.reduce { a, b -> "max($a\\,$b)" } + ")"
        if (drops.isEmpty()) return duck
        val silence = drops.map { envelope(TimeRange(it, it + dropLength), 20.milliseconds, 600.milliseconds) }.reduce { a, b -> "max($a\\,$b)" }
        return "($duck)*(1-($silence))"
    }

    /** Trapèze : 0 hors de [range], 1 dedans, montée de [attack] avant, descente de [release] après. */
    internal fun envelope(range: TimeRange, attack: Duration, release: Duration): String {
        val from = (range.start - attack).coerceAtLeast(Duration.ZERO)
        val rise = (range.start - from).coerceAtLeast(1.milliseconds)
        val fall = release.coerceAtLeast(1.milliseconds)
        val up = "min(max((t-${sec(from)})/${num(secs(rise))}\\,0)\\,1)"
        val down = "min(max((${sec(range.end + fall)}-t)/${num(secs(fall))}\\,0)\\,1)"
        return "$up*$down"
    }

    private fun sec(d: Duration) = Durations.ffmpegSeconds(d)

    private fun secs(d: Duration) = d.inWholeMicroseconds / 1e6

    private fun num(v: Double) = fmt(v, 4)

    private fun fmt(v: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", v)
}
