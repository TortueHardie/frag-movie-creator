package dev.highlights.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.pipeline.Pipelines
import dev.highlights.pipeline.RecapOptions
import java.time.LocalDate

/** Récap d'une soirée déjà analysée : un intercalaire par partie, ses meilleurs moments, un écran de fin. */
class RecapCommand : PipelineCommand("recap") {
    private val sessions by argument("SESSION", help = "Parties à monter (.session.json), prises telles quelles comme une soirée ; sans : la soirée de --date")
        .path(mustExist = true, canBeDir = false).multiple()
    private val date by option("-d", "--date", help = "Soirée (AAAA-MM-JJ, jour où elle a commencé) ; défaut : la plus récente (voir « app soiree --list »)")
        .convert { runCatching { LocalDate.parse(it) }.getOrElse { _ -> throw BadParameterValue("date invalide '$it' (AAAA-MM-JJ)") } }
    private val game by option("-g", "--game", help = "Jeu (identifiant de profil : wardogs, valorant…) quand la soirée en a plusieurs")
    private val style by option("--style", help = "Moments de chaque partie : simple (bout à bout) ou story (façon YouTube) ; défaut : celui du profil")
        .choice("simple" to EditStyle.SIMPLE, "story" to EditStyle.STORY)
    private val formats by option("-f", "--format", help = "Formats de sortie séparés par des virgules : source, 16:9, 9:16")
        .convert { OutputFormat.parse(it) ?: throw BadParameterValue("format inconnu '$it' (source, 16:9 ou 9:16)") }.split(",")
    private val platform by option("--platform", help = "Plateforme visée : tiktok, shorts, reels, youtube (format, volume, zone sûre)")
    private val out by option("-o", "--out").path(canBeFile = false)

    override fun help(context: Context) =
        "Récap d'une soirée analysée, partie par partie : « Partie 3 – 14 kills » avant les meilleurs moments de chaque partie, puis le bilan de la soirée."

    override fun run() {
        val pipeline = Pipelines.create(env.config)
        val evening = if (sessions.isNotEmpty()) execute { pipeline.eveningOf(sessions) } else {
            val evenings = execute { pipeline.evenings() }.filter { game == null || it.profileId == game }
            (if (date == null) evenings.firstOrNull() else evenings.firstOrNull { it.date == date })
                ?: throw BadParameterValue("aucune soirée analysée${date?.let { " le $it" } ?: ""}${game?.let { " pour $it" } ?: ""} (voir « app soiree --list »)")
        }
        echo("Récap du ${evening.date} — ${evening.game}, ${evening.sessionFiles.size} partie(s), ${evening.kills} kills")
        val progress = ConsoleProgress()
        val result = execute {
            try {
                pipeline.eveningRecap(evening, RecapOptions(formats, style, platform, out), ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        printExport(result)
    }
}
