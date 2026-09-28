package dev.highlights.publish

import com.sun.net.httpserver.HttpServer
import dev.highlights.core.ConfigException
import dev.highlights.core.HighlightsException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

private val log = KotlinLogging.logger {}

/** Identifiant OAuth d'une application de bureau (console Google Cloud). */
data class OAuthClient(val id: String, val secret: String) {
    companion object {
        /**
         * Lit le fichier `client_secret_….json` que la console Google Cloud fait télécharger : une application de bureau
         * y range son identifiant sous « installed » (« web » accepté aussi, pour qui se serait trompé de type).
         */
        fun fromFile(file: Path): OAuthClient {
            if (!file.isRegularFile()) throw ConfigException("Fichier d'identifiant Google introuvable : $file")
            val root = try {
                Json.parseToJsonElement(file.readText().removePrefix("﻿")).jsonObject
            } catch (e: Exception) {
                throw ConfigException("Fichier d'identifiant Google illisible ($file) : ${e.message}", e)
            }
            val block = (root["installed"] ?: root["web"])?.jsonObject
                ?: throw ConfigException("$file n'est pas un identifiant OAuth Google (pas de bloc « installed »)")
            val id = block.string("client_id") ?: throw ConfigException("$file : client_id manquant")
            val secret = block.string("client_secret") ?: throw ConfigException("$file : client_secret manquant")
            return OAuthClient(id, secret)
        }
    }
}

/** Adresses de Google ; remplacées en test par un faux serveur. */
data class GoogleEndpoints(
    val authorize: URI = URI("https://accounts.google.com/o/oauth2/v2/auth"),
    val token: URI = URI("https://oauth2.googleapis.com/token"),
    val revoke: URI = URI("https://oauth2.googleapis.com/revoke"),
    val upload: URI = URI("https://www.googleapis.com/upload/youtube/v3/videos"),
)

/** Jeton gardé entre deux lancements : le rafraîchissement évite de se reconnecter à chaque envoi. */
@Serializable
internal data class StoredToken(
    val refreshToken: String,
    val accessToken: String? = null,
    /** Expiration du jeton d'accès (secondes depuis 1970). */
    val expiresAt: Long = 0,
)

/**
 * Connexion à un compte Google pour envoyer des vidéos (portée `youtube.upload`, rien d'autre : ni lecture de la chaîne,
 * ni suppression). Flux des applications de bureau : le navigateur s'ouvre sur la page de Google, qui renvoie vers un
 * petit serveur local éphémère (127.0.0.1, port libre) ; PKCE empêche qu'un autre programme réutilise le code reçu.
 *
 * Le jeton de rafraîchissement est gardé dans [tokenFile] (dossier de l'utilisateur) : il donne le droit d'envoyer des
 * vidéos sur la chaîne jusqu'à [logout] ou révocation depuis le compte Google.
 */
class GoogleAuth(
    private val client: OAuthClient,
    private val tokenFile: Path,
    private val browse: (URI) -> Unit,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val http: HttpClient = HttpClient.newHttpClient(),
) {
    /** Un compte est connecté (jeton gardé). */
    val connected: Boolean get() = load() != null

    /** Jeton d'accès valable, rafraîchi si besoin ; connexion dans le navigateur s'il n'y a pas de compte connecté. */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val stored = load() ?: return login().accessToken!!
        if (!forceRefresh && stored.accessToken != null && stored.expiresAt > Instant.now().epochSecond + 60) return stored.accessToken
        return try {
            refresh(stored).accessToken!!
        } catch (e: RevokedException) {
            log.info { "Accès YouTube révoqué ou expiré (${e.message}) : nouvelle connexion" }
            tokenFile.deleteIfExists()
            login().accessToken!!
        }
    }

    /** Connexion dans le navigateur ; attend au plus 5 minutes que l'utilisateur accepte. */
    suspend fun login(): StoredTokenView {
        val verifier = randomToken(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(24)
        val answer = CompletableFuture<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val query = parseQuery(exchange.requestURI.rawQuery ?: "")
            val ok = query["state"] == state && query["code"] != null
            val page = if (ok) "Connexion réussie : tu peux fermer cet onglet et revenir à Highlights."
            else "Connexion refusée ou interrompue : ${query["error"] ?: "réponse inattendue"}. Tu peux fermer cet onglet."
            val bytes = "<!doctype html><meta charset=utf-8><title>Highlights</title><p style=\"font:16px sans-serif\">$page</p>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            // Une requête parasite (favicon) ne doit pas clore l'attente avant la vraie réponse de Google.
            if (query.containsKey("code") || query.containsKey("error")) answer.complete(query)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = URI(
                endpoints.authorize.toString() + "?" + form(
                    "client_id" to client.id,
                    "redirect_uri" to redirect,
                    "response_type" to "code",
                    "scope" to SCOPE,
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                    "state" to state,
                    // Hors ligne + consentement : Google ne renvoie un jeton de rafraîchissement qu'à ces conditions.
                    "access_type" to "offline",
                    "prompt" to "consent",
                ),
            )
            log.info { "Connexion à YouTube dans le navigateur : $url" }
            browse(url)
            val query = withTimeout(LOGIN_TIMEOUT) { answer.await() }
            query["error"]?.let { throw HighlightsException("Connexion à YouTube refusée : $it") }
            if (query["state"] != state) throw HighlightsException("Connexion à YouTube : réponse qui ne vient pas de cette demande (state)")
            val json = post(
                "code" to query.getValue("code"),
                "client_id" to client.id,
                "client_secret" to client.secret,
                "redirect_uri" to redirect,
                "grant_type" to "authorization_code",
                "code_verifier" to verifier,
            )
            val refreshToken = json.string("refresh_token")
                ?: throw HighlightsException("Google n'a pas donné de jeton de rafraîchissement : retire l'accès de Highlights dans ton compte Google puis reconnecte-toi")
            return save(StoredToken(refreshToken, json.string("access_token"), expiry(json))).view()
        } finally {
            server.stop(0)
        }
    }

    /** Oublie le compte (et révoque le jeton chez Google, au mieux). */
    suspend fun logout() {
        val stored = load() ?: return
        tokenFile.deleteIfExists()
        runCatching {
            val request = HttpRequest.newBuilder(URI(endpoints.revoke.toString() + "?" + form("token" to stored.refreshToken)))
                .POST(HttpRequest.BodyPublishers.noBody()).build()
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
        }.onFailure { log.warn { "Révocation du jeton YouTube impossible (${it.message}) : il reste valable jusqu'à son retrait du compte Google" } }
    }

    private suspend fun refresh(stored: StoredToken): StoredToken {
        val json = post(
            "refresh_token" to stored.refreshToken,
            "client_id" to client.id,
            "client_secret" to client.secret,
            "grant_type" to "refresh_token",
        )
        // Google garde le même jeton de rafraîchissement ; s'il en donne un nouveau, c'est lui qu'on garde.
        return save(StoredToken(json.string("refresh_token") ?: stored.refreshToken, json.string("access_token"), expiry(json)))
    }

    private suspend fun post(vararg fields: Pair<String, String>): JsonObject {
        val request = HttpRequest.newBuilder(endpoints.token)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(*fields)))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: java.io.IOException) {
            throw HighlightsException("Google injoignable : ${e.message}", e)
        }
        val json = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        if (response.statusCode() !in 200..299) {
            val error = json.string("error") ?: "HTTP ${response.statusCode()}"
            if (error == "invalid_grant") throw RevokedException(json.string("error_description") ?: error)
            if (error == "invalid_client") throw ConfigException("Identifiant OAuth refusé par Google (invalid_client) : vérifie publish.youtube dans app.yaml")
            throw HighlightsException("Google refuse la connexion : $error ${json.string("error_description") ?: ""}".trim())
        }
        if (json.string("access_token") == null) throw HighlightsException("Réponse de Google sans jeton d'accès")
        return json
    }

    private fun expiry(json: JsonObject): Long =
        Instant.now().epochSecond + (json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600)

    private fun load(): StoredToken? {
        if (!tokenFile.exists()) return null
        return runCatching { tokenJson.decodeFromString(StoredToken.serializer(), tokenFile.readText()) }
            .onFailure { log.warn { "Jeton YouTube illisible ($tokenFile), reconnexion nécessaire : ${it.message}" } }
            .getOrNull()
    }

    private fun save(token: StoredToken): StoredToken {
        tokenFile.parent?.createDirectories()
        val tmp = tokenFile.resolveSibling(tokenFile.fileName.toString() + ".tmp")
        tmp.writeText(tokenJson.encodeToString(StoredToken.serializer(), token))
        tmp.moveTo(tokenFile, overwrite = true)
        return token
    }

    /** Jeton exposé aux appelants : l'accès seul, jamais le jeton de rafraîchissement. */
    class StoredTokenView internal constructor(val accessToken: String?)

    private fun StoredToken.view() = StoredTokenView(accessToken)

    private class RevokedException(message: String) : Exception(message)

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/youtube.upload"
        private val LOGIN_TIMEOUT = 5.minutes
        private val tokenJson = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()

        private fun randomToken(bytes: Int) = base64Url(ByteArray(bytes).also(random::nextBytes))
        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        internal fun form(vararg fields: Pair<String, String>) =
            fields.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }

        internal fun parseQuery(raw: String): Map<String, String> = raw.split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
        }
    }
}

internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
