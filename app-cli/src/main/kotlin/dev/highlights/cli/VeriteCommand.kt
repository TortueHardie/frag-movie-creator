package dev.highlights.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.core.HighlightsException
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.serialization.toShortText
import dev.highlights.core.serialization.toTimecode
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.pipeline.AnalyzeOptions
import dev.highlights.pipeline.Pipelines
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * `app verite` : réanalyse les captures dont la vérité terrain est relevée (fichiers YAML de `verite/<jeu>/`) et mesure chaque
 * détecteur (précision, rappel). Échoue (code 1) sous un seuil enregistré dans le fichier de vérité. Lancée à la main
 * (ou par `gradlew :app-cli:verite`) : il faut les captures, absentes du dépôt, et une analyse complète par capture.
 */
class VeriteCommand : PipelineCommand("verite") {
    private val sources by argument("VERITE", help = "Fichiers de vérité (.yaml) ou dossiers qui en contiennent (défaut : ./verite)")
        .path(mustExist = true).multiple()
    private val videos by option("--videos", help = "Dossier où chercher les captures (sous-dossiers compris), répétable")
        .path(mustExist = true, canBeFile = false).multiple()
    private val sessions by option("--sessions", help = "Mesurer les sessions déjà analysées de ce dossier au lieu de réanalyser (rapide, mais mesure l'analyse d'alors)")
        .path(mustExist = true, canBeFile = false)
    private val out by option("-o", "--out", help = "Dossier des analyses refaites et du rapport (défaut : <dossier de travail>/verite)")
        .path(canBeFile = false)

    override fun help(context: Context) =
        "Mesure les détecteurs contre la vérité terrain (kills et morts relevés à la main) : précision et rappel par " +
            "détecteur et par capture, échec sous les seuils enregistrés. Réanalyse chaque capture, sauf avec --sessions."

    override fun run() {
        val files = truthFiles()
        if (files.isEmpty()) {
            echo("Aucun fichier de vérité trouvé.", err = true)
            throw ProgramResult(2)
        }
        val outDir = (out ?: env.config.workDir.resolve("verite")).toAbsolutePath().normalize()
        // Analyses refaites rangées à part, mémoire d'analyses comprise : la bibliothèque de l'utilisateur n'est pas touchée.
        val config = env.config.copy(app = env.config.app.copy(outputDir = outDir.toString()))
        val results = files.map { file ->
            val truth = execute { GroundTruth.load(file) }
            echo("== ${truth.capture.name}  (${file.name})")
            if (truth.validated == null) echo("  Attention : vérité non validée par l'utilisateur", err = true)
            val session = execute { sessionFor(truth, config) }
            val scores = TruthScoring.score(truth, session.timeline.events)
            printScores(truth, scores)
            CaptureResult(file, truth, session.createdAt, scores)
        }
        val failures = results.flatMap { r ->
            r.scores.flatMap { s -> s.failures(r.truth.thresholds[s.detector]?.get(s.kind)).map { "${r.truth.capture.name} ${s.detector}/${s.kind} : $it" } }
        }
        val report = outDir.resolve("verite-rapport.json")
        outDir.createDirectories()
        report.writeText(reportJson.encodeToString(VeriteReport.serializer(), VeriteReport.of(results, failures)))
        echo("")
        echo("Rapport : $report")
        if (failures.isNotEmpty()) {
            echo("ÉCHEC, sous les seuils :", err = true)
            failures.forEach { echo("  $it", err = true) }
            throw ProgramResult(1)
        }
        echo("Tous les seuils sont tenus.")
    }

    private fun truthFiles(): List<Path> =
        sources.ifEmpty { listOf(Path("verite")).filter { it.isDirectory() } }.flatMap { p ->
            if (p.isDirectory()) Files.walk(p).use { w -> w.filter { it.isRegularFile() && it.extension in setOf("yaml", "yml") }.sorted().toList() }
            else listOf(p)
        }

    private suspend fun sessionFor(truth: GroundTruth, config: dev.highlights.core.config.LoadedConfig): Session {
        sessions?.let { dir ->
            val session = recordedSession(dir, truth)
                ?: throw HighlightsException("aucune session de ${truth.capture.name} (${truth.capture.sizeBytes} octets) dans $dir")
            echo("  session enregistrée du ${session.createdAt} (pas de réanalyse)")
            return checked(truth, session)
        }
        val capture = findCapture(truth)
        echo("  réanalyse de $capture…")
        val progress = ConsoleProgress()
        try {
            val outcome = Pipelines.create(config).analyze(
                capture, AnalyzeOptions(profileId = truth.profile, reuse = false), ProgressTracker(listener = progress).root,
            )
            return checked(truth, outcome.session)
        } finally {
            progress.finish()
        }
    }

    /** La capture est bien celle de la vérité : même taille, même durée à la seconde près. */
    private fun checked(truth: GroundTruth, session: Session): Session {
        val media = session.media
        if (media.sizeBytes != truth.capture.sizeBytes || (media.duration - truth.capture.duration).absoluteValue > 1.seconds) {
            throw HighlightsException(
                "${media.path} ne correspond pas à la vérité (${media.sizeBytes} octets, ${media.duration.toShortText()} ; " +
                    "attendu ${truth.capture.sizeBytes} octets, ${truth.capture.duration.toShortText()})",
            )
        }
        return session
    }

    private fun findCapture(truth: GroundTruth): Path {
        if (videos.isEmpty()) throw HighlightsException("--videos manquant : dossier des captures (ou --sessions pour mesurer les analyses enregistrées)")
        val found = videos.asSequence().flatMap { dir ->
            Files.walk(dir, 4).use { w -> w.filter { it.isRegularFile() && it.name.equals(truth.capture.name, ignoreCase = true) }.toList() }
        }.toList()
        return found.firstOrNull { it.fileSize() == truth.capture.sizeBytes }
            ?: throw HighlightsException(
                if (found.isEmpty()) "${truth.capture.name} introuvable dans ${videos.joinToString()}"
                else "${truth.capture.name} trouvée mais d'une autre taille que ${truth.capture.sizeBytes} octets : ${found.joinToString()}",
            )
    }

    /** Session la plus récente de la capture dans [dir] (même nom de fichier, même taille). */
    private fun recordedSession(dir: Path, truth: GroundTruth): Session? =
        Files.list(dir).use { s -> s.filter { it.name.endsWith(".session.json") }.toList() }
            .mapNotNull { runCatching { SessionStore.load(it) }.getOrNull() }
            .filter { it.media.path.name.equals(truth.capture.name, ignoreCase = true) && it.media.sizeBytes == truth.capture.sizeBytes }
            .maxByOrNull { it.createdAt }

    private fun printScores(truth: GroundTruth, scores: List<DetectorScore>) {
        val counts = truth.kinds.joinToString { k -> "${truth.events.count { it.kind == k }} $k" }
        echo("  vérité : $counts (tolérance ${truth.tolerance.toShortText()})")
        echo("  %-16s %-8s %5s %5s %5s %9s %7s  %s".format("détecteur", "sorte", "vrais", "faux", "manqués", "précision", "rappel", "seuil"))
        scores.forEach { s ->
            val threshold = truth.thresholds[s.detector]?.get(s.kind)
            val verdict = when {
                threshold == null -> "-"
                s.failures(threshold).isEmpty() -> "OK (p ≥ %.2f, r ≥ %.2f)".format(threshold.precision, threshold.recall)
                else -> "ÉCHEC (p ≥ %.2f, r ≥ %.2f)".format(threshold.precision, threshold.recall)
            }
            echo(
                "  %-16s %-8s %5d %5d %7d %9s %7s  %s".format(
                    s.detector, s.kind, s.hits.size, s.falseAlarms.size, s.missed.size, ratio(s.precision), ratio(s.recall), verdict,
                ),
            )
            if (s.missed.isNotEmpty()) echo("      manqués : ${s.missed.joinToString { it.toTimecode() }}")
            if (s.falseAlarms.isNotEmpty()) echo("      faux    : ${s.falseAlarms.joinToString { it.toTimecode() }}")
        }
    }

    private fun ratio(v: Double?) = v?.let { "%.2f".format(it) } ?: "-"

    private class CaptureResult(val file: Path, val truth: GroundTruth, val analyzedAt: Instant, val scores: List<DetectorScore>)

    @Serializable
    private data class VeriteReport(val generatedAt: String, val captures: List<CaptureReport>, val failures: List<String>) {
        companion object {
            fun of(results: List<CaptureResult>, failures: List<String>) = VeriteReport(
                Instant.now().toString(),
                results.map { r ->
                    CaptureReport(
                        r.truth.capture.name, r.file.toString(), r.analyzedAt.toString(), r.truth.validated,
                        r.scores.map { s ->
                            ScoreReport(
                                s.detector, s.kind, s.hits.size, s.falseAlarms.size, s.missed.size, s.precision, s.recall,
                                s.missed.map { it.toTimecode() }, s.falseAlarms.map { it.toTimecode() },
                            )
                        },
                    )
                },
                failures,
            )
        }
    }

    @Serializable
    private data class CaptureReport(val capture: String, val truthFile: String, val analyzedAt: String, val validated: String?, val scores: List<ScoreReport>)

    @Serializable
    private data class ScoreReport(
        val detector: String, val kind: String, val hits: Int, val falseAlarms: Int, val missed: Int,
        val precision: Double?, val recall: Double?, val missedAt: List<String>, val falseAt: List<String>,
    )

    private companion object {
        val reportJson = Json { prettyPrint = true }
    }
}
