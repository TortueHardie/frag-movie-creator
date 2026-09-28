package dev.highlights.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.core.config.YouTubePrivacy
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.pipeline.Pipelines
import dev.highlights.pipeline.YouTubePublisher
import dev.highlights.publish.YouTubeMetadata
import dev.highlights.publish.YouTubeText
import java.awt.Desktop
import java.net.URI
import java.time.ZoneId
import kotlin.io.path.readText

class PublishCommand : PipelineCommand("publish") {
    private val video by argument("VIDEO", help = "Vidéo exportée (son rapport JSON, dans le même dossier, fournit titre et description)")
        .path(mustExist = true, canBeDir = false).optional()
    private val title by option("--title", help = "Titre (défaut : écrit d'après la vidéo, ou publish.youtube.titleTemplate)")
    private val description by option("--description", help = "Description (défaut : écrite d'après la vidéo)")
    private val descriptionFile by option("--description-file", help = "Description lue dans un fichier texte").path(mustExist = true, canBeDir = false)
    private val tags by option("--tags", help = "Tags séparés par des virgules (défaut : jeu + publish.youtube.tags)").split(",")
    private val privacy by option("--privacy", help = "private (défaut), unlisted ou public")
        .choice("private" to YouTubePrivacy.PRIVATE, "unlisted" to YouTubePrivacy.UNLISTED, "public" to YouTubePrivacy.PUBLIC)
    private val category by option("--category", help = "Catégorie YouTube (20 = Jeux vidéo)")
    private val language by option("--language", help = "Langue du titre et de la voix (ex. fr, en)")
    private val kids by option("--kids", help = "Vidéo conçue pour les enfants (déclaration obligatoire)").flag()
    private val noNotify by option("--no-notify", help = "Ne pas prévenir les abonnés (vidéo publique)").flag()
    private val publishAt by option("--publish-at", help = "Mise en ligne programmée, heure locale : 2026-10-01T18:00 (la vidéo reste privée d'ici là)")
        .convert { YouTubeText.parseSchedule(it) ?: throw BadParameterValue("heure invalide '$it' (ex. 2026-10-01T18:00)") }
    private val dryRun by option("--dry-run", help = "Affiche ce qui serait envoyé, sans rien envoyer").flag()
    private val open by option("--open", help = "Envoi manuel : ouvre la page d'envoi de YouTube et montre la vidéo dans l'Explorateur").flag()
    private val login by option("--login", help = "Connecte (ou reconnecte) le compte YouTube dans le navigateur").flag()
    private val logout by option("--logout", help = "Oublie le compte YouTube connecté").flag()

    override fun help(context: Context) =
        "Prépare la publication d'une vidéo exportée sur YouTube : titre, description et tags écrits d'après la vidéo, chacun " +
            "modifiable, à coller dans la page d'envoi (--open l'ouvre). Avec publish.youtube.api: true, envoi direct (privé par défaut)."

    override fun run() {
        val youtube = Pipelines.create(env.config).youtube(::browse)
        if ((login || logout) && !youtube.apiEnabled) throw UsageError("Envoi par l'API YouTube désactivé : publish.youtube.api: true dans app.yaml pour l'activer")
        if (logout) {
            execute { youtube.logout() }
            echo("Compte YouTube oublié.")
            if (video == null && !login) return
        }
        if (login) {
            execute { youtube.login() }
            echo("Compte YouTube connecté.")
            if (video == null) return
        }
        val file = video ?: throw UsageError("Indique la vidéo à envoyer (ou --login / --logout)")
        val suggested = execute { youtube.suggest(file) }
        val metadata = YouTubeMetadata(
            title = title ?: suggested.title,
            description = descriptionFile?.readText()?.trim() ?: description ?: suggested.description,
            tags = tags?.map { it.trim() }?.filter { it.isNotEmpty() } ?: suggested.tags,
            privacy = privacy ?: if (publishAt != null) YouTubePrivacy.PRIVATE else suggested.privacy,
            categoryId = category ?: suggested.categoryId,
            madeForKids = kids || suggested.madeForKids,
            language = language ?: suggested.language,
            publishAt = publishAt,
            notifySubscribers = !noNotify && suggested.notifySubscribers,
        )
        echo("Titre       : ${metadata.title}")
        echo("Description :")
        metadata.description.lines().forEach { echo("  $it") }
        echo("Tags        : ${metadata.tags.joinToString(", ")}")
        // Envoi manuel : visibilité, catégorie et langue se choisissent dans la page de YouTube.
        if (youtube.apiEnabled) {
            echo("Visibilité  : ${metadata.privacy.label}${metadata.publishAt?.let { " (mise en ligne le ${it.atZone(ZoneId.systemDefault()).toLocalDateTime()})" } ?: ""}")
            echo("Catégorie ${metadata.categoryId}, langue ${metadata.language}${if (metadata.madeForKids) ", conçue pour les enfants" else ""}")
        }
        metadata.problems().takeIf { it.isNotEmpty() }?.let { problems ->
            problems.forEach { echo("  ! $it", err = true) }
            throw UsageError("YouTube refuserait cet envoi")
        }
        if (dryRun) return
        if (!youtube.apiEnabled) {
            echo("")
            echo("Envoi manuel : glisse la vidéo dans ${YouTubePublisher.UPLOAD_PAGE} puis colle ces textes${if (open) "." else " (--open ouvre la page)."}")
            if (open) {
                browse(YouTubePublisher.UPLOAD_PAGE, quiet = true)
                runCatching { ProcessBuilder("explorer.exe", "/select,${file.toAbsolutePath()}").start() }
            }
            return
        }
        val progress = ConsoleProgress()
        val uploaded = execute {
            try {
                youtube.upload(file, metadata, ProgressTracker(listener = progress).root)
            } finally {
                progress.finish()
            }
        }
        echo("En ligne : ${uploaded.url}")
        echo("Studio   : ${uploaded.studio}")
    }

    /** Page de connexion de Google : dans le navigateur, et affichée au cas où il ne s'ouvrirait pas. */
    private fun browse(url: URI) = browse(url, quiet = false)

    private fun browse(url: URI, quiet: Boolean) {
        if (!quiet) {
            echo("Connexion à YouTube dans le navigateur. S'il ne s'ouvre pas, ouvre cette adresse :", err = true)
            echo("  $url", err = true)
        }
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(url) }
    }
}
