package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Le montage porte la date de la partie, jamais celle du rendu (QA finale : `valorant_2026-11-18_killmontage` pour le 17). */
class RecordingDateTest : FunSpec({
    fun session(path: Path, created: Instant? = null, analyzed: String = "2026-11-18T09:00:00Z"): Session {
        val media = MediaInfo(path, 1, 30.minutes, creationTime = created)
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(createdAt = Instant.parse(analyzed), media = media, profileId = "valorant", timeline = ScoredTimeline(grid, List(grid.count) { 0.0 }, emptyMap()), highlights = emptyList())
    }

    test("date inscrite dans la capture : elle nomme le montage") {
        val s = session(Path.of("absente.mp4"), created = Instant.parse("2026-11-17T21:30:00Z"))
        KillMontageExporter.recordingDate(listOf(s), ZoneOffset.UTC) shouldBe LocalDate.of(2026, 11, 17)
    }

    test("sans date inscrite (capture VALORANT de la QA) : la date du fichier, pas celle du rendu") {
        val file = tempdir().toPath().resolve("Valorant- 44 kills.mp4").also { it.writeText("") }
        file.setLastModifiedTime(FileTime.from(Instant.parse("2026-11-17T22:10:00Z")))
        KillMontageExporter.recordingDate(listOf(session(file)), ZoneOffset.UTC) shouldBe LocalDate.of(2026, 11, 17)
    }

    test("plusieurs parties : la première jouée ; rien de lisible : la date de l'analyse") {
        val a = session(Path.of("a.mp4"), created = Instant.parse("2026-11-17T23:50:00Z"))
        val b = session(Path.of("b.mp4"), created = Instant.parse("2026-11-16T20:00:00Z"))
        KillMontageExporter.recordingDate(listOf(a, b), ZoneOffset.UTC) shouldBe LocalDate.of(2026, 11, 16)
        KillMontageExporter.recordingDate(listOf(session(Path.of("introuvable.mp4"), analyzed = "2026-11-15T10:00:00Z")), ZoneOffset.UTC) shouldBe LocalDate.of(2026, 11, 15)
    }
})
