package dev.highlights.publish

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.highlights.core.ConfigException
import dev.highlights.core.HighlightsException
import dev.highlights.core.config.YouTubePrivacy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random

/**
 * Faux Google : page de connexion (le « navigateur » y répond aussitôt), jetons, et envoi reprenable qui tombe une fois
 * en panne (503) au milieu. Ce que le client envoie est gardé pour vérification.
 */
private class FakeGoogle {
    val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val base = "http://127.0.0.1:${server.address.port}"
    val endpoints = GoogleEndpoints(URI("$base/auth"), URI("$base/token"), URI("$base/revoke"), URI("$base/upload"))
    val tokenRequests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
    var metadata: String? = null
    val received = ByteArrayOutputStream()
    var failOnce = true
    var rejectFirstToken = false
    var revoked = false
    private var tokens = 0

    init {
        server.createContext("/token") { ex ->
            val form = GoogleAuth.parseQuery(ex.requestBody.readBytes().decodeToString())
            tokenRequests += form
            tokens++
            val refresh = if (form["grant_type"] == "authorization_code") """"refresh_token":"rafraichir",""" else ""
            reply(ex, 200, """{$refresh"access_token":"acces-$tokens","expires_in":3600}""")
        }
        server.createContext("/revoke") { ex -> revoked = true; reply(ex, 200, "") }
        server.createContext("/upload") { ex ->
            if (ex.requestHeaders.getFirst("Authorization") != "Bearer acces-${tokens}" || (rejectFirstToken && tokens == 1)) {
                reply(ex, 401, """{"error":{"message":"jeton expiré"}}""")
                return@createContext
            }
            metadata = ex.requestBody.readBytes().decodeToString()
            ex.responseHeaders.add("Location", "$base/session")
            reply(ex, 200, "")
        }
        server.createContext("/session") { ex ->
            val range = ex.requestHeaders.getFirst("Content-Range")
            val body = ex.requestBody.readBytes()
            if (range.startsWith("bytes */")) {
                ex.responseHeaders.add("Range", "bytes=0-${received.size() - 1}")
                reply(ex, 308, "")
                return@createContext
            }
            val (from, total) = Regex("""bytes (\d+)-\d+/(\d+)""").find(range)!!.destructured
            if (from.toLong() != received.size().toLong()) {
                reply(ex, 400, """{"error":{"message":"décalage"}}""")
                return@createContext
            }
            // Panne passagère au deuxième morceau : il n'est pas gardé, le client doit le renvoyer.
            if (failOnce && received.size() > 0) {
                failOnce = false
                reply(ex, 503, "")
                return@createContext
            }
            received.write(body)
            if (received.size().toLong() < total.toLong()) {
                ex.responseHeaders.add("Range", "bytes=0-${received.size() - 1}")
                reply(ex, 308, "")
            } else {
                reply(ex, 200, """{"id":"abc123"}""")
            }
        }
        server.start()
    }

    /** Le « navigateur » : suit la page de connexion jusqu'au retour local, comme si l'utilisateur acceptait. */
    fun browse(url: URI) {
        val query = GoogleAuth.parseQuery(url.rawQuery)
        val back = URI("${query["redirect_uri"]}/?state=${query["state"]}&code=code-recu")
        Thread { HttpClient.newHttpClient().send(HttpRequest.newBuilder(back).build(), HttpResponse.BodyHandlers.discarding()) }.start()
    }

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }
}

class YouTubeUploadTest : FunSpec({
    val metadata = YouTubeMetadata("TRIPLÉ — 19 kills en 57 s | VALORANT", "19 kills.\n\n#VALORANT #Shorts", listOf("VALORANT", "gaming"), YouTubePrivacy.PRIVATE)

    test("connexion dans le navigateur, puis envoi par morceaux repris après une panne") {
        val google = FakeGoogle()
        val dir = tempdir().toPath()
        val video = dir.resolve("montage.mp4").also { it.writeBytes(Random(1).nextBytes(3 * YouTubeClient.CHUNK_UNIT + 1234)) }
        val tokenFile = dir.resolve("youtube-token.json")
        val auth = GoogleAuth(OAuthClient("client", "secret"), tokenFile, google::browse, google.endpoints)
        auth.connected shouldBe false

        val uploaded = YouTubeClient(auth, google.endpoints, chunkBytes = YouTubeClient.CHUNK_UNIT).upload(video, metadata)

        uploaded.id shouldBe "abc123"
        uploaded.url shouldBe URI("https://youtu.be/abc123")
        google.received.toByteArray().contentEquals(video.toFile().readBytes()) shouldBe true
        // Code échangé avec son vérificateur PKCE ; jeton de rafraîchissement gardé pour les prochains envois.
        val exchange = google.tokenRequests.single()
        exchange["grant_type"] shouldBe "authorization_code"
        exchange["code"] shouldBe "code-recu"
        (exchange["code_verifier"]!!.length >= 43) shouldBe true
        auth.connected shouldBe true
        tokenFile.readText() shouldContain "rafraichir"
        // Titre, visibilité et déclaration « pour enfants » partent avec la vidéo.
        val sent = Json.parseToJsonElement(google.metadata!!).jsonObject
        sent["snippet"]!!.jsonObject["title"]!!.jsonPrimitive.content shouldBe metadata.title
        sent["snippet"]!!.jsonObject["tags"]!!.jsonArray.size shouldBe 2
        sent["status"]!!.jsonObject["privacyStatus"]!!.jsonPrimitive.content shouldBe "private"
        sent["status"]!!.jsonObject["selfDeclaredMadeForKids"]!!.jsonPrimitive.content shouldBe "false"

        auth.logout()
        auth.connected shouldBe false
        google.revoked shouldBe true
        google.server.stop(0)
    }

    test("jeton refusé : rafraîchi une fois, sans reconnexion") {
        val google = FakeGoogle().apply { failOnce = false; rejectFirstToken = true }
        val dir = tempdir().toPath()
        val video = dir.resolve("montage.mp4").also { it.writeBytes(ByteArray(1000)) }
        val tokenFile = dir.resolve("youtube-token.json").also {
            it.writeText("""{"refreshToken":"rafraichir","accessToken":"vieux","expiresAt":0}""")
        }
        var browsed = false
        val auth = GoogleAuth(OAuthClient("client", "secret"), tokenFile, { browsed = true }, google.endpoints)
        YouTubeClient(auth, google.endpoints, chunkBytes = YouTubeClient.CHUNK_UNIT).upload(video, metadata).id shouldBe "abc123"
        browsed shouldBe false
        google.tokenRequests.map { it["grant_type"] } shouldBe listOf("refresh_token", "refresh_token")
        google.server.stop(0)
    }

    test("métadonnées refusées d'avance : rien n'est envoyé") {
        val google = FakeGoogle()
        val dir = tempdir().toPath()
        val video = dir.resolve("montage.mp4").also { it.writeBytes(ByteArray(10)) }
        val auth = GoogleAuth(OAuthClient("client", "secret"), dir.resolve("t.json"), google::browse, google.endpoints)
        shouldThrow<HighlightsException> { YouTubeClient(auth, google.endpoints).upload(video, metadata.copy(title = "")) }
            .message shouldContain "titre est vide"
        google.tokenRequests.size shouldBe 0
        dir.resolve("t.json").exists() shouldBe false
        google.server.stop(0)
    }

    test("identifiant lu dans le fichier client_secret de la console Google Cloud") {
        val dir = tempdir().toPath()
        val file = dir.resolve("client_secret_123.json")
        file.writeText("""{"installed":{"client_id":"123.apps.googleusercontent.com","client_secret":"GOCSPX-x","redirect_uris":["http://localhost"]}}""")
        OAuthClient.fromFile(file) shouldBe OAuthClient("123.apps.googleusercontent.com", "GOCSPX-x")
        file.writeText("""{"autre":{}}""")
        shouldThrow<ConfigException> { OAuthClient.fromFile(file) }.message shouldContain "installed"
    }
})
