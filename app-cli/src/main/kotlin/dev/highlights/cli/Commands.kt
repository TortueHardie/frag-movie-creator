package dev.highlights.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
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
import dev.highlights.core.model.KillCounterMode
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
import dev.highlights.montage.MontageChoiceRecord
import dev.highlights.montage.KillRef
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontagePin
import dev.highlights.montage.MontageReport
import dev.highlights.montage.MontageScore
import dev.highlights.montage.MusicAnalyzer
import dev.highlights.montage.MusicLibrary
import dev.highlights.montage.ThumbnailStyle
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.pipeline.AnalysisOutcome
import dev.highlights.pipeline.AnalyzeOptions
import dev.highlights.pipeline.Diagnostics
import dev.highlights.pipeline.ExportOptions
import dev.highlights.pipeline.MontageOptions
import dev.highlights.pipeline.Pipelines
import dev.highlights.pipeline.VariantPick
import dev.highlights.pipeline.PlannedMontage
import dev.highlights.pipeline.render
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlinx.serialization.json.Json

private fun PipelineCommand.formatsOption() = option("-f", "--format", help = "Formats de sortie séparés par des virgules : source (ratio de la capture, ex. 21:9), 16:9, 9:16")
    .convert { OutputFormat.parse(it) ?: throw BadParameterValue("format inconnu '$it' (source, 16:9 ou 9:16)") }
    .split(",")

private fun PipelineCommand.platformOption() = option(
    "--platform",
    help = "Plateforme visée : tiktok, shorts, reels, youtube (ou définie dans app.yaml) — format, volume, débit, textes hors de l'interface de l'appli, durée maximale",
)

/** `--thumbnail` : miniature YouTube de chaque vidéo ; `--thumbnail-style` choisit son habillage (vif par défaut). */
private fun PipelineCommand.thumbnailFlag() = option("--thumbnail", help = "Écrit aussi une miniature YouTube (PNG 1280x720, 1080x1920 en vertical) à côté de chaque vidéo").flag()

internal fun PipelineCommand.thumbnailStyleOption() = option("--thumbnail-style", help = "Habillage de la miniature : vivid (défaut : couleurs vives, texte jaune), sober ou big (texte aussi large que l'image)")
    .choice(*ThumbnailStyle.entries.map { it.id to it }.toTypedArray())

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
    private val reanalyze by option("--reanalyze", help = "Tout recalculer, même pour une capture déjà analysée").flag()
    private val formats by formatsOption()
    private val platform by platformOption()
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
                    AnalyzeOptions(profile, threshold, target, requiredEvent = if (kills) "kill" else null, reuse = !reanalyze),
                    ExportOptions(formats, out, style, music, platform),
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
    private val reanalyze by option("--reanalyze", help = "Tout recalculer, même pour une capture déjà analysée").flag()
    private val out by option("-o", "--out", help = "Dossier de sortie (la session va dans <out>/sessions)").path(canBeFile = false)

    override fun help(context: Context) =
        "Analyse seulement : écrit une session JSON. Passe enabled à false sur un segment puis lance « export »."

    override fun run() {
        val progress = ConsoleProgress()
        val outcomes = execute {
            try {
                Pipelines.create(env.config).analyzeAll(files, AnalyzeOptions(profile, threshold, target, requiredEvent = if (kills) "kill" else null, outputDir = out, reuse = !reanalyze), ProgressTracker(listener = progress).root)
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
    private val platform by platformOption()
    private val style by styleOption()
    private val music by musicOption()
    private val out by option("-o", "--out").path(canBeFile = false)
    private val thumbnail by thumbnailFlag()
    private val thumbnailStyle by thumbnailStyleOption()

    override fun help(context: Context) = "Exporte le montage à partir d'une session (segments enabled uniquement), sans réanalyser."

    override fun run() {
        val progress = ConsoleProgress()
        val result = execute {
            val sessions = sessionFiles.map { SessionStore.load(it) }
            try {
                Pipelines.create(env.config).export(sessions, exportOptions(), ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        printExport(result)
    }

    /** Options lues sur la ligne de commande, sans rien lancer (testées seules). */
    internal fun exportOptions() = ExportOptions(formats, out, style, music, platform, thumbnail = thumbnailStyle ?: ThumbnailStyle.DEFAULT.takeIf { thumbnail })
}

class MontageCommand : PipelineCommand("montage") {
    private val sessions by argument("SESSION", help = "Une ou plusieurs sessions (.session.json) analysées avec détection des kills")
        .path(mustExist = true, canBeDir = false).multiple(required = true)
    private val music by option(
        "-m", "--music",
        help = "Musique (mp3, wav, flac…) : tempo détecté, coupes et kills calés sur les temps. Un dossier : la musique y est choisie, " +
            "celle sur laquelle le montage est le mieux noté (analyses gardées d'un montage à l'autre)",
    ).path(mustExist = true).required()
    private val fill by option("--fill", help = "Occuper toute la durée maximale au lieu d'adapter la durée au nombre de kills").flag()
    private val max by option("--max", help = "Durée maximale, ex. 60s").convert { text ->
        Durations.parseOrNull(text) ?: throw BadParameterValue("durée invalide '$text'")
    }
    private val formats by formatsOption()
    private val platform by platformOption()
    private val chronological by option("--chronological", help = "Ordre chronologique au lieu de la montée en puissance").flag()
    private val effects by option("--effects", help = "Quantité d'effets : sober, balanced (défaut) ou heavy")
        .choice("sober" to EffectDensity.SOBER, "balanced" to EffectDensity.BALANCED, "heavy" to EffectDensity.HEAVY)
    private val noHook by option("--no-hook", help = "Sans accroche : le meilleur groupe restant n'ouvre pas le montage").flag()
    private val fromStart by option("--from-start", help = "Musique depuis son début, au lieu du passage le plus intense autour de la drop").flag()
    private val noZoom by option("--no-zoom", help = "Sans zoom sur les kills").flag()
    private val noFlash by option("--no-flash", help = "Sans flash aux coupes").flag()
    private val flashEveryCut by option("--flash-every-cut", help = "Flash à chaque coupe, pas seulement aux coupes fortes").flag()
    private val noWhip by option("--no-whip", help = "Sans whip pan dans le sens du flick").flag()
    private val showcase by option("--showcase", help = "Ouverture vitrine : passages calmes des parties, sans kill, avant le premier kill (par défaut)").flag()
    private val noShowcase by option("--no-showcase", help = "Sans ouverture vitrine : le montage commence sur un kill").flag()
    private val noWhoosh by option("--no-whoosh", help = "Whip pans muets, sans souffle sur la coupe (par défaut)").flag()
    private val whoosh by option("--whoosh", help = "Souffle (whoosh) sur les whip pans, muets par défaut").flag()
    private val whooshDir by option("--whoosh-dir", help = "Dossier de souffles (wav, mp3…) pour les whip pans, chacun à son tour, à la place des souffles générés")
        .path(mustExist = true, canBeFile = false)
    private val noMotionBlur by option("--no-motion-blur", help = "Sans flou de mouvement (traînées sur les mouvements rapides, flou radial sur les coupes)").flag()
    private val colors by option("--colors", help = "Couleurs : boost (vives, noirs francs, comme les kill montages de TikTok) ou natural (celles du jeu) ; défaut : celui du profil")
        .choice("boost" to true, "natural" to false)
    private val noMatchCut by option("--no-match-cut", help = "Sans raccord sur la pose de l'arme (visée ou arme au repos, qui reste en place par-dessus la coupe)").flag()
    private val noRounds by option("--no-rounds", help = "Sans classement selon le round (mort juste après, ace, clutch)").flag()
    private val noSlowmo by option("--no-slowmo", help = "Sans ralenti").flag()
    private val noRamp by option("--no-ramp", help = "Sans rampe de vitesse entre les kills d'un multi-kill").flag()
    private val noText by option("--no-text", help = "Sans textes (DOUBLÉ, TRIPLÉ…)").flag()
    private val killCounter by option("--kill-counter", help = "Compteur de kills à l'écran (« 1… 2… 3… », puis ACE) : clip (compté dans chaque plan), montage (cumulé), off")
        .choice("off" to KillCounterMode.OFF, "clip" to KillCounterMode.CLIP, "montage" to KillCounterMode.MONTAGE)
    private val balance by option("--balance", help = "Équilibre jeu / musique, de -1 (musique devant) à 1 (jeu devant) ; ±6 dB par cran")
        .convert { text ->
            text.replace(',', '.').toDoubleOrNull()?.takeIf { it in -1.0..1.0 } ?: throw BadParameterValue("équilibre invalide '$text' (entre -1 et 1)")
        }
    private val reactions by option("--reactions", help = "Mettre en avant voix et rires : plans prolongés jusqu'à la fin de la phrase, micro monté, musique baissée dessous").flag()
    private val gameAudio by option("--game-audio", help = "Son du jeu : full (défaut) ou kills (le son du kill seul, musique baissée dessous)")
        .choice("full" to GameAudio.FULL, "kills" to GameAudio.KILLS)
    private val fast by option("--fast", help = "Rythme rapide : un kill sur chaque temps de la musique (chaque coup de basse), un plan très court chacun").flag()
    private val noFastParts by option(
        "--no-fast-parts",
        help = "Pas de rythme rapide sur les parties rapides et intenses de la musique (drop et après) : tout le montage au rythme du style",
    ).flag()
    private val oneTaps by option("--onetaps", help = "Que les kills d'une balle à la tête, un plan très court chacun, enchaînés sur le rythme").flag()
    private val fails by option("--fails", help = "Montage des morts du joueur : les secondes d'avant, ralenti, image figée et petit texte (OUPS, RIP…), sur la musique").flag()
    private val style by option(
        "--style",
        help = "Style de montage (config/montage-styles.yaml, ex. tiktok, propre, onetaps, cinematique) : un jeu de réglages, " +
            "les options ci-dessus passant devant ; « none » : réglages du profil seuls. Défaut : celui du fichier de styles",
    )
    private val exclude by option("--exclude", help = "Kill à écarter, « capture@instant » (ex. partie.mp4@3:12.4, voir --plan) ; répétable")
        .convert { killRef(it) }.multiple()
    private val force by option("--force", help = "Groupe à garder même si le plan l'écarte, désigné par un de ses kills (« capture@instant ») ; répétable")
        .convert { killRef(it) }.multiple()
    private val drop by option("--drop", help = "Groupe à poser sur la drop, désigné par un de ses kills (« capture@instant »)").convert { killRef(it) }
    private val pin by option("--pin", help = "Groupe dont le dernier kill tombe sur un instant du morceau : « capture@instant=0:42 » ; répétable")
        .convert { text ->
            val (kill, at) = text.substringBeforeLast('=') to text.substringAfterLast('=', "")
            val pin = MontagePin.parse(at) ?: throw BadParameterValue("instant du morceau invalide dans '$text' (attendu « capture@instant=0:42 »)")
            killRef(kill) to pin
        }.multiple()
    private val planOnly by option("--plan", help = "Affiche le plan sans rendre : la place de chaque kill et sa désignation pour --exclude, --force, --drop, --pin").flag()
    private val out by option("-o", "--out").path(canBeFile = false)
    private val thumbnail by thumbnailFlag()
    private val thumbnailStyle by thumbnailStyleOption()
    private val variants by option(
        "--variants",
        help = "Aperçus de N variantes du montage (défaut 3), en 360p et vite, dans <sortie>/apercus avec un fichier *_variantes.json ; " +
            "puis la même commande avec --pick <n> rend la variante choisie en pleine qualité",
    ).int().restrictTo(min = 1, max = 6)
    private val pick by option("--pick", help = "Rendre la variante n (1 = la mieux notée) proposée par --variants, en pleine qualité ; le choix est enregistré (*.choix.json)")
        .int().restrictTo(min = 1)
    private val variantsFile by option("--variants-file", help = "Fichier *_variantes.json des aperçus pour --pick (défaut : le plus récent pour ces sessions et cette musique)")
        .path(mustExist = true, canBeDir = false)

    override fun help(context: Context) =
        "Montage de tous les kills calé sur une musique : coupes sur le beat, chaque kill sur un temps, zoom, flash, ralenti, textes (--reactions pour mettre en avant voix et rires, --onetaps pour ne garder que les one taps). " +
            "--plan montre le plan, --exclude, --force, --drop et --pin le corrigent."

    private fun killRef(text: String): KillRef =
        runCatching { KillRef.parse(text) }.getOrElse { throw BadParameterValue("kill invalide '$text' (attendu « capture@instant », ex. partie.mp4@3:12.4)") }

    override fun run() {
        if (variants != null && pick != null) throw UsageError("--variants et --pick vont chacun dans leur commande : d'abord les aperçus, puis le choix")
        if (variantsFile != null && pick == null) throw UsageError("--variants-file ne sert qu'avec --pick")
        val progress = ConsoleProgress()
        val constraints = constraints()
        if (planOnly) {
            val planned = execute {
                val loaded = sessions.map { SessionStore.load(it) }
                try {
                    Pipelines.create(env.config).planKillMontage(loaded, music, montageOptions(constraints, defaultStyle()), ProgressTracker(listener = progress).root)
                } finally {
                    progress.finish()
                }
            }
            printPlan(planned, constraints)
            return
        }
        variants?.let { return previews(it, progress, constraints) }
        val result = execute {
            val loaded = sessions.map { SessionStore.load(it) }
            try {
                val pipeline = Pipelines.create(env.config)
                val picked = pick?.let { n ->
                    val file = variantsFile ?: pipeline.latestVariantsFile(loaded, music, out)
                    if (file == null) echo("  aucun fichier de variantes trouvé : variante $n recalculée sans vérifier qu'elle est celle de l'aperçu", err = true)
                    else echo("  variante $n de ${file.fileName}")
                    VariantPick(n, file)
                }
                pipeline.killMontage(
                    loaded,
                    music,
                    montageOptions(constraints, defaultStyle()).copy(pick = picked),
                    ProgressTracker(listener = progress).root,
                )
            } finally {
                progress.finish()
            }
        }
        printExport(result)
        val report = runCatching { readReport(result.report) }.getOrNull()
        // Musique choisie dans une bibliothèque : les autres essayées, pour voir de combien elle l'a emporté.
        report?.musicCandidates?.takeIf { it.isNotEmpty() }?.let { candidates ->
            echo("  musique choisie parmi ${candidates.size} :")
            candidates.forEachIndexed { i, c ->
                val name = Path.of(c.music).fileName
                val recent = if (c.recency > 0) ", récente -%.3f".format(c.recency) else ""
                echo(if (c.error != null) "    -  $name : aucun plan (${c.error})" else "    %s %.3f  %s (note %.3f, %d groupe(s)%s)".format(if (i == 0) ">" else " ", c.value, name, c.score, c.groups, recent))
            }
        }
        // La note du montage : ce que le moteur prétend faire, mesuré. « app score » compare deux rapports.
        report?.score?.let { s ->
            val parts = criteria(s).joinToString(", ") { (n, v) -> if (v == null) "$n -" else "%s %.2f".format(n, v) }
            echo("  note %.3f  ($parts)".format(s.total))
        }
        if (pick != null) echo("  choix enregistré : ${MontageChoiceRecord.fileFor(result.report)}")
    }

    /** Aperçus des variantes, leurs notes, et la commande pour rendre l'une d'elles. */
    private fun previews(count: Int, progress: ConsoleProgress, constraints: MontageConstraints) {
        val started = System.nanoTime()
        val result = execute {
            val loaded = sessions.map { SessionStore.load(it) }
            try {
                Pipelines.create(env.config).montagePreviews(loaded, music, montageOptions(constraints, defaultStyle()), count, ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        echo("${result.previews.size} aperçu(s) en %.0f s (%s) :".format((System.nanoTime() - started) / 1e9, result.variantsFile))
        if (result.previews.size < count) echo("  seulement ${result.previews.size} variante(s) assez différentes sur les $count demandées")
        result.previews.forEach { p ->
            val e = p.entry
            val distance = e.distance?.let { ", écart %.2f".format(it) } ?: ""
            echo("  %d. note %.3f  %s, %d clips, %d kills, %.1f s, musique dès %s%s".format(e.index, e.score.total, e.label, e.clips, e.kills, e.durationSeconds, e.musicStart, distance))
            echo("     ${p.video}")
        }
        result.notices.forEach { echo("  ! ${it.text()}") }
        echo("Rendre la variante choisie : la même commande avec --pick <n> à la place de --variants")
    }

    /** Plan lisible : chaque plan dans l'ordre, puis les groupes écartés, chaque kill avec sa désignation. */
    private fun printPlan(planned: PlannedMontage, constraints: MontageConstraints) {
        val plan = planned.plan
        val music = plan.music
        echo("Plan sur ${music.file.fileName} : ${plan.clips.size} plans, ${KillRef.clock(plan.duration)} depuis ${KillRef.clock(plan.musicStart)} du morceau")
        val placed = plan.clips.mapNotNull { c -> c.group.takeIf { !c.showcase } }
        plan.clips.zip(plan.clipOffsets()).forEachIndexed { i, (c, offset) ->
            if (c.showcase) return@forEachIndexed
            val marks = listOfNotNull("DROP".takeIf { c.slot.dropBeat != null }, "forcé".takeIf { c.group.forced }, "épinglé".takeIf { c.group.pin != null })
            echo("  %2d. %s  %d temps  %s%s".format(
                i + 1, KillRef.clock(offset), c.beats, c.group.kills.joinToString(" ") { KillRef.of(c.group.media, it).toString() },
                if (marks.isEmpty()) "" else "  [${marks.joinToString()}]",
            ))
        }
        val left = planned.candidates.filter { g -> placed.none { p -> p.media.path == g.media.path && p.kills.any { it in g.kills } } }
        if (left.isNotEmpty()) {
            echo("  écartés :")
            left.forEach { g ->
                val excluded = g.kills.all { k ->
                    constraints.excluded.any { r -> dev.highlights.montage.MontageEdits.designated(r, planned.candidates) == (g.media.path to k) }
                }
                echo("      %s%s".format(g.kills.joinToString(" ") { KillRef.of(g.media, it).toString() }, if (excluded) "  [exclu]" else ""))
            }
        }
    }

    /** Corrections du plan lues sur la ligne de commande (--exclude, --force, --pin, --drop). */
    internal fun constraints() = MontageConstraints(
        excluded = exclude.toSet(),
        forced = force.toSet(),
        pins = pin.toMap() + listOfNotNull(drop?.let { it to MontagePin.Drop }),
    )

    /** Style par défaut de `montage-styles.yaml` (lu dans la configuration : à part, pour que [montageOptions] reste pure). */
    private fun defaultStyle(): String? = env.config.loadMontageStyles().default

    /**
     * Options lues sur la ligne de commande (testées seules, sans rien lancer ni lire la configuration) ; [constraints] :
     * corrections du plan ; [defaultStyle] : style appliqué quand `--style` est absent (`--style none` n'en met aucun).
     */
    internal fun montageOptions(constraints: MontageConstraints = MontageConstraints(), defaultStyle: String? = null) = MontageOptions(
        formats = formats,
        platform = platform,
        outputDir = out,
        maxDuration = max,
        fitKills = if (fill) false else null,
        order = if (chronological) MontageOrder.CHRONOLOGICAL else null,
        hook = if (noHook) false else null,
        musicFromStart = if (fromStart) true else null,
        effectDensity = effects,
        zoom = if (noZoom) false else null,
        flash = if (noFlash) false else null,
        flashEveryCut = if (flashEveryCut) true else null,
        whip = if (noWhip) false else null,
        showcase = when {
            noShowcase -> false
            showcase -> true
            else -> null
        },
        whoosh = when {
            noWhoosh -> false
            whoosh -> true
            else -> null
        },
        whooshDir = whooshDir,
        boostColors = colors,
        motionBlur = if (noMotionBlur) false else null,
        matchCut = if (noMatchCut) false else null,
        rounds = if (noRounds) false else null,
        slowMotion = if (noSlowmo) false else null,
        speedRamp = if (noRamp) false else null,
        text = if (noText) false else null,
        killCounter = killCounter,
        audioBalance = balance,
        gameAudio = gameAudio,
        reactions = if (reactions) true else null,
        oneTaps = if (oneTaps) true else null,
        fails = if (fails) true else null,
        fast = if (fast) true else null,
        fastParts = if (noFastParts) false else null,
        style = style.let { if (it == "none") null else it ?: defaultStyle },
        constraints = constraints,
        thumbnail = thumbnailStyle ?: ThumbnailStyle.DEFAULT.takeIf { thumbnail },
    )
}

class MusicCommand : PipelineCommand("music") {
    private val file by argument("MUSIQUE", help = "Fichier audio (mp3, wav, flac…), ou dossier de musiques à analyser d'avance").path(mustExist = true)
    private val max by option("--max", help = "Affiche aussi la grille de coupes d'un montage de cette durée (ex. 60s), réglages du profil").convert { text ->
        Durations.parseOrNull(text) ?: throw BadParameterValue("durée invalide '$text'")
    }
    private val clips by option("--clips", help = "Nombre de clips disponibles pour la grille (défaut : illimité)").int().restrictTo(min = 1)
    private val profile by option("-p", "--profile", help = "Profil dont on prend les réglages de montage (défaut : default)")

    override fun help(context: Context) =
        "Analyse une musique comme le fait le montage kills : tempo, mesures, sections (intensité), drop et grille de coupes. " +
            "Un dossier : analyse (et garde) chaque musique pour que « montage --music <dossier> » n'ait plus qu'à choisir."

    override fun run() {
        if (file.isDirectory()) return library()
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

    /** Bibliothèque : chaque musique analysée (ou reprise de ce qui est gardé), une ligne par musique. */
    private fun library() {
        val progress = ConsoleProgress()
        val analyses = execute {
            try {
                Pipelines.create(env.config).loadMusicLibrary(file, ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        echo("$file : ${analyses.size} musique(s) exploitable(s) sur ${MusicLibrary.files(file).size}")
        analyses.forEach { a ->
            echo(
                "  %-40s %s  %6.1f BPM  drop à %s  %d sections".format(
                    a.file.fileName.toString().take(40), a.duration.toShortText(), a.bpm, a.beatTime(a.dropBeat).toTimecode(), a.sections.size,
                ),
            )
        }
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
        if (m.audio.isNotEmpty()) echo("  rôles     : ${tracks.explanation.text()}")
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
        if (outcome.reused) echo("Déjà analysée : analyse reprise (--reanalyze pour tout recalculer).")
        printHighlights(outcome.session.highlights, outcome.session.shownWarnings.map { it.text() })
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

internal fun PipelineCommand.printExport(result: ExportResult) {
    echo("Montage (${result.duration.toShortText()}, encodeur ${result.encoder}) :")
    result.videos.forEach { (format, path) -> echo("  ${format.label}  $path") }
    result.thumbnails.forEach { echo("  miniature  $it") }
    echo("  rapport ${result.report}")
    // Avertissements (montage presque vide…) : visibles même quand on ne lit que la fin de la sortie.
    result.notices.forEach { echo("  ! ${it.text()}") }
    // Montage kills : la question du ressenti, comme dans l'application.
    val music = result.music
    if (result.fastShots != null && music != null) {
        echo("  rythme rapide : ${result.fastShots} plan(s) (ressenti de la musique : ${result.musicFeel?.name?.lowercase() ?: "auto"})")
        echo("  trop rapide ou pas assez ? app musique-ressenti \"$music\" calm|fast")
    }
}

private val reportJson = Json { ignoreUnknownKeys = true }

internal fun readReport(file: Path): MontageReport =
    reportJson.decodeFromString(MontageReport.serializer(), file.readText())

/** Criteres d'une note, dans l'ordre d'affichage. Null = non mesurable sur ce montage. */
private fun criteria(s: MontageScore): List<Pair<String, Double?>> = listOf(
    "sync" to s.sync, "accent" to s.accent, "sobriete" to s.restraint, "variete" to s.variety,
    "duree" to s.fill, "rythme" to s.pacing, "source" to s.coverage,
    "ouverture" to s.opening, "trou" to s.lull, "action" to s.action,
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
