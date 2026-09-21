package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SlowAudio
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import dev.highlights.editing.CutInput
import dev.highlights.editing.SourceCuts
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MontageRenderBuilderTest : FunSpec({
    val media = MediaInfo(
        Path("partie.mp4"), 1, 30.minutes,
        video = VideoStream(0, "h264", 3440, 1440, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000), AudioStream(2, 1, "aac", 2, 48000)),
    )
    val settings = MontageSettings(killOffset = Duration.ZERO)
    val music = MusicAnalysis(
        Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
        listOf(MusicSection(0, 96, -14.0, 0.4), MusicSection(96, 240, -8.0, 1.0)), 96,
    )

    fun plan(s: MontageSettings = settings): MontagePlan {
        val groups = listOf(
            KillGroup(media, listOf(100.seconds, 102.seconds), 1.0, emptyList(), listOf(TimeRange(101.seconds, 102.8.seconds))),
            KillGroup(media, listOf(500.seconds), 0.5, emptyList(), emptyList()),
        )
        return MontagePlanner.plan(groups, music, s)
    }

    val edit = EditSettings(audioStreams = listOf(0))

    fun build(p: MontagePlan, format: OutputFormat = OutputFormat.VERTICAL, cuts: SourceCuts = SourceCuts.NONE) = MontageRenderBuilder.build(
        MontageRenderRequest(p, format, edit, EncoderProfile("h264_amf", listOf("-c:v", "h264_amf"), true), Path("out.mp4"), Path("f.txt"), cuts = cuts),
    )

    test("captures de tailles différentes : tout est ramené au format du premier clip") {
        val small = media.copy(path = Path("autre.mp4"), video = VideoStream(0, "h264", 1920, 1080, 60.0))
        val groups = listOf(
            KillGroup(media, listOf(100.seconds, 102.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(small, listOf(500.seconds), 0.5, emptyList(), emptyList()),
        )
        val graph = MontageRenderBuilder.build(
            MontageRenderRequest(
                MontagePlanner.plan(groups, music, settings), OutputFormat.SOURCE, edit,
                EncoderProfile("libx264", listOf("-c:v", "libx264"), false), Path("out.mp4"), Path("f.txt"),
            ),
        ).filterGraph

        // Première capture 3440x1440 → 2580x1080 ; la seconde (16:9) est mise à cette taille avec des bandes noires.
        graph shouldContain "scale=2580:1080:force_original_aspect_ratio=decrease"
        graph shouldContain "pad=2580:1080:(ow-iw)/2:(oh-ih)/2"
    }

    test("pré-découpes lues à la place des sources : les intervalles annoncés sont ceux du graphe") {
        val p = plan()
        val cuts = MontageRenderBuilder.sourceCuts(p, edit.fps)
        val inputs = cuts.withIndex().associate { (i, cut) -> cut to CutInput(Path("cuts/cut_$i.mp4"), 1.seconds) }
        val args = build(p, cuts = SourceCuts.of(inputs)).command.args

        // Un fichier de découpe par clip, départ recalé sur son début, et plus aucune lecture de la capture d'origine.
        cuts.size shouldBe p.clips.size
        inputs.values.forEach { args shouldContainInOrder listOf("-ss", "1.000000", "-i", it.path.toString()) }
        args shouldNotContain media.path.toString()
    }

    test("effets, ralenti, textes et mixage présents") {
        val cmd = build(plan())
        val g = cmd.filterGraph
        g shouldContain "setpts=(PTS-STARTPTS)/0.5000"
        // Le son du jeu n'est pas étiré avec l'image ; la rampe du multi-kill, elle, l'est.
        g shouldNotContain "atempo=0.5000"
        g shouldContain "atempo=1.1111"
        g shouldContain "eval=frame:flags=bicubic"
        // Décélération par paliers avant le kill, puis ralenti plein.
        g shouldContain "setpts=(PTS-STARTPTS)/0.8333"
        g shouldContain "setpts=(PTS-STARTPTS)/0.6667"
        g shouldContain "text='DOUBLÉ'"
        g shouldContain """fontfile='C\:/Windows/Fonts/impact.ttf'"""
        g shouldContain "concat=n=2:v=1:a=0[vcat]"
        g shouldContain "amix=inputs=2:normalize=0:duration=longest"
        g shouldContain "amix=inputs=2:normalize=0:duration=first"
        g shouldContain "[vcat]fade=t=out"
        // La musique baisse progressivement pendant la réaction du premier clip.
        g shouldContain "volume='1.0000-0.5500*(min(max((t-"
        cmd.command.args.count { it == "-i" } shouldBe 3
    }

    test("longueurs alignées à l'image, sans dérive") {
        val p = plan()
        val cmd = build(p)
        val frames = Math.round(p.duration.inWholeMicroseconds * 60 / 1_000_000.0)
        (cmd.expectedDuration.inWholeMicroseconds * 60 / 1_000_000.0) shouldBe frames.toDouble()
    }

    test("équilibre : le jeu monte et la musique baisse d'autant") {
        val g = build(plan(settings.copy(audio = settings.audio.copy(balance = 1.0)))).filterGraph
        // Jeu ×2 (base 0,5 → 1, kill 1 → 2), musique ×0,5, ducking sous la voix toujours proportionnel.
        g shouldContain "volume='max(1.0000\\,(1.0000+1.0000*"
        g shouldContain "volume='0.5000-0.2750*(min(max((t-"
    }

    test("son du kill seul : le reste du jeu se tait et la musique baisse sous chaque kill") {
        val kills = settings.copy(audio = settings.audio.copy(game = GameAudio.KILLS))
        val g = build(plan(kills)).filterGraph
        g shouldContain "volume='max(0.0000\\,(0.0000+1.0000*"
        // Musique à 0,3 sous le kill, plus sous la voix (qu'on n'entend plus).
        g shouldContain "volume='1.0000-0.7000*(max(max(min(max((t-"
        g shouldNotContain "-0.5500*("
    }

    test("enveloppes de volume évaluées sur des trames courtes : pas d'escalier audible") {
        val g = build(plan()).filterGraph
        // Chaque volume variable (jeu de chaque clip, musique) est précédé du découpage en trames de 64 échantillons.
        val envelopes = Regex("volume='[^']*':eval=frame").findAll(g).count()
        Regex("asetnsamples=n=64:p=0,volume='[^']*':eval=frame").findAll(g).count() shouldBe envelopes
        envelopes shouldBe 3
    }

    test("effets désactivables") {
        val off = settings.copy(
            zoom = settings.zoom.copy(enabled = false), flash = settings.flash.copy(enabled = false),
            slowMotion = settings.slowMotion.copy(enabled = false), text = settings.text.copy(enabled = false),
        )
        val g = build(plan(off), OutputFormat.SOURCE).filterGraph
        g shouldNotContain "eval=frame:flags=bicubic"
        g shouldNotContain "fade=t=in"
        g shouldNotContain "atempo"
        g shouldNotContain "drawtext"
    }

    test("volume du jeu : fort aux kills et pendant la voix, sans marche") {
        val expr = MontageRenderBuilder.volumeExpression(
            0.3, 1.0, 1.0, listOf(2.seconds), listOf(TimeRange(3.seconds, 4.seconds)), 80.milliseconds, 220.milliseconds,
        )
        // Base 0,3 relevée à 1 autour du kill (1,850 → 2,600) puis pendant la voix, par rampes de 80 ms et 220 ms.
        expr shouldBe """max(max(0.3000\,(0.3000+0.7000*min(max((t-1.770)/0.0800\,0)\,1)*min(max((2.820-t)/0.2200\,0)\,1)))""" +
            """\,(0.3000+0.7000*min(max((t-2.920)/0.0800\,0)\,1)*min(max((4.220-t)/0.2200\,0)\,1)))"""
    }

    test("enveloppe tronquée au début du clip : montée raccourcie, pas de division par zéro") {
        MontageRenderBuilder.envelope(TimeRange(Duration.ZERO, 1.seconds), 80.milliseconds, 200.milliseconds) shouldBe
            """min(max((t-0.000)/0.0010\,0)\,1)*min(max((1.200-t)/0.2000\,0)\,1)"""
    }

    test("flash : coupes fortes seulement, jamais à l'ouverture") {
        // Deux clips d'une même section, le multi-kill en tête : plus aucun flash.
        val p = plan()
        MontageRenderBuilder.flashes(p) shouldBe listOf(false, false)
        build(p).filterGraph shouldNotContain "fade=t=in"

        val every = plan(settings.copy(flash = settings.flash.copy(onEveryCut = true)))
        MontageRenderBuilder.flashes(every) shouldBe listOf(false, true)
        build(every).filterGraph shouldContain "fade=t=in:st=0:d=0.060:color=white"
    }

    test("flash : une coupe forte le déclenche, les autres non") {
        // Le meilleur multi-kill part sur la drop (en tête ici) ; le second ouvre une coupe forte, plus loin.
        val groups = listOf(
            KillGroup(media, listOf(500.seconds, 502.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(media, listOf(100.seconds, 102.seconds), 0.5, emptyList(), emptyList()),
            KillGroup(media, listOf(300.seconds), 0.5, emptyList(), emptyList()),
        )
        val p = MontagePlanner.plan(groups, music, settings)
        val flashes = MontageRenderBuilder.flashes(p)
        flashes.first() shouldBe false
        flashes.count { it } shouldBe 1
        flashes.indexOf(true) shouldBe p.clips.indexOfLast { it.kills.size > 1 }
    }

    test("flash : un multi-kill dont le début est coupé ne compte pas comme une coupe forte") {
        // Slot court : seul le dernier kill du groupe reste visible, le plan n'a donc rien d'un multi-kill à l'écran.
        val tight = settings.copy(cuts = settings.cuts.copy(maxBeats = 4, minLead = 250.milliseconds))
        val groups = listOf(
            KillGroup(media, listOf(500.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(media, listOf(100.seconds, 104.seconds), 0.5, emptyList(), emptyList()),
            KillGroup(media, listOf(300.seconds), 0.5, emptyList(), emptyList()),
        )
        val p = MontagePlanner.plan(groups, music, tight)
        val trimmed = p.clips.single { it.group.kills.size > 1 }
        trimmed.kills.size shouldBe 1
        MontageRenderBuilder.flashes(p)[p.clips.indexOf(trimmed)] shouldBe false
    }

    test("coupe en avance sur son temps : la coupe avance, le kill ne bouge pas") {
        val frame = (1_000_000L / edit.fps).microseconds
        MontageRenderBuilder.leads(plan(), edit.fps) shouldBe listOf(Duration.ZERO, frame)

        // Départ de lecture de chaque entrée, et instant où chaque clip est posé dans le montage.
        fun starts(s: MontageSettings) = build(plan(s)).command.args.let { a ->
            a.indices.filter { a[it] == "-ss" }.map { a[it + 1].toDouble() }
        }
        fun delays(s: MontageSettings) = Regex("""adelay=(\d+)\|""").findAll(build(plan(s)).filterGraph).map { it.groupValues[1].toInt() }.toList()

        val sharp = settings.copy(cuts = settings.cuts.copy(preBeatFrames = 0))
        val shift = frame.inWholeMicroseconds / 1e6
        // Le second plan lit sa source une image plus tôt et se place une image plus tôt : les deux décalages
        // s'annulent, donc son kill tombe toujours exactement sur le temps.
        (starts(sharp)[1] - starts(settings)[1]) shouldBe (shift plusOrMinus 1e-6)
        // adelay ne s'exprime qu'en millisecondes entières : une image de 16,67 ms y devient 16 ou 17.
        (delays(sharp)[1] - delays(settings)[1]).toDouble() shouldBe (shift * 1000 plusOrMinus 1.0)
        // Le premier plan n'a pas de coupe à anticiper.
        starts(sharp)[0] shouldBe starts(settings)[0]
    }

    test("coupe en avance : un plan collé au début de sa capture n'invente pas d'images") {
        val groups = listOf(
            KillGroup(media, listOf(500.seconds), 1.0, emptyList(), emptyList()),
            // Kill au tout début de la capture : rien à montrer avant, le plan démarre déjà à zéro.
            KillGroup(media, listOf(1.seconds), 0.5, emptyList(), emptyList()),
        )
        val p = MontagePlanner.plan(groups, music, settings)
        val tight = p.clips.indexOfFirst { it.group.kills.first() == 1.seconds }
        tight shouldBe 1
        p.clips[tight].start shouldBe Duration.ZERO
        MontageRenderBuilder.leads(p, edit.fps)[tight] shouldBe Duration.ZERO
    }

    test("zoom : un par kill, ou seulement sur celui calé sur le temps") {
        // L'expression du zoom est reprise pour la largeur et pour la hauteur : deux occurrences par kill.
        fun punches(s: MontageSettings) = Regex("""if\(gte\(t\\,""").findAll(build(plan(s)).filterGraph).count() / 2
        val all = settings.copy(effectDensity = EffectDensity.HEAVY)
        // Trois kills visibles en tout (un doublé et un simple) ; sinon un seul par plan.
        punches(all) shouldBe 3
        punches(all.copy(zoom = all.zoom.copy(onEveryKill = false))) shouldBe 2
    }

    test("densité d'effets : un plan ralenti ne reçoit pas de zoom en plus") {
        // Le plan fort (multi-kill sur la drop) est ralenti, l'autre reçoit le zoom : une seule emphase par plan.
        val p = plan()
        p.clips.map { it.slow != null } shouldBe listOf(true, false)
        MontageRenderBuilder.zooms(p) shouldBe listOf(false, true)

        val heavy = plan(settings.copy(effectDensity = EffectDensity.HEAVY))
        heavy.clips.map { it.slow != null } shouldBe listOf(true, true)
        MontageRenderBuilder.zooms(heavy) shouldBe listOf(true, true)

        val sober = plan(settings.copy(effectDensity = EffectDensity.SOBER))
        MontageRenderBuilder.zooms(sober) shouldBe listOf(false, false)
        sober.clips.map { it.slow != null } shouldBe listOf(true, false)
    }

    test("son du ralenti : joué à sa vitesse puis effacé, ou étiré, ou tu") {
        val audio = settings.audio
        val slow = MontageRenderBuilder.SpeedPart(Duration.ZERO, 500.milliseconds, 0.5, SpeedKind.SLOW)
        // 500 ms de source à jouer sur 1 s : le son sort normalement, s'efface sur 250 ms, le silence complète.
        MontageRenderBuilder.slowAudio(slow, Duration.ZERO, audio) shouldBe
            ",afade=t=out:st=0.250:d=0.250,apad=whole_dur=1.000,atrim=duration=1.000"
        MontageRenderBuilder.slowAudio(slow, Duration.ZERO, audio.copy(slowMotion = SlowAudio.MUTE)) shouldBe
            ",volume=0,apad=whole_dur=1.000,atrim=duration=1.000"
        MontageRenderBuilder.slowAudio(slow, Duration.ZERO, audio.copy(slowMotion = SlowAudio.STRETCH)) shouldBe ",atempo=0.5000"

        // Une rampe de multi-kill reste étirée : ±15 % ne s'entend pas. Et à vitesse normale, rien du tout.
        val ramp = MontageRenderBuilder.SpeedPart(Duration.ZERO, 500.milliseconds, 1.1, SpeedKind.RAMP)
        MontageRenderBuilder.slowAudio(ramp, Duration.ZERO, audio) shouldBe ",atempo=1.1000"
        MontageRenderBuilder.slowAudio(slow.copy(factor = 1.0), Duration.ZERO, audio) shouldBe ""
    }

    test("ralenti : images calculées seulement sur les portions ralenties, et sur demande") {
        val g = build(plan(settings.copy(slowMotion = settings.slowMotion.copy(interpolate = true)))).filterGraph
        g shouldContain "setpts=(PTS-STARTPTS)/0.5000,minterpolate=fps=60"
        // Les portions à vitesse normale gardent une simple conversion de cadence.
        g shouldNotContain "PTS-STARTPTS,minterpolate"
        // Par défaut, aucune image n'est inventée.
        build(plan()).filterGraph shouldNotContain "minterpolate"
    }

    test("compteur de kills : absent par défaut, présent sur demande") {
        build(plan()).filterGraph shouldNotContain "text='KILL"
        build(plan(settings.copy(text = settings.text.copy(killCounter = true)))).filterGraph shouldContain "text='KILL 3'"
    }

})
