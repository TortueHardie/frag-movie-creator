package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.MontageAudio
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SlowAudio
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.WhipPanEffect
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
 * premier plan pendant les kills et les réactions, musique baissée sous la voix ; ou, en mode « kills », le son du
 * kill seul avec la musique baissée dessous), dans le rapport choisi par `audio.balance` ; la musique se tait un instant
 * juste avant la drop (`audio.dropBreak`). Les frontières suivent les temps réels de la musique, arrondis à l'image.
 */
object MontageRenderBuilder {
    private val KILL_AUDIO_BEFORE = 150.milliseconds
    private val KILL_AUDIO_AFTER = 600.milliseconds
    private val TEXT_DURATION = 900.milliseconds

    /** Effacement de la musique à l'entrée de la coupure avant la drop : assez court pour sonner comme une coupe, sans clic. */
    private val DROP_BREAK_FADE = 25.milliseconds

    /**
     * Découpe le son en trames de 64 échantillons (1,3 ms) avant une enveloppe de volume : `volume` n'évalue son
     * expression qu'une fois par trame, et sur les trames de 1024 échantillons d'un AAC une rampe de 80 ms devient un
     * escalier de 4 marches, qui claque à chaque kill quand le jeu part du silence ou que la musique plonge dessous.
     * `p=0` : la dernière trame n'est pas complétée de silence, la durée ne bouge pas.
     */
    private const val GAIN_FRAMES = "asetnsamples=n=64:p=0"

    fun build(request: MontageRenderRequest): RenderCommand {
        val plan = request.plan
        val settings = plan.settings
        val edit = request.edit.copy(fps = request.edit.fps)
        val clips = plan.clips
        val fps = request.edit.fps
        val leads = leads(plan, fps)
        val boundaries = boundaries(plan, fps, leads)
        val offsets = boundaries.dropLast(1).map { (it * 1_000_000 / fps).microseconds }
        val total = (boundaries.last() * 1_000_000 / fps).microseconds
        val audio = settings.audio
        val bleeds = bleeds(plan, fps)
        val flashes = flashes(plan)
        val whips = whips(plan)
        val zooms = zooms(plan)

        val args = mutableListOf<String>()
        clips.forEachIndexed { i, clip ->
            request.hwaccel?.let { args += listOf("-hwaccel", it) }
            val range = sourceRange(clip, bleeds[i], leads[i])
            val input = request.cuts.input(clip.group.media, range)
            args += listOf("-ss", Durations.ffmpegSecondsPrecise(input.start), "-t", sec(range.length), "-i", input.path.toString())
        }
        val musicInput = clips.size
        args += listOf("-ss", sec(plan.musicStart), "-t", sec(total + plan.period), "-i", plan.music.file.toString())

        val graph = mutableListOf<String>()
        var killCounter = 0
        val musicDuck = mutableListOf<TimeRange>()

        // Taille commune à tout le montage, prise sur la première capture : les suivantes y sont ramenées, avec des
        // bandes noires si leur format diffère. Sans cela, un montage mélangeant du 1080p et du 1440p (ou du 16:9 et
        // de l'ultrawide) échouerait au moment de concaténer des images de tailles différentes.
        val (w, h) = RenderCommandBuilder.outputSize(request.format, clips.first().group.media, edit)

        clips.forEachIndexed { i, clip ->
            val media = clip.group.media
            val lead = leads[i]
            val length = ((boundaries[i + 1] - boundaries[i]) * 1_000_000 / fps).microseconds
            // La coupe avance de quelques images, le contenu du plan la suit : le kill reste sur son temps.
            val outKills = clip.outputKills().map { it + lead }
            val parts = speedParts(clip, lead)

            // --- vidéo : géométrie du format (recadrage 9:16 + HUD), puis ralenti et rampes
            graph += RenderCommandBuilder.videoChain(i, media, request.format, edit, "g$i")
            val own = RenderCommandBuilder.outputSize(request.format, media, edit)
            val sized = if (own == w to h) {
                "g$i"
            } else {
                graph += "[g$i]scale=$w:$h:force_original_aspect_ratio=decrease:flags=lanczos," +
                    "pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1[z$i]"
                "z$i"
            }
            val base = if (parts.size > 1) {
                graph += "[$sized]split=${parts.size}" + parts.indices.joinToString("") { "[g${i}s$it]" }
                parts.forEachIndexed { p, part ->
                    // Sans interpolation, ralentir répète les images de la source ; minterpolate en calcule de nouvelles,
                    // au prix d'un rendu bien plus lent — réservé aux portions effectivement ralenties.
                    val resample = if (settings.slowMotion.interpolate && part.factor < 1.0) {
                        "minterpolate=fps=${edit.fps}:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1"
                    } else {
                        "fps=${edit.fps}"
                    }
                    val pts = if (part.factor == 1.0) "setpts=PTS-STARTPTS" else "setpts=(PTS-STARTPTS)/${num(part.factor)},$resample"
                    graph += "[g${i}s$p]trim=start=${sec(part.from)}:end=${sec(part.to)},$pts[g${i}p$p]"
                }
                graph += parts.indices.joinToString("") { "[g${i}p$it]" } + "concat=n=${parts.size}:v=1:a=0[s$i]"
                "s$i"
            } else {
                sized
            }

            val effects = mutableListOf<String>()
            // Source trop courte pour le slot : image gelée avant / après.
            if (clip.padBefore.isPositive()) effects += "tpad=start_mode=clone:start_duration=${sec(clip.padBefore)}"
            // Le dernier kill du clip est celui calé sur le temps : c'est lui qui mérite le zoom en priorité.
            val zoomKills = if (settings.zoom.onEveryKill) outKills else outKills.takeLast(1)
            if (settings.zoom.enabled && zooms[i] && zoomKills.isNotEmpty()) {
                val decay = settings.zoom.decay.inWholeMicroseconds / 1e6
                val z = "(1+${num(settings.zoom.amount)}*(" + zoomKills.joinToString("+") { k ->
                    val tk = sec(k)
                    "if(gte(t\\,$tk)\\,exp(-(t-$tk)/${num(decay)})\\,0)"
                } + "))"
                effects += "scale=w='trunc($w*$z/2)*2':h='trunc($h*$z/2)*2':eval=frame:flags=${settings.zoom.scaleFlags}"
                effects += "crop=$w:$h:(iw-$w)/2:(ih-$h)/2"
            }
            if (settings.flash.enabled && flashes[i]) {
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
            // Le zoom arrondit largeur et hauteur séparément : `scale` modifie alors la forme des pixels (SAR), que
            // concat refuse si elle diffère d'un plan à l'autre.
            effects += "setsar=1"
            val finish = "format=yuv420p,settb=AVTB"
            val stages = whipStages(settings.whip, whips[i], whips.getOrNull(i + 1), length, w, h)
            if (stages.isEmpty()) {
                graph += "[$base]${(effects + finish).joinToString(",")}[v$i]"
            } else {
                // Whip pan : chaque étape superpose deux copies décalées du plan, puis la dernière rend le plan fini.
                var label = "w${i}e"
                graph += "[$base]${effects.joinToString(",")}[$label]"
                stages.forEachIndexed { k, (first, second) ->
                    val out = if (k == stages.lastIndex) "v$i" else "w${i}s$k"
                    val tail = if (k == stages.lastIndex) ",$finish" else ""
                    graph += "[$label]split=3[w${i}m$k][w${i}a$k][w${i}b$k]"
                    graph += "[w${i}m$k][w${i}a$k]$first[w${i}o$k]"
                    graph += "[w${i}o$k][w${i}b$k]$second$tail[$out]"
                    label = out
                }
            }

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
                if (e > s) TimeRange(clip.toOutput(s) + lead, clip.toOutput(e) + lead) else null
            }
            val g = audio.gameGain
            val volume = when (audio.game) {
                GameAudio.FULL -> {
                    voice.forEach { musicDuck += TimeRange(offsets[i] + it.start, minOf(offsets[i] + it.end, total)) }
                    volumeExpression(audio.gameVolume * g, audio.killVolume * g, audio.voiceVolume * g, outKills, voice, audio.duckAttack, audio.duckRelease)
                }
                // Seul le kill s'entend : la musique lui laisse la place le temps du tir et de la notification.
                GameAudio.KILLS -> {
                    outKills.forEach { musicDuck += TimeRange(offsets[i] + killWindow(it).start, minOf(offsets[i] + killWindow(it).end, total)) }
                    volumeExpression(0.0, audio.killVolume * g, 0.0, outKills, emptyList(), audio.duckAttack, audio.duckRelease)
                }
            }
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
                    val extra = if (p == parts.lastIndex) bleed else Duration.ZERO
                    val to = sec(part.to + extra)
                    graph += "[x${i}s$p]atrim=start=${sec(part.from)}:end=$to,asetpts=PTS-STARTPTS" +
                        slowAudio(part, extra, audio) + "[x${i}p$p]"
                }
                graph += parts.indices.joinToString("") { "[x${i}p$it]" } + "concat=n=${parts.size}:v=0:a=1,${delay}$GAIN_FRAMES,volume='$volume':eval=frame,$tail$cut,$place[a$i]"
            } else {
                graph += "${source}asetpts=PTS-STARTPTS,$format,${delay}$GAIN_FRAMES,volume='$volume':eval=frame,$tail$cut,$place[a$i]"
            }
        }

        val fadeOut = minOf(plan.period * 2, total / 4)
        graph += clips.indices.joinToString("") { "[v$it]" } + "concat=n=${clips.size}:v=1:a=0[vcat]"
        // Format imposé juste avant l'encodeur : concat et xfade peuvent sinon négocier du 4:4:4 (selon la version
        // de FFmpeg), que le profil « high » de x264 refuse.
        graph += "[vcat]fade=t=out:st=${sec(total - fadeOut)}:d=${sec(fadeOut)},format=yuv420p[vout]"
        graph += clips.indices.joinToString("") { "[a$it]" } +
            "amix=inputs=${clips.size}:normalize=0:duration=longest,apad=whole_dur=${sec(total)},atrim=duration=${sec(total)}[game]"

        // Ducking progressif : une marche de volume sous la voix s'entend plus que la voix elle-même.
        val music = audio.musicVolume * audio.musicGain
        val duck = if (musicDuck.isEmpty()) {
            num(music)
        } else {
            val env = musicDuck.map { envelope(it, audio.duckAttack, audio.duckRelease) }.reduce { a, b -> "max($a\\,$b)" }
            val under = if (audio.game == GameAudio.KILLS) audio.musicUnderKill else audio.musicUnderVoice
            "${num(music)}-${num(music * (1 - under))}*($env)"
        }.let { level ->
            // Coupure avant la drop : la musique s'efface en quelques millisecondes, puis revient d'un coup sur la drop.
            val cut = dropBreak(plan) ?: return@let level
            val gate = envelope(TimeRange(cut.start, cut.end - 1.milliseconds), DROP_BREAK_FADE, 1.milliseconds)
            "($level)*(1-${num(1 - audio.dropBreak.musicLevel)}*($gate))"
        }
        graph += "[$musicInput:a]asetpts=PTS-STARTPTS,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo," +
            "$GAIN_FRAMES,volume='$duck':eval=frame,afade=t=out:st=${sec(total - fadeOut)}:d=${sec(fadeOut)},apad=whole_dur=${sec(total)},atrim=duration=${sec(total)}[music]"
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
    fun sourceCuts(plan: MontagePlan, fps: Int): List<SourceCut> {
        val leads = leads(plan, fps)
        return bleeds(plan, fps).mapIndexed { i, bleed -> SourceCut(plan.clips[i].group.media, sourceRange(plan.clips[i], bleed, leads[i])) }
    }

    /**
     * Coupes qui reçoivent un flash blanc. Par défaut les seules coupes fortes : entrée dans une nouvelle section de la
     * musique, plan de la drop, multi-kill. Un flash sur chaque coupe noie l'action au lieu de la souligner.
     */
    internal fun flashes(plan: MontagePlan): List<Boolean> {
        val whips = whips(plan)
        return plan.clips.mapIndexed { i, clip -> flash(plan, i, clip) && whips[i] == null }
    }

    private fun flash(plan: MontagePlan, i: Int, clip: MontageClip): Boolean =
        when {
            // Le montage n'ouvre pas sur un écran blanc.
            i == 0 -> false
            plan.settings.flash.onEveryCut -> true
            clip.slot.dropBeat != null -> true
            clip.slot.section != plan.clips[i - 1].slot.section -> true
            // Au minimum, seules la drop et les frontières de section méritent encore un flash.
            plan.settings.effectDensity == EffectDensity.SOBER -> false
            // Les kills visibles, pas ceux du groupe : un multi-kill dont le début a été coupé n'en est plus un à l'écran.
            else -> clip.kills.size > 1
        }

    /**
     * Sens du whip pan à la coupe qui ouvre chaque clip, ou null : coupe franche. Le plan qui s'achève sur un flick
     * donne son sens (le mouvement se prolonge au-delà de la coupe) ; sinon le plan qui arrive, si son premier kill
     * visible en est un (le mouvement l'annonce). Le premier plan n'a pas de coupe d'entrée.
     */
    internal fun whips(plan: MontagePlan): List<FlickDirection?> {
        if (!plan.settings.whip.enabled) return plan.clips.map { null }
        fun flickOf(clip: MontageClip, kill: Duration?) =
            kill?.let { clip.group.traitsOf(it) }?.takeIf { it.flick >= KillTraits.STRONG_FLICK }?.direction
        return plan.clips.mapIndexed { i, clip ->
            if (i == 0) return@mapIndexed null
            val previous = plan.clips[i - 1]
            flickOf(previous, previous.kills.lastOrNull()) ?: flickOf(clip, clip.kills.firstOrNull())
        }
    }

    /**
     * Étapes du whip pan d'un plan de [length] : [enter], sens du raccord à son début ; [exit], à sa fin. Chaque étape
     * décale l'image dans le sens où le décor file (à l'opposé de la caméra), deux fois de suite pour que le bord laissé
     * libre soit rempli par l'image elle-même, puis la floute le long du mouvement. Le plan sortant accélère jusqu'à une
     * demi-image de décalage sur la coupe ; l'entrant repart de là et ralentit jusqu'à une image entière, qui retombe
     * exactement sur l'image d'origine. Une étape : superposition de la première copie, puis de la seconde et flou.
     */
    internal fun whipStages(whip: WhipPanEffect, enter: FlickDirection?, exit: FlickDirection?, length: Duration, w: Int, h: Int): List<Pair<String, String>> {
        val half = minOf(whip.duration / 2, length / 3)
        val hs = num(secs(half))
        val tail = num(secs(length - half))
        fun stage(direction: FlickDirection, active: String, progress: String): Pair<String, String> {
            val size = if (direction.horizontal) "W" else "H"
            val first = "${sign(direction)}*$progress*$size"
            // La seconde copie suit la première à une image d'écart, du côté que le décalage laisse vide.
            val second = if (sign(direction) == "-1") "$first+$size" else "$first-$size"
            fun overlay(offset: String) =
                if (direction.horizontal) "overlay=x='$offset':y=0:enable='$active'" else "overlay=x=0:y='$offset':enable='$active'"
            val sigma = whip.blur * (if (direction.horizontal) w else h)
            val blur = if (sigma <= 0) "" else {
                val (sx, sy) = if (direction.horizontal) sigma to 0.5 else 0.5 to sigma
                ",gblur=sigma=${num(sx)}:sigmaV=${num(sy)}:enable='$active'"
            }
            return overlay(first) to overlay(second) + blur
        }
        return listOfNotNull(
            enter?.let { stage(it, "lt(t\\,$hs)", "(1-0.5*pow(1-t/$hs\\,2))") },
            exit?.let { stage(it, "gte(t\\,$tail)", "0.5*pow((t-$tail)/$hs\\,2)") },
        )
    }

    /** Sens où le décor file dans l'image : à l'opposé de la caméra (elle tourne à droite, la scène part à gauche). */
    private fun sign(direction: FlickDirection) = when (direction) {
        FlickDirection.RIGHT, FlickDirection.DOWN -> "-1"
        FlickDirection.LEFT, FlickDirection.UP -> "1"
    }

    /**
     * Plans qui reçoivent un zoom « punch ». Au rythme normal, un plan déjà ralenti n'en reçoit pas : le ralenti est
     * son emphase, et empiler les deux surcharge l'image sans rien souligner de plus.
     */
    internal fun zooms(plan: MontagePlan): List<Boolean> = plan.clips.map { clip ->
        when (plan.settings.effectDensity) {
            EffectDensity.SOBER -> false
            EffectDensity.BALANCED -> clip.slow == null
            EffectDensity.HEAVY -> true
        }
    }

    /**
     * Coupure de la musique juste avant la drop (instants du montage), ou null : drop hors du montage, ou trop proche
     * de son début pour qu'on ait entendu la musique avant de la couper.
     */
    internal fun dropBreak(plan: MontagePlan): TimeRange? {
        val settings = plan.settings.audio.dropBreak
        val drop = plan.dropAt ?: return null
        if (!settings.enabled) return null
        val start = drop - plan.period * settings.beats
        if (start < plan.period * 2) return null
        return TimeRange(start, drop)
    }

    /** Intervalle lu dans la source pour un clip, débordement sonore compris. */
    private fun sourceRange(clip: MontageClip, bleed: Duration, lead: Duration) =
        TimeRange(clip.start - lead, clip.start + clip.sourceLength + bleed)

    /**
     * Avance de la coupe qui ouvre chaque clip : le plan démarre quelques images avant son temps et montre d'autant
     * plus de source avant le kill, si bien que le kill, lui, reste exactement sur le temps. Le premier plan n'a pas de
     * coupe à anticiper, et un plan qui commence au tout début de sa capture n'a rien de plus à montrer.
     */
    internal fun leads(plan: MontagePlan, fps: Int): List<Duration> {
        val frame = (1_000_000L / fps).microseconds
        return plan.clips.mapIndexed { i, clip ->
            if (i == 0) return@mapIndexed Duration.ZERO
            val available = (clip.start - clip.group.media.bounds.start).coerceAtLeast(Duration.ZERO)
            frame * plan.settings.cuts.preBeatFrames.coerceAtMost((available / frame).toInt())
        }
    }

    /**
     * Frontières des clips en images, sur la position cumulée : vidéo et audio de même longueur, sans dérive vis-à-vis
     * du beat. Chaque coupe intérieure est avancée de [leads] ; les deux extrémités du montage ne bougent pas.
     */
    private fun boundaries(plan: MontagePlan, fps: Int, leads: List<Duration>): List<Long> {
        val raw = (plan.clipOffsets() + plan.duration).map { (it.inWholeMicroseconds * fps / 1_000_000.0).roundToLong() }
        return raw.mapIndexed { i, b ->
            if (i == 0 || i == raw.lastIndex) b
            else (b - (leads[i].inWholeMicroseconds * fps / 1_000_000.0).roundToLong()).coerceAtLeast(raw[i - 1] + 1)
        }
    }

    /** Débordement sonore de chaque clip sur le suivant : jusqu'à audio.bleed pour finir un kill ou une phrase. */
    private fun bleeds(plan: MontagePlan, fps: Int): List<Duration> {
        val boundaries = boundaries(plan, fps, leads(plan, fps))
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
    internal data class SpeedPart(val from: Duration, val to: Duration, val factor: Double, val kind: SpeedKind = SpeedKind.RAMP) {
        val sourceLength: Duration get() = to - from
        val outputLength: Duration get() = sourceLength / factor
    }

    /**
     * Découpe de l'extrait en portions à vitesse constante (1 entre les segments), sans portion vide. Les instants sont
     * relatifs au début de l'entrée, qui commence [lead] avant le plan (avance de la coupe).
     */
    internal fun speedParts(clip: MontageClip, lead: Duration = Duration.ZERO): List<SpeedPart> {
        val parts = mutableListOf<SpeedPart>()
        var pos = clip.start
        for (seg in clip.speeds) {
            val from = maxOf(seg.range.start, clip.start)
            val to = minOf(seg.range.end, clip.end)
            if (to <= from) continue
            if (from > pos) parts += SpeedPart(pos - clip.start + lead, from - clip.start + lead, 1.0)
            parts += SpeedPart(from - clip.start + lead, to - clip.start + lead, seg.factor, seg.kind)
            pos = to
        }
        if (clip.end > pos || parts.isEmpty()) parts += SpeedPart(pos - clip.start + lead, clip.end - clip.start + lead, 1.0)
        // Les images d'avance se jouent à vitesse normale : elles prolongent la première portion, ou en ouvrent une.
        if (!lead.isPositive()) return parts
        return if (parts.first().factor == 1.0) {
            listOf(parts.first().copy(from = Duration.ZERO)) + parts.drop(1)
        } else {
            listOf(SpeedPart(Duration.ZERO, lead, 1.0)) + parts
        }
    }

    /**
     * Traitement du son d'une portion, préfixé d'une virgule (vide à vitesse normale). Étirer le son du jeu comme
     * l'image délite le timbre d'un tir ou d'un impact : pendant un ralenti, il vaut mieux le laisser à sa vitesse
     * puis l'effacer, ou le taire, et laisser la musique porter la suite. Les rampes de vitesse d'un multi-kill, elles,
     * restent à ±15 % : `atempo` y est inaudible. [extra] est le débordement sonore sur le plan suivant, jamais étiré.
     */
    internal fun slowAudio(part: SpeedPart, extra: Duration, audio: MontageAudio): String {
        if (part.factor == 1.0) return ""
        val out = part.outputLength + extra
        if (part.kind != SpeedKind.SLOW || audio.slowMotion == SlowAudio.STRETCH) return ",atempo=${num(part.factor)}"
        val played = part.sourceLength + extra
        val fade = audio.slowFade.coerceAtMost(played)
        val head = when (audio.slowMotion) {
            SlowAudio.MUTE -> "volume=0"
            else -> "afade=t=out:st=${sec(played - fade)}:d=${sec(fade)}"
        }
        return ",$head,apad=whole_dur=${sec(out)},atrim=duration=${sec(out)}"
    }

    /**
     * Enveloppe trapézoïdale d'un intervalle : 0 en dehors, 1 dedans, avec une montée de [attack] juste avant et une
     * descente de [release] après. Un `between` brut ferait une marche de volume, audible à chaque kill.
     */
    internal fun envelope(range: TimeRange, attack: Duration, release: Duration): String {
        val from = (range.start - attack).coerceAtLeast(Duration.ZERO)
        val rise = (range.start - from).coerceAtLeast(1.milliseconds)
        val fall = release.coerceAtLeast(1.milliseconds)
        val up = "min(max((t-${sec(from)})/${num(secs(rise))}\\,0)\\,1)"
        val down = "min(max((${sec(range.end + fall)}-t)/${num(secs(fall))}\\,0)\\,1)"
        return "$up*$down"
    }

    /** Fenêtre sonore d'un kill : le tir juste avant, l'impact et la notification juste après. */
    private fun killWindow(k: Duration) = TimeRange((k - KILL_AUDIO_BEFORE).coerceAtLeast(Duration.ZERO), k + KILL_AUDIO_AFTER)

    /** Volume du jeu : base faible, fort autour des kills, voix et rires bien audibles, sans marche entre les niveaux. */
    internal fun volumeExpression(
        base: Double,
        kill: Double,
        voice: Double,
        kills: List<Duration>,
        voiceRanges: List<TimeRange>,
        attack: Duration,
        release: Duration,
    ): String {
        val terms = mutableListOf(num(base))
        kills.forEach { k -> terms += "(${num(base)}+${num(kill - base)}*${envelope(killWindow(k), attack, release)})" }
        voiceRanges.forEach { r -> terms += "(${num(base)}+${num(voice - base)}*${envelope(r, attack, release)})" }
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

    private fun secs(d: Duration) = d.inWholeMicroseconds / 1e6

    private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}
