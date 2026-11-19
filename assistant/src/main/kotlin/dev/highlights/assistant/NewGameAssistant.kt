package dev.highlights.assistant

import dev.highlights.core.InputException
import dev.highlights.core.config.LoadedConfig
import dev.highlights.ffmpeg.ProcessFfmpegService
import dev.highlights.core.i18n.Message
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.pipeline.AnalyzeOptions
import dev.highlights.pipeline.Pipelines
import dev.highlights.vision.OcrLine
import dev.highlights.vision.RewardsLog
import dev.highlights.vision.WindowsOcr
import dev.highlights.core.serialization.Durations
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.text.Normalizer
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/** Demande : la capture, le nom du jeu, 1 à 3 kills marqués ; [id] du profil (défaut : tiré du nom). */
data class NewGameRequest(val video: Path, val game: String, val marks: List<KillMark>, val id: String? = null) {
    init {
        require(game.isNotBlank()) { "nom du jeu vide" }
        require(marks.isNotEmpty()) { "au moins un kill marqué" }
    }

    val profileId: String get() = id ?: NewGameAssistant.slug(game)
}

/** Kill (ou mort) trouvé par un candidat ; [score] : corrélation du modèle d'image, 1 pour les autres méthodes. */
data class Hit(val at: Duration, val kind: String, val score: Double = 1.0)

/** Résultat d'un candidat sur toute la capture. [sample] : les vignettes de la planche, dans l'ordre. */
data class CandidateResult(
    val candidate: Candidate,
    val hits: List<Hit>,
    val marksFound: Int,
    val sample: List<Hit>,
    val sheet: Path?,
) {
    val kills: List<Hit> get() = hits.filter { it.kind == "kill" }
}

/** Balayage : un résultat par méthode essayée, et les méthodes écartées avant (raison en clair). */
data class NewGameScan(val request: NewGameRequest, val media: MediaInfo, val results: List<CandidateResult>, val skipped: Map<CueMethod, Message>)

/** Choix fait par l'assistant, d'après les vignettes confirmées. */
data class Choice(val result: CandidateResult, val precision: Double, val estimatedKills: Double)

/**
 * Assistant « nouveau jeu » : à partir d'une capture et de 1 à 3 kills marqués (instant + cadre autour du repère),
 * construit un détecteur par méthode applicable (modèle d'image, ligne de couleur, compteur, texte), les fait tourner
 * ensemble sur toute la capture (un seul décodage, comme une analyse normale), puis montre une planche par méthode pour
 * que l'utilisateur confirme ou rejette quelques détections. La méthode qui garde le plus de vrais kills en écrivant le
 * moins de faux est écrite dans `profiles/<jeu>.yaml`, sans jamais remplacer un profil existant.
 */
class NewGameAssistant(private val config: LoadedConfig, private val ffmpeg: ProcessFfmpegService) {

    /** Image de la capture à [at], en PNG dans [out] : l'écran où l'utilisateur trace le cadre du repère. */
    suspend fun preview(video: Path, at: Duration, out: Path): Path {
        val stream = ffmpeg.probe(video).video ?: throw InputException(Message("error.newgame.noVideo", "file" to video))
        val frame = FrameGrabber(ffmpeg, video, stream.width, stream.height).at(at)
        out.parent?.createDirectories()
        ImageIO.write(frame.toImage(), "png", out.toFile())
        return out
    }

    /** Fait tourner toutes les méthodes applicables ; [workDir] reçoit la configuration d'essai et les planches. */
    suspend fun scan(request: NewGameRequest, workDir: Path, progress: ProgressReporter): NewGameScan {
        val media = ffmpeg.probe(request.video)
        val video = media.video ?: throw InputException(Message("error.newgame.noVideo", "file" to request.video))
        val frames = FrameGrabber(ffmpeg, request.video, video.width, video.height)
        val prepare = progress.child(tr("progress.newgame.prepare"), 0.1)
        // Pour chaque kill : l'image où le repère est affiché (0,6 s après son apparition) et une image d'avant.
        val pairs = request.marks.map { m -> frames.at(m.at - 2.seconds) to frames.at(m.at + CUE_DELAY) }
        val boxes = request.marks.map { m -> pairs[0].second.pixelsOf(m.box) }
        val skipped = linkedMapOf<CueMethod, Message>()
        val candidates = mutableListOf<Candidate>()
        val id = request.profileId
        template(id, pairs, boxes, video.width, video.height)?.let { candidates += it }
        colorLine(pairs, boxes, video.width, video.height).let { (c, why) -> if (c != null) candidates += c else skipped[CueMethod.COLOR_LINE] = why }
        candidates += counter(boxes, video.width, video.height)
        ocr(workDir, frames, request, pairs, boxes, media).let { (c, why) -> if (c != null) candidates += c else skipped[CueMethod.OCR] = why }
        prepare.complete()

        // Configuration d'essai : une copie de celle de l'utilisateur, un profil qui contient tous les candidats.
        val trial = workDir.resolve("essai").createDirectories()
        val profiles = trial.resolve("profiles").createDirectories()
        candidates.forEach { c -> c.files.forEach { (path, bytes) -> trial.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes) } }
        profiles.resolve("$TRIAL_ID.yaml").writeText(trialProfile(candidates))
        val trialConfig = LoadedConfig(
            config.app.copy(profilesDir = profiles.toString(), outputDir = trial.resolve("sortie").toString(), workDir = config.workDir.toString()),
            trial,
            null,
        )
        val outcome = Pipelines.create(trialConfig, ffmpeg)
            .analyze(request.video, AnalyzeOptions(profileId = TRIAL_ID, outputDir = trial.resolve("sortie"), reuse = false), progress.child(tr("progress.newgame.scan"), 0.75))
        val events = outcome.session.timeline.events

        val sheets = progress.child(tr("progress.newgame.sheets"), 0.15)
        val results = candidates.mapIndexed { i, c ->
            var hits = events.filter { it.detectorId == c.detectorId }.map { Hit(it.at, it.kind, it.confidence) }
            // Modèle d'image : seuil fixé d'après les kills marqués (un peu sous le plus faible), jamais sous 0,6.
            val markScores = request.marks.mapNotNull { m -> hits.filter { abs((it.at - m.at).inWholeMilliseconds) <= MARK_TOLERANCE.inWholeMilliseconds }.maxOfOrNull { it.score } }
            val minScore = if (c.method == CueMethod.TEMPLATE) ((markScores.minOrNull() ?: 0.75) - 0.05).coerceIn(0.6, 0.9) else null
            if (minScore != null) hits = hits.filter { it.score >= minScore }
            val found = request.marks.count { m -> hits.any { it.kind == "kill" && abs((it.at - m.at).inWholeMilliseconds) <= MARK_TOLERANCE.inWholeMilliseconds } }
            val sample = sampleOf(hits.filter { it.kind == "kill" })
            val sheet = if (sample.isEmpty()) null else ContactSheet.write(
                frames, sample, boxes.reduce { a, b -> union(a, b) }, video.width, video.height,
                workDir.resolve("planche_${c.method.name.lowercase()}.png"),
            )
            sheets.update((i + 1).toDouble() / candidates.size, null)
            CandidateResult(c.copy(minScore = minScore), hits, found, sample, sheet)
        }
        sheets.complete()
        results.forEach { r -> log.info { "nouveau jeu : ${r.candidate.method} ${r.kills.size} kills, ${r.marksFound}/${request.marks.size} marqués retrouvés ${r.candidate.detail}" } }
        return NewGameScan(request, media, results, skipped)
    }

    /**
     * Choisit la méthode : sur ses vignettes jugées par l'utilisateur ([verdicts] : méthode → vrai/faux par vignette,
     * dans l'ordre de [CandidateResult.sample]), la précision estime combien de ses kills sont vrais ; on garde celle qui
     * a le plus de vrais kills moins de faux. Une méthode qui ne retrouve aucun des kills marqués n'est pas retenue.
     */
    fun choose(scan: NewGameScan, verdicts: Map<CueMethod, List<Boolean>>): Choice? =
        scan.results.filter { it.marksFound > 0 && it.kills.isNotEmpty() }.map { r ->
            val v = verdicts[r.candidate.method].orEmpty()
            val precision = if (v.isEmpty()) 0.5 else v.count { it }.toDouble() / v.size
            Choice(r, precision, r.kills.size * precision)
        }.filter { it.precision > 0 }.maxByOrNull { it.estimatedKills - (it.result.kills.size - it.estimatedKills) }

    /** Écrit le profil et ses fichiers (modèles) ; refuse si le profil existe déjà. Renvoie le fichier du profil. */
    fun write(scan: NewGameScan, choice: Choice): Path {
        val id = scan.request.profileId
        val file = config.profilesDir.resolve("$id.yaml")
        if (file.exists()) throw InputException(Message("error.newgame.exists", "file" to file))
        val c = choice.result.candidate
        val filesTarget = c.files.keys.map { config.baseDir.resolve(it) }
        filesTarget.firstOrNull { it.exists() }?.let { throw InputException(Message("error.newgame.exists", "file" to it)) }
        c.files.forEach { (path, bytes) -> config.baseDir.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes) }
        file.parent.createDirectories()
        file.writeText(profile(scan, choice))
        return file
    }

    /** Profil complet : en-tête, son, le détecteur choisi, puis sélection et montage du profil par défaut. */
    internal fun profile(scan: NewGameScan, choice: Choice): String {
        val r = scan.request
        val c = choice.result.candidate
        val video = scan.media.video
        val marks = r.marks.joinToString { Durations.format(it.at) }
        val defaults = config.profilesDir.resolve("default.yaml").takeIf { it.exists() }?.readText()?.removePrefix("﻿").orEmpty()
        val tail = defaults.indexOf("\nselection:").takeIf { it >= 0 }?.let { defaults.substring(it + 1) } ?: "selection:\n  threshold: 0.6\n"
        val names = listOf(r.game, r.profileId).distinct().joinToString { "\"${it.replace("\"", "")}\"" }
        val detector = c.yaml.replace("id: ${c.detectorId}", "id: ${if (c.method == CueMethod.COLOR_LINE) "killfeed" else "game-events"}")
            .let { y -> c.minScore?.let { y.replace(TEMPLATE_THRESHOLD_MARK, it.f(2)) } ?: y }
        return buildString {
            appendLine("id: ${r.profileId}")
            appendLine("displayName: \"${r.game.replace("\"", "")}\"")
            appendLine()
            appendLine("match:")
            appendLine("  pathContains: [$names]")
            appendLine("  priority: 10")
            appendLine()
            appendLine("# Profil écrit par l'assistant « nouveau jeu » le ${java.time.LocalDate.now()} depuis « ${r.video.name} »")
            appendLine("# (${video?.width}x${video?.height}) et ${r.marks.size} kill(s) marqué(s) : $marks. Méthode : ${c.method.type} ${c.detail}.")
            appendLine("# Sur cette capture : ${choice.result.kills.size} kills trouvés, précision estimée ${(choice.precision * 100).roundToInt()} %")
            appendLine("# d'après les vignettes confirmées. Les tailles sont en pixels de cette capture (reference).")
            appendLine("window: { size: 1s, hop: 500ms }")
            appendLine()
            appendLine("detectors:")
            appendLine("  - id: game-audio")
            appendLine("    type: audio-loudness")
            appendLine("    weight: 0.7")
            appendLine("    normalization: { lowPercentile: 0.5, highPercentile: 0.98, minSpread: 10 }")
            appendLine("    params: { role: game, localBaseline: 20s }")
            appendLine()
            appendLine("  - id: mic-audio")
            appendLine("    type: audio-loudness")
            appendLine("    weight: 0.3")
            appendLine("    normalization: { lowPercentile: 0.8, highPercentile: 0.99, minSpread: 12 }")
            appendLine("    params: { role: mic, optional: true }")
            appendLine()
            append(detector)
            appendLine()
            appendLine("  - id: voice")
            appendLine("    type: voice-activity")
            appendLine("    weight: 0")
            appendLine("    params: { role: mic, optional: true, silenceDb: -50 }")
            appendLine()
            append(tail)
        }
    }

    private fun trialProfile(candidates: List<Candidate>) = buildString {
        appendLine("id: $TRIAL_ID")
        appendLine("displayName: essai")
        appendLine("window: { size: 1s, hop: 500ms }")
        appendLine("detectors:")
        candidates.forEach { append(it.yaml.replace(TEMPLATE_THRESHOLD_MARK, "0.6")); appendLine() }
        appendLine("selection:")
        appendLine("  threshold: 0.6")
    }

    // --- Méthodes ---

    /** Modèle d'image : le repère découpé sur le kill marqué où il se détache le plus de l'image d'avant. */
    private fun template(id: String, pairs: List<Pair<RgbFrame, RgbFrame>>, boxes: List<PixelBox>, w: Int, h: Int): Candidate {
        val best = pairs.indices.maxBy { i ->
            val (before, during) = pairs[i]
            val b = boxes[i]
            var diff = 0L
            for (y in b.y until b.bottom) for (x in b.x until b.right) diff += abs(during.gray(x, y) - before.gray(x, y))
            diff.toDouble() / (b.width * b.height)
        }
        val box = boxes[best]
        val png = ByteArrayOutputStream().also { ImageIO.write(pairs[best].second.toImage(box, grayscale = true), "png", it) }.toByteArray()
        val file = "templates/$id/repere.png"
        val search = box.grow(max(8, box.width / 4), max(8, box.height / 4), w, h).toRegion(w, h)
        val yaml = """
            |  - id: c-template
            |    type: hud-template
            |    weight: 0
            |    eventBoosts: { kill: 0.8 }
            |    params:
            |      mode: events
            |      referenceWidth: $w
            |      referenceHeight: $h
            |      sampling: fps
            |      fps: 4
            |      templates:
            |        - name: repere
            |          kind: kill
            |          file: $file
            |          region: ${region(search)}
            |          threshold: $TEMPLATE_THRESHOLD_MARK
            |          minContrast: 8
            |          minConsecutive: 2
            |          gap: 2s
            |""".trimMargin()
        return Candidate(CueMethod.TEMPLATE, "c-template", yaml, mapOf(file to png), detail = "(modèle ${box.width}x${box.height} px)")
    }

    /** Ligne de couleur : la teinte qui apparaît au kill, et la hauteur de la ligne mesurée sur ses traits. */
    private fun colorLine(pairs: List<Pair<RgbFrame, RgbFrame>>, boxes: List<PixelBox>, w: Int, h: Int): Pair<Candidate?, Message> {
        val grown = boxes.map { it.grow(it.width / 10, it.height / 3, w, h) }
        var k = 0
        val color = LineColor.find(pairs) { grown[k++ % grown.size] } ?: return null to Message("notice.newgame.noColor")
        val lines = pairs.indices.mapNotNull { measureLine(pairs[it].second, grown[it], color) }
        if (lines.isEmpty()) return null to Message("notice.newgame.noLine")
        val height = lines.map { it.height }.sorted()[lines.size / 2]
        val right = lines.maxOf { it.right }
        val top = lines.minOf { it.top }
        fun px(v: Double) = max(1, (v * height / 16.0).roundToInt())
        // Zone : du haut de la ligne marquée la plus haute à 8 lignes plus bas, de la ligne la plus longue au bord droit.
        val x1 = (right + px(6.0)).coerceAtMost(w)
        val x0 = (x1 - px(310.0)).coerceAtLeast(0)
        val y0 = (top - px(4.0)).coerceAtLeast(0)
        val y1 = (top + px(140.0)).coerceAtMost(h)
        val zone = PixelBox(x0, y0, x1 - x0, y1 - y0).toRegion(w, h)
        val yaml = """
            |  - id: c-line
            |    type: killfeed
            |    weight: 0
            |    eventBoosts: { kill: 0.8 }
            |    params:
            |      region: ${region(zone)}
            |      referenceWidth: $w
            |      referenceHeight: $h
            |      anchor: right
            |      scale: 1.0
            |      fps: 10
            |      color: "${color.expression()}"
            |      threshold: ${LineColor.THRESHOLD}
            |      bridge: $height
            |      minSightings: 10
            |      keyframeScan: null
            |      outline:
            |        minHeight: ${px(13.0)}
            |        maxHeight: ${px(17.0)}
            |        lineHeight: $height
            |        minWidth: ${px(40.0)}
            |        maxWidth: ${px(290.0)}
            |        edgeTolerance: ${px(6.0)}
            |        maxEdgeGap: ${px(20.0)}
            |        newLineGrowth: ${px(45.0)}
            |""".trimMargin()
        val detail = "(teinte ${color.c1.roundToInt()}/${color.c2.roundToInt()}, ligne de $height px)"
        return Candidate(CueMethod.COLOR_LINE, "c-line", yaml, detail = detail) to Message.raw("")
    }

    /** Compteur : la zone marquée, comparée d'une image à l'autre sans lire les chiffres. */
    private fun counter(boxes: List<PixelBox>, w: Int, h: Int): Candidate {
        val box = boxes.reduce { a, b -> union(a, b) }
        val yaml = """
            |  - id: c-counter
            |    type: hud-change
            |    weight: 0
            |    eventBoosts: { kill: 0.8 }
            |    params:
            |      region: ${region(box.toRegion(w, h))}
            |      referenceWidth: $w
            |      referenceHeight: $h
            |      kind: kill
            |""".trimMargin()
        return Candidate(CueMethod.COUNTER, "c-counter", yaml, detail = "(zone ${box.width}x${box.height} px)")
    }

    /**
     * Texte : la zone lue par l'OCR de Windows (agrandie 3 fois) sur chaque kill marqué et sur quelques images prises au
     * hasard. Le mot (5 lettres au moins) lu sur la plupart des kills et sur aucune des autres images devient la règle.
     */
    private suspend fun ocr(workDir: Path, frames: FrameGrabber, request: NewGameRequest, pairs: List<Pair<RgbFrame, RgbFrame>>, boxes: List<PixelBox>, media: MediaInfo): Pair<Candidate?, Message> {
        if (!WindowsOcr.supported) return null to Message("notice.ocr.unavailable")
        val video = media.video ?: return null to Message("notice.noVideo")
        val dir = workDir.resolve("ocr").createDirectories()
        val box = boxes.reduce { a, b -> union(a, b) }.let { it.grow(it.width / 10, it.height / 4, video.width, video.height) }
        fun save(frame: RgbFrame, name: String): Path {
            val img = frame.toImage(box, grayscale = true)
            val big = java.awt.image.BufferedImage(img.width * OCR_SCALE, img.height * OCR_SCALE, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            big.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(img, 0, 0, big.width, big.height, null)
                dispose()
            }
            return dir.resolve("$name.png").also { ImageIO.write(big, "png", it.toFile()) }
        }
        val marked = pairs.mapIndexed { i, p -> save(p.second, "kill_$i") }
        val step = media.duration / (RANDOM_FRAMES + 1)
        val others = (1..RANDOM_FRAMES).map { n -> save(frames.at(step * n), "autre_$n") }
        val read = WindowsOcr(dir, OCR_LANGUAGE, 2).use { ocr -> (marked + others).forEach(ocr::submit); ocr.results(10) }
        fun words(file: Path): Set<String> = read[file].orEmpty().flatMap { line: OcrLine -> line.text.split(' ', ':', '!', '+', '-') }.map(RewardsLog::letters).filter { it.length >= 5 }.toSet()
        val inMarks = marked.map(::words)
        val inOthers = others.flatMap(::words).toSet()
        val counts = inMarks.flatten().groupingBy { it }.eachCount()
        val word = counts.filter { (w, n) -> n >= ceil(marked.size / 2.0) && w !in inOthers }.maxByOrNull { it.value }?.key
            ?: return null to Message("notice.newgame.noWord", "read" to inMarks.joinToString(" | ") { it.joinToString(" ").ifEmpty { "–" } })
        val zone = box.toRegion(video.width, video.height)
        val yaml = """
            |  - id: c-ocr
            |    type: ocr-log
            |    weight: 0
            |    eventBoosts: { kill: 0.8 }
            |    params:
            |      region: ${region(zone)}
            |      referenceWidth: ${video.width * OCR_SCALE}
            |      referenceHeight: ${video.height * OCR_SCALE}
            |      language: $OCR_LANGUAGE
            |      sampling: fps
            |      fps: 2
            |      minSightings: 1
            |      hold: 2s
            |      rowPitch: ${max(8, box.height * OCR_SCALE / 2)}
            |      rules:
            |        - kind: kill
            |          match: [$word]
            |""".trimMargin()
        return Candidate(CueMethod.OCR, "c-ocr", yaml, detail = "(mot « $word »)") to Message.raw("")
    }

    private fun sampleOf(hits: List<Hit>): List<Hit> {
        if (hits.size <= SAMPLE) return hits
        return (0 until SAMPLE).map { hits[(it * (hits.size - 1).toDouble() / (SAMPLE - 1)).roundToInt()] }.distinct()
    }

    companion object {
        const val TRIAL_ID = "assistant-essai"
        private const val TEMPLATE_THRESHOLD_MARK = "@SEUIL@"
        /** Le repère est lu 0,6 s après son apparition : animation d'entrée finie, encore affiché. */
        val CUE_DELAY = 600.milliseconds
        /** Écart toléré entre un kill marqué et une détection (comme `app verite`). */
        val MARK_TOLERANCE = 3.seconds
        /** Vignettes par planche. */
        const val SAMPLE = 12
        private const val RANDOM_FRAMES = 8
        private const val OCR_SCALE = 3
        private const val OCR_LANGUAGE = "en-US"

        /** Identifiant de profil tiré du nom : « Counter-Strike 2 » → counter-strike-2. */
        fun slug(name: String): String =
            Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "jeu" }

        internal fun union(a: PixelBox, b: PixelBox): PixelBox {
            val x0 = minOf(a.x, b.x)
            val y0 = minOf(a.y, b.y)
            return PixelBox(x0, y0, maxOf(a.right, b.right) - x0, maxOf(a.bottom, b.bottom) - y0)
        }
    }
}
