package dev.highlights.publish

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.progress.ProgressReporter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Vidéo mise en ligne : [id] YouTube. */
data class UploadedVideo(val id: String) {
    val url: URI get() = URI("https://youtu.be/$id")
    val studio: URI get() = URI("https://studio.youtube.com/video/$id/edit")
}

/**
 * Envoi d'une vidéo sur YouTube par le protocole reprenable : une session est ouverte avec les métadonnées, puis la
 * vidéo part par morceaux ([chunkBytes], multiple de 256 Kio comme l'exige Google). Une coupure réseau ou une erreur
 * passagère de Google (5xx) ne fait pas tout recommencer : on demande où en est la session et on reprend de là.
 */
class YouTubeClient(
    private val auth: GoogleAuth,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val chunkBytes: Int = 32 * CHUNK_UNIT,
) {
    init {
        require(chunkBytes > 0 && chunkBytes % CHUNK_UNIT == 0) { "morceaux multiples de 256 Kio" }
    }

    suspend fun upload(video: Path, metadata: YouTubeMetadata, progress: ProgressReporter = ProgressReporter.NONE): UploadedVideo {
        if (!video.isRegularFile()) throw InputException("Vidéo introuvable : $video")
        val problems = metadata.problems()
        if (problems.isNotEmpty()) throw InputException("Envoi YouTube impossible : ${problems.joinToString(" ; ")}")
        val size = video.fileSize()
        val session = openSession(size, metadata)
        log.info { "Envoi YouTube de ${video.fileName} (${size / 1_000_000} Mo), privée : ${metadata.privacy}" }
        FileChannel.open(video, StandardOpenOption.READ).use { channel ->
            var offset = 0L
            var failures = 0
            while (true) {
                progress.update(offset.toDouble() / size, "YouTube")
                val result = try {
                    sendChunk(session, channel, offset, size)
                } catch (e: IOException) {
                    ChunkResult.Retry("réseau : ${e.message}")
                }
                when (result) {
                    is ChunkResult.Done -> {
                        progress.complete()
                        log.info { "Vidéo en ligne : https://youtu.be/${result.id}" }
                        return UploadedVideo(result.id)
                    }
                    is ChunkResult.Next -> {
                        offset = result.offset
                        failures = 0
                    }
                    is ChunkResult.Retry -> {
                        if (++failures > MAX_RETRIES) throw HighlightsException("Envoi YouTube interrompu (${result.reason}) après $MAX_RETRIES tentatives")
                        log.warn { "Envoi YouTube : ${result.reason}, nouvelle tentative ($failures/$MAX_RETRIES)" }
                        delay(RETRY_DELAY * (1 shl (failures - 1)))
                        offset = resumeOffset(session, size) ?: continue
                    }
                }
            }
        }
    }

    /** Ouvre la session d'envoi avec titre, description, tags et visibilité ; renvoie son adresse. */
    private suspend fun openSession(size: Long, metadata: YouTubeMetadata): URI {
        val body = buildJsonObject {
            put("snippet", buildJsonObject {
                put("title", metadata.title)
                put("description", metadata.description)
                put("tags", JsonArray(metadata.tags.map(::JsonPrimitive)))
                put("categoryId", metadata.categoryId)
                put("defaultLanguage", metadata.language)
                put("defaultAudioLanguage", metadata.language)
            })
            put("status", buildJsonObject {
                put("privacyStatus", metadata.privacy.apiName)
                put("selfDeclaredMadeForKids", metadata.madeForKids)
                metadata.publishAt?.let { put("publishAt", it.toString()) }
            })
        }.toString()
        val url = URI(
            endpoints.upload.toString() + "?" + GoogleAuth.form(
                "uploadType" to "resumable",
                "part" to "snippet,status",
                "notifySubscribers" to metadata.notifySubscribers.toString(),
            ),
        )
        var response = open(url, body, size, auth.accessToken())
        // Jeton refusé (révoqué entre-temps, horloge décalée) : un seul nouvel essai avec un jeton neuf.
        if (response.statusCode() == 401) response = open(url, body, size, auth.accessToken(forceRefresh = true))
        if (response.statusCode() !in 200..299) throw error("ouverture de l'envoi", response)
        return response.headers().firstValue("Location").map(::URI)
            .orElseThrow { HighlightsException("YouTube n'a pas donné d'adresse d'envoi") }
    }

    private suspend fun open(url: URI, body: String, size: Long, token: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Length", size.toString())
            .header("X-Upload-Content-Type", "video/mp4")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw HighlightsException("YouTube injoignable : ${e.message}", e)
        }
    }

    private sealed interface ChunkResult {
        data class Done(val id: String) : ChunkResult
        data class Next(val offset: Long) : ChunkResult
        data class Retry(val reason: String) : ChunkResult
    }

    private suspend fun sendChunk(session: URI, channel: FileChannel, offset: Long, size: Long): ChunkResult {
        val length = minOf(chunkBytes.toLong(), size - offset).toInt()
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) if (channel.read(buffer, offset + buffer.position()) < 0) break
        val request = HttpRequest.newBuilder(session)
            .header("Content-Range", "bytes $offset-${offset + length - 1}/$size")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(buffer.array(), 0, buffer.position()))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return when (val code = response.statusCode()) {
            200, 201 -> ChunkResult.Done(videoId(response))
            RESUME_INCOMPLETE -> ChunkResult.Next(received(response) ?: (offset + length))
            in 500..599 -> ChunkResult.Retry("YouTube HTTP $code")
            else -> throw error("envoi", response)
        }
    }

    /** Où en est la session après une coupure : octets reçus par Google ; null s'il faut réessayer plus tard. */
    private suspend fun resumeOffset(session: URI, size: Long): Long? {
        val request = HttpRequest.newBuilder(session)
            .header("Content-Range", "bytes */$size")
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            return null
        }
        return when (response.statusCode()) {
            RESUME_INCOMPLETE -> received(response) ?: 0L
            in 500..599 -> null
            404, 410 -> throw HighlightsException("Session d'envoi YouTube expirée : relance l'envoi")
            else -> throw error("reprise", response)
        }
    }

    /** En-tête Range « bytes=0-N » : N + 1 octets reçus. */
    private fun received(response: HttpResponse<String>): Long? =
        response.headers().firstValue("Range").orElse(null)?.substringAfter('-')?.toLongOrNull()?.plus(1)

    private fun videoId(response: HttpResponse<String>): String =
        runCatching { Json.parseToJsonElement(response.body()).jsonObject.string("id") }.getOrNull()
            ?: throw HighlightsException("YouTube a reçu la vidéo mais n'a pas donné son identifiant")

    /** Erreur de Google en clair, avec les cas qu'on sait expliquer (quota, chaîne non vérifiée…). */
    private fun error(step: String, response: HttpResponse<String>): HighlightsException {
        val json = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        val error = json?.get("error")?.let { runCatching { it.jsonObject }.getOrNull() }
        val message = error?.string("message") ?: response.body().take(200)
        val reason = error?.get("errors")?.let { runCatching { it.jsonArray.firstOrNull()?.jsonObject?.string("reason") }.getOrNull() }
        val hint = when (reason) {
            "quotaExceeded" -> " — quota du projet Google Cloud épuisé pour aujourd'hui (environ 6 envois par jour avec le quota gratuit)"
            "uploadLimitExceeded" -> " — limite d'envois de la chaîne atteinte, réessaie plus tard"
            "youtubeSignupRequired" -> " — le compte Google n'a pas encore de chaîne YouTube"
            "forbidden", "insufficientPermissions" -> " — accès refusé : reconnecte le compte"
            else -> ""
        }
        return HighlightsException("YouTube refuse : $step (HTTP ${response.statusCode()}${reason?.let { ", $it" } ?: ""}) : $message$hint")
    }

    companion object {
        const val CHUNK_UNIT = 256 * 1024
        private const val RESUME_INCOMPLETE = 308
        private const val MAX_RETRIES = 5
        private val RETRY_DELAY = 2.seconds
    }
}
