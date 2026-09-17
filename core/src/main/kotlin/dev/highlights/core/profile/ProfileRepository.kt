package dev.highlights.core.profile

import dev.highlights.core.ConfigException
import dev.highlights.core.config.ConfigYaml
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

private val log = KotlinLogging.logger {}

class ProfileRepository(
    profiles: List<GameProfile>,
    /** Fichier YAML de chaque profil, quand il vient du disque. */
    private val files: Map<String, Path> = emptyMap(),
) {
    private val byId: Map<String, GameProfile> = profiles.associateBy { it.id }

    fun fileOf(id: String): Path? = files[id]

    fun all(): List<GameProfile> = byId.values.sortedBy { it.id }

    fun byId(id: String): GameProfile = byId[id]
        ?: throw ConfigException("Profil inconnu '$id'. Disponibles : ${byId.keys.sorted().joinToString()}")

    /** Profil forcé, sinon celui dont un motif apparaît dans le chemin du fichier, sinon "default". */
    fun resolve(file: Path, forcedId: String? = null): GameProfile {
        if (forcedId != null) return byId(forcedId)
        val haystack = file.toAbsolutePath().normalize().toString().lowercase()
        return byId.values
            .filter { p -> p.match.pathContains.any { haystack.contains(it.lowercase()) } }
            .maxByOrNull { it.match.priority }
            ?: byId[DEFAULT_ID]
            ?: throw ConfigException("Aucun profil ne correspond à $file et le profil '$DEFAULT_ID' est absent")
    }

    companion object {
        const val DEFAULT_ID = "default"

        fun loadDirectory(dir: Path): ProfileRepository {
            if (!dir.isDirectory()) throw ConfigException("Dossier de profils introuvable : $dir")
            val loaded = dir.listDirectoryEntries()
                .filter { it.extension.lowercase() in setOf("yaml", "yml") }
                .sorted()
                .map { it to ConfigYaml.decodeFile(it, GameProfile.serializer()) }
            val profiles = loaded.map { it.second }
            val duplicates = profiles.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (duplicates.isNotEmpty()) throw ConfigException("Profils en double dans $dir : $duplicates")
            log.info { "Profils chargés depuis $dir : ${profiles.joinToString { it.id }}" }
            return ProfileRepository(profiles, loaded.associate { (file, p) -> p.id to file })
        }
    }
}
