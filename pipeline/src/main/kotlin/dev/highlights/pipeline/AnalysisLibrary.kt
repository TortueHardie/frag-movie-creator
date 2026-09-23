package dev.highlights.pipeline

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.profile.DetectorConfig
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.profile.WindowSpec
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import dev.highlights.core.session.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/** Une capture déjà analysée : de quoi reconnaître le fichier, et la session qui garde l'analyse. */
@Serializable
data class LibraryEntry(
    val source: SerialPath,
    val sizeBytes: Long,
    /** Date de modification du fichier au moment de l'analyse (ms) : une capture réécrite est réanalysée. */
    val modifiedAtMillis: Long,
    val profileId: String,
    /** Empreinte des réglages d'analyse du profil (voir [AnalysisLibrary.fingerprint]). */
    val fingerprint: String,
    val sessionFile: SerialPath,
    val analyzedAt: SerialInstant,
    val duration: SerialDuration,
    val recordedAt: SerialInstant? = null,
    val events: Map<String, Int> = emptyMap(),
)

@Serializable
private data class LibraryIndex(val version: Int = 1, val entries: List<LibraryEntry> = emptyList())

/**
 * Mémoire des analyses déjà faites, partagée par l'application et la ligne de commande : rechoisir une capture déjà
 * analysée (même fichier, même profil, mêmes réglages d'analyse) reprend sa session au lieu de tout recalculer.
 * L'index est relu à chaque accès : plusieurs processus peuvent l'alimenter.
 */
class AnalysisLibrary(val file: Path) {
    private val lock = Any()

    /** Analyses dont la session existe encore, la plus récente d'abord. */
    fun entries(): List<LibraryEntry> = synchronized(lock) {
        load().filter { it.sessionFile.isRegularFile() }.sortedByDescending { it.analyzedAt }
    }

    /** Analyse réutilisable pour [source] tel qu'il est sur le disque, avec ce profil et ces réglages. */
    fun find(source: Path, profileId: String, fingerprint: String): LibraryEntry? = synchronized(lock) {
        val (size, modified) = stamp(source) ?: return null
        load().firstOrNull {
            it.source == key(source) && it.profileId == profileId && it.fingerprint == fingerprint &&
                it.sizeBytes == size && it.modifiedAtMillis == modified && it.sessionFile.isRegularFile()
        }
    }

    /** [source] a déjà été analysé dans son état actuel (quel que soit le profil). */
    fun contains(source: Path): Boolean = synchronized(lock) {
        val (size, modified) = stamp(source) ?: return false
        load().any { it.source == key(source) && it.sizeBytes == size && it.modifiedAtMillis == modified && it.sessionFile.isRegularFile() }
    }

    /** Enregistre (ou remplace) l'analyse d'une capture pour un profil. */
    fun record(entry: LibraryEntry) = synchronized(lock) {
        val normalized = entry.copy(source = key(entry.source))
        val kept = load().filterNot { it.source == normalized.source && it.profileId == normalized.profileId }
        runCatching { save(kept + normalized) }.onFailure { log.warn(it) { "Mémoire des analyses non enregistrée ($file)" } }
    }

    private fun load(): List<LibraryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(LibraryIndex.serializer(), file.readText().removePrefix("﻿")).entries
        } catch (e: Exception) {
            log.warn { "Mémoire des analyses illisible ($file), ignorée : ${e.message}" }
            emptyList()
        }
    }

    private fun save(entries: List<LibraryEntry>) {
        file.parent?.createDirectories()
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        tmp.writeText(json.encodeToString(LibraryIndex.serializer(), LibraryIndex(entries = entries)))
        tmp.moveTo(file, overwrite = true)
    }

    companion object {
        /** À augmenter quand un détecteur change de calcul : les analyses précédentes sont alors refaites. */
        const val ANALYSIS_VERSION = 1

        private val json = Json(SessionStore.json) { prettyPrint = true }
        // Sans les null : le sérialiseur YAML des paramètres (retirés ici) ne les accepte pas.
        private val compact = Json { encodeDefaults = true; explicitNulls = false }

        private fun key(path: Path): Path = path.toAbsolutePath().normalize()

        /** Taille et date de modification (ms) du fichier, null s'il est illisible. */
        fun stamp(path: Path): Pair<Long, Long>? =
            runCatching { path.fileSize() to path.getLastModifiedTime().toMillis() }.getOrNull()

        /**
         * Empreinte de ce qui décide de la timeline : fenêtres, pistes audio et détecteurs du profil. Le reste du profil
         * (sélection, montage, étalonnage) s'applique sans réanalyser et n'en fait pas partie.
         */
        fun fingerprint(profile: GameProfile): String {
            val canonical = compact.encodeToString(
                AnalysisInputs.serializer(),
                AnalysisInputs(
                    ANALYSIS_VERSION,
                    profile.window,
                    profile.audio,
                    profile.detectors.map { it.copy(params = null) },
                    profile.detectors.map { it.params?.let(::canonical) },
                ),
            )
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        /** Paramètres YAML sous une forme stable : sans les positions dans le fichier, qui bougent à chaque retouche. */
        private fun canonical(node: YamlNode): String = when (node) {
            is YamlScalar -> "\"${node.content}\""
            is YamlNull -> "null"
            is YamlList -> node.items.joinToString(",", "[", "]") { canonical(it) }
            is YamlMap -> node.entries.entries.sortedBy { it.key.content }
                .joinToString(",", "{", "}") { (k, v) -> "\"${k.content}\":${canonical(v)}" }
            is YamlTaggedNode -> "!${node.tag} ${canonical(node.innerNode)}"
            else -> node.toString()
        }
    }
}

@Serializable
private data class AnalysisInputs(
    val version: Int,
    val window: WindowSpec,
    val audio: AudioLayout,
    val detectors: List<DetectorConfig>,
    val params: List<String?>,
)

/** Entrée de la mémoire pour une analyse qui vient d'être faite. */
internal fun libraryEntry(outcome: AnalysisOutcome, fingerprint: String, stamp: Pair<Long, Long>): LibraryEntry {
    val session = outcome.session
    return LibraryEntry(
        source = session.media.path,
        sizeBytes = stamp.first,
        modifiedAtMillis = stamp.second,
        profileId = session.profileId,
        fingerprint = fingerprint,
        sessionFile = outcome.sessionFile.toAbsolutePath().normalize(),
        analyzedAt = Instant.now(),
        duration = session.media.duration,
        recordedAt = session.media.recordedAt,
        events = session.timeline.events.groupingBy { it.kind }.eachCount(),
    )
}
