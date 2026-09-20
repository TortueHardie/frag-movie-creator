package dev.highlights.analysis.outplayed

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorAvailability
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.SerialDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

@Serializable
data class OutplayedEventsParams(
    /** Base IndexedDB d'Outplayed ; par défaut celle du profil Windows courant. */
    val database: String? = null,
    /**
     * Types Outplayed retenus et nom de l'événement émis (ex. kill: kill). Vide = tous, sous leur nom d'origine.
     * Types vus : kill, death, assist, headshot, spike_defused, manualVideo.
     */
    val kinds: Map<String, String> = emptyMap(),
    /**
     * Décalage ajouté à l'instant d'un type : Outplayed reçoit l'événement du jeu avec un peu de retard
     * (VALORANT : kills 0,39 s après l'apparition dans le killfeed, écart constant à ±10 ms sur une partie).
     */
    val offsets: Map<String, SerialDuration> = emptyMap(),
)

/**
 * Événements de jeu enregistrés par Outplayed pour la capture analysée (voir [OutplayedLibrary]) : kills, morts,
 * assistances… tels que transmis par le jeu, sans analyse d'image. Si la capture n'a pas été enregistrée par Outplayed
 * (OBS, fichier renommé ailleurs) ou si Outplayed n'est pas installé, le signal est simplement absent.
 */
class OutplayedEventsDetector(override val id: String, private val params: OutplayedEventsParams) : SignalDetector {

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val database = params.database?.let { Path(it) } ?: OutplayedLibrary.defaultDatabase()
        if (database == null || !database.isDirectory()) return missing(ctx, "base Outplayed introuvable (${database ?: "LOCALAPPDATA inconnu"})")
        val library = try {
            withContext(Dispatchers.IO) { OutplayedLibrary.load(database) }
        } catch (e: Exception) {
            log.warn(e) { "$id : lecture de la base Outplayed impossible" }
            return missing(ctx, "base Outplayed illisible : ${e.message}")
        }
        val media = library.find(ctx.media.path)
            ?: return missing(ctx, "capture inconnue d'Outplayed (${library.size} vidéos dans sa base)")

        val end = ctx.media.duration
        val events = media.events.mapNotNull { e ->
            val kind = if (params.kinds.isEmpty()) e.type else params.kinds[e.type] ?: return@mapNotNull null
            val at = (e.at + (params.offsets[e.type] ?: Duration.ZERO)).coerceIn(Duration.ZERO, end)
            SignalEvent(at, kind, 1.0)
        }.sortedBy { it.at }
        log.info {
            "$id : ${media.type ?: "vidéo"} du jeu ${media.gameId}, résumé ${media.info.filterKeys { it.endsWith("Count") || it == "agentKey" || it == "isVictory" }}, " +
                "${events.groupingBy { it.kind }.eachCount()} : " + events.joinToString { "${it.kind} ${Durations.format(it.at)}" }
        }
        return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, events)
    }

    private fun missing(ctx: AnalysisContext, note: String): SignalTrack {
        log.info { "$id : $note" }
        return SignalTrack.missing(id, ctx.grid.count, note)
    }
}

class OutplayedEventsDetectorFactory : SignalDetectorFactory {
    override val type = "outplayed-events"

    override fun availability(): DetectorAvailability {
        val database = OutplayedLibrary.defaultDatabase()
        return when {
            database == null -> DetectorAvailability(false, "profil Windows introuvable : événements Outplayed indisponibles")
            !database.isDirectory() -> DetectorAvailability(false, "Outplayed non installé ou base absente ($database)")
            else -> DetectorAvailability(true, "base Outplayed trouvée ($database)")
        }
    }

    override fun create(id: String, params: DetectorParams): SignalDetector =
        OutplayedEventsDetector(id, params.decode(OutplayedEventsParams.serializer()) { OutplayedEventsParams() })
}
