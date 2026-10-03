package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.WhipPanEffect
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Souffle des whip pans rendu pour de vrai : le graphe doit passer dans FFmpeg, et le souffle s'entendre sur la coupe.
 * La vidéo de test n'a ni tir ni musique dans la bande du souffle (800 à 3000 Hz) : ce qu'on y mesure vient de lui.
 */
class WhooshIT : FunSpec({
    test("whip pan : le souffle s'entend sur la coupe, et plus du tout quand il est coupé").config(
        enabledIf = { TestMedia.available },
        timeout = 5.seconds * 60,
    ) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = TestMedia.generate(root.resolve("partie.mp4"), durationSeconds = 40, audioTracks = listOf(TestMedia.AudioTrackSpec("Game", emptyList())))
        val musicFile = root.resolve("musique.wav")
        ffmpeg.run(FfmpegCommand(listOf("-y", "-f", "lavfi", "-i", "sine=f=110:d=60", musicFile.toString()), "musique de test"))
        val media = ffmpeg.probe(video)
        val music = MusicAnalysis(
            musicFile, 60.seconds, 120.0, List(120) { (it * 0.5).seconds }, 0, DoubleArray(120) { 1.0 }, DoubleArray(120) { 0.8 },
            listOf(MusicSection(0, 48, -14.0, 0.4), MusicSection(48, 120, -8.0, 1.0)), 48,
        )
        val flick = KillTraits(flick = 1.0, direction = FlickDirection.RIGHT)
        val groups = listOf(
            KillGroup(media, listOf(10.seconds, 12.seconds), 1.0, emptyList(), emptyList(), traits = listOf(flick, flick)),
            KillGroup(media, listOf(25.seconds), 0.5, emptyList(), emptyList()),
        )
        val edit = EditSettings(fps = 30, sourceHeight = 360)
        val encoder = EncoderProfile("libx264", listOf("-c:v", "libx264", "-preset", "ultrafast"), false)

        /** Volume moyen (dB) de la bande du souffle autour de la coupe du plan 1. */
        suspend fun render(whip: WhipPanEffect): Double {
            val settings = MontageSettings(killOffset = Duration.ZERO, whip = whip)
            val plan = MontagePlanner.plan(groups, music, settings)
            MontageRenderBuilder.whips(plan)[1] shouldBe (if (whip.enabled) FlickDirection.RIGHT else null)
            val out = root.resolve("montage_${whip.whoosh}.mp4")
            val script: Path = root.resolve("filtres_${whip.whoosh}.txt")
            val render = MontageRenderBuilder.build(
                MontageRenderRequest(plan, OutputFormat.SOURCE, edit, encoder, out, script, filterScriptOption = ffmpeg.filterScriptOption()),
            )
            script.writeText(render.filterGraph)
            ffmpeg.run(render.command)
            val cut = plan.clipOffsets()[1].inWholeMilliseconds / 1000.0
            var mean = Double.NaN
            ffmpeg.run(
                FfmpegCommand(
                    listOf(
                        "-ss", "%.3f".format(java.util.Locale.ROOT, cut - 0.35), "-t", "0.4", "-i", out.toString(),
                        "-af", "highpass=f=800,lowpass=f=3000,volumedetect", "-vn", "-f", "null", "-",
                    ),
                    "mesure du souffle",
                ),
                onStderrLine = { line -> Regex("""mean_volume: (-?[\d.]+) dB""").find(line)?.let { mean = it.groupValues[1].toDouble() } },
            )
            return mean
        }

        val loud = render(WhipPanEffect())
        val quiet = render(WhipPanEffect(whoosh = 0.0))
        // Mesuré : −35 dB avec le souffle, −48 dB sans (le peu qui reste de la musique).
        (loud - quiet) shouldBeGreaterThan 10.0
    }
})
