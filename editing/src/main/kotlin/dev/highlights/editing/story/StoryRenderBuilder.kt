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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.random.Random
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
    /** Bruitages pris dans les dossiers du profil ; vide = fichier unique du profil, ou variantes générées. */
    val sfxBank: SfxBank = SfxBank(),
)

/** Fichiers de bruitages disponibles, dans l'ordre où ils serviront. */
data class SfxBank(val whoosh: List<String> = emptyList(), val impact: List<String> = emptyList()) {
    companion object {
        private val AUDIO = setOf("wav", "mp3", "ogg", "flac", "m4a", "aac", "opus")

        /**
         * Fichiers audio d'un dossier, dans un ordre mélangé mais toujours le même (graine fixe) : le montage change
         * de son d'un bruitage à l'autre sans que deux exports des mêmes moments ne sonnent différemment.
         */
        fun list(dir: Path?): List<String> {
            if (dir == null || !Files.isDirectory(dir)) return emptyList()
            val files = Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && it.extension.lowercase() in AUDIO }.toList() }
            return files.map { it.toString() }.sorted().shuffled(Random(SEED))
        }

        private const val SEED = 7L
    }
}

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

    /** Effacement du son du jeu pendant un ralenti, une fois joué à sa vitesse. */
    private val SLOW_AUDIO_FADE = 200.milliseconds

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
        // Bruitages fournis (dossier ou fichier) : une entrée par fichier ; sinon les variantes générées.
        val bank = request.sfxBank
        val whooshFiles = bank.whoosh.ifEmpty { listOfNotNull(sfx.whooshFile) }
        val impactFiles = bank.impact.ifEmpty { listOfNotNull(sfx.impactFile) }
        val whooshSources = if (!sfx.enabled) emptyList() else whooshFiles.map { "[${input("-i", it)}:a]" }.ifEmpty { WHOOSHES }
        val impactSources = if (!sfx.enabled) emptyList() else impactFiles.map { "[${input("-i", it)}:a]" }.ifEmpty { IMPACTS }
        // La musique tourne en boucle : un montage plus long qu'elle n'y perd pas son fond sonore.
        val musicInput = story.music.file?.let { input("-stream_loop", "-1", "-i", it) }

        val (w, h) = RenderCommandBuilder.outputSize(request.format, shots.first().media, edit)
        val graph = mutableListOf<String>()
        // Instants des effets passés à l'écran (ralenti compris) : le reste du graphe ne connaît que le temps de sortie.
        val screen = shots.map(::onScreen)
        shots.forEachIndexed { i, source ->
            val shot = screen[i]
            graph += RenderCommandBuilder.videoChain(i, shot.media, request.format, edit, "g$i")
            val sized = if (RenderCommandBuilder.outputSize(request.format, shot.media, edit) == w to h) {
                "g$i"
            } else {
                graph += "[g$i]scale=$w:$h:force_original_aspect_ratio=decrease:flags=lanczos,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1[z$i]"
                "z$i"
            }
            val base = slowVideo(graph, i, sized, source, edit.fps)
            val effects = mutableListOf<String>()
            effects += framing(shot, w, h, story.punchIn.amount, story.punchIn.ramp, story.shake.amplitude, story.shake.duration, story.shake.frequency)
            // Après le cadrage : le texte ne suit ni le zoom ni la secousse.
            val captionY = if (request.format == OutputFormat.VERTICAL) story.captions.verticalY else story.captions.y
            shot.captions.forEach { effects += Captions.drawText(it, story.captions, h, captionY) }
            val labels = story.labels
            val labelY = if (request.format == OutputFormat.VERTICAL) labels.verticalY else labels.y
            shot.labels.forEach { (at, text) ->
                val end = minOf(at + labels.duration, shot.outputLength)
                effects += Captions.drawLabel(text, TimeRange(at, end), story.captions.font, labels.size, labels.color, h, labelY, story.captions.pop)
            }
            if (story.transition.flash && shot.role == ShotRole.OPENING && i > 0) {
                effects += "fade=t=in:st=0:d=${sec(story.transition.flashDuration)}:color=white"
            }
            effects += "trim=duration=${sec(shot.outputLength)}"
            effects += "setpts=PTS-STARTPTS"
            effects += "format=yuv420p"
            effects += "settb=AVTB"
            graph += "[$base]${effects.joinToString(",")}[v$i]"
            graph += audioChain(graph, i, source, edit.audioIndices(AudioTracks.of(shot.media.audio, request.audioLayout)), story.transition.audioFade)
        }
        graph += shots.indices.joinToString("") { "[v$it][a$it]" } + "concat=n=${shots.size}:v=1:a=1[vcat][acat]"

        val fadeOut = minOf(story.fadeOut, total / 4)
        graph += "[vcat]" + (if (fadeOut.isPositive()) "fade=t=out:st=${sec(total - fadeOut)}:d=${sec(fadeOut)}," else "") + "format=yuv420p[vout]"

        // --- bruitages : whoosh sur chaque changement de moment, impact sous chaque secousse
        val mixInputs = mutableListOf("[acat]")
        if (sfx.enabled) {
            val whooshes = shots.indices.filter { shots[it].role == ShotRole.OPENING && it > 0 }
                .map { (offsets[it] - WHOOSH_PEAK).coerceAtLeast(Duration.ZERO) }
            val impacts = shots.indices.flatMap { i -> screen[i].shakes.map { offsets[i] + it } }
            mixInputs += placeSfx(graph, "wh", whooshes, sfx.whooshVolume, whooshSources, WHOOSH_LENGTH)
            mixInputs += placeSfx(graph, "im", impacts, sfx.impactVolume, impactSources, IMPACT_LENGTH)
        }

        // --- musique de fond : baissée sous la voix, coupée sur le pic de chaque moment
        if (musicInput != null) {
            val music = story.music
            val voice = shots.indices.flatMap { i -> screen[i].voice.map { TimeRange(offsets[i] + it.start, offsets[i] + it.end) } }
            val drops = if (music.dropOut) shots.indices.flatMap { i -> screen[i].drops.map { offsets[i] + it } } else emptyList()
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
    /**
     * Variantes générées, utilisées à tour de rôle quand aucun fichier n'est fourni : whooshes plus ou moins aigus et
     * longs, impacts plus ou moins graves.
     */
    internal val WHOOSHES = listOf(
        WHOOSH_SOURCE_1,
        "anoisesrc=d=0.40:c=pink:r=48000:a=0.8,bandpass=f=2200:t=q:w=0.9,afade=t=in:d=0.28:curve=exp,afade=t=out:st=0.28:d=0.12",
        "anoisesrc=d=0.50:c=brown:r=48000:a=0.9,bandpass=f=900:t=q:w=0.6,afade=t=in:d=0.32:curve=exp,afade=t=out:st=0.32:d=0.18",
    )
    internal val IMPACTS = listOf(
        IMPACT_SOURCE_1,
        "aevalsrc=exprs='sin(2*PI*(38+70*exp(-t*22))*t)*exp(-t*6)':s=48000:d=0.6",
        "aevalsrc=exprs='sin(2*PI*(55+120*exp(-t*35))*t)*exp(-t*9)':s=48000:d=0.6",
    )

    internal const val WHOOSH_SOURCE_1 = "anoisesrc=d=0.45:c=pink:r=48000:a=0.8,bandpass=f=1400:t=q:w=0.7," +
        "afade=t=in:d=0.3:curve=exp,afade=t=out:st=0.3:d=0.15"

    /** Impact : une sinusoïde grave dont la hauteur plonge, qui s'éteint en un peu plus d'une demi-seconde. */
    internal const val IMPACT_SOURCE_1 = "aevalsrc=exprs='sin(2*PI*(45+90*exp(-t*28))*t)*exp(-t*7)':s=48000:d=0.6"

    /**
     * Pose un bruitage à chaque instant de [at] et renvoie les labels à mixer. [source] est soit un label d'entrée
     * (fichier fourni), soit la description d'une source générée.
     */
    private fun placeSfx(graph: MutableList<String>, prefix: String, at: List<Duration>, volume: Double, sources: List<String>, maxLength: Duration): List<String> {
        if (at.isEmpty() || volume <= 0.0 || sources.isEmpty()) return emptyList()
        // Chaque source à son tour : deux bruitages voisins ne sont jamais le même son (dès qu'il y en a deux).
        val uses = at.indices.groupBy { it % sources.size }
        for ((j, placements) in uses) {
            val source = sources[j]
            val chain = if (source.startsWith("[")) "${source}asetpts=PTS-STARTPTS,atrim=duration=${sec(maxLength * 3)}," else "$source,"
            graph += "$chain$AUDIO_FORMAT,volume=${fmt(volume, 3)},asplit=${placements.size}" +
                placements.joinToString("") { "[${prefix}s$it]" }
        }
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
    private fun audioChain(graph: MutableList<String>, i: Int, shot: StoryShot, streams: List<Int>, fade: Duration): String {
        val length = shot.outputLength
        val f = minOf(fade, length / 4)
        val fades = if (f.isPositive()) "afade=t=in:d=${sec(f)},afade=t=out:st=${sec(length - f)}:d=${sec(f)}," else ""
        val tail = "$AUDIO_FORMAT,apad,atrim=duration=${sec(length)},${fades}asetpts=PTS-STARTPTS[a$i]"
        val source = when (streams.size) {
            0 -> return "anullsrc=r=48000:cl=stereo,$tail"
            1 -> "[$i:a:${streams[0]}]asetpts=PTS-STARTPTS,"
            else -> streams.joinToString("") { "[$i:a:$it]" } + "amix=inputs=${streams.size}:normalize=0:duration=longest,"
        }
        if (shot.slow == null) return source + tail

        // Ralenti : le son garde sa vitesse (un tir étiré sonne faux) puis s'efface, le silence comble le reste du
        // ralenti. Avant et après, il est lu tel quel.
        val parts = speedParts(shot)
        graph += "$source$AUDIO_FORMAT,asplit=${parts.size}" + parts.indices.joinToString("") { "[x${i}s$it]" }
        parts.forEachIndexed { p, (range, factor) ->
            val cut = "[x${i}s$p]atrim=start=${sec(range.start)}:end=${sec(range.end)},asetpts=PTS-STARTPTS"
            graph += if (factor == 1.0) {
                "$cut[x${i}p$p]"
            } else {
                val out = range.length / factor
                val fadeLength = minOf(SLOW_AUDIO_FADE, range.length)
                "$cut,afade=t=out:st=${sec(range.length - fadeLength)}:d=${sec(fadeLength)},apad=whole_dur=${sec(out)},atrim=duration=${sec(out)}[x${i}p$p]"
            }
        }
        return parts.indices.joinToString("") { "[x${i}p$it]" } + "concat=n=${parts.size}:v=0:a=1,$tail"
    }

    /**
     * Vidéo d'un plan ralenti : découpée en portions à vitesse constante, la portion lente étirée (`setpts`) puis
     * ramenée à la cadence du montage. Renvoie le label à suivre (celui d'entrée si le plan n'est pas ralenti).
     */
    private fun slowVideo(graph: MutableList<String>, i: Int, input: String, shot: StoryShot, fps: Int): String {
        if (shot.slow == null) return input
        val parts = speedParts(shot)
        graph += "[$input]split=${parts.size}" + parts.indices.joinToString("") { "[r${i}s$it]" }
        parts.forEachIndexed { p, (range, factor) ->
            val pts = if (factor == 1.0) "setpts=PTS-STARTPTS" else "setpts=(PTS-STARTPTS)/${num(factor)},fps=$fps"
            graph += "[r${i}s$p]trim=start=${sec(range.start)}:end=${sec(range.end)},$pts[r${i}p$p]"
        }
        graph += parts.indices.joinToString("") { "[r${i}p$it]" } + "concat=n=${parts.size}:v=1:a=0[r$i]"
        return "r$i"
    }

    /** Portions à vitesse constante d'un plan ralenti (relatives à son début, en temps source), sans portion vide. */
    internal fun speedParts(shot: StoryShot): List<Pair<TimeRange, Double>> {
        val slow = shot.slow ?: return listOf(TimeRange(Duration.ZERO, shot.length) to 1.0)
        return listOf(
            TimeRange(Duration.ZERO, slow.start) to 1.0,
            slow to shot.slowFactor,
            TimeRange(slow.end, shot.length) to 1.0,
        ).filter { it.first.length.isPositive() }
    }

    /** Plan dont tous les instants d'effets sont passés à l'écran (voir [StoryShot.toOutput]). */
    internal fun onScreen(shot: StoryShot): StoryShot = if (shot.slow == null) {
        shot
    } else {
        shot.copy(
            punchIns = shot.punchIns.map(shot::toOutput),
            shakes = shot.shakes.map(shot::toOutput),
            drops = shot.drops.map(shot::toOutput),
            voice = shot.voice.map(shot::toOutput),
            captions = shot.captions.map { it.copy(range = shot.toOutput(it.range)) },
            labels = shot.labels.map { (at, text) -> shot.toOutput(at) to text },
        )
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
