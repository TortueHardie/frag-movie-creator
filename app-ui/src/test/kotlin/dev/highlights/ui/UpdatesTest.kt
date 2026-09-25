package dev.highlights.ui

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class UpdatesTest : FunSpec({
    test("les versions se comparent nombre par nombre") {
        compareVersions("1.10.0", "1.9.0") shouldBe 1
        compareVersions("v1.3.0", "1.3.0") shouldBe 0
        compareVersions("1.3", "1.3.0") shouldBe 0
        compareVersions("1.2.9", "1.3.0") shouldBe -1
        listOf("2.0.0", "1.3.0", "1.10.1", "1.9.9").sortedWith(::compareVersions) shouldBe listOf("1.3.0", "1.9.9", "1.10.1", "2.0.0")
    }

    test("la release GitHub donne la version, le MSI et son empreinte") {
        val json = """
            {"tag_name": "v1.4.0", "html_url": "https://github.com/x/y/releases/tag/v1.4.0",
             "assets": [
               {"name": "Highlights-1.4.0-portable.zip", "size": 10, "browser_download_url": "https://h/zip"},
               {"name": "Highlights-1.4.0.msi", "size": 1234, "digest": "sha256:abcd",
                "browser_download_url": "https://github.com/x/y/releases/download/v1.4.0/Highlights-1.4.0.msi"}
             ]}
        """
        val release = parseRelease(json).shouldNotBeNull()
        release.version shouldBe "1.4.0"
        release.msiName shouldBe "Highlights-1.4.0.msi"
        release.msiUrl shouldBe URI("https://github.com/x/y/releases/download/v1.4.0/Highlights-1.4.0.msi")
        release.size shouldBe 1234
        release.sha256 shouldBe "abcd"

        // Release sans installeur (build encore en cours, par exemple) : rien à proposer.
        parseRelease("""{"tag_name": "v1.4.0", "assets": []}""").shouldBeNull()
    }

    test("le téléchargement est vérifié, et un fichier altéré est refusé") {
        val payload = Random(1).nextBytes(300_000)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/msi") { ex -> ex.sendResponseHeaders(200, payload.size.toLong()); ex.responseBody.use { it.write(payload) } }
            start()
        }
        try {
            val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            val release = Release("9.9.9", "Highlights-9.9.9.msi", URI("http://127.0.0.1:${server.address.port}/msi"), payload.size.toLong(), sha, URI("https://h"))
            val dir = createTempDirectory("maj")
            val updater = GitHubUpdater(currentVersion = "1.3.0", appExe = null, downloadDir = dir)

            val progress = mutableListOf<Double>()
            val msi = updater.download(release) { progress += it }
            msi.readBytes() shouldBe payload
            progress.last() shouldBe 1.0
            progress shouldBeSortedWith naturalOrder()

            val tampered = release.copy(msiName = "autre.msi", sha256 = "0".repeat(64))
            shouldThrow<IllegalStateException> { updater.download(tampered) {} }.message shouldContain "corrompu"
            dir.resolve("autre.msi").exists() shouldBe false
            dir.resolve("autre.msi.part").exists() shouldBe false
        } finally {
            server.stop(0)
        }
    }

    test("le script attend la fermeture, installe dans le dossier actuel puis relance") {
        val script = installScript(
            pid = 4242,
            msi = Path("C:/Users/l'invité/AppData/Local/Temp/Highlights-mises-a-jour/Highlights-1.4.0.msi"),
            exe = Path("C:/Users/l'invité/AppData/Local/Highlights/Highlights.exe"),
            logFile = Path("C:/Temp/installation.log"),
        )
        val lines = script.lines()
        lines[0] shouldContain "Wait-Process -Id 4242"
        lines[1] shouldContain "msiexec.exe"
        lines[1] shouldContain "/passive"
        // Apostrophe doublée dans les chaînes PowerShell, et pas de « \ » avant le guillemet fermant.
        lines[1] shouldContain "INSTALLDIR=\"C:\\Users\\l''invité\\AppData\\Local\\Highlights\""
        lines[2] shouldBe "Start-Process -FilePath 'C:\\Users\\l''invité\\AppData\\Local\\Highlights\\Highlights.exe'"
    }

    test("le contrôleur propose la version plus récente, la télécharge puis demande la fermeture") {
        val release = Release("1.4.0", "Highlights-1.4.0.msi", URI("https://h/msi"), 1, null, URI("https://h"))
        val installed = mutableListOf<Path>()
        val updater = object : Updater {
            override val currentVersion = "1.3.0"
            override fun latest() = release
            override fun download(release: Release, onProgress: (Double) -> Unit): Path = Path("C:/Temp/${release.msiName}").also { onProgress(1.0) }
            override fun installAfterExit(msi: Path) { installed.add(msi) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AppController(scope, RecordingPlatform(), updater = updater, backendFactory = { error("pas de config") })
            withTimeout(10.seconds) { controller.state.first { it.update != null } }.update shouldBe UpdateState.Available(release)

            controller.installUpdate()
            val done = withTimeout(10.seconds) { controller.state.first { it.exitRequested } }
            done.update.shouldBeInstanceOf<UpdateState.Installing>()
            installed shouldBe listOf(Path("C:/Temp/Highlights-1.4.0.msi"))
        } finally {
            scope.cancel()
        }
    }

    test("rien n'est proposé quand la version installée est la dernière, ni en cas d'échec réseau") {
        for (latest in listOf<() -> Release?>({ Release("1.3.0", "a.msi", URI("https://h"), 1, null, URI("https://h")) }, { error("hors ligne") })) {
            val updater = object : Updater {
                override val currentVersion = "1.3.0"
                override fun latest() = latest()
                override fun download(release: Release, onProgress: (Double) -> Unit): Path = error("inattendu")
                override fun installAfterExit(msi: Path) = error("inattendu")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val controller = AppController(scope, RecordingPlatform(), updater = updater, backendFactory = { error("pas de config") })
                withTimeout(10.seconds) { controller.state.first { it.config is ConfigStatus.Failed } }
                kotlinx.coroutines.delay(300)
                controller.state.value.update.shouldBeNull()
            } finally {
                scope.cancel()
            }
        }
    }
})
