package dev.highlights.pipeline

import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Vérification sur de vraies captures, en lecture seule (ffprobe seulement) : `REAL_RECORDINGS_DIR=<dossier>`. Écrit
 * pour chaque capture l'heure lue, clip ou partie, puis les soirées et les séances que les statistiques en feraient
 * (jeu supposé connu : le nom FTHR ne le dit pas). Inactif sans la variable.
 */
class RealRecordingsCheck : FunSpec({
    val dir = System.getenv("REAL_RECORDINGS_DIR")?.let { Path(it) }
    test("vraies captures : heure, clip ou partie, soirées et séances").config(enabledIf = { dir != null && TestMedia.available }) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val files = dir!!.listDirectoryEntries().filter { Files.isRegularFile(it) && it.extension.lowercase() in setOf("mp4", "mkv", "mov") }.sorted()
        val games = files.map { file ->
            val media = ffmpeg.probe(file)
            println("${file.name} : ${media.duration}, début ${media.recordedAt}, fiable ${media.reliableTime}, clip ${media.isClip}, creation_time ${media.creationTime}")
            GameStats(
                Path("${file.name}.session.json"), file, "valorant", "VALORANT", media.recordedAt!!, media.duration, 1, null, null, emptyMap(), 0, 0, null, null,
                sizeBytes = media.sizeBytes, clip = media.isClip, reliableTime = media.reliableTime,
            )
        }
        val distinct = Statistics.distinctGames(games)
        println("parties distinctes : ${distinct.size} / ${games.size}")
        Statistics.evenings(distinct).forEach { e ->
            println("soirée ${e.date} : ${e.games.size} captures, ${e.clips} clips, ${e.parts.size} séances")
            e.parts.forEach { p -> println("  séance ${p.start} → ${p.end} : ${p.games.size} × ${if (p.clips) "clip" else "partie"} ${p.games.map { it.source.name }}") }
        }
    }
})
