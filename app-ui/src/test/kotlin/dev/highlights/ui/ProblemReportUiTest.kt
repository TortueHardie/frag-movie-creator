package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.pipeline.ProblemReport
import dev.highlights.pipeline.Redactor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class ProblemReportUiTest : FunSpec({
    test("plantage noté, proposé une fois, puis plus après avoir été vu") {
        val dir = createTempDirectory("journaux")
        val jvm = createTempDirectory("installation")
        jvm.resolve("hs_err_pid1.log").writeText("# ancien plantage\n").also {
            jvm.resolve("hs_err_pid1.log").setLastModifiedTime(FileTime.fromMillis(1_000))
        }
        val log = CrashLog(dir) { listOf(jvm) }
        // Premier lancement : le vieux hs_err n'est pas proposé.
        log.pending().shouldBeNull()

        log.record("AWT-EventQueue-0", IllegalStateException("boum"))
        val crash = log.pending().shouldNotBeNull()
        crash.summary shouldBe "java.lang.IllegalStateException: boum"
        crash.jvm shouldBe false
        log.acknowledge()
        log.pending().shouldBeNull()

        Thread.sleep(20)
        jvm.resolve("hs_err_pid2.log").writeText("# There is insufficient memory for the Java Runtime Environment to continue.\n# Native memory allocation (mmap) failed\n")
        val fatal = log.pending().shouldNotBeNull()
        fatal.jvm shouldBe true
        fatal.summary shouldContain "insufficient memory"
        fatal.summary shouldContain "Native memory allocation"
    }

    test("signaler : résumé, ZIP enregistré sans secrets, ticket prérempli ouvert dans le navigateur") {
        val root = createTempDirectory("signalement")
        val home = root.resolve("Users").resolve("jdoe").createDirectories()
        val logs = home.resolve(".highlights/logs").createDirectories()
        logs.resolve("highlights.log").writeText("ERROR échec sur $home\\Videos\\partie.mp4\n")
        logs.resolve("client_secret_1.json").writeText("{}")
        val target = root.resolve("Bureau").createDirectories().resolve("rapport")
        val platform = SavingPlatform(target)
        val env = ReportEnvironment(
            logsDir = logs,
            redactor = Redactor(listOf(home), listOf("jdoe")),
            version = "1.6.0",
            system = { listOf("système : Windows 11", "dossier : $home") },
            windows = { "Windows 11 (10.0.26300)" },
            totalMemory = { 16L shl 30 },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AppController(scope, platform, reportEnv = env, backendFactory = { error("pas de config") })
            withTimeout(10.seconds) { controller.state.first { it.config is ConfigStatus.Failed } }

            controller.openReport()
            val ready = withTimeout(20.seconds) { controller.state.first { it.report?.loading == false } }.report.shouldNotBeNull()
            ready.failed.shouldBeNull()
            ready.items.map { it.part } shouldBe listOf(ProblemReport.Part.SYSTEM, ProblemReport.Part.DIAGNOSTICS, ProblemReport.Part.LOG)

            controller.saveReport()
            val saved = controller.state.value.report.shouldNotBeNull()
            val zip = target.resolveSibling("rapport.zip")
            saved.saved shouldBe zip
            zip.exists() shouldBe true
            val names = ZipFile(zip.toFile()).use { z -> z.entries().toList().map { it.name } }
            names shouldBe listOf("system.txt", "diagnostics.txt", "logs/highlights.log")
            ZipFile(zip.toFile()).use { z ->
                z.entries().toList().forEach { e ->
                    val text = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    text shouldNotContain home.toString()
                    text.lowercase() shouldNotContain "jdoe"
                }
            }

            val url = platform.browsed.single().toString()
            url shouldStartWith ProblemReport.ISSUES_URL + "/new?"
            saved.issueUrl shouldBe url
            val body = URLDecoder.decode(url.substringAfter("&body="), Charsets.UTF_8)
            body shouldContain "Highlights 1.6.0 · Windows 11 (10.0.26300)"
            body shouldContain "rapport.zip"

            controller.revealReport()
            platform.revealed shouldContain zip
            controller.closeReport()
            controller.state.value.report.shouldBeNull()
        } finally {
            scope.cancel()
        }
    }

    test("chaque rubrique du rapport a son libellé traduit") {
        val english = I18n.bundle("en")
        ProblemReport.Part.entries.map { "ui.report.part.${it.name.lowercase()}" }.filterNot(english::containsKey).shouldBeEmpty()
    }
})

/** Plateforme de test : « Enregistrer sous » répond [target], le navigateur et l'Explorateur sont seulement notés. */
private class SavingPlatform(private val target: Path) : Platform {
    val browsed: MutableList<URI> = Collections.synchronizedList(mutableListOf())
    val revealed: MutableList<Path> = Collections.synchronizedList(mutableListOf())
    override fun chooseVideos(initialDir: Path?): List<Path> = emptyList()
    override fun chooseAudio(initialDir: Path?): Path? = null
    override fun chooseSessions(initialDir: Path?): List<Path> = emptyList()
    override fun chooseDirectory(initialDir: Path?, title: String): Path? = null
    override fun open(path: Path) = Unit
    override fun reveal(path: Path) { revealed.add(path) }
    override fun edit(path: Path) = Unit
    override fun browse(uri: URI) { browsed += uri }
    override fun chooseSaveFile(initialDir: Path?, fileName: String, title: String): Path = target
}
