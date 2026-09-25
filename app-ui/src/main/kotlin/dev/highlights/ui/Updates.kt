package dev.highlights.ui

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.outputStream

private val log = KotlinLogging.logger {}

/** Release publiée sur GitHub, avec son installeur MSI. [sha256] : empreinte annoncée par GitHub, si elle l'est. */
data class Release(
    val version: String,
    val msiName: String,
    val msiUrl: URI,
    val size: Long,
    val sha256: String?,
    val page: URI,
)

/** Où en est la mise à jour proposée (null dans [UiState] : rien à proposer). */
sealed interface UpdateState {
    val release: Release

    data class Available(override val release: Release) : UpdateState
    data class Downloading(override val release: Release, val fraction: Double = 0.0) : UpdateState
    /** Installeur lancé : l'application se ferme pour le laisser remplacer ses fichiers, puis est relancée. */
    data class Installing(override val release: Release) : UpdateState
    data class Failed(override val release: Release, val message: String) : UpdateState
}

/** Recherche, téléchargement et installation des nouvelles versions, remplaçables en test. */
interface Updater {
    /** Version installée ; null quand l'application ne tourne pas depuis une installation (développement). */
    val currentVersion: String?

    /** Dernière release publiée, ou null s'il n'y en a pas. */
    fun latest(): Release?

    /** Télécharge l'installeur (vérifié : taille et empreinte) et renvoie son chemin. */
    fun download(release: Release, onProgress: (Double) -> Unit): Path

    /**
     * Lance l'installeur une fois l'application fermée, puis la relance. L'appelant doit quitter aussitôt après :
     * tant qu'elle tourne, ses fichiers sont verrouillés.
     */
    fun installAfterExit(msi: Path)

    companion object {
        /** Aucune mise à jour : développement et tests. */
        val NONE = object : Updater {
            override val currentVersion: String? = null
            override fun latest(): Release? = null
            override fun download(release: Release, onProgress: (Double) -> Unit): Path = error("Mises à jour désactivées")
            override fun installAfterExit(msi: Path) = error("Mises à jour désactivées")
        }
    }
}

/** Compare deux numéros « 1.2.3 » nombre par nombre (1.10.0 est plus récent que 1.9.0) ; un « v » initial est ignoré. */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/** Release décrite par l'API GitHub (`/releases/latest`) ; null si elle n'a pas d'installeur MSI. */
internal fun parseRelease(json: String): Release? {
    val root = Json.parseToJsonElement(json).jsonObject
    val tag = root.string("tag_name") ?: return null
    val msi = root["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        .firstOrNull { it.string("name")?.endsWith(".msi", ignoreCase = true) == true } ?: return null
    return Release(
        version = tag.removePrefix("v"),
        msiName = msi.string("name")!!,
        msiUrl = URI(msi.string("browser_download_url") ?: return null),
        size = msi["size"]?.jsonPrimitive?.longOrNull ?: -1,
        sha256 = msi.string("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        page = URI(root.string("html_url") ?: "https://github.com/$GITHUB_REPO/releases"),
    )
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

const val GITHUB_REPO = "TortueHardie/frag-movie-creator"

/**
 * Mises à jour publiées dans les releases GitHub du projet (voir le workflow « Installeur »). Le MSI garde le même code
 * de mise à niveau d'une version à l'autre : lancé par-dessus, il remplace l'installation existante.
 */
class GitHubUpdater(
    // Propriétés posées par le lanceur jpackage : absentes en développement, où rien n'est proposé.
    override val currentVersion: String? = System.getProperty("jpackage.app-version"),
    private val appExe: Path? = installedExe(),
    private val downloadDir: Path = Path(System.getProperty("java.io.tmpdir"), "Highlights-mises-a-jour"),
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : Updater {

    override fun latest(): Release? {
        val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/$GITHUB_REPO/releases/latest"))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Highlights/${currentVersion ?: "dev"}")
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> parseRelease(response.body())
            404 -> null // aucune release publiée
            else -> error("GitHub a répondu ${response.statusCode()}")
        }
    }

    override fun download(release: Release, onProgress: (Double) -> Unit): Path {
        downloadDir.createDirectories()
        val target = downloadDir.resolve(release.msiName)
        if (target.isRegularFile() && matches(target, release)) return target.also { onProgress(1.0) }
        val partial = downloadDir.resolve(release.msiName + ".part")
        val request = HttpRequest.newBuilder(release.msiUrl)
            .header("User-Agent", "Highlights/${currentVersion ?: "dev"}")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "Téléchargement de ${release.msiName} : réponse ${response.statusCode()}" }
        val total = release.size.takeIf { it > 0 } ?: response.headers().firstValueAsLong("Content-Length").orElse(-1)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            response.body().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toDouble() / total).coerceAtMost(1.0))
                    }
                }
            }
            if (release.size > 0) check(partial.fileSize() == release.size) {
                "Téléchargement incomplet : ${partial.fileSize()} octets sur ${release.size}"
            }
            val sha = digest.digest().toHex()
            release.sha256?.let { expected -> check(sha.equals(expected, ignoreCase = true)) { "Installeur corrompu : empreinte $sha au lieu de $expected" } }
            partial.moveTo(target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            partial.deleteIfExists()
            throw e
        }
        log.info { "Installeur ${release.version} téléchargé : $target" }
        return target
    }

    override fun installAfterExit(msi: Path) {
        val exe = checkNotNull(appExe) { "Emplacement de l'application inconnu : installer ${msi.name} à la main" }
        val script = installScript(ProcessHandle.current().pid(), msi, exe, downloadDir.resolve("installation.log"))
        // Script encodé (UTF-16LE en base 64) : aucun souci de guillemets ni d'accents dans les chemins.
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        log.info { "Installation de $msi lancée, l'application va se fermer" }
    }

    private fun matches(file: Path, release: Release): Boolean =
        (release.size <= 0 || file.fileSize() == release.size) &&
            (release.sha256 == null || sha256(file).equals(release.sha256, ignoreCase = true))

    companion object {
        /** Highlights.exe de l'installation en cours : donné par le lanceur jpackage, sinon celui du processus. */
        fun installedExe(): Path? =
            System.getProperty("jpackage.app-path")?.let(::Path)
                ?: ProcessHandle.current().info().command().orElse(null)?.let(::Path)?.takeIf { it.name.equals("Highlights.exe", ignoreCase = true) }
    }
}

/**
 * Script PowerShell détaché de l'application : attend qu'elle soit fermée, installe le MSI par-dessus (barre de
 * progression, aucune question) dans le dossier de l'installation actuelle, puis relance Highlights, même si
 * l'installation a échoué (Windows annule alors tout et l'ancienne version reste en place).
 */
internal fun installScript(pid: Long, msi: Path, exe: Path, logFile: Path): String {
    fun quoted(s: String) = "'" + s.replace("'", "''") + "'"
    // Sans « \ » final : devant le guillemet fermant, msiexec le lirait comme un guillemet échappé. Windows Installer
    // ajoute lui-même le séparateur aux propriétés de dossier.
    val installDir = exe.parent.toString().trimEnd('\\')
    val arguments = "/i \"$msi\" /passive /norestart INSTALLDIR=\"$installDir\" /l*v \"$logFile\""
    return """
        Wait-Process -Id $pid -Timeout 120 -ErrorAction SilentlyContinue
        Start-Process -FilePath 'msiexec.exe' -ArgumentList ${quoted(arguments)} -Wait
        Start-Process -FilePath ${quoted(exe.toString())}
    """.trimIndent()
}

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.toFile().inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
