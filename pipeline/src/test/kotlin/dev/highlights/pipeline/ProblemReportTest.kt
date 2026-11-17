package dev.highlights.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.net.URLDecoder
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ProblemReportTest : FunSpec({
    /** Faux dossier personnel « …\home\jdoe » : le nom d'utilisateur est aussi le dernier dossier, comme sous Windows. */
    fun machine(): Triple<Path, Path, Redactor> {
        val root = createTempDirectory("rapport")
        val home = root.resolve("home").resolve("jdoe").createDirectories()
        return Triple(root, home, Redactor(listOf(home), listOf("jdoe")))
    }

    fun zip(file: Path): Map<String, String> = ZipFile(file.toFile()).use { z ->
        z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
    }

    test("le ZIP contient journaux, plantages, diagnostic et analyses, jamais les secrets, vidéos ou musiques") {
        val (root, home, redactor) = machine()
        val logs = home.resolve(".highlights/logs").createDirectories()
        logs.resolve("highlights.log").writeText("2026-11-17 INFO lecture de $home\\Videos\\partie.mp4\n")
        logs.resolve("highlights.log.1").writeText("ancien journal\n")
        logs.resolve("crash-20261117-101500.txt").writeText("java.lang.IllegalStateException: boum\n")
        // Rangés par erreur dans le dossier des journaux : exclus quand même.
        logs.resolve("client_secret_1234.json").writeText("""{"installed":{"client_secret":"GOCSPX-abc"}}""")
        logs.resolve("youtube-token.json").writeText("""{"refresh_token":"1//0gSecretSecretSecretSecret"}""")
        val config = root.resolve("config").createDirectories()
        val secret = config.resolve("client_secret_999.apps.googleusercontent.com.json").apply { writeText("{}") }
        val token = home.resolve(".highlights/youtube-token.json").apply { writeText("{}") }
        val crashes = root.resolve("install").createDirectories()
        crashes.resolve("hs_err_pid4242.log").writeText("# There is insufficient memory for the Java Runtime Environment to continue.\n")
        crashes.resolve("replay.mp4").writeBytes(ByteArray(64))
        val session = root.resolve("sessions").createDirectories().resolve("partie.session.json")
        session.writeText("""{"media":{"path":"${home.toString().replace("\\", "\\\\")}\\Videos\\partie.mp4"}}""")
        val video = root.resolve("partie.mp4").apply { writeBytes(ByteArray(64)) }
        val music = root.resolve("musique.mp3").apply { writeBytes(ByteArray(64)) }
        val report = root.resolve("montage.json").apply { writeText("""{"music":"$music"}""") }

        val problem = ProblemReport(redactor)
        val entries = problem.collect(
            ProblemReport.Sources(
                texts = listOf(Triple("system.txt", ProblemReport.Part.SYSTEM, "application : Highlights 1.6.0")),
                logsDir = logs,
                crashDirs = listOf(crashes, logs),
                sessions = listOf(session, video, token, secret),
                reports = listOf(report, music),
            ),
        )
        val target = root.resolve("sortie").resolve("rapport.zip")
        problem.write(entries, target)

        val files = zip(target)
        files.keys shouldContainExactlyInAnyOrder listOf(
            "system.txt", "logs/highlights.log", "logs/highlights.log.1", "crash/crash-20261117-101500.txt",
            "crash/hs_err_pid4242.log", "sessions/1-partie.session.json", "reports/1-montage.json",
        )
        files.getValue("logs/highlights.log") shouldContain "~\\Videos\\partie.mp4"
        files.getValue("sessions/1-partie.session.json") shouldContain "~\\\\Videos\\\\partie.mp4"
        files.getValue("crash/hs_err_pid4242.log") shouldContain "insufficient memory"
        for ((name, text) in files) {
            text.shouldNotContain(home.toString())
            text.shouldNotContain(home.toString().replace('\\', '/'))
            text.lowercase().shouldNotContain("jdoe")
            text.shouldNotContain("GOCSPX")
            name.shouldNotContain("secret")
            name.shouldNotContain("token")
        }
    }

    test("chemin personnel, nom d'utilisateur et jetons expurgés sous toutes leurs écritures") {
        val redactor = Redactor(listOf(Path.of("C:\\Users\\Jean.Dupont")), listOf("Jean.Dupont"))
        val text = """
            C:\Users\Jean.Dupont\Videos\a.mp4
            c:/users/jean.dupont/Music
            "C:\\Users\\Jean.Dupont\\AppData"
            C:\Users\JEAN~1.DUP\AppData\Local\Temp
            C:\Users\Jean.Dupont2\autre
            connecté en tant que jean.dupont@gmail.com
            Authorization: Bearer ya29.a0AfB_byC-secret
            "refresh_token": "1//0gAbCdEfGhIjKlMnOpQrStUv"
        """.trimIndent()
        val out = redactor.redact(text)
        out shouldContain "~\\Videos\\a.mp4"
        out shouldContain "~/Music"
        out shouldContain "\"~\\\\AppData\""
        out shouldContain "<email>"
        out shouldContain "Bearer <hidden>"
        out shouldContain "\"refresh_token\": \"<hidden>"
        out.lowercase().shouldNotContain("jean.dupont")
        out.shouldNotContain("ya29")
        out.shouldNotContain("1//0g")
        // Nom court 8.3 de %TEMP% et autre compte : tout dossier sous C:\Users devient ~.
        out shouldContain "~\\AppData\\Local\\Temp"
        out shouldContain "~\\autre"
        out.uppercase().shouldNotContain("JEAN~1")
    }

    test("un long journal est coupé à sa fin, à un début de ligne") {
        val (root, _, redactor) = machine()
        val logs = root.resolve("logs").createDirectories()
        val line = "x".repeat(99) + "\n"
        logs.resolve("highlights.log").writeText(line.repeat(30_000) + "derniere ligne\n")
        val entry = ProblemReport(redactor).collect(ProblemReport.Sources(logsDir = logs)).single()
        (entry.size <= ProblemReport.MAX_LOG_BYTES + 8) shouldBe true
        val text = entry.content.toString(Charsets.UTF_8)
        text shouldStartWith "[…]\n" + "x".repeat(99)
        text shouldContain "derniere ligne"
    }

    test("l'adresse du ticket prérempli reste courte, titre et début du modèle gardés") {
        val body = (listOf("Highlights 1.6.0 · Windows 11", "", "### Что произошло?") + List(200) { "строка $it" }).joinToString("\n")
        val url = ProblemReport.issueUrl("Проблема : analyse impossible", body)
        url.length shouldBeLessThanOrEqual ProblemReport.MAX_URL
        url shouldStartWith ProblemReport.ISSUES_URL + "/new?title="
        url.shouldNotContain("+")
        val decoded = URLDecoder.decode(url.substringAfter("&body="), Charsets.UTF_8)
        decoded shouldStartWith "Highlights 1.6.0 · Windows 11\n\n### Что произошло?"
        URLDecoder.decode(url.substringAfter("title=").substringBefore("&"), Charsets.UTF_8) shouldBe "Проблема : analyse impossible"
    }

    test("seuls des fichiers texte non secrets sont acceptés") {
        val (root, _, _) = machine()
        val names = listOf("a.log", "highlights.log.3", "b.txt", "c.json", "d.mp4", "e.mp3", "client_secret_x.json", "youtube-token.json", "control-bridge.json")
        val accepted = names.map { root.resolve(it).apply { writeText("x") } }.filter(ProblemReport::accepted).map { it.fileName.toString() }
        accepted shouldContainAll listOf("a.log", "highlights.log.3", "b.txt", "c.json")
        accepted.size shouldBe 4
    }
})
