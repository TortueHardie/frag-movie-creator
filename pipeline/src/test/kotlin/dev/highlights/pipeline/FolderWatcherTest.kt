package dev.highlights.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class FolderWatcherTest : FunSpec({
    val start = Instant.parse("2026-09-23T20:00:00Z")

    fun Path.modifiedAt(at: Instant): Path = also { Files.setLastModifiedTime(it, FileTime.from(at)) }

    test("une capture n'est proposée qu'une fois terminée, et une seule fois") {
        val root = tempdir().toPath()
        var now = start
        val watcher = FolderWatcher(root, since = start, settle = 15.seconds, clock = { now })
        val game = Files.createDirectories(root.resolve("League of Legends"))
        val video = game.resolve("partie.mp4").also { it.writeText("début") }.modifiedAt(start.plusSeconds(5))

        now = start.plusSeconds(10)
        watcher.poll { false }.shouldBeEmpty()
        // L'enregistreur écrit encore : l'attente repart.
        now = start.plusSeconds(20)
        video.appendText(" suite")
        video.modifiedAt(start.plusSeconds(20))
        watcher.poll { false }.shouldBeEmpty()
        now = start.plusSeconds(30)
        watcher.poll { false }.shouldBeEmpty()
        now = start.plusSeconds(36)
        watcher.poll { false } shouldBe listOf(video.toAbsolutePath().normalize())
        now = start.plusSeconds(60)
        watcher.poll { false }.shouldBeEmpty()

        // Réécrite (réencodée, coupée…), elle est reproposée.
        video.appendText(" encore")
        video.modifiedAt(start.plusSeconds(61))
        watcher.poll { false }.shouldBeEmpty()
        now = start.plusSeconds(80)
        watcher.poll { false } shouldBe listOf(video.toAbsolutePath().normalize())
    }

    test("ignore les captures anciennes, déjà analysées ou qui ne sont pas des vidéos") {
        val root = tempdir().toPath()
        var now = start
        val watcher = FolderWatcher(root, since = start, settle = 1.seconds, clock = { now })
        root.resolve("ancienne.mp4").also { it.writeText("x") }.modifiedAt(start.minusSeconds(3600))
        val analyzed = root.resolve("analysee.mkv").also { it.writeText("x") }.modifiedAt(start.plusSeconds(1))
        root.resolve("notes.txt").also { it.writeText("x") }.modifiedAt(start.plusSeconds(1))
        val fresh = root.resolve("nouvelle.MP4").also { it.writeText("x") }.modifiedAt(start.plusSeconds(2))

        watcher.poll { false }.shouldBeEmpty()
        now = start.plusSeconds(10)
        watcher.poll { it == analyzed.toAbsolutePath().normalize() } shouldBe listOf(fresh.toAbsolutePath().normalize())
    }

    test("dossier absent : rien, sans erreur") {
        FolderWatcher(tempdir().toPath().resolve("absent"), since = start).poll { false }.shouldBeEmpty()
    }
})
