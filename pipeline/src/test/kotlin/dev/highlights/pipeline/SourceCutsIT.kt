package dev.highlights.pipeline

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import dev.highlights.editing.DefaultEditPlanner
import dev.highlights.editing.RenderCommandBuilder
import dev.highlights.editing.RenderRequest
import dev.highlights.editing.SourceCuts
import dev.highlights.editing.SourceCutter
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * La pré-découpe doit rester invisible dans le résultat. Elle repose sur de la sémantique FFmpeg (départ sur l'image
 * clé précédente, base de temps conservée, `-ss` recalé) qu'aucun test à double ne peut vérifier : on rend donc
 * réellement le même plan avec et sans pré-découpe, et on compare les images décodées une à une.
 */
class SourceCutsIT : FunSpec({
    test("rendu identique avec et sans pré-découpe").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        // Deux pistes audio distinctes : la découpe doit les garder dans l'ordre, le rendu choisit la seconde.
        val source = TestMedia.generate(
            root.resolve("capture.mp4"),
            durationSeconds = 120,
            audioTracks = listOf(
                TestMedia.AudioTrackSpec("Jeu", bursts = listOf(10.0..12.0), frequency = 440),
                TestMedia.AudioTrackSpec("Micro", bursts = listOf(30.0..32.0), frequency = 880),
            ),
        )
        val media = ffmpeg.probe(source)

        // Départs volontairement décalés des images clés (toutes les 2 s) : la découpe doit remonter avant chaque extrait.
        val clips = (0 until SourceCutter.MIN_CUTS).map { i ->
            TimeRange((5.3 + i * 8).seconds, (10.3 + i * 8).seconds)
        }
        val session = Session(
            createdAt = java.time.Instant.EPOCH,
            media = media,
            profileId = "test",
            timeline = ScoredTimeline(WindowGrid(1.seconds, 1.seconds, 1.seconds), listOf(0.0), emptyMap()),
            highlights = clips.mapIndexed { i, r -> Highlight("h$i", media.path, r, r.start, 1.0) },
        )
        val plan = DefaultEditPlanner.plan(
            session,
            EditSettings(formats = listOf(OutputFormat.SOURCE), sourceHeight = 360, audioStreams = listOf(1)),
        )
        val encoder = EncoderProfile("libx264", listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "28"), hardware = false)

        suspend fun render(name: String, cuts: SourceCuts): Path {
            val script = root.resolve("$name.txt")
            val output = root.resolve("$name.mp4")
            val command = RenderCommandBuilder.build(
                RenderRequest(plan, OutputFormat.SOURCE, encoder, output, script, cuts = cuts),
            )
            script.writeText(command.filterGraph)
            ffmpeg.run(command.command)
            return output
        }

        suspend fun frameHashes(video: Path, stream: String): List<String> {
            val lines = mutableListOf<String>()
            ffmpeg.run(
                FfmpegCommand(listOf("-v", "error", "-i", video.toString(), "-map", stream, "-f", "framemd5", "-"), "empreintes $stream de $video"),
                StdoutHandler.Lines { if (!it.startsWith("#")) lines += it.trim() },
            )
            return lines.filter { it.isNotEmpty() }
        }

        val cuts = SourceCutter.prepare(ffmpeg, RenderCommandBuilder.sourceCuts(plan), root.resolve("cuts").createDirectories())
        cuts.size shouldBe clips.size
        // La découpe recule jusqu'à l'image clé, donc le rendu ne redemande pas le même instant qu'en lisant la source.
        cuts.input(media, clips[1]).start shouldNotBe clips[1].start

        val direct = render("direct", SourceCuts.NONE)
        val precut = render("precut", cuts)

        val expectedVideo = frameHashes(direct, "0:v:0")
        expectedVideo shouldHaveAtLeastSize 1000
        frameHashes(precut, "0:v:0") shouldBe expectedVideo

        // La piste choisie doit rester la même : `-map 0:a?` conserve l'ordre des pistes dans la découpe.
        val expectedAudio = frameHashes(direct, "0:a:0")
        expectedAudio shouldHaveAtLeastSize 100
        frameHashes(precut, "0:a:0") shouldBe expectedAudio
    }
})
