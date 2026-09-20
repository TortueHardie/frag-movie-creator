package dev.highlights.core.session

import dev.highlights.core.InputException
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.serialization.SerialInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories
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
    val warnings: List<String> = emptyList(),
) {
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
        tmp.moveTo(file, overwrite = true)
    }

    fun load(file: Path): Session {
        if (!file.isRegularFile()) throw InputException("Session introuvable : $file")
        val session = try {
            // BOM toléré : PowerShell 5.1 et certains éditeurs Windows en ajoutent un à l'enregistrement.
            json.decodeFromString(Session.serializer(), file.readText().removePrefix("﻿"))
        } catch (e: Exception) {
            throw InputException("Session illisible ($file) : ${e.message}", e)
        }
        if (session.version > Session.CURRENT_VERSION) {
            throw InputException("Session $file en version ${session.version}, version supportée ≤ ${Session.CURRENT_VERSION}")
        }
        return session
    }
}
