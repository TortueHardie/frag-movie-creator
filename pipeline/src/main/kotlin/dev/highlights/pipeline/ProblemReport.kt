package dev.highlights.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * Rapport de problème : un ZIP que l'utilisateur enregistre lui-même et joint à un ticket GitHub. Rien n'est envoyé
 * automatiquement (pas de télémétrie, voulu). Contenu : journaux récents, rapports de plantage de la JVM
 * (`hs_err_pid*.log`), diagnostic de la machine, analyses et rapport de montage ouverts.
 *
 * Confidentialité d'abord : seuls des fichiers texte connus entrent (jamais une vidéo, une musique, un identifiant
 * OAuth ou un jeton YouTube, voir [isSecret] et [TEXT_EXTENSIONS]), et chaque texte passe par [Redactor] (dossier
 * personnel → `~`, nom d'utilisateur Windows → `<user>`, jetons et adresses e-mail masqués) avant d'être compressé.
 */
class ProblemReport(private val redactor: Redactor) {
    /** Rubrique du rapport : décide du libellé affiché dans le résumé avant l'enregistrement. */
    enum class Part { SYSTEM, DIAGNOSTICS, ERROR, CRASH, JVM_CRASH, LOG, SESSION, MONTAGE_REPORT }

    /** Un fichier du ZIP, déjà expurgé : [name] dans l'archive, [content] en UTF-8. */
    class Entry(val name: String, val part: Part, val content: ByteArray) {
        val size: Long get() = content.size.toLong()
    }

    /**
     * Ce qu'on peut mettre dans le rapport. [texts] : textes produits par l'application (système, diagnostic, erreur
     * affichée), par nom de fichier. [logsDir] : journaux de l'application et traces de plantage notées par elle.
     * [crashDirs] : dossiers où la JVM a pu écrire un `hs_err_pid*.log` (dossier de lancement, d'installation…).
     * [sessions], [reports] : analyses ouvertes, rapport du dernier export (JSON).
     */
    data class Sources(
        val texts: List<Triple<String, Part, String>> = emptyList(),
        val logsDir: Path? = null,
        val crashDirs: List<Path> = emptyList(),
        val sessions: List<Path> = emptyList(),
        val reports: List<Path> = emptyList(),
    )

    fun collect(sources: Sources): List<Entry> = buildList {
        for ((name, part, text) in sources.texts) add(Entry(name, part, redactor.redact(text).toByteArray()))
        val logs = sources.logsDir?.takeIf { it.isDirectory() }
        if (logs != null) {
            listFiles(logs).filter { CRASH_NOTE.matches(it.name) && recent(it) }.sortedByDescending { it.getLastModifiedTime() }.take(MAX_CRASH_FILES)
                .forEach { file(it, "crash/${it.name}", Part.CRASH)?.let(::add) }
            // Journal courant et précédent (rotation logback) : la fin de chacun, là où le problème vient d'arriver.
            listOf(LOG_NAME, "$LOG_NAME.1").map(logs::resolve).filter { it.isRegularFile() }
                .forEach { tail(it, "logs/${it.name}")?.let(::add) }
        }
        sources.crashDirs.filter { it.isDirectory() }.flatMap(::listFiles).filter { JVM_CRASH.matches(it.name) && recent(it) }
            .distinctBy { it.toAbsolutePath().normalize() }.distinctBy { it.name }
            .sortedByDescending { it.getLastModifiedTime() }.take(MAX_CRASH_FILES)
            .forEach { file(it, "crash/${it.name}", Part.JVM_CRASH)?.let(::add) }
        sources.sessions.distinct().forEachIndexed { i, s -> file(s, "sessions/${numbered(i, s)}", Part.SESSION)?.let(::add) }
        sources.reports.distinct().forEachIndexed { i, r -> file(r, "reports/${numbered(i, r)}", Part.MONTAGE_REPORT)?.let(::add) }
    }.distinctBy { it.name }

    /** Écrit l'archive ; un fichier partiel n'est jamais laissé à la place du rapport demandé. */
    fun write(entries: List<Entry>, target: Path) {
        target.parent?.let(Files::createDirectories)
        val partial = target.resolveSibling("${target.name}.part")
        partial.outputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                for (e in entries) {
                    zip.putNextEntry(ZipEntry(e.name))
                    zip.write(e.content)
                    zip.closeEntry()
                }
            }
        }
        Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        log.info { "Rapport de problème enregistré : ${redactor.redact(target.toString())} (${entries.size} fichiers)" }
    }

    /** Un plantage de plus de [MAX_CRASH_AGE] concerne sans doute une autre version : il n'éclaire pas le problème du jour. */
    private fun recent(path: Path) =
        runCatching { path.getLastModifiedTime().toInstant().isAfter(java.time.Instant.now().minus(MAX_CRASH_AGE)) }.getOrDefault(false)

    private fun numbered(i: Int, path: Path) = "${i + 1}-${path.name}"

    private fun file(path: Path, name: String, part: Part): Entry? {
        if (!accepted(path)) return null
        return runCatching { Entry(name, part, redactor.redact(path.readText()).toByteArray()) }
            .onFailure { log.warn { "Rapport : ${path.name} illisible (${it.message})" } }.getOrNull()
    }

    /** Fin du fichier (au plus [MAX_LOG_BYTES]), à partir d'un début de ligne. */
    private fun tail(path: Path, name: String): Entry? {
        if (!accepted(path)) return null
        return runCatching {
            RandomAccessFile(path.toFile(), "r").use { f ->
                val start = (f.length() - MAX_LOG_BYTES).coerceAtLeast(0)
                f.seek(start)
                val bytes = ByteArray((f.length() - start).toInt())
                f.readFully(bytes)
                var text = String(bytes, Charsets.UTF_8)
                if (start > 0) text = "[…]\n" + text.substringAfter('\n')
                Entry(name, Part.LOG, redactor.redact(text).toByteArray())
            }
        }.onFailure { log.warn { "Rapport : ${path.name} illisible (${it.message})" } }.getOrNull()
    }

    private fun listFiles(dir: Path): List<Path> =
        runCatching { Files.list(dir).use { s -> s.filter { it.isRegularFile() }.toList() } }.getOrDefault(emptyList())

    companion object {
        /** Tickets du projet. Changer de dépôt (renommage) : cette ligne seulement. */
        const val ISSUES_URL = "https://github.com/TortueHardie/frag-movie-creator/issues"

        /**
         * Longueur maximale de l'adresse du ticket prérempli. GitHub accepte plus, mais certains navigateurs et
         * l'ouverture par Windows (ShellExecute) coupent vers 2 048 caractères : on reste en dessous.
         */
        const val MAX_URL = 2000

        const val LOG_NAME = "highlights.log"
        const val MAX_LOG_BYTES = 2L shl 20
        const val MAX_CRASH_FILES = 3
        val MAX_CRASH_AGE: java.time.Duration = java.time.Duration.ofDays(30)

        /** Seuls ces fichiers peuvent entrer dans le rapport : du texte, jamais une vidéo ou une musique. */
        val TEXT_EXTENSIONS = setOf("log", "txt", "json")

        /** Journal tourné par logback : `highlights.log.1`. */
        private val ROTATED_LOG = Regex("""[\w.-]+\.log\.\d+""")

        val JVM_CRASH = Regex("""hs_err_pid\d+\.log""")
        val CRASH_NOTE = Regex("""crash-[\w.-]+\.txt""")

        /** Identifiants et jetons : exclus quel que soit l'endroit où ils sont rangés. */
        fun isSecret(path: Path): Boolean {
            val n = path.name.lowercase()
            return n.startsWith("client_secret") || "token" in n || n == "control-bridge.json" || n.endsWith(".p12") || n.endsWith(".pem")
        }

        fun accepted(path: Path): Boolean =
            path.isRegularFile() && !isSecret(path) &&
                (path.name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS || ROTATED_LOG.matches(path.name))

        /**
         * Adresse d'un ticket prérempli. Le corps est raccourci (lignes retirées par la fin) si l'adresse dépasse
         * [MAX_URL] : le titre et le début du modèle comptent plus que la fin.
         */
        fun issueUrl(title: String, body: String, base: String = ISSUES_URL): String {
            fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
            var lines = body.lines()
            while (true) {
                val url = "$base/new?title=${enc(title)}&body=${enc(lines.joinToString("\n"))}"
                if (url.length <= MAX_URL || lines.isEmpty()) return url.take(MAX_URL)
                lines = lines.dropLast(1)
            }
        }

        /** Rapport complet par défaut : journaux de l'application et dossiers où la JVM écrit ses plantages. */
        fun defaultCrashDirs(appDir: Path? = null): List<Path> = listOfNotNull(
            Path.of(System.getProperty("user.dir")),
            Path.of(System.getProperty("user.home")),
            System.getProperty("java.io.tmpdir")?.let(Path::of),
            appDir,
        ).filter { it.exists() }
    }
}

/**
 * Expurge un texte avant qu'il quitte la machine : chemins sous le dossier personnel → `~`, nom d'utilisateur Windows
 * → `<user>` (y compris dans les noms courts du type `COLIN~1` de %TEMP%), jetons OAuth, secrets et e-mails masqués.
 * Les chemins sont cherchés sous toutes leurs écritures : `\`, `/`, `\\` (JSON), sans tenir compte de la casse.
 */
class Redactor(homes: List<Path>, userNames: List<String>) {
    private val homePatterns: List<Regex> = homes.map { it.toAbsolutePath().normalize().toString().trimEnd('\\', '/') }
        .filter { it.length > 3 }.distinct()
        .flatMap { h ->
            val parts = h.split('\\', '/')
            listOf("\\", "/", "\\\\").map { sep -> parts.joinToString(sep) }
        }
        .distinct().sortedByDescending { it.length }
        .map { Regex(Regex.escape(it) + "(?![\\w.-])", RegexOption.IGNORE_CASE) }

    private val names: List<Regex> = (userNames + homes.mapNotNull { it.fileName?.toString() })
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        .map { name ->
            // Un nom très court (« a ») serait remplacé partout : seulement entre deux séparateurs de chemin.
            if (name.length >= 3) Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            else Regex("(?<=[\\\\/])" + Regex.escape(name) + "(?=[\\\\/])", RegexOption.IGNORE_CASE)
        }

    fun redact(text: String): String {
        var out = text
        for (p in homePatterns) out = p.replace(out, "~")
        // Tout autre dossier sous C:\Users : nom court 8.3 (« JEAN~1.DUP » dans %TEMP%), autre compte, autre écriture.
        out = USERS_DIR.replace(out, "~")
        // Jetons et e-mails avant le nom : « jean.dupont@gmail.com » doit disparaître en entier.
        for ((pattern, replacement) in SECRETS) out = pattern.replace(out, replacement)
        for (n in names) out = n.replace(out) { "<user>" }
        return out
    }

    companion object {
        private val USERS_DIR = Regex("""[A-Za-z]:(\\\\|\\|/)Users\1[^\\/"\s:*?<>|]+""", RegexOption.IGNORE_CASE)

        private val SECRETS = listOf(
            Regex("""("?(?:access_token|refresh_token|id_token|client_secret|token|code_verifier)"?\s*[:=]\s*"?)[^"\s,&}]+""", RegexOption.IGNORE_CASE) to "$1<hidden>",
            Regex("""(Bearer\s+)[\w.~+/=-]+""") to "$1<hidden>",
            Regex("""ya29\.[\w.-]+""") to "<hidden>",
            Regex("""1//[\w-]{20,}""") to "<hidden>",
            Regex("""GOCSPX-[\w-]+""") to "<hidden>",
            Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""") to "<email>",
        )

        /** Pour cette machine : dossier personnel (Java et Windows) et nom d'utilisateur. */
        fun forThisMachine(): Redactor = Redactor(
            listOfNotNull(System.getProperty("user.home"), System.getenv("USERPROFILE")).map(Path::of),
            listOfNotNull(System.getProperty("user.name"), System.getenv("USERNAME")),
        )
    }
}
