package dev.highlights.ui

import dev.highlights.pipeline.HighlightPipeline
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.streams.asSequence

private val log = KotlinLogging.logger {}

/** Enregistreurs que l'écran de premier lancement sait trouver, dans l'ordre où il les montre. */
enum class RecorderKind(val displayName: String, val site: String) {
    OUTPLAYED("Outplayed", "https://www.overwolf.com/app/overwolf-outplayed"),
    OBS("OBS Studio", "https://obsproject.com/"),
    SHADOWPLAY("NVIDIA ShadowPlay", "https://www.nvidia.com/software/nvidia-app/"),
    FTHR("FTHR Clips", "https://github.com/FTHR-Community/FTHR-Clips"),
    MEDAL("Medal", "https://medal.tv/"),
}

/**
 * Un enregistreur installé. [folders] : dossiers où il range ses vidéos (le premier est celui à surveiller), lus dans
 * ses propres réglages, sinon son dossier par défaut. [separateMic] : micro sur une piste à part (true), mélangé au jeu
 * (false), ou réglage illisible (null) ; c'est ce qui permet les montages de réactions et les sous-titres sans le jeu.
 */
data class DetectedRecorder(val kind: RecorderKind, val folders: List<Path>, val separateMic: Boolean? = null, val fromSettings: Boolean = true) {
    val folder: Path? get() = folders.firstOrNull()
}

/**
 * Où chercher sur la machine : dossier personnel, `%APPDATA%`, `%LOCALAPPDATA%`, programmes, registre. Remplaçable en
 * test, et par `-Dhighlights.recorders.root=<dossier>` (une fausse machine pour piloter l'application sans dépendre des
 * enregistreurs installés : `home`, `AppData/Roaming`, `AppData/Local`, `Program Files` dedans, registre vide).
 */
class RecorderEnvironment(
    val home: Path,
    val appData: Path,
    val localAppData: Path,
    val programFiles: List<Path>,
    /** Racine du disque système, où Medal crée son dossier par défaut (`C:\Medal`). */
    val systemDrive: Path,
    /** Valeur brute d'une clé du registre de l'utilisateur, null si absente. Lecture seule. */
    val registry: (key: String, value: String) -> RegistryValue? = { _, _ -> null },
) {
    companion object {
        fun forThisMachine(): RecorderEnvironment {
            System.getProperty("highlights.recorders.root")?.let { return fake(Path.of(it)) }
            // USERPROFILE et pas user.home : l'application pilotée isole user.home (-PuserHome) mais lit les vrais
            // réglages des enregistreurs, sans jamais y écrire.
            val home = Path.of(System.getenv("USERPROFILE") ?: System.getProperty("user.home"))
            return RecorderEnvironment(
                home = home,
                appData = System.getenv("APPDATA")?.let(Path::of) ?: home.resolve("AppData/Roaming"),
                localAppData = System.getenv("LOCALAPPDATA")?.let(Path::of) ?: home.resolve("AppData/Local"),
                programFiles = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")).map(Path::of),
                systemDrive = Path.of((System.getenv("SystemDrive") ?: "C:") + "\\"),
                registry = WindowsRegistry::read,
            )
        }

        fun fake(root: Path) = RecorderEnvironment(
            home = root.resolve("home"),
            appData = root.resolve("AppData/Roaming"),
            localAppData = root.resolve("AppData/Local"),
            programFiles = listOf(root.resolve("Program Files")),
            systemDrive = root,
        )
    }
}

/** Valeur du registre telle que `reg query` la donne : texte (REG_SZ…) ou octets (REG_BINARY). */
sealed interface RegistryValue {
    data class Text(val text: String) : RegistryValue
    class Binary(val bytes: ByteArray) : RegistryValue
}

/** Lecture du registre de l'utilisateur par `reg.exe query` : jamais d'écriture, 5 s au plus. */
internal object WindowsRegistry {
    fun read(key: String, value: String): RegistryValue? = runCatching {
        val process = ProcessBuilder("reg", "query", key, "/v", value).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charset.defaultCharset())
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        parse(output, value)
    }.getOrNull()

    /** Ligne « `    Nom    REG_TYPE    donnée` » de la sortie de `reg query`. */
    fun parse(output: String, value: String): RegistryValue? {
        val line = output.lines().map { it.trim() }.firstOrNull { it.startsWith("$value ") || it.startsWith("$value\t") } ?: return null
        val parts = line.split(Regex("\\s{2,}|\\t")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val data = parts.drop(2).joinToString(" ")
        return if (parts[1] == "REG_BINARY") {
            RegistryValue.Binary(data.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray())
        } else {
            RegistryValue.Text(data)
        }
    }
}

/**
 * Trouve les enregistreurs installés et leurs dossiers de vidéos en lisant leurs réglages, sans rien y changer.
 * Chaque lecture est protégée : un fichier de réglages illisible donne le dossier par défaut, jamais une erreur.
 */
class RecorderDetector(private val env: RecorderEnvironment = RecorderEnvironment.forThisMachine()) {

    fun detect(): List<DetectedRecorder> = RecorderKind.entries.mapNotNull { kind ->
        runCatching {
            when (kind) {
                RecorderKind.OUTPLAYED -> outplayed()
                RecorderKind.OBS -> obs()
                RecorderKind.SHADOWPLAY -> shadowPlay()
                RecorderKind.FTHR -> fthr()
                RecorderKind.MEDAL -> medal()
            }
        }.onFailure { log.warn(it) { "Détection de ${kind.displayName} impossible" } }.getOrNull()
    }

    private val videos: Path get() = env.home.resolve("Videos")

    /**
     * Outplayed (application Overwolf, identifiant d'extension fixe). Il range chaque jeu dans `<captures>/Outplayed/<jeu>`,
     * `<captures>` = « Folders_VideoCapturesFolder » des réglages d'Overwolf (`Settings/SettingsPageGeneral.xml`, la valeur
     * précède le nom), `Vidéos\Overwolf` par défaut.
     */
    internal fun outplayed(): DetectedRecorder? {
        val overwolf = env.localAppData.resolve("Overwolf")
        if (!overwolf.resolve("Extensions").resolve(OUTPLAYED_ID).isDirectory()) return null
        val settings = overwolf.resolve("Settings/SettingsPageGeneral.xml")
        val captures = runCatching {
            Regex("<Value[^>]*>([^<]+)</Value>\\s*<Name>Folders_VideoCapturesFolder</Name>").find(settings.readText())?.groupValues?.get(1)?.let(::xmlText)
        }.getOrNull()?.let(Path::of)
        val root = captures ?: videos.resolve("Overwolf")
        return DetectedRecorder(RecorderKind.OUTPLAYED, listOf(root.resolve("Outplayed")), fromSettings = captures != null)
    }

    /**
     * OBS Studio : profil courant dans `user.ini` (OBS 31+) ou `global.ini` (`[Basic] ProfileDir`), dossier des profils
     * déplaçable (`[Locations] Profiles`). Dans le `basic.ini` du profil : mode Simple (`[SimpleOutput] FilePath`) ou
     * Avancé (`[AdvOut] RecFilePath`, `FFFilePath` en sortie FFmpeg) ; pistes enregistrées = bits de `RecTracks`.
     * Chemins écrits avec des `\\` doublés.
     */
    internal fun obs(): DetectedRecorder? {
        val dir = env.appData.resolve("obs-studio")
        if (!dir.isDirectory()) return null
        val user = Ini.read(dir.resolve("user.ini"))
        val global = Ini.read(dir.resolve("global.ini"))
        val profileDir = user["Basic", "ProfileDir"] ?: global["Basic", "ProfileDir"]
        val profilesRoot = (user["Locations", "Profiles"] ?: global["Locations", "Profiles"])
            ?.let { Path.of(it).resolve("obs-studio") } ?: dir
        val profiles = profilesRoot.resolve("basic/profiles")
        val profile = profileDir?.let { profiles.resolve(it) }?.takeIf { it.isDirectory() }
            ?: runCatching { profiles.listDirectoryEntries().firstOrNull { it.isDirectory() } }.getOrNull()
        val basic = profile?.let { Ini.read(it.resolve("basic.ini")) } ?: Ini.EMPTY
        val advanced = basic["Output", "Mode"].equals("Advanced", ignoreCase = true)
        val folder = if (advanced) {
            if (basic["AdvOut", "RecType"].equals("FFmpeg", ignoreCase = true)) basic["AdvOut", "FFFilePath"] else basic["AdvOut", "RecFilePath"]
        } else {
            basic["SimpleOutput", "FilePath"]
        }
        // Mode Simple : une seule piste, sauf les OBS récents qui y acceptent aussi plusieurs pistes.
        val tracks = basic[if (advanced) "AdvOut" else "SimpleOutput", "RecTracks"]?.toIntOrNull()?.let(Integer::bitCount)
        val separate = when {
            tracks != null -> tracks >= 2
            profile != null -> false
            else -> null
        }
        return DetectedRecorder(RecorderKind.OBS, listOf(folder?.let(Path::of) ?: videos), separate, fromSettings = folder != null)
    }

    /**
     * NVIDIA ShadowPlay (application NVIDIA ou GeForce Experience) : dossier dans la valeur binaire `DefaultPathW`
     * (UTF-16, terminée par un zéro) de `HKCU\Software\NVIDIA Corporation\Global\ShadowPlay\NVSPCAPS`, `Vidéos` par
     * défaut (un sous-dossier par jeu). La séparation des pistes n'est pas lisible : conseil seulement.
     */
    internal fun shadowPlay(): DetectedRecorder? {
        val raw = env.registry(NVIDIA_KEY, "DefaultPathW")
        val installed = raw != null || env.programFiles.any { pf ->
            NVIDIA_APPS.any { pf.resolve("NVIDIA Corporation").resolve(it).isDirectory() }
        }
        if (!installed) return null
        val folder = when (raw) {
            is RegistryValue.Binary -> String(raw.bytes, Charsets.UTF_16LE).substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
            is RegistryValue.Text -> raw.text.trim().takeIf { it.isNotEmpty() }
            null -> null
        }
        return DetectedRecorder(RecorderKind.SHADOWPLAY, listOf(folder?.let(Path::of) ?: videos), fromSettings = folder != null)
    }

    /**
     * FTHR Clips (libre) : `~/.fthr/settings.json` (FTHR_UI/core/settings_manager.py), clés `clips_directory`
     * (`~/FTHR_Clips` par défaut) et `recording_directory` (`<clips>/Recordings`) ; `audio_capture_mode` vaut
     * `separated` quand le micro est sur sa propre piste (`combined` par défaut).
     */
    internal fun fthr(): DetectedRecorder? {
        val settingsFile = env.home.resolve(".fthr/settings.json")
        val installed = settingsFile.isRegularFile() ||
            listOf("FTHR Clips", "FTHRClips").any { env.localAppData.resolve(it).isDirectory() || env.appData.resolve(it).isDirectory() }
        if (!installed) return null
        val settings = runCatching { Json.parseToJsonElement(settingsFile.readText()) as? JsonObject }.getOrNull()
        fun text(key: String) = (settings?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val clips = text("clips_directory")?.let(Path::of) ?: env.home.resolve("FTHR_Clips")
        val recordings = text("recording_directory")?.let(Path::of) ?: clips.resolve("Recordings")
        val separate = settings?.let { text("audio_capture_mode") == "separated" }
        // Les enregistrements longs (parties entières) d'abord : c'est d'eux qu'on tire les meilleurs moments.
        val folders = if (recordings.startsWith(clips)) listOf(clips) else listOf(recordings, clips)
        return DetectedRecorder(RecorderKind.FTHR, folders, separate, fromSettings = settings != null)
    }

    /**
     * Medal : réglages dans `%APPDATA%\Medal` (format non documenté). Le dossier des clips est cherché dans les fichiers
     * JSON de `store` (une clé de dossier qui désigne un dossier existant, ou le dossier commun des vidéos de
     * `clips.json`, la bibliothèque), sinon `C:\Medal\Clips`, le défaut de Medal.
     */
    internal fun medal(): DetectedRecorder? {
        val dir = env.appData.resolve("Medal")
        if (!dir.isDirectory() && !env.localAppData.resolve("Medal").isDirectory()) return null
        val store = dir.resolve("store")
        val jsons = runCatching { store.listDirectoryEntries("*.json") }.getOrDefault(emptyList())
        val fromKeys = jsons.filter { it.fileName.toString() != "clips.json" }.asSequence()
            .mapNotNull { runCatching { Json.parseToJsonElement(it.readText()) }.getOrNull() }
            .flatMap { folderValues(it) }
            .map(Path::of).firstOrNull { it.isDirectory() }
        val fromClips = fromKeys ?: runCatching {
            val clips = Json.parseToJsonElement(store.resolve("clips.json").readText())
            videoPaths(clips).mapNotNull { Path.of(it).parent }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }.getOrNull()
        val default = env.systemDrive.resolve("Medal").let { root -> root.resolve("Clips").takeIf { it.isDirectory() } ?: root }
        return DetectedRecorder(RecorderKind.MEDAL, listOf(fromClips ?: default), fromSettings = fromClips != null)
    }

    /** Valeurs texte des clés qui parlent d'un dossier (« clipFolder », « captureFolderPath »…), à toute profondeur. */
    private fun folderValues(e: JsonElement): Sequence<String> = when (e) {
        is JsonObject -> e.entries.asSequence().flatMap { (k, v) ->
            val own = (v as? JsonPrimitive)?.takeIf { it.isString && FOLDER_KEY.containsMatchIn(k) }?.content
            listOfNotNull(own).asSequence() + folderValues(v)
        }
        is JsonArray -> e.asSequence().flatMap { folderValues(it) }
        else -> emptySequence()
    }

    private fun videoPaths(e: JsonElement): Sequence<String> = when (e) {
        is JsonObject -> e.values.asSequence().flatMap { videoPaths(it) }
        is JsonArray -> e.asSequence().flatMap { videoPaths(it) }
        is JsonPrimitive -> if (e.isString && e.content.substringAfterLast('.').lowercase() in HighlightPipeline.SUPPORTED_EXTENSIONS) sequenceOf(e.content) else emptySequence()
    }

    private fun xmlText(s: String) = s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    companion object {
        const val OUTPLAYED_ID = "cghphpbjeabdkomiphingnegihoigeggcfphdofo"
        const val NVIDIA_KEY = "HKCU\\Software\\NVIDIA Corporation\\Global\\ShadowPlay\\NVSPCAPS"
        private val NVIDIA_APPS = listOf("NVIDIA app", "NVIDIA GeForce Experience")
        private val FOLDER_KEY = Regex("folder|directory|dir$|path$", RegexOption.IGNORE_CASE)
    }
}

/** Fichier INI minimal d'OBS : sections, `clé=valeur`, `\\` doublés dans les chemins. */
internal class Ini(private val values: Map<String, Map<String, String>>) {
    operator fun get(section: String, key: String): String? = values[section]?.get(key)?.takeIf { it.isNotEmpty() }

    companion object {
        val EMPTY = Ini(emptyMap())

        fun read(file: Path): Ini = runCatching { parse(file.readText().removePrefix("\uFEFF")) }.getOrDefault(EMPTY)

        fun parse(text: String): Ini {
            val values = mutableMapOf<String, MutableMap<String, String>>()
            var section = ""
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("[") && line.endsWith("]") -> section = line.substring(1, line.length - 1)
                    '=' in line && !line.startsWith(";") -> values.getOrPut(section) { mutableMapOf() }[line.substringBefore('=').trim()] =
                        line.substringAfter('=').trim().replace("\\\\", "\\")
                }
            }
            return Ini(values)
        }
    }
}

/** Vidéos trouvées dans un dossier d'enregistreur : les plus récentes d'abord. */
data class FoundVideo(val path: Path, val modified: Instant)

object RecordingScan {
    /**
     * Vidéos du dossier et de ses sous-dossiers (Outplayed et ShadowPlay rangent par jeu), [limit] plus récentes ; les
     * dossiers de travail des enregistreurs (`temp-capture` d'Outplayed) sont écartés.
     */
    fun videos(folder: Path, limit: Int = 300, maxDepth: Int = 4): List<FoundVideo> {
        if (!folder.isDirectory()) return emptyList()
        return runCatching {
            Files.walk(folder, maxDepth).use { stream ->
                stream.asSequence()
                    .filter { p -> p.extension.lowercase() in HighlightPipeline.SUPPORTED_EXTENSIONS && folder.relativize(p).none { it.toString().lowercase() in SKIPPED } && p.isRegularFile() }
                    .map { FoundVideo(it.toAbsolutePath().normalize(), it.getLastModifiedTime().toInstant()) }
                    .sortedByDescending { it.modified }
                    .take(limit)
                    .toList()
            }
        }.onFailure { log.warn { "Lecture de $folder impossible : ${it.message}" } }.getOrDefault(emptyList())
    }

    private val SKIPPED = setOf("temp-capture", "temp", "cache")
}
