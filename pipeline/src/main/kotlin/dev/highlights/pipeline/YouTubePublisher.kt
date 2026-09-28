package dev.highlights.pipeline

import dev.highlights.core.ConfigException
import dev.highlights.core.InputException
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.config.YouTubeSettings
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.export.ExportReport
import dev.highlights.montage.MontageReport
import dev.highlights.publish.GoogleAuth
import dev.highlights.publish.GoogleEndpoints
import dev.highlights.publish.OAuthClient
import dev.highlights.publish.UploadedVideo
import dev.highlights.publish.VideoFacts
import dev.highlights.publish.VideoKind
import dev.highlights.publish.YouTubeClient
import dev.highlights.publish.YouTubeMetadata
import dev.highlights.publish.YouTubeText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

private val log = KotlinLogging.logger {}

/**
 * Publication d'une vidéo exportée sur YouTube : ce qu'on en dit (titre, description, tags, tirés de son rapport),
 * la connexion au compte et l'envoi. [browse] ouvre la page de connexion de Google dans le navigateur.
 */
class YouTubePublisher(
    private val config: LoadedConfig,
    private val ffmpeg: FfmpegService,
    private val profiles: () -> ProfileRepository,
    private val browse: (URI) -> Unit,
    private val tokenFile: Path = DEFAULT_TOKEN_FILE,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
) {
    val settings: YouTubeSettings get() = config.app.publish.youtube

    /** Envoi direct par l'API activé (publish.youtube.api) ; sinon envoi manuel assisté. */
    val apiEnabled: Boolean get() = settings.api

    /** API activée et identifiant OAuth trouvé (app.yaml, ou un client_secret*.json déposé dans le dossier de configuration). */
    val configured: Boolean get() = apiEnabled && runCatching { client() }.getOrNull() != null

    /** Un compte YouTube est connecté. */
    val connected: Boolean get() = configured && auth().connected

    /** Titre, description et tags proposés pour [video], d'après son rapport et les réglages. */
    suspend fun suggest(video: Path): YouTubeMetadata = YouTubeText.suggest(facts(video), settings)

    suspend fun login() {
        auth().login()
    }

    suspend fun logout() = auth().logout()

    /** Envoie [video] ; connexion dans le navigateur d'abord si aucun compte n'est connecté. */
    suspend fun upload(video: Path, metadata: YouTubeMetadata, progress: ProgressReporter = ProgressReporter.NONE): UploadedVideo =
        YouTubeClient(auth(), endpoints).upload(video, metadata, progress)

    private fun auth(): GoogleAuth {
        if (!apiEnabled) throw ConfigException("Envoi par l'API YouTube désactivé : publish.youtube.api: true dans app.yaml pour l'activer")
        return GoogleAuth(client(), tokenFile, browse, endpoints)
    }

    private fun client(): OAuthClient {
        val s = settings
        s.clientSecretFile?.let { return OAuthClient.fromFile(config.resolve(it)) }
        if (s.clientId != null && s.clientSecret != null) return OAuthClient(s.clientId!!, s.clientSecret!!)
        // Le plus simple : déposer le fichier téléchargé depuis la console Google Cloud à côté d'app.yaml.
        val dropped = runCatching { config.baseDir.listDirectoryEntries("client_secret*.json") }.getOrDefault(emptyList()).firstOrNull()
        return dropped?.let(OAuthClient::fromFile) ?: throw ConfigException(
            "Aucun identifiant YouTube : dépose le fichier client_secret….json de Google Cloud dans ${config.baseDir}, " +
                "ou renseigne publish.youtube.clientSecretFile dans app.yaml (voir le README, « Publier sur YouTube »)",
        )
    }

    /** Ce qu'on sait de [video] : sa durée et son format, et ce que dit le rapport écrit avec elle. */
    suspend fun facts(video: Path): VideoFacts {
        if (!video.isRegularFile()) throw InputException("Vidéo introuvable : $video")
        val media = ffmpeg.probe(video)
        val width = media.video?.width ?: 0
        val height = media.video?.height ?: 0
        val date = DATE.find(video.name)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val report = reportOf(video)
        fun game(id: String?) = id?.let { runCatching { profiles().byId(it).displayName }.getOrNull() ?: it }
            ?: video.name.substringBefore('_').replaceFirstChar { it.uppercase() }
        val montage = report?.takeIf { "clips" in it }?.let { runCatching { json.decodeFromJsonElement(MontageReport.serializer(), it) }.getOrNull() }
        val highlights = report?.takeIf { "highlights" in it }?.let { runCatching { json.decodeFromJsonElement(ExportReport.serializer(), it) }.getOrNull() }
        return when {
            montage != null -> VideoFacts(
                kind = VideoKind.KILL_MONTAGE,
                game = game(montage.profile ?: video.name.substringBefore('_')),
                date = date,
                duration = media.duration,
                width = width,
                height = height,
                kills = montage.clips.sumOf { it.kills.size },
                headshots = montage.clips.sumOf { it.headshots },
                multiKills = montage.clips.map { it.kills.size }.filter { it > 1 }.groupingBy { it }.eachCount(),
                aces = montage.clips.count { it.ace },
                clutches = montage.clips.count { it.clutch },
                music = Path(montage.music).nameWithoutExtension,
            )
            highlights != null -> VideoFacts(
                kind = VideoKind.HIGHLIGHTS,
                game = game(highlights.profile),
                date = date,
                duration = media.duration,
                width = width,
                height = height,
                moments = highlights.highlights.count { it.order != null },
            )
            else -> {
                log.info { "Pas de rapport pour ${video.fileName} : titre tiré du nom du fichier" }
                VideoFacts(
                    kind = if ("killmontage" in video.name) VideoKind.KILL_MONTAGE else VideoKind.HIGHLIGHTS,
                    game = game(null),
                    date = date,
                    duration = media.duration,
                    width = width,
                    height = height,
                )
            }
        }
    }

    /** Rapport JSON qui liste [video] parmi ses sorties, dans le même dossier. */
    private fun reportOf(video: Path): JsonObject? {
        val target = video.toAbsolutePath().normalize().toString().lowercase()
        val candidates = runCatching { video.toAbsolutePath().parent.listDirectoryEntries("*.json") }.getOrDefault(emptyList())
        return candidates.firstNotNullOfOrNull { file ->
            val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
            val outputs = runCatching { root["outputs"]!!.jsonArray }.getOrNull() ?: return@firstNotNullOfOrNull null
            val paths = outputs.mapNotNull { o ->
                runCatching { (if (o is JsonObject) o["path"]!! else o).jsonPrimitive.content }.getOrNull()
            }
            root.takeIf { paths.any { Path(it).toAbsolutePath().normalize().toString().lowercase() == target } }
        }
    }

    companion object {
        /** Page d'envoi de YouTube (redirige vers YouTube Studio), pour l'envoi manuel. */
        val UPLOAD_PAGE: URI = URI("https://www.youtube.com/upload")

        /** Jeton du compte connecté : il permet d'envoyer des vidéos sur la chaîne, et rien d'autre. */
        val DEFAULT_TOKEN_FILE: Path = Path(System.getProperty("user.home"), ".highlights", "youtube-token.json")
        private val DATE = Regex("""_(\d{4}-\d{2}-\d{2})_""")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
