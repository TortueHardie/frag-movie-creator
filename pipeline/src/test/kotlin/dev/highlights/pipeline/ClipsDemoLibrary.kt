package dev.highlights.pipeline

import dev.highlights.core.model.Highlight
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.time.Duration.Companion.seconds

/**
 * Pas un test : prépare une bibliothèque de démonstration (une soirée de clips FTHRClips synthétiques et une partie OBS)
 * pour les captures d'écran de l'application. Ne tourne que si `CLIPS_DEMO_DIR` désigne un dossier (jamais celui de
 * l'utilisateur) : `<dossier>/clips` (vidéos) et `<dossier>/out/sessions` (bibliothèque).
 */
class ClipsDemoLibrary : FunSpec({
    test("bibliothèque de démonstration des clips courts").config(enabledIf = { System.getenv("CLIPS_DEMO_DIR") != null && TestMedia.available }) {
        val root = Path(System.getenv("CLIPS_DEMO_DIR"))
        val ffmpeg = TestMedia.requireFfmpeg()
        val profiles = ProfileRepository.loadDirectory(Path("../config/profiles").toAbsolutePath())
        val library = AnalysisLibrary(root.resolve("out").resolve("sessions").resolve("library.json"))
        val names = DateTimeFormatter.ofPattern("ddMMMyyyy_HH-mm-ss", Locale.ENGLISH)
        fun add(file: java.nio.file.Path, profileId: String, kills: List<Double>, deaths: List<Double> = emptyList(), index: Int) = runBlocking {
            val media = ffmpeg.probe(file)
            val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
            val events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "game-events") } + deaths.map { TimelineEvent(it.seconds, "death", 1.0, "game-events") }
            val highlights = kills.mapIndexed { i, k ->
                Highlight("h${i + 1}", media.path, TimeRange((k - 4).coerceAtLeast(0.0).seconds, (k + 2).coerceAtMost(media.duration.inWholeMilliseconds / 1000.0).seconds), k.seconds, 0.7 + i * 0.01)
            }
            val session = Session(createdAt = Instant.now(), media = media, profileId = profileId, timeline = ScoredTimeline(grid, List(grid.count) { w -> if (kills.any { k -> kotlin.math.abs(k - w) <= 2 }) 0.9 else 0.3 }, emptyMap(), events = events.sortedBy { it.at }), highlights = highlights)
            val out = root.resolve("out").resolve("sessions").resolve("${file.fileName.toString().substringBeforeLast('.')}.session.json")
            SessionStore.save(session, out)
            library.record(
                LibraryEntry(
                    file, file.fileSize(), file.getLastModifiedTime().toMillis(), profileId, AnalysisLibrary.fingerprint(profiles.byId(profileId)), out,
                    Instant.now().plusSeconds(index.toLong()), media.duration, media.recordedAt, events.groupingBy { it.kind }.eachCount(),
                ),
            )
        }
        val valorant = Files.createDirectories(root.resolve("clips").resolve("VALORANT"))
        // Séance 1 : neuf clips de 21:04 à 22:10, dont deux sauvegardés à 15 s d'écart (le second revoit un kill).
        val first = listOf(
            "21:04:40" to listOf(12.0, 13.1), "21:09:15" to listOf(20.0), "21:17:02" to listOf(8.0, 9.2, 10.5, 38.0), "21:17:17" to listOf(23.0, 38.0),
            "21:31:44" to listOf(30.0), "21:40:03" to listOf(15.0, 16.0), "21:52:30" to listOf(22.0), "22:01:10" to listOf(10.0, 34.0), "22:10:05" to listOf(25.0),
        )
        // Séance 2, après une pause d'une heure : trois clips.
        val second = listOf("23:14:20" to listOf(18.0), "23:22:45" to listOf(9.0, 10.0, 11.4, 12.2), "23:30:00" to listOf(27.0))
        (first + second).forEachIndexed { i, (time, kills) ->
            val name = "VALORANT_clip_from_${LocalDateTime.parse("2026-11-21T$time").format(names)}.mp4"
            val video = runBlocking { TestMedia.generate(valorant.resolve(name), durationSeconds = 45) }
            add(video, "valorant", kills, index = i)
        }
        // La veille : une partie entière enregistrée par OBS (nommée par OBS ; vidéo courte, durée lue telle quelle).
        val obs = Files.createDirectories(root.resolve("clips").resolve("OBS"))
        val game = runBlocking { TestMedia.generate(obs.resolve("2026-11-20 20-30-00.mp4"), durationSeconds = 360) }
        add(game, "valorant", listOf(40.0, 41.0, 120.0, 200.0, 310.0), deaths = listOf(60.0, 150.0, 250.0), index = 20)
    }
})
