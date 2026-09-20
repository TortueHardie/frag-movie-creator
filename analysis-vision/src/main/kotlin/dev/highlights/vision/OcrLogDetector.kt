package dev.highlights.vision

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
    /** Une ligne non relue depuis cette durée est oubliée (une ligne reste affichée une dizaine de secondes). */
    val hold: SerialDuration = 6.seconds,
    /** Lectures nécessaires avant de compter une ligne. */
    val minSightings: Int = 2,
    /** Icônes de notification à fusionner avec le journal (voir [EventFusion]) ; null = journal seul. */
    val icons: HudTemplateParams? = null,
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
        require(referenceHeight > 0 && rowPitch > 0 && minSightings >= 1 && parallelism >= 1 && fps > 0) { "paramètres invalides : $this" }
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
    )

    private val icons = params.icons?.let { HudTemplateDetector("$id-icons", it) }

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        if (!WindowsOcr.supported) return
        icons?.prepare(ctx)
        val referenceAspect = params.referenceWidth?.let { it.toDouble() / params.referenceHeight }
        val region = referenceAspect
            ?.let { ScreenGeometry.rescale(params.region, it, video.width.toDouble() / video.height, params.anchor) }
            ?: params.region
        val cx = (region.x * video.width).roundToInt().coerceIn(0, video.width - 1)
        val cy = (region.y * video.height).roundToInt().coerceIn(0, video.height - 1)
        val cw = (region.width * video.width).roundToInt().coerceIn(1, video.width - cx)
        val ch = (region.height * video.height).roundToInt().coerceIn(1, video.height - cy)
        val scale = params.referenceHeight.toDouble() / video.height
        val zone = FrameZone(cx, cy, cw, ch, (cw * scale).roundToInt().coerceAtLeast(1), (ch * scale).roundToInt().coerceAtLeast(1))
        val spec = chooseSampling(ctx, id, params.sampling, params.fps, params.hwaccel, params.maxKeyframeInterval)
        val dir = ctx.workDir.resolve("ocr-$id").createDirectories()
        val ocr = WindowsOcr(dir, params.language, params.parallelism)
        val images = ConcurrentHashMap<Int, Path>()
        log.info { "$id : journal ${zone.width}x${zone.height} lu par OCR, ${if (spec.keyframes) "images clés (~${spec.interval})" else "${params.fps} img/s"}" }
        val subscription = ctx.frames.subscribe(
            spec = spec,
            zones = listOf(zone),
            label = id,
            progress = ctx.progress,
            onReset = { images.clear() },
            onFrame = { index, zones ->
                val frame = zones[0]
                val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_BYTE_GRAY)
                image.raster.setDataElements(0, 0, frame.width, frame.height, frame.pixels)
                val file = dir.resolve("f_%07d.png".format(index))
                ImageIO.write(image, "png", file.toFile())
                images[index] = file
                ocr.submit(file)
            },
        )
        prepared = Prepared(subscription, ocr, images)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, "pas de flux vidéo")
        if (!WindowsOcr.supported) return SignalTrack.missing(id, ctx.grid.count, "OCR indisponible hors de Windows")
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, "pas de flux vidéo")
        try {
            val times = state.subscription.await()
            // OCR en échec (langue absente, PowerShell bloqué…) : les icônes seules restent utilisables.
            val read = try {
                withContext(Dispatchers.IO) { state.ocr.results() }
            } catch (e: Exception) {
                log.warn(e) { "$id : OCR en échec, icônes seules" }
                emptyMap()
            }
            val tracker = LogTracker(params.hold, params.rowPitch, params.minSightings)
            val logEvents = mutableListOf<LogEvent>()
            var readable = 0
            for (index in times.indices) {
                val file = state.images[index] ?: continue
                val lines = read[file] ?: continue
                if (lines.isNotEmpty()) readable++
                val rows = RewardsLog.rows(lines, params.rowTolerance).mapNotNull { (y, text) ->
                    val kind = RewardsLog.classify(text, params.rules)
                    if (kind.isNullOrEmpty()) null else Triple(y, kind, text)
                }
                logEvents += tracker.update(times[index], rows)
            }
            log.info { "$id : texte lu sur $readable/${times.size} images ; journal : " + logEvents.joinToString { "${it.kind} ${Durations.format(it.at)} « ${it.text} »" } }

            val iconEvents = icons?.analyze(ctx)?.events.orEmpty().map { TimedKind(it.at, it.kind) }
            val fused = if (icons == null) logEvents.map { TimedKind(it.at, it.kind) } else EventFusion.fuse(logEvents, iconEvents, params.fusionBefore, params.fusionAfter)
            log.info { "$id : ${fused.groupingBy { it.kind }.eachCount()} (journal ${logEvents.size}, icônes ${iconEvents.size})" }
            return SignalTrack(id, DoubleArray(ctx.grid.count) { Double.NaN }, fused.map { SignalEvent(it.at, it.kind, 1.0) })
        } finally {
            state.ocr.close()
        }
    }
}

class OcrLogDetectorFactory : SignalDetectorFactory {
    override val type = "ocr-log"

    override fun availability() = DetectorAvailability(
        WindowsOcr.supported,
        if (WindowsOcr.supported) "OCR de Windows disponible" else "OCR de Windows indisponible (${System.getProperty("os.name")})",
    )

    override fun create(id: String, params: DetectorParams): SignalDetector =
        OcrLogDetector(id, params.decode(OcrLogParams.serializer()) { throw ConfigException("$id : paramètres region et rules requis") })
}
