package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.config.AppConfig
import dev.highlights.core.config.FfmpegSettings
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.Session
import dev.highlights.core.serialization.toTimecode
import dev.highlights.montage.KillRef
import dev.highlights.montage.MontageChoiceRecord
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontageReport
import dev.highlights.montage.MontageVariantsFile
import dev.highlights.testing.TestMedia
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/** Aperçus de variantes puis rendu final de la variante choisie : même montage, image pour image, et choix enregistré. */
class MontageVariantsIT : FunSpec({
    test("aperçus 360p, puis variante choisie rendue en pleine qualité avec la même empreinte").config(enabledIf = { TestMedia.available }, timeout = 8.seconds * 60) {
        val root = tempdir().toPath()
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = TestMedia.generate(Files.createDirectories(root.resolve("WARDOGS")).resolve("partie.mp4"), durationSeconds = 90)
        val music = root.resolve("musique.wav")
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-y", "-f", "lavfi",
                    "-i", "aevalsrc='0.7*sin(2*PI*(50+90*exp(-mod(t\\,0.5)*28))*mod(t\\,0.5))*exp(-mod(t\\,0.5)*12)*(0.4+0.6*gte(t\\,30))':s=44100:d=70",
                    music.toString(),
                ),
                "musique de test",
            ),
        )
        val media = ffmpeg.probe(video)
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        val session = Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "wardogs",
            timeline = ScoredTimeline(
                grid, List(grid.count) { 0.5 }, emptyMap(),
                events = listOf(8.0, 16.0, 20.0, 21.4, 32.0, 44.0, 52.0, 60.0, 70.0, 80.0).map { TimelineEvent(it.seconds, "kill", 1.0, "notifications") },
            ),
            highlights = emptyList(),
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
        val options = MontageOptions(formats = listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL), maxDuration = 20.seconds, motionBlur = true)
        val json = Json { ignoreUnknownKeys = true }

        val previews = pipeline.montagePreviews(listOf(session), music, options, 3, ProgressReporter.NONE)
        previews.previews.shouldNotBeEmpty()
        val described = MontageVariantsFile.load(previews.variantsFile)
        described.variants.map { it.index } shouldBe (1..previews.previews.size).toList()
        previews.previews.forEach { p ->
            // Aperçu : le vertical seul, 360 pixels sur le petit côté, son rapport marqué aperçu et à la même empreinte.
            val probe = ffmpeg.probe(p.video)
            (probe.video?.width to probe.video?.height) shouldBe (360 to 640)
            val report = json.decodeFromString(MontageReport.serializer(), p.report.readText())
            report.preview shouldBe true
            report.signature shouldBe p.entry.signature
            (probe.duration.inWholeMilliseconds / 1000.0) shouldBe (report.durationSeconds plusOrMinus 0.1)
            p.frames.shouldNotBeEmpty()
        }
        pipeline.latestVariantsFile(listOf(session), music, null) shouldBe previews.variantsFile

        val first = previews.previews.first()
        val result = pipeline.killMontage(listOf(session), music, options.copy(pick = VariantPick(1, previews.variantsFile)), ProgressReporter.NONE)
        val final = json.decodeFromString(MontageReport.serializer(), result.report.readText())
        final.preview shouldBe false
        final.signature shouldBe first.entry.signature
        // Même montage, image pour image : mêmes kills aux mêmes instants que dans l'aperçu.
        val previewReport = json.decodeFromString(MontageReport.serializer(), first.report.readText())
        final.clips.map { it.killsInMontage } shouldBe previewReport.clips.map { it.killsInMontage }
        final.durationSeconds shouldBe previewReport.durationSeconds
        val vertical = ffmpeg.probe(result.videos.getValue(OutputFormat.VERTICAL))
        (vertical.video?.width to vertical.video?.height) shouldBe (1080 to 1920)
        val choice = MontageChoiceRecord.load(MontageChoiceRecord.fileFor(result.report))
        choice.chosen shouldBe 1
        choice.signature shouldBe first.entry.signature
        choice.variants shouldBe described.variants
        // Goûts : le choix est gardé pour l'apprentissage, critères de chaque variante proposée compris.
        val taste = pipeline.tasteHistory.load()
        if (previews.previews.size >= 2) {
            taste.choices.single().chosen shouldBe 0
            taste.choices.single().variants shouldBe described.variants.map { it.score.criteria() }
        }

        // Aperçu qui ne correspond plus (empreinte changée) : le rendu est refusé plutôt que de rendre autre chose.
        val tampered = root.resolve("tampered_variantes.json")
        tampered.writeText(previews.variantsFile.readText().replace(first.entry.signature, "0000000000000000"))
        shouldThrow<InputException> {
            pipeline.killMontage(listOf(session), music, options.copy(pick = VariantPick(1, tampered)), ProgressReporter.NONE)
        }
        shouldThrow<InputException> {
            pipeline.killMontage(listOf(session), music, options.copy(pick = VariantPick(50, null)), ProgressReporter.NONE)
        }
        MontageChoiceRecord.fileFor(result.report).exists() shouldBe true

        // Corrections de l'éditeur de plan : un kill exclu n'apparaît dans aucune variante.
        val excluded = pipeline.planKillMontage(listOf(session), music, options, ProgressReporter.NONE).plan.clips.first { it.kills.isNotEmpty() }.kills.first()
        val constrained = pipeline.montagePreviews(
            listOf(session), music, options.copy(constraints = MontageConstraints(excluded = setOf(KillRef.of(media, excluded)))), 2, ProgressReporter.NONE,
        )
        constrained.previews.shouldNotBeEmpty()
        constrained.previews.forEach { p ->
            val report = json.decodeFromString(MontageReport.serializer(), p.report.readText())
            report.clips.flatMap { it.kills }.none { it == excluded.toTimecode() } shouldBe true
        }
        // Rendu avec des corrections de l'éditeur : elles sont gardées aussi.
        pipeline.killMontage(
            listOf(session), music,
            options.copy(constraints = MontageConstraints(excluded = setOf(KillRef.of(media, excluded))), pick = VariantPick(1, constrained.variantsFile)),
            ProgressReporter.NONE,
        )
        pipeline.tasteHistory.load().edits.single().excluded shouldBe listOf(KillRef.of(media, excluded).toString())
    }
})
