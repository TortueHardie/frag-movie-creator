package dev.highlights.core.session

import dev.highlights.core.i18n.Message
import dev.highlights.core.InputException
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.serialization.SerialInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Résultat d'analyse persisté : permet de décocher des segments puis de relancer uniquement l'export.
 */
@Serializable
data class Session(
    val version: Int = CURRENT_VERSION,
    val createdAt: SerialInstant,
    val media: MediaInfo,
    val profileId: String,
    val timeline: ScoredTimeline,
    val highlights: List<Highlight>,
    /** Avertissements en anglais, pour la ligne de commande et les versions d'avant les traductions. */
    val warnings: List<String> = emptyList(),
    /** Les mêmes, traduisibles (clé + arguments) : l'interface les montre dans sa langue. Vide dans les anciennes sessions. */
    val notices: List<Message> = emptyList(),
) {
    /** Avertissements à montrer : traduits si la session les a, sinon le texte d'origine. */
    val shownWarnings: List<Message> get() = notices.ifEmpty { warnings.map(Message::raw) }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

object SessionStore {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun save(session: Session, file: Path) {
        file.parent?.createDirectories()
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        tmp.writeText(json.encodeToString(Session.serializer(), session))
        dev.highlights.core.io.AtomicFiles.replace(tmp, file)
    }

    fun load(file: Path): Session {
        if (!file.isRegularFile()) throw InputException(Message("error.session.notFound", "file" to file))
        // Une vidéo passée à la place de sa session : la lire en texte épuisait la mémoire (pile d'erreur, pas de message).
        if (!file.extension.equals("json", ignoreCase = true)) {
            throw InputException(Message("error.session.notSession", "file" to file))
        }
        val session = try {
            // BOM toléré : PowerShell 5.1 et certains éditeurs Windows en ajoutent un à l'enregistrement.
            json.decodeFromString(Session.serializer(), file.readText().removePrefix("﻿"))
        } catch (e: Exception) {
            throw InputException(Message("error.session.unreadable", "file" to file, "detail" to e.message), e)
        }
        if (session.version > Session.CURRENT_VERSION) {
            throw InputException(Message("error.session.version", "file" to file, "version" to session.version, "supported" to Session.CURRENT_VERSION))
        }
        return session
    }
}
