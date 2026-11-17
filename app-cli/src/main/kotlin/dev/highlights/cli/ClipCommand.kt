package dev.highlights.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.session.SessionStore
import dev.highlights.pipeline.ClipOptions
import dev.highlights.pipeline.Pipelines

/** `app clip SESSION [--moment h003] [--platform tiktok]` : un moment en vidéo 9:16 de 15 à 30 s (voir [ClipOptions]). */
class ClipCommand : PipelineCommand("clip") {
    private val sessionFile by argument("SESSION", help = "Fichier .session.json analysé").path(mustExist = true, canBeDir = false)
    private val moment by option("--moment", help = "Moment à monter (h003…, voir la commande export ou l'application) ; défaut : le meilleur de la session")
    private val platform by option(
        "--platform",
        help = "Plateforme visée : tiktok, shorts, reels (ou définie dans app.yaml) — zone sûre, volume, débit, et sa durée maximale plafonne le clip",
    )
    private val out by option("-o", "--out").path(canBeFile = false)

    override fun help(context: Context) =
        "Clip « meilleur moment » : un seul moment en vidéo verticale 9:16 de 15 à 30 s (accroche, jump cuts, punch-in, sous-titres), sans musique imposée."

    override fun run() {
        val progress = ConsoleProgress()
        val result = execute {
            val session = SessionStore.load(sessionFile)
            try {
                Pipelines.create(env.config).clip(session, clipOptions(), ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        printExport(result)
    }

    /** Options lues sur la ligne de commande, sans rien lancer (testées seules). */
    internal fun clipOptions() = ClipOptions(moment = moment, platform = platform, outputDir = out)
}
