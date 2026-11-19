package dev.highlights.vision

import dev.highlights.core.i18n.Message
import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorAvailability
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.RegionAnchor
import dev.highlights.core.model.ScreenGeometry
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.video.FrameSampler
import dev.highlights.core.video.FrameZone
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

@Serializable
data class OcrLogParams(
    /** Zone du journal (normalisée). */
    val region: CropRegion,
    /** Hauteur de capture à laquelle la zone est ramenée (le texte y garde la taille sur laquelle les réglages sont faits). */
    val referenceHeight: Int = 1606,
    /**
     * Largeur de cette même capture (ex. 3840 avec referenceHeight 1606). Renseignée, la zone est convertie au format
     * de la capture analysée : un réglage fait en 21:9 vaut alors aussi en 16:9 (voir [ScreenGeometry]).
     */
    val referenceWidth: Int? = null,
    /** Bord de l'écran auquel le journal est accroché, pour cette conversion. */
    val anchor: RegionAnchor = RegionAnchor.AUTO,
    /** Langue de l'OCR Windows (repli sur les langues du profil Windows si elle n'est pas installée). */
    val language: String = "fr-FR",
    /** Règles dans l'ordre : la première qui correspond donne le type de la ligne. */
    val rules: List<LogRule>,
    /** Écart vertical entre deux lignes du journal, et écart en dessous duquel deux morceaux lus sont la même ligne. */
    val rowPitch: Int = 32,
    val rowTolerance: Int = 10,
    /**
     * Lignes dont une ligne déjà vue peut descendre d'une image à l'autre : notifications empilées, la nouvelle au-dessus
     * des anciennes (Apex). 0 : journal qui ne fait que remonter (Wardogs). Voir [LogTracker].
     */
    val maxDrop: Int = 0,
    /** Notifications qui créditent des kills en retard ou en rafale (Apex : « SQUAD WIPE ») : voir [LogCredit]. */
    val credits: List<LogCredit> = emptyList(),
    /** Une ligne non relue depuis cette durée est oubliée (une ligne reste affichée une dizaine de secondes). */
    val hold: SerialDuration = 6.seconds,
    /** Lectures nécessaires avant de compter une ligne. */
    val minSightings: Int = 2,
    /** Icônes de notification à fusionner avec le journal (voir [EventFusion]) ; null = journal seul. */
    val icons: HudTemplateParams? = null,
    /** Libellé écrit sous ces icônes, qui corrige leur type (voir [NoticeLabels]) ; null = icônes prises telles quelles. */
    val notice: NoticeParams? = null,
    /** Fenêtre de rapprochement entre une icône et une ligne du journal. */
    val fusionBefore: SerialDuration = 3.seconds,
    val fusionAfter: SerialDuration = 8.seconds,
    val sampling: Sampling = Sampling.AUTO,
    /** Décodage matériel de l'échantillonnage : « auto » laisse FFmpeg choisir, null = logiciel. */
    val hwaccel: String? = Hwaccel.AUTO,
    val fps: Double = 1.0,
    val maxKeyframeInterval: SerialDuration = 1500.milliseconds,
    /** Processus OCR simultanés. */
    val parallelism: Int = 4,
) {
    init {
        require(rules.isNotEmpty()) { "au moins une règle attendue" }
        require(referenceHeight > 0 && rowPitch > 0 && maxDrop >= 0 && minSightings >= 1 && parallelism >= 1 && fps > 0) { "paramètres invalides : $this" }
    }
}

/**
 * Libellé de la notification centrale (sous l'icône et le montant : « ÉLIMINATION CONFIRMÉE », « AIDE : ÉLIMINATION »…),
 * lu par l'OCR sur les mêmes images que le journal. Une seule ligne de texte : les morceaux lus sont mis bout à bout.
 */
@Serializable
data class NoticeParams(
    /** Zone du libellé (normalisée). */
    val region: CropRegion,
    val referenceHeight: Int = 1440,
    val referenceWidth: Int? = null,
    val anchor: RegionAnchor = RegionAnchor.CENTER,
    /** Agrandissement avant l'OCR : le libellé fait ~18 px de haut sur 1440, petit pour une lecture sûre. */
    val scale: Double = 2.0,
    /** Règles dans l'ordre, comme celles du journal ; une règle sans `kind` écarte l'icône. */
    val rules: List<LogRule>,
    /** Fenêtre autour de l'icône où chercher son libellé. */
    val before: SerialDuration = 1.seconds,
    val after: SerialDuration = 3.seconds,
) {
    init {
        require(rules.isNotEmpty() && referenceHeight > 0 && scale > 0) { "paramètres du libellé invalides : $this" }
    }
}

/**
 * Événements lus dans un journal textuel à l'écran (journal des gains de Wardogs : « ÉLIMINATION », « AIDE :
 * ÉLIMINATION », « COÉQUIPIER RÉANIMÉ »…) par l'OCR de Windows, éventuellement confirmés et datés par les icônes de
 * notification ([OcrLogParams.icons], détecteur hud-template intégré). La zone passe par le décodage partagé ; les
 * images sont lues par lots pendant le décodage.
 */
class OcrLogDetector(override val id: String, private val params: OcrLogParams) : SignalDetector {

    private class Prepared(
        val subscription: FrameSampler.Subscription,
        val ocr: WindowsOcr,
        val images: ConcurrentHashMap<Int, Path>,
        val notices: ConcurrentHashMap<Int, Path>,
    )

    private val icons = params.icons?.let { HudTemplateDetector("$id-icons", it) }

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        if (!WindowsOcr.supported) return
        icons?.prepare(ctx)
        val zone = zoneOf(params.region, params.referenceWidth, params.referenceHeight, params.anchor, video.width, video.height, params.referenceHeight.toDouble() / video.height)
        // Libellé de la notification : ramené à sa hauteur de référence, puis agrandi pour l'OCR.
        val noticeZone = params.notice?.let { n ->
            zoneOf(n.region, n.referenceWidth, n.referenceHeight, n.anchor, video.width, video.height, n.referenceHeight.toDouble() / video.height * n.scale)
        }
        val spec = chooseSampling(ctx, id, params.sampling, params.fps, params.hwaccel, params.maxKeyframeInterval)
        val dir = ctx.workDir.resolve("ocr-$id").createDirectories()
        val ocr = WindowsOcr(dir, params.language, params.parallelism)
        val images = ConcurrentHashMap<Int, Path>()
        val notices = ConcurrentHashMap<Int, Path>()
        log.info { "$id : journal ${zone.width}x${zone.height} lu par OCR, ${if (spec.keyframes) "images clés (~${spec.interval})" else "${params.fps} img/s"}" }
        val subscription = ctx.frames.subscribe(
            spec = spec,
            zones = listOfNotNull(zone, noticeZone),
            label = id,
            progress = ctx.progress,
            onReset = {
                images.clear()
                notices.clear()
            },
            onFrame = { index, zones ->
                images[index] = gray(zones[0].width, zones[0].height, zones[0].pixels, dir.resolve("f_%07d.png".format(index))).also(ocr::submit)
                if (noticeZone != null) {
                    notices[index] = gray(zones[1].width, zones[1].height, zones[1].pixels, dir.resolve("n_%07d.png".format(index))).also(ocr::submit)
                }
            },
        )
        prepared = Prepared(subscription, ocr, images, notices)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        if (!WindowsOcr.supported) return SignalTrack.missing(id, ctx.grid.count, Message("notice.ocr.unavailable"))
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, Message("notice.noVideo"))
        try {
            val times = state.subscription.await()
            // OCR en échec (langue absente, PowerShell bloqué…) : les icônes seules restent utilisables.
            val read = try {
                withContext(Dispatchers.IO) { state.ocr.results() }
            } catch (e: Exception) {
                log.warn(e) { "$id : OCR en échec, icônes seules" }
                emptyMap()
            }
            val tracker = LogTracker(params.hold, params.rowPitch, params.minSightings, params.maxDrop)
            val tracked = mutableListOf<LogEvent>()
            var readable = 0
            for (index in times.indices) {
                val file = state.images[index] ?: continue
                val lines = read[file] ?: continue
                if (lines.isNotEmpty()) readable++
                val rows = RewardsLog.rows(lines, params.rowTolerance).mapNotNull { (y, text) ->
                    val kind = RewardsLog.classify(text, params.rules)
                    if (kind.isNullOrEmpty()) null else Triple(y, kind, text)
                }
                tracked += tracker.update(times[index], rows)
            }
            log.info { "$id : texte lu sur $readable/${times.size} images ; journal : " + tracked.joinToString { "${it.kind} ${Durations.format(it.at)} « ${it.text} »" } }
            val logEvents = LogCredits.apply(tracked, params.credits)
            if (params.credits.isNotEmpty()) log.info { "$id : après crédits : " + logEvents.joinToString { "${it.kind} ${Durations.format(it.at)}" } }

            val notice = params.notice
            val noticeReads = if (notice == null) emptyList() else times.indices.mapNotNull { index ->
                val lines = state.notices[index]?.let { read[it] }.orEmpty()
                val text = lines.sortedBy { it.x }.joinToString(" ") { it.text }
                RewardsLog.classify(text, notice.rules)?.let { NoticeRead(times[index], it, text) }
            }
            val shown = icons?.analyze(ctx)?.events.orEmpty().map { TimedKind(it.at, it.kind) }
            val iconEvents = if (notice == null) shown else NoticeLabels.relabel(shown, noticeReads, notice.before, notice.after)
            if (notice != null) {
                log.info { "$id : libellés " + noticeReads.joinToString { "${it.kind.ifEmpty { "-" }} ${Durations.format(it.at)} « ${it.text} »" } }
                val changed = shown.filterNot { it in iconEvents }
                if (changed.isNotEmpty()) log.info { "$id : icônes corrigées par leur libellé : " + changed.joinToString { "${it.kind} ${Durations.format(it.at)}" } }
            }
            val fused = if (icons == null) logEvents.map { TimedKind(it.at, it.kind) } else EventFusion.fuse(logEvents, iconEvents, params.fusionBefore, params.fusionAfter)
            log.info { "$id : ${fused.groupingBy { it.kind }.eachCount()} (journal ${logEvents.size}, icônes ${iconEvents.size})" }
            return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, fused.map { SignalEvent(it.at, it.kind, 1.0) })
        } finally {
            state.ocr.close()
        }
    }
}

/** Zone de capture : [region] convertie au format de la vidéo, puis mise à l'échelle [scale]. */
private fun zoneOf(region: CropRegion, referenceWidth: Int?, referenceHeight: Int, anchor: RegionAnchor, width: Int, height: Int, scale: Double): FrameZone {
    val r = referenceWidth?.let { ScreenGeometry.rescale(region, it.toDouble() / referenceHeight, width.toDouble() / height, anchor) } ?: region
    val cx = (r.x * width).roundToInt().coerceIn(0, width - 1)
    val cy = (r.y * height).roundToInt().coerceIn(0, height - 1)
    val cw = (r.width * width).roundToInt().coerceIn(1, width - cx)
    val ch = (r.height * height).roundToInt().coerceIn(1, height - cy)
    return FrameZone(cx, cy, cw, ch, (cw * scale).roundToInt().coerceAtLeast(1), (ch * scale).roundToInt().coerceAtLeast(1))
}

/** Pixels gris d'une zone décodée, écrits en PNG pour l'OCR. */
private fun gray(width: Int, height: Int, pixels: ByteArray, file: Path): Path {
    val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    image.raster.setDataElements(0, 0, width, height, pixels)
    ImageIO.write(image, "png", file.toFile())
    return file
}

class OcrLogDetectorFactory : SignalDetectorFactory {
    override val type = "ocr-log"

    override fun availability() = DetectorAvailability(
        WindowsOcr.supported,
        if (WindowsOcr.supported) "OCR de Windows disponible" else "OCR de Windows indisponible (${System.getProperty("os.name")})",
    )

    override fun create(id: String, params: DetectorParams): SignalDetector =
        OcrLogDetector(id, params.decode(OcrLogParams.serializer()) { throw ConfigException(Message("error.detector.paramRequired", "id" to id, "params" to "region, rules")) })
}
