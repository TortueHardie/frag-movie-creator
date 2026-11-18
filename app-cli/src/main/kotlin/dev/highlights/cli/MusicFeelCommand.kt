package dev.highlights.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.core.model.MusicFeel
import dev.highlights.montage.MusicFeatures
import dev.highlights.montage.MusicFeelLearner
import dev.highlights.pipeline.Pipelines

/**
 * `app musique-ressenti` : ressenti des musiques pour le rythme rapide. `app musique-ressenti MUSIQUE fast|calm|auto`
 * le dit pour une musique (auto l'efface) ; sans argument, la liste des ressentis gardés et ce que le modèle en a appris.
 */
class MusicFeelCommand : PipelineCommand("musique-ressenti") {
    private val music by argument("MUSIQUE", help = "Fichier de la musique").path(mustExist = true, canBeDir = false).optional()
    private val feel by argument("RESSENTI", help = "fast : rapide après la drop ; calm : jamais rapide ; auto : la règle par défaut")
        .choice("fast" to MusicFeel.FAST, "calm" to MusicFeel.CALM, "auto" to MusicFeel.AUTO).optional()

    override fun help(context: Context) =
        "Ressenti d'une musique pour le rythme rapide des montages kills : fast (rapide dès la drop), calm (jamais rapide), " +
            "auto (la règle par défaut). Sans argument : les ressentis gardés et le modèle appris de leurs mesures " +
            "(actif à partir de ${MusicFeelLearner.MIN_SONGS} musiques, ${MusicFeelLearner.MIN_PER_KIND} de chaque sorte, " +
            "et ${(MusicFeelLearner.MIN_ACCURACY * 100).toInt()} % de bonnes prédictions en validation croisée)."

    override fun run() {
        val pipeline = Pipelines.create(env.config)
        val file = music
        if (file != null) {
            val wanted = feel ?: run {
                echo("${file.fileName} : ${pipeline.musicFeelStore.feelOf(file).name.lowercase()}")
                return
            }
            val entry = execute { pipeline.setMusicFeel(file, wanted) }
            echo(
                if (entry == null) "${file.fileName} : ressenti effacé (règle par défaut)."
                else "${file.fileName} : ${entry.feel.name.lowercase()}" + (if (entry.features == null) " (mesures indisponibles : n'apprend rien)" else ""),
            )
            return
        }
        val state = pipeline.musicFeels()
        echo("Ressentis : ${state.file}")
        if (state.songs.isEmpty()) {
            echo("  aucun. Après un montage, répondez à la question sur le rythme rapide, ou : app musique-ressenti MUSIQUE fast|calm")
            return
        }
        state.songs.sortedBy { it.name.lowercase() }.forEach { s ->
            echo("  %-6s %s%s".format(s.feel.name.lowercase(), s.name, s.vote?.let { " (réponse : ${it.name.lowercase()})" } ?: ""))
        }
        val l = state.learned
        echo("")
        echo(
            "Modèle : ${l.songs} musique(s) mesurée(s) dont ${l.fast} rapide(s) ; validation croisée : " +
                (l.accuracy?.let { "%.0f %% bien classées".format(it * 100) } ?: "impossible (il faut les deux sortes)") + " ; " +
                (if (l.active) "prédictions utilisées pour les musiques sans ressenti" else "règle par défaut pour les musiques sans ressenti"),
        )
        if (l.songs > 0) {
            echo("Poids (sur mesures centrées réduites ; positif = plus c'est haut, plus c'est rapide) :")
            MusicFeatures.NAMES.zip(l.weights).sortedByDescending { kotlin.math.abs(it.second) }.forEach { (name, w) -> echo("  %-11s %+.3f".format(name, w)) }
        }
    }
}
