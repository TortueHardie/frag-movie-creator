package dev.highlights.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.pipeline.MontageOptions
import dev.highlights.pipeline.Pipelines
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Montage d'une soirée déjà analysée : ce que le dossier surveillé fait seul le lendemain matin, à la demande (et pour
 * l'essayer sans attendre la fin d'une soirée).
 */
class EveningCommand : PipelineCommand("soiree") {
    private val date by option("-d", "--date", help = "Soirée (AAAA-MM-JJ, jour où elle a commencé : une partie à 1 h du matin compte pour la veille) ; défaut : la plus récente")
        .convert { runCatching { LocalDate.parse(it) }.getOrElse { _ -> throw BadParameterValue("date invalide '$it' (AAAA-MM-JJ)") } }
    private val game by option("-g", "--game", help = "Jeu (identifiant de profil : wardogs, valorant…) quand la soirée en a plusieurs")
    private val music by option("-m", "--music", help = "Musique, ou dossier de musiques où le montage choisit la sienne").path(mustExist = true)
    private val list by option("--list", help = "Liste les soirées analysées et celles déjà montées, sans rien monter").flag()
    private val formats by option("-f", "--format", help = "Formats de sortie séparés par des virgules : source, 16:9, 9:16")
        .convert { OutputFormat.parse(it) ?: throw BadParameterValue("format inconnu '$it' (source, 16:9 ou 9:16)") }.split(",")
    private val platform by option("--platform", help = "Plateforme visée : tiktok, shorts, reels, youtube")
    private val out by option("-o", "--out").path(canBeFile = false)

    override fun help(context: Context) =
        "Montage kills d'une soirée entière déjà analysée (toutes ses parties, de 6 h à 6 h, un jeu à la fois). " +
            "--list montre les soirées et celles déjà montées (le dossier surveillé ne les remonte jamais)."

    override fun run() {
        val pipeline = Pipelines.create(env.config)
        val evenings = execute { pipeline.evenings() }.filter { game == null || it.profileId == game }
        val done = pipeline.eveningHistory.entries()
        if (list || evenings.isEmpty()) {
            if (evenings.isEmpty()) echo("Aucune soirée analysée${game?.let { " pour $it" } ?: ""}.")
            evenings.forEach { e ->
                val record = done.firstOrNull { it.date == e.date.toString() && it.profileId == e.profileId }
                val state = when {
                    record == null -> ""
                    record.error != null -> "  [échec : ${record.error}]"
                    else -> "  [montée : ${record.video}]"
                }
                echo("${e.date}  ${e.game}  ${e.sessionFiles.size} partie(s), ${e.kills} kills$state")
            }
            return
        }
        val evening = (if (date == null) evenings.firstOrNull() else evenings.firstOrNull { it.date == date })
            ?: throw BadParameterValue("aucune soirée analysée le $date${game?.let { " pour $it" } ?: ""} (voir --list)")
        val track = music ?: throw BadParameterValue("--music est nécessaire pour monter (une musique ou un dossier de musiques)")
        echo("Soirée du ${evening.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH))} — ${evening.game}, ${evening.sessionFiles.size} partie(s), ${evening.kills} kills")
        val progress = ConsoleProgress()
        val result = execute {
            try {
                pipeline.eveningMontage(evening, track, MontageOptions(formats = formats, platform = platform, outputDir = out), ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        printExport(result)
    }
}
