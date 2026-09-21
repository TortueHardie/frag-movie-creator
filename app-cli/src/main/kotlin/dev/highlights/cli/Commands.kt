package dev.highlights.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.toShortText
import dev.highlights.core.serialization.toTimecode
import dev.highlights.core.session.SessionStore
import dev.highlights.export.ExportResult
import dev.highlights.ffmpeg.FfmpegEncoderSelector
import dev.highlights.montage.CutGrid
import dev.highlights.montage.MontageReport
import dev.highlights.montage.MontageScore
import dev.highlights.montage.MusicAnalyzer
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.pipeline.AnalysisOutcome
import dev.highlights.pipeline.AnalyzeOptions
import dev.highlights.pipeline.Diagnostics
import dev.highlights.pipeline.ExportOptions
import dev.highlights.pipeline.MontageOptions
import dev.highlights.pipeline.Pipelines
import dev.highlights.pipeline.render
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.Json

private fun PipelineCommand.formatsOption() = option("-f", "--format", help = "Formats de sortie séparés par des virgules : source (ratio de la capture, ex. 21:9), 16:9, 9:16")
    .convert { OutputFormat.parse(it) ?: throw BadParameterValue("format inconnu '$it' (source, 16:9 ou 9:16)") }
    .split(",")

private fun PipelineCommand.styleOption() = option(
    "--style",
    help = "Montage : simple (moments bout à bout) ou story (façon YouTube : jump cuts, accroche, punch-in, secousses, bruitages)",
).choice("simple" to EditStyle.SIMPLE, "story" to EditStyle.STORY)

private fun PipelineCommand.musicOption() = option("--music", help = "Musique de fond du montage story (baissée sous la voix, coupée sur les pics)")
    .path(mustExist = true, canBeDir = false)

private fun PipelineCommand.targetOption() = mutuallyExclusiveOptions(
    option("--all", help = "Garder tous les moments retenus, sans limite").flag().convert { if (it) SelectionTarget(all = true) else null },
    option("--top", help = "Garder les N meilleurs moments").int().restrictTo(min = 1).convert { SelectionTarget(topN = it) },
    option("--duration", help = "Durée cible du montage, ex. 60s ou 5m").convert { text ->
        SelectionTarget(totalDuration = Durations.parseOrNull(text) ?: throw BadParameterValue("durée invalide '$text'"))
    },
)

class ProcessCommand : PipelineCommand("process") {
    private val files by argument("VIDEO", help = "Une ou plusieurs vidéos (mp4, mkv, mov, avi, flv, ts…) : plusieurs = un seul montage, parties dans l'ordre d'enregistrement")
        .path(mustExist = true, canBeDir = false).multiple(required = true)
    private val profile by option("-p", "--profile", help = "Profil de jeu (sinon détecté d'après le chemin)")
    private val threshold by option("--threshold", help = "Seuil de score 0..1").double().restrictTo(0.0, 1.0)
    private val target by targetOption()
    private val kills by option("--kills", help = "Un moment par kill (ou multi-kill) détecté, au lieu des meilleurs moments").flag()
    private val formats by formatsOption()
    private val style by styleOption()
    private val music by musicOption()
    private val out by option("-o", "--out", help = "Dossier de sortie").path(canBeFile = false)

    override fun help(context: Context) =
        "Analyse une ou plusieurs vidéos puis exporte le montage des meilleurs moments. Avec plusieurs vidéos, la cible " +
            "(--top, --duration) vaut pour l'ensemble et les moments suivent l'ordre dans lequel les parties ont été jouées."

    override fun run() {
        val progress = ConsoleProgress()
        val outcome = execute {
            val pipeline = Pipelines.create(env.config)
            try {
                pipeline.processAll(
                    files,
                    AnalyzeOptions(profile, threshold, target, requiredEvent = if (kills) "kill" else null),
                    ExportOptions(formats, out, style, music),
                    ProgressTracker(listener = progress).root,
                )
            } finally {
                progress.finish()
            }
        }
        printAnalyses(outcome.analyses)
        outcome.export?.let(::printExport) ?: echo("Rien à exporter.")
    }
}

class AnalyzeCommand : PipelineCommand("analyze") {
    private val files by argument("VIDEO", help = "Une ou plusieurs vidéos à analyser : plusieurs = cible commune, une session par vidéo")
        .path(mustExist = true, canBeDir = false).multiple(required = true)
    private val profile by option("-p", "--profile")
    private val threshold by option("--threshold").double().restrictTo(0.0, 1.0)
    private val target by targetOption()
    private val kills by option("--kills", help = "Un moment par kill (ou multi-kill) détecté").flag()
    private val out by option("-o", "--out", help = "Dossier de sortie (la session va dans <out>/sessions)").path(canBeFile = false)

    override fun help(context: Context) =
        "Analyse seulement : écrit une session JSON. Passe enabled à false sur un segment puis lance « export »."

    override fun run() {
        val progress = ConsoleProgress()
        val outcomes = execute {
            try {
                Pipelines.create(env.config).analyzeAll(files, AnalyzeOptions(profile, threshold, target, requiredEvent = if (kills) "kill" else null, outputDir = out), ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        printAnalyses(outcomes)
    }
}

class ExportCommand : PipelineCommand("export") {
    private val sessionFiles by argument("SESSION", help = "Un ou plusieurs fichiers .session.json : plusieurs = un seul montage, parties dans l'ordre d'enregistrement")
        .path(mustExist = true, canBeDir = false).multiple(required = true)
    private val formats by formatsOption()
    private val style by styleOption()
    private val music by musicOption()
    private val out by option("-o", "--out").path(canBeFile = false)

    override fun help(context: Context) = "Exporte le montage à partir d'une session (segments enabled uniquement), sans réanalyser."

    override fun run() {
        val progress = ConsoleProgress()
        val result = execute {
            val sessions = sessionFiles.map { SessionStore.load(it) }
            try {
                Pipelines.create(env.config).export(sessions, ExportOptions(formats, out, style, music), ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        printExport(result)
    }
}

class MontageCommand : PipelineCommand("montage") {
    private val sessions by argument("SESSION", help = "Une ou plusieurs sessions (.session.json) analysées avec détection des kills")
        .path(mustExist = true, canBeDir = false).multiple(required = true)
    private val music by option("-m", "--music", help = "Musique (mp3, wav, flac…) : tempo détecté, coupes et kills calés sur les temps")
        .path(mustExist = true, canBeDir = false).required()
    private val max by option("--max", help = "Durée maximale, ex. 60s").convert { text ->
        Durations.parseOrNull(text) ?: throw BadParameterValue("durée invalide '$text'")
    }
    private val formats by formatsOption()
    private val chronological by option("--chronological", help = "Ordre chronologique au lieu de la montée en puissance").flag()
    private val effects by option("--effects", help = "Quantité d'effets : sober, balanced (défaut) ou heavy")
        .choice("sober" to EffectDensity.SOBER, "balanced" to EffectDensity.BALANCED, "heavy" to EffectDensity.HEAVY)
    private val noHook by option("--no-hook", help = "Sans accroche : le meilleur groupe restant n'ouvre pas le montage").flag()
    private val noZoom by option("--no-zoom", help = "Sans zoom sur les kills").flag()
    private val noFlash by option("--no-flash", help = "Sans flash aux coupes").flag()
    private val flashEveryCut by option("--flash-every-cut", help = "Flash à chaque coupe, pas seulement aux coupes fortes").flag()
    private val noSlowmo by option("--no-slowmo", help = "Sans ralenti").flag()
    private val noRamp by option("--no-ramp", help = "Sans rampe de vitesse entre les kills d'un multi-kill").flag()
    private val noText by option("--no-text", help = "Sans textes (DOUBLÉ, TRIPLÉ…)").flag()
    private val balance by option("--balance", help = "Équilibre jeu / musique, de -1 (musique devant) à 1 (jeu devant) ; ±6 dB par cran")
        .convert { text ->
            text.replace(',', '.').toDoubleOrNull()?.takeIf { it in -1.0..1.0 } ?: throw BadParameterValue("équilibre invalide '$text' (entre -1 et 1)")
        }
    private val reactions by option("--reactions", help = "Mettre en avant voix et rires : plans prolongés jusqu'à la fin de la phrase, micro monté, musique baissée dessous").flag()
    private val gameAudio by option("--game-audio", help = "Son du jeu : full (défaut) ou kills (le son du kill seul, musique baissée dessous)")
        .choice("full" to GameAudio.FULL, "kills" to GameAudio.KILLS)
    private val out by option("-o", "--out").path(canBeFile = false)

    override fun help(context: Context) =
        "Montage de tous les kills calé sur une musique : coupes sur le beat, chaque kill sur un temps, zoom, flash, ralenti, textes (--reactions pour mettre en avant voix et rires)."

    override fun run() {
        val progress = ConsoleProgress()
        val result = execute {
            val loaded = sessions.map { SessionStore.load(it) }
            try {
                Pipelines.create(env.config).killMontage(
                    loaded,
                    music,
                    MontageOptions(
                        formats = formats,
                        outputDir = out,
                        maxDuration = max,
                        order = if (chronological) MontageOrder.CHRONOLOGICAL else null,
                        hook = if (noHook) false else null,
                        effectDensity = effects,
                        zoom = if (noZoom) false else null,
                        flash = if (noFlash) false else null,
                        flashEveryCut = if (flashEveryCut) true else null,
                        slowMotion = if (noSlowmo) false else null,
                        speedRamp = if (noRamp) false else null,
                        text = if (noText) false else null,
                        audioBalance = balance,
                        gameAudio = gameAudio,
                        reactions = if (reactions) true else null,
                    ),
                    ProgressTracker(listener = progress).root,
                )
            } finally {
                progress.finish()
            }
        }
        printExport(result)
        // La note du montage : ce que le moteur prétend faire, mesuré. « app score » compare deux rapports.
        runCatching { readReport(result.report).score }.getOrNull()?.let { s ->
            val parts = criteria(s).joinToString(", ") { (n, v) -> if (v == null) "$n -" else "%s %.2f".format(n, v) }
            echo("  note %.3f  ($parts)".format(s.total))
        }
    }
}

class MusicCommand : PipelineCommand("music") {
    private val file by argument("MUSIQUE", help = "Fichier audio (mp3, wav, flac…)").path(mustExist = true, canBeDir = false)
    private val max by option("--max", help = "Affiche aussi la grille de coupes d'un montage de cette durée (ex. 60s), réglages du profil").convert { text ->
        Durations.parseOrNull(text) ?: throw BadParameterValue("durée invalide '$text'")
    }
    private val clips by option("--clips", help = "Nombre de clips disponibles pour la grille (défaut : illimité)").int().restrictTo(min = 1)
    private val profile by option("-p", "--profile", help = "Profil dont on prend les réglages de montage (défaut : default)")

    override fun help(context: Context) =
        "Analyse une musique comme le fait le montage kills : tempo, mesures, sections (intensité), drop et grille de coupes."

    override fun run() {
        val a = execute { MusicAnalyzer.analyze(Pipelines.ffmpeg(env.config), file) }
        echo("${a.file.fileName} : ${a.duration.toShortText()}, ${"%.2f".format(a.bpm)} BPM, ${a.beats.size} temps, premier temps de mesure = temps ${a.downbeatPhase}")
        echo("Drop : temps ${a.dropBeat} à ${a.beatTime(a.dropBeat).toTimecode()}")
        echo("Sections :")
        a.sections.forEachIndexed { i, s ->
            val marker = if (s.startBeat == a.dropBeat) " <- drop" else ""
            echo(
                "  %2d. %s -> %s  %3d temps  %6.1f dB  intensite %.2f  pente %+5.1f dB  %-4s %-9s%s".format(
                    i + 1, a.beatTime(s.startBeat).toTimecode(), a.beatTime(s.endBeat).toTimecode(), s.beats,
                    s.loudnessDb, s.intensity, s.rise, s.level, s.kind.name.lowercase(), marker,
                ),
            )
        }
        val maxDuration = max ?: return
        val settings = env.config.let { Pipelines.create(it).profiles.byId(profile ?: "default").montage }
        val cuts = settings.cuts
        val period = a.beatPeriod
        val minBeats = maxOf(2, Math.ceil(cuts.minLead / period).toInt() + Math.ceil(cuts.minTail / period).toInt())
        val (scale, _, window) = CutGrid.select(a, cuts, minBeats, maxDuration, clips ?: Int.MAX_VALUE)
        if (window.isEmpty()) {
            echo("Aucune fenetre de ${maxDuration.toShortText()} possible")
            return
        }
        val start = a.beatTime(window.first().startBeat)
        val end = a.beatTime(window.last().endBeat)
        echo("Grille x${"%.0f".format(scale)} : ${window.size} plans de ${start.toTimecode()} a ${end.toTimecode()} (${(end - start).toShortText()})")
        echo("  " + window.joinToString(" ") { s -> "${s.beats}${if (s.dropBeat != null) "*" else ""}" } + "  (temps par plan, * = drop)")
    }
}

class PreviewCommand : PipelineCommand("preview") {
    private val file by argument(help = "Vidéo source").path(mustExist = true, canBeDir = false)
    private val at by option("--at", help = "Instant à capturer, ex. 21:00 ou 1260s").convert { text ->
        Durations.parseOrNull(text) ?: throw BadParameterValue("instant invalide '$text' (ex. 21:00)")
    }.required()
    private val profile by option("-p", "--profile")
    private val formats by formatsOption()
    private val out by option("-o", "--out", help = "Dossier de sortie (les images vont dans <out>/previews)").path(canBeFile = false)

    override fun help(context: Context) =
        "Génère une image PNG de chaque format à un instant donné, pour régler le recadrage et le HUD du profil sans rendu complet."

    override fun run() {
        val images = execute { Pipelines.create(env.config).preview(file, at, profile, formats, out) }
        images.forEach { echo("  $it") }
    }
}

class ProbeCommand : PipelineCommand("probe") {
    private val file by argument().path(mustExist = true, canBeDir = false)

    override fun help(context: Context) = "Affiche les flux d'une vidéo et le rôle déduit de chaque piste audio."

    override fun run() {
        val media = execute { Pipelines.ffmpeg(env.config).probe(file) }
        printMedia(media)
    }

    private fun printMedia(m: MediaInfo) {
        echo("${m.path}")
        echo("  conteneur : ${m.container}, durée ${m.duration.toTimecode()}, ${m.sizeBytes / 1_048_576} Mo, créé ${m.creationTime ?: "?"}")
        m.video?.let { echo("  vidéo     : ${it.codec} ${it.width}x${it.height} @ ${"%.2f".format(it.fps)} fps (${it.pixelFormat})") }
            ?: echo("  vidéo     : aucune")
        if (m.audio.isEmpty()) echo("  audio     : aucune piste")
        val tracks = AudioTracks.of(m.audio)
        m.audio.forEach { stream ->
            val roles = AudioRole.entries.filter { tracks[it]?.audioIndex == stream.audioIndex }
            val label = roles.joinToString("/") { it.name.lowercase() }.ifEmpty { "non utilisée" }
            echo("  audio     : ${stream.label} ${stream.codec} ${stream.channels} canaux ${stream.sampleRate} Hz → $label")
        }
        if (m.audio.isNotEmpty()) echo("  rôles     : ${tracks.explanation}")
    }
}

class DoctorCommand : PipelineCommand("doctor") {
    private val file by argument(help = "Capture à examiner (facultatif) : pistes, format d'écran, profil retenu")
        .path(mustExist = true, canBeDir = false).optional()

    override fun help(context: Context) =
        "Vérifie l'installation : FFmpeg, encodeurs utilisables, prérequis des détecteurs, et ce que donnerait une capture."

    override fun run() {
        val report = execute {
            val ffmpeg = Pipelines.ffmpeg(env.config)
            Diagnostics(env.config, ffmpeg, ProfileRepository.loadDirectory(env.config.profilesDir)).run(file)
        }
        echo(report.render())
    }
}

class EncodersCommand : PipelineCommand("encoders") {
    override fun help(context: Context) = "Vérifie quels encodeurs de la liste de préférence fonctionnent sur cette machine."

    override fun run() {
        val checks = execute {
            FfmpegEncoderSelector(Pipelines.ffmpeg(env.config), env.config.app.encoder).checkAll()
        }
        checks.forEach { echo("  ${if (it.usable) "OK " else "KO "} ${it.name}${it.reason?.let { r -> " : $r" } ?: ""}") }
    }
}

/** Moments et session de chaque capture ; le nom de la capture en tête quand il y en a plusieurs. */
private fun PipelineCommand.printAnalyses(outcomes: List<AnalysisOutcome>) {
    outcomes.forEach { outcome ->
        if (outcomes.size > 1) echo("== ${outcome.session.media.path.fileName}")
        printHighlights(outcome.session.highlights, outcome.session.warnings)
        echo("Session : ${outcome.sessionFile}")
    }
}

private fun PipelineCommand.printHighlights(highlights: List<Highlight>, warnings: List<String>) {
    warnings.forEach { echo("Attention : $it", err = true) }
    if (highlights.isEmpty()) {
        echo("Aucun moment retenu.")
        return
    }
    echo("${highlights.size} moment(s) retenu(s) :")
    highlights.forEach { h ->
        val parts = h.contributions.entries.filter { it.value > 0.005 }.joinToString { "${it.key}=${"%.2f".format(it.value)}" }
        val events = h.events.entries.joinToString { "${it.value} ${it.key}" }.let { if (it.isEmpty()) "" else "  <$it>" }
        echo("  ${h.id}  ${h.range.start.toTimecode()} -> ${h.range.end.toTimecode()}  (${h.range.length.toShortText()})  score ${"%.2f".format(h.score)}$events  [$parts]")
    }
}

private fun PipelineCommand.printExport(result: ExportResult) {
    echo("Montage (${result.duration.toShortText()}, encodeur ${result.encoder}) :")
    result.videos.forEach { (format, path) -> echo("  ${format.label}  $path") }
    echo("  rapport ${result.report}")
}

private val reportJson = Json { ignoreUnknownKeys = true }

internal fun readReport(file: Path): MontageReport =
    reportJson.decodeFromString(MontageReport.serializer(), file.readText())

/** Criteres d'une note, dans l'ordre d'affichage. Null = non mesurable sur ce montage. */
private fun criteria(s: MontageScore): List<Pair<String, Double?>> = listOf(
    "sync" to s.sync, "accent" to s.accent, "sobriete" to s.restraint, "variete" to s.variety,
    "duree" to s.fill, "rythme" to s.pacing, "source" to s.coverage,
)

private fun cell(v: Double?) = if (v == null) "%8s".format("-") else "%8.3f".format(v)

class ScoreCommand : PipelineCommand("score") {
    private val reports by argument("RAPPORT", help = "Un ou plusieurs rapports de montage (.json)")
        .path(mustExist = true, canBeDir = false).multiple(required = true)

    override fun help(context: Context) =
        "Note un ou plusieurs montages d'apres leur rapport, et les compare : juger une version du moteur sans la regarder."

    override fun run() {
        val loaded = reports.mapNotNull { file ->
            val report = runCatching { readReport(file) }.getOrElse {
                echo("Rapport illisible : $file (${it.message})", err = true)
                return@mapNotNull null
            }
            val score = report.score ?: run {
                echo("Rapport sans note (montage anterieur) : $file", err = true)
                return@mapNotNull null
            }
            file to score
        }
        if (loaded.isEmpty()) return
        // Les rapports portent souvent le meme nom : on remonte d'un dossier quand il le faut pour les distinguer.
        val labels = loaded.map { it.first.fileName.toString() }.let { names ->
            if (names.distinct().size == names.size) names
            else loaded.map { it.first.parent?.fileName?.let { p -> "$p/${it.first.fileName}" } ?: it.first.fileName.toString() }
        }
        val width = labels.maxOf { it.length }.coerceAtMost(60)
        val names = criteria(loaded.first().second).map { it.first }
        echo("%-${width}s  %5s  %s".format("rapport", "total", names.joinToString("  ") { "%8s".format(it) }))
        loaded.forEachIndexed { i, (_, s) ->
            echo("%-${width}s  %5.3f  %s".format(labels[i].take(width), s.total, criteria(s).joinToString("  ") { cell(it.second) }))
        }
        // Detail du dernier : c'est lui qu'on vient de produire.
        val last = loaded.last().second
        echo("")
        echo("Mesures (${labels.last()}) : " + last.details.entries.joinToString(", ") { (k, v) -> "$k=$v" })
        if (loaded.size > 1) {
            val first = loaded.first().second
            echo("Ecart avec ${labels.first()} : %+.3f".format(last.total - first.total))
            criteria(last).zip(criteria(first))
                .mapNotNull { (a, b) -> if (a.second != null && b.second != null) Triple(a.first, a.second!!, b.second!!) else null }
                .filter { (_, x, y) -> kotlin.math.abs(x - y) >= 0.01 }
                .forEach { (name, x, y) -> echo("  $name %+.3f".format(x - y)) }
        }
    }
}
