package dev.highlights.pipeline

import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.montage.MontageReport
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/**
 * Une soirée de clips FTHRClips (30 s chacun, deux sauvegardés à 20 s d'écart qui partagent 10 s et un kill), de la
 * bibliothèque jusqu'au montage de soirée et au récap : une seule séance, le kill commun compté et monté une fois.
 */
class ClipsEveningIT : FunSpec({
    test("soirée de clips : statistiques, montage de soirée et récap d'une séance, sans kill en double").config(
        enabledIf = { TestMedia.available },
        timeout = 8.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val folder = Files.createDirectories(root.resolve("Clips").resolve("WARDOGS"))
        val names = DateTimeFormatter.ofPattern("ddMMMyyyy_HH-mm-ss", Locale.ENGLISH)
        // Heure de sauvegarde (fin du clip) et kills (secondes depuis le début du clip).
        val plan = listOf(
            "2026-11-21T21:00:30" to listOf(10.0, 25.0), // 21:00:00 → 21:00:30
            "2026-11-21T21:00:50" to listOf(5.0, 20.0, 21.4), // 21:00:20 → 21:00:50 : revoit le kill de 21:00:25
            "2026-11-21T21:10:30" to listOf(15.0),
            "2026-11-21T21:20:30" to listOf(12.0, 13.0),
        )
        val config = LoadedConfig(
            app = AppConfig(
                ffmpeg = FfmpegSettings(hwaccelDecode = null),
                outputDir = root.resolve("out").toString(),
                profilesDir = Path("../config/profiles").toAbsolutePath().toString(),
                workDir = root.resolve("work").toString(),
            ),
            baseDir = Path("../config").toAbsolutePath().normalize(),
            source = null,
        )
        val pipeline = Pipelines.create(config, ffmpeg)
        val profile = pipeline.profiles.byId("wardogs")
        plan.forEachIndexed { i, (saved, kills) ->
            val name = "WARDOGS_clip_from_${LocalDateTime.parse(saved).format(names)}.mp4"
            val video = TestMedia.generate(folder.resolve(name), durationSeconds = 30)
            val media = ffmpeg.probe(video)
            val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
            val session = Session(
                createdAt = Instant.EPOCH,
                media = media,
                profileId = "wardogs",
                timeline = ScoredTimeline(grid, List(grid.count) { w -> if (kills.any { k -> kotlin.math.abs(k - w) <= 2 }) 0.9 else 0.3 }, emptyMap(), events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "notifications") }),
                highlights = emptyList(),
            )
            val file = root.resolve("out").resolve("sessions").resolve("clip$i.session.json")
            SessionStore.save(session, file)
            pipeline.library.record(
                LibraryEntry(
                    video, video.fileSize(), video.getLastModifiedTime().toMillis(), "wardogs", AnalysisLibrary.fingerprint(profile), file,
                    Instant.now(), media.duration, media.recordedAt, mapOf("kill" to kills.size),
                ),
            )
        }
        val music = root.resolve("musique.wav")
        ffmpeg.run(
            FfmpegCommand(
                listOf("-y", "-f", "lavfi", "-i", "aevalsrc='0.7*sin(2*PI*(50+90*exp(-mod(t\\,0.5)*28))*mod(t\\,0.5))*exp(-mod(t\\,0.5)*12)*(0.4+0.6*gte(t\\,20))':s=44100:d=60", music.toString()),
                "musique de test",
            ),
        )

        // Statistiques : quatre clips, une soirée, une séance ; le kill vu par deux clips compte une fois (8 - 1).
        val games = pipeline.statistics()
        games shouldHaveSize 4
        games.all { it.clip } shouldBe true
        val evening = pipeline.evenings().single()
        evening.kills shouldBe 7
        evening.sessionFiles shouldHaveSize 4
        evening.parts.map { it.games.size } shouldBe listOf(4)

        // Montage de soirée : tous les clips, sans le doublon (le kill à 5 s du deuxième clip n'est jamais monté).
        val montage = runBlocking {
            pipeline.eveningMontage(evening, music, MontageOptions(formats = listOf(OutputFormat.VERTICAL), maxDuration = 30.seconds, showcase = false), ProgressReporter.NONE)
        }
        val report = Json { ignoreUnknownKeys = true }.decodeFromString(MontageReport.serializer(), montage.report.readText())
        val second = report.clips.filter { "21-00-50" in it.source }
        second.flatMap { it.kills }.none { ":05" in it } shouldBe true
        (report.clips.sumOf { it.kills.size } <= 7) shouldBe true
        pipeline.eveningHistory.done(evening) shouldBe true

        // Récap : un seul carton pour la séance de quatre clips, puis l'écran de fin.
        val recap = I18n.withLanguage("fr") {
            runBlocking { pipeline.eveningRecap(evening, RecapOptions(formats = listOf(OutputFormat.SOURCE)), ProgressReporter.NONE) }
        }
        val recapReport = SessionStore.json.decodeFromString(RecapReport.serializer(), recap.report.readText())
        recapReport.games shouldHaveSize 1
        recapReport.games.single().clips shouldBe 4
        recapReport.games.single().kills shouldBe 7
        recapReport.cards shouldHaveSize 2
        I18n.withLanguage("fr") { RecapText.clipsTitle(7) shouldBe "Clips – 7 kills" }
    }
})
