package dev.highlights.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.montage.ThumbnailStyle
import dev.highlights.pipeline.Pipelines

/**
 * `app thumbnail VIDEO…` : miniature YouTube d'une vidéo déjà exportée, d'après le rapport JSON écrit avec elle. Le
 * kill le plus fort, à l'instant le plus net, tiré de la capture d'origine (qui doit encore être là où elle était).
 */
class ThumbnailCommand : PipelineCommand("thumbnail") {
    private val videos by argument("VIDEO", help = "Vidéos exportées (highlights ou montage kills), leur rapport JSON à côté")
        .path(mustExist = true, canBeDir = false).multiple(required = true)
    private val style by thumbnailStyleOption()

    override fun help(context: Context) =
        "Miniature YouTube (PNG 1280x720, 1080x1920 pour une vidéo verticale) d'une vidéo exportée : le kill le plus fort, " +
            "punch-in, couleurs et texte (ACE, TRIPLÉ, 12 KILLS…), écrite à côté de la vidéo sans rien écraser."

    override fun run() {
        val written = execute {
            val pipeline = Pipelines.create(env.config)
            videos.map { pipeline.miniature(it, chosenStyle()) }
        }
        written.forEach { echo("  miniature  $it") }
    }

    /** Habillage demandé, sobre par défaut. */
    internal fun chosenStyle(): ThumbnailStyle = style ?: ThumbnailStyle.SOBER
}
