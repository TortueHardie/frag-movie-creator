package dev.highlights.pipeline

import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private val log = KotlinLogging.logger {}

/**
 * Une soirée de jeu analysée, comme le montage de soirée la prend : celle des statistiques ([Statistics.evenings] : de
 * 6 h à 6 h, un jeu à la fois, une partie enregistrée deux fois comptée une fois). [end] : fin de la dernière partie
 * (début de sa capture + durée) ; [sessionFiles] : les parties, dans l'ordre où elles ont été jouées, [sources] leurs
 * captures (même ordre).
 */
data class Evening(
    val date: LocalDate,
    val game: String,
    val profileId: String,
    val sessionFiles: List<Path>,
    val sources: List<Path>,
    val kills: Int,
    val end: Instant,
)

/** Soirée déjà montée (ou dont le montage a échoué : elle n'est pas retentée d'office). */
@Serializable
data class EveningRecord(
    val date: String,
    val profileId: String,
    val kills: Int,
    val video: SerialPath? = null,
    val error: String? = null,
    val at: SerialInstant,
)

/**
 * Soirées déjà montées, gardées dans `outputDir/sessions/evening-montages.json` : une soirée n'est jamais remontée
 * d'office, même si une partie de plus y arrive après coup (on ne refait pas dans le dos de l'utilisateur une vidéo
 * qu'il a peut-être déjà publiée). `app soiree` la remonte sur demande.
 */
class EveningHistory(val file: Path) {
    private val lock = Any()

    fun entries(): List<EveningRecord> = synchronized(lock) { load() }

    fun done(e: Evening): Boolean = synchronized(lock) { load().any { it.date == e.date.toString() && it.profileId == e.profileId } }

    fun record(e: Evening, video: Path?, error: String? = null) = synchronized(lock) {
        val record = EveningRecord(e.date.toString(), e.profileId, e.kills, video?.toAbsolutePath()?.normalize(), error, Instant.now())
        val next = load().filterNot { it.date == record.date && it.profileId == record.profileId } + record
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            tmp.writeText(json.encodeToString(Stored.serializer(), Stored(next)))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Soirées montées non enregistrées ($file) : ${it.message}" } }
    }

    private fun load(): List<EveningRecord> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(Stored.serializer(), file.readText()).evenings
        } catch (e: Exception) {
            log.warn { "Soirées montées illisibles ($file), ignorées : ${e.message}" }
            emptyList()
        }
    }

    @Serializable
    private data class Stored(val evenings: List<EveningRecord> = emptyList())

    private companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

/** Quelles soirées monter, et quand : sans état, pour être testé sans attendre une heure. */
object EveningPlanner {
    /** Soirées des parties analysées (voir [Statistics.evenings]), la plus récente d'abord. */
    fun of(games: List<GameStats>, zone: ZoneId = ZoneId.systemDefault()): List<Evening> =
        Statistics.evenings(games, zone).map { ev ->
            Evening(
                date = ev.date,
                game = ev.game,
                profileId = ev.games.first().profileId,
                sessionFiles = ev.games.map { it.sessionFile },
                sources = ev.games.map { it.source },
                kills = ev.kills,
                end = ev.games.maxOf { it.playedAt!! + it.duration.toJavaDuration() },
            )
        }

    /**
     * Soirées à monter maintenant : finies (aucune partie depuis [quiet] après la fin de la dernière, et rien en cours
     * d'enregistrement ou d'analyse : [busy]), jamais montées, d'au moins [minKills] kills. [eligible] : seules comptent
     * les soirées dont une capture est arrivée dans le dossier surveillé depuis que l'option est active ; sinon
     * l'activer remonterait tout l'historique de la bibliothèque.
     */
    fun due(evenings: List<Evening>, done: (Evening) -> Boolean, now: Instant, quiet: Duration, minKills: Int, eligible: (Evening) -> Boolean, busy: Boolean): List<Evening> {
        if (busy) return emptyList()
        return evenings.filter { e ->
            !now.isBefore(e.end + quiet.toJavaDuration()) && e.kills >= minKills && eligible(e) && !done(e)
        }.sortedBy { it.end }
    }

    /**
     * Prochain instant où une soirée pas encore finie le sera (pour ne relire la bibliothèque qu'alors) ; null : aucune
     * en attente.
     */
    fun nextCheck(evenings: List<Evening>, done: (Evening) -> Boolean, now: Instant, quiet: Duration, minKills: Int, eligible: (Evening) -> Boolean): Instant? =
        evenings.filter { e -> e.kills >= minKills && eligible(e) && !done(e) }
            .map { it.end + quiet.toJavaDuration() }
            .filter { it.isAfter(now) }
            .minOrNull()
}
