package dev.highlights.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.assistant.CueMethod
import dev.highlights.assistant.KillMark
import dev.highlights.assistant.NewGameAssistant
import dev.highlights.assistant.NewGameRequest
import dev.highlights.assistant.NewGameScan
import dev.highlights.core.model.CropRegion
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.serialization.Durations
import dev.highlights.pipeline.Pipelines
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `app nouveau-jeu VIDEO --game NOM --mark 1:32.5 --box x,y,l,h` : crée le profil d'un nouveau jeu à partir de 1 à 3
 * kills marqués (voir [NewGameAssistant]). Les planches de chaque méthode sont écrites dans le dossier de travail ;
 * l'utilisateur dit pour chacune quelles vignettes sont fausses. `--answers-from` répond à sa place d'après un fichier de
 * vérité (mesure de l'assistant, `verite/`).
 */
class NewGameCommand : PipelineCommand("nouveau-jeu") {
    private val video by argument("VIDEO", help = "Capture du jeu").path(mustExist = true, canBeDir = false)
    private val game by option("--game", help = "Nom du jeu (ex. « Counter-Strike 2 »)").required()
    private val marks by option(
        "--mark",
        help = "Kill marqué : instant où le repère apparaît (1:32.5, 92.5s…), suivi de @x,y,l,h pour son cadre (fractions de l'image) ; 1 à 3",
    ).multiple(required = true)
    private val box by option("--box", help = "Cadre du repère (x,y,largeur,hauteur en fractions de l'image) pour les --mark qui n'en ont pas")
    private val id by option("--id", help = "Identifiant du profil (défaut : tiré du nom du jeu)")
    private val answersFrom by option("--answers-from", help = "Fichier de vérité (verite/…) qui juge les vignettes à la place de l'utilisateur")
        .path(mustExist = true, canBeDir = false)
    private val work by option("--work", help = "Dossier des planches et de l'essai (défaut : <dossier de travail>/nouveau-jeu/<id>)").path(canBeFile = false)

    override fun help(context: Context) =
        "Crée le profil d'un nouveau jeu : marquez 1 à 3 kills (instant + cadre autour du repère : notification, ligne " +
            "du killfeed, compteur), l'assistant essaie modèle d'image, ligne de couleur, compteur et texte sur toute la " +
            "vidéo, montre une planche par méthode, puis écrit config/profiles/<jeu>.yaml (jamais par-dessus un profil existant)."

    override fun run() {
        if (marks.size > 3) {
            echo("Au plus 3 kills marqués.", err = true)
            throw ProgramResult(2)
        }
        val request = NewGameRequest(video, game, marks.map(::parseMark), id)
        val dir = (work ?: env.config.workDir.resolve("nouveau-jeu").resolve(request.profileId)).toAbsolutePath().normalize().createDirectories()
        val assistant = NewGameAssistant(env.config, Pipelines.ffmpeg(env.config))
        val progress = ConsoleProgress()
        val scan = execute {
            try {
                assistant.scan(request, dir, ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        val verdicts = scan.results.associate { r -> r.candidate.method to verdicts(scan, r.candidate.method) }
        echo("")
        echo("Méthode        kills  morts  marqués  vignettes vraies")
        for (r in scan.results) {
            val v = verdicts[r.candidate.method].orEmpty()
            echo(
                "%-13s %6d %6d %5d/%d %9s   %s".format(
                    r.candidate.method.name.lowercase(), r.kills.size, r.hits.count { it.kind == "death" }, r.marksFound, request.marks.size,
                    if (v.isEmpty()) "-" else "${v.count { it }}/${v.size}", r.candidate.detail,
                ),
            )
        }
        scan.skipped.forEach { (m, why) -> echo("%-13s écartée : %s".format(m.name.lowercase(), why.text())) }
        val choice = assistant.choose(scan, verdicts)
        if (choice == null) {
            echo("Aucune méthode ne retrouve les kills marqués : profil non écrit. Planches dans $dir", err = true)
            throw ProgramResult(1)
        }
        val file = execute { assistant.write(scan, choice) }
        echo("")
        echo("Méthode retenue : ${choice.result.candidate.method.name.lowercase()} (${choice.result.candidate.detectorType}), " +
            "${choice.result.kills.size} kills, précision estimée ${(choice.precision * 100).toInt()} %")
        echo("Profil écrit : $file")
    }

    /** Vignettes vraies ou fausses : d'après la vérité si --answers-from, sinon demandé à l'utilisateur. */
    private fun verdicts(scan: NewGameScan, method: CueMethod): List<Boolean> {
        val r = scan.results.first { it.candidate.method == method }
        if (r.sample.isEmpty()) return emptyList()
        answersFrom?.let { file ->
            val truth = GroundTruth.load(file)
            return r.sample.map { hit -> truth.events.any { it.kind == hit.kind && (it.at - hit.at).absoluteValue <= truth.tolerance } }
        }
        echo("")
        echo("${method.name.lowercase()} : ${r.kills.size} kills. Planche : ${r.sheet}")
        echo("Numéros des vignettes qui SONT des kills (ex. 1,3,4), vide = aucune, « * » = toutes :")
        val answer = readlnOrNull()?.trim().orEmpty()
        if (answer == "*") return r.sample.map { true }
        val good = answer.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.toSet()
        return r.sample.indices.map { (it + 1) in good }
    }

    private fun parseMark(text: String): KillMark {
        val (time, frame) = text.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
        val at = parseTime(time) ?: throw com.github.ajalt.clikt.core.UsageError("instant illisible : $time")
        val b = (frame ?: box) ?: throw com.github.ajalt.clikt.core.UsageError("cadre du repère manquant (--box ou --mark t@x,y,l,h)")
        val v = b.split(',').map { it.trim().toDouble() }
        if (v.size != 4) throw com.github.ajalt.clikt.core.UsageError("cadre attendu : x,y,largeur,hauteur")
        return KillMark(at, CropRegion(v[0], v[1], v[2], v[3]))
    }

    companion object {
        /** « 1:32.5 », « 01:02:03 », ou une durée (« 92.5s », « 1m32s »). */
        fun parseTime(text: String): Duration? {
            val parts = text.trim().split(':')
            if (parts.size in 2..3 && parts.all { it.toDoubleOrNull() != null }) {
                val seconds = parts.fold(0.0) { acc, p -> acc * 60 + p.toDouble() }
                return (seconds * 1000).toLong().milliseconds
            }
            return text.trim().toDoubleOrNull()?.let { (it * 1000).toLong().milliseconds } ?: Durations.parseOrNull(text)
        }
    }
}
