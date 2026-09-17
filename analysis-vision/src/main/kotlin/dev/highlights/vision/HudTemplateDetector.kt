package dev.highlights.vision

import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.SerialDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.EOFException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

@Serializable
enum class HudMode {
    /** Événements ponctuels (ex. notification de kill). */
    @SerialName("events") EVENTS,

    /** Présence continue d'un élément (ex. HUD visible = en jeu), utilisé avec role: gate. */
    @SerialName("presence") PRESENCE,
}

@Serializable
enum class Sampling {
    /** Images clés si elles sont assez rapprochées, sinon échantillonnage à fps. */
    @SerialName("auto") AUTO,
    @SerialName("keyframes") KEYFRAMES,
    @SerialName("fps") FPS,
}

@Serializable
data class HudTemplateParams(
    val mode: HudMode = HudMode.EVENTS,
    /** Hauteur de la capture sur laquelle les modèles ont été découpés : les zones sont mises à cette échelle. */
    val referenceHeight: Int = 1440,
    val sampling: Sampling = Sampling.AUTO,
    /** Décodage matériel (d3d11va sous Windows). null = logiciel. Repli automatique sur le logiciel en cas d'échec. */
    val hwaccel: String? = "d3d11va",
    val fps: Double = 2.0,
    /** En mode auto, au-delà de cet intervalle entre images clés on bascule sur l'échantillonnage à fps. */
    val maxKeyframeInterval: SerialDuration = 1500.milliseconds,
    /** Réduction appliquée aux zones et aux modèles avant comparaison (0.5 = 4 fois moins de calcul). */
    val matchScale: Double = 1.0,
    /** Pour un même type d'événement : any = un seul modèle suffit, all = tous doivent être reconnus sur la même image. */
    val combine: Combine = Combine.ANY,
    /** Sur une même image, seul le modèle au meilleur score (au-dessus de son seuil) compte : icônes concurrentes au même endroit. */
    val exclusive: Boolean = false,
    val templates: List<TemplateSpec>,
    /** Mode presence : une fenêtre est « en jeu » si l'élément a été vu à moins de cette durée. */
    val presenceHold: SerialDuration = 5.seconds,
    /**
     * Mode presence : si l'élément est reconnu sur moins de cette proportion d'images, le modèle ne correspond sans doute pas
     * à cette capture (autre jeu, HUD déplacé, résolution) : le signal est ignoré plutôt que d'exclure toute la partie.
     */
    val minPresenceRatio: Double = 0.2,
) {
    init {
        require(templates.isNotEmpty()) { "au moins un modèle attendu dans templates" }
        require(fps > 0) { "fps doit être > 0" }
        require(matchScale > 0 && matchScale <= 1) { "matchScale doit être dans ]0, 1]" }
    }
}

@Serializable
enum class Combine {
    @SerialName("any") ANY,
    @SerialName("all") ALL,
}

@Serializable
enum class MatchMethod {
    /** Corrélation normalisée sur tous les pixels : élément opaque sur fond stable. */
    @SerialName("ncc") NCC,

    /** Forme des pixels clairs uniquement : icône ou texte blanc sur décor changeant. */
    @SerialName("bright") BRIGHT,
}

@Serializable
data class TemplateSpec(
    val name: String,
    /** Type d'événement émis (mode events). */
    val kind: String = name,
    /** Image du modèle, relative au dossier de configuration. */
    val file: String,
    /** Zone de recherche (normalisée), légèrement plus grande que le modèle. */
    val region: CropRegion,
    val threshold: Double = 0.7,
    val method: MatchMethod = MatchMethod.NCC,
    /** Méthode bright : luminosité minimale (0-255) d'un pixel « allumé ». */
    val brightness: Int = 200,
    /** Échelles essayées (animation d'apparition qui agrandit l'élément, ex. [1.0, 1.2, 1.4]). */
    val scales: List<Double> = listOf(1.0),
    /** Méthode bright : distance (pixels) tolérée entre un pixel du modèle et un pixel clair. 0 = exact (recommandé). */
    val tolerance: Int = 0,
    /** Remplace le matchScale global pour ce modèle (ex. 1.0 pour un texte fin). */
    val matchScale: Double? = null,
    /** Nombre d'échantillons consécutifs requis pour valider un événement (filtre les faux positifs isolés). */
    val minConsecutive: Int = 2,
    /** Absence minimale pour considérer qu'une nouvelle occurrence commence. */
    val gap: SerialDuration = 2500.milliseconds,
)

/**
 * Détecte des éléments d'interface à position fixe (notification de kill, HUD de jeu) par corrélation normalisée.
 * FFmpeg ne décode que les images clés (ou un échantillonnage à quelques fps), découpe les zones utiles, les met à
 * l'échelle de référence et les envoie en niveaux de gris sur stdout : aucun réencodage, très peu de mémoire.
 */
class HudTemplateDetector(override val id: String, private val params: HudTemplateParams) : SignalDetector {

    private class Zone(
        val spec: TemplateSpec,
        val score: (GrayImage) -> Double,
        val crop: IntArray,
        val scaledW: Int,
        val scaledH: Int,
        val offsetY: Int,
    )

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        val media = ctx.media
        val video = media.video ?: return SignalTrack.missing(id, ctx.grid.count, "pas de flux vidéo")
        val baseScale = params.referenceHeight.toDouble() / video.height

        var offsetY = 0
        val zones = params.templates.map { spec ->
            val source = GrayImage.load(ctx.configDir.resolve(spec.file))
            val matchScale = spec.matchScale ?: params.matchScale
            val scale = baseScale * matchScale
            val variants = spec.scales.map { source.resized(it * matchScale) }
            val largest = variants.maxBy { it.width * it.height }
            val scorer: (GrayImage) -> Double = when (spec.method) {
                MatchMethod.NCC -> {
                    val prepared = variants.map { PreparedTemplate(it, spec.name) }
                    val scorer: (GrayImage) -> Double = { roi ->
                        prepared.filter { it.width <= roi.width && it.height <= roi.height }.maxOfOrNull { TemplateMatcher.match(roi, it).score } ?: 0.0
                    }
                    scorer
                }
                MatchMethod.BRIGHT -> {
                    val prepared = variants.map { BrightTemplate(it, spec.brightness, spec.name, spec.tolerance) }
                    val scorer: (GrayImage) -> Double = { roi -> prepared.maxOf { BrightMatcher.match(roi, it).score } }
                    scorer
                }
            }
            val cx = (spec.region.x * video.width).roundToInt().coerceIn(0, video.width - 1)
            val cy = (spec.region.y * video.height).roundToInt().coerceIn(0, video.height - 1)
            val cw = (spec.region.width * video.width).roundToInt().coerceIn(1, video.width - cx)
            val ch = (spec.region.height * video.height).roundToInt().coerceIn(1, video.height - cy)
            val sw = (cw * scale).roundToInt().coerceAtLeast(1)
            val sh = (ch * scale).roundToInt().coerceAtLeast(1)
            if (sw < largest.width || sh < largest.height) {
                throw ConfigException(
                    "$id : la zone de '${spec.name}' (${sw}x$sh à l'échelle) est plus petite que le modèle (${largest.width}x${largest.height})",
                )
            }
            Zone(spec, scorer, intArrayOf(cw, ch, cx, cy), sw, sh, offsetY).also { offsetY += sh }
        }
        val frameWidth = zones.maxOf { it.scaledW }
        val frameHeight = offsetY
        val frameBytes = frameWidth * frameHeight

        val (useKeyframes, interval) = chooseSampling(ctx)
        log.info { "$id : ${zones.size} modèle(s), ${if (useKeyframes) "images clés (~$interval)" else "${params.fps} img/s"}" }

        val times = ConcurrentHashMap<Int, Duration>()
        val scores = List(zones.size) { mutableListOf<Double>() }
        val showinfo = Regex("""\bn:\s*(\d+)\b.*\bpts_time:\s*(-?[\d.]+)""")
        val expectedFrames = (media.duration / interval).coerceAtLeast(1.0)

        suspend fun extract(hwaccel: String?) = ctx.ffmpeg.run(
            FfmpegCommand(
                buildList {
                    hwaccel?.let { addAll(listOf("-hwaccel", it)) }
                    if (useKeyframes) addAll(listOf("-skip_frame", "nokey"))
                    addAll(listOf("-i", media.path.toString(), "-an", "-sn", "-dn"))
                    addAll(listOf("-filter_complex", filterGraph(zones, frameWidth, useKeyframes)))
                    addAll(listOf("-map", "[out]", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "pipe:1"))
                },
                "$id : modèles HUD",
            ),
            StdoutHandler.Binary { input ->
                val data = DataInputStream(input.buffered(frameBytes * 4))
                val frame = ByteArray(frameBytes)
                var index = 0
                while (true) {
                    try {
                        data.readFully(frame)
                    } catch (_: EOFException) {
                        break
                    }
                    zones.forEachIndexed { k, zone ->
                        val roi = ByteArray(zone.scaledW * zone.scaledH)
                        for (row in 0 until zone.scaledH) {
                            System.arraycopy(frame, (zone.offsetY + row) * frameWidth, roi, row * zone.scaledW, zone.scaledW)
                        }
                        scores[k] += zone.score(GrayImage(zone.scaledW, zone.scaledH, roi))
                    }
                    index++
                    if (index % 20 == 0) ctx.progress.update((index / expectedFrames).coerceAtMost(0.99), "$index images")
                }
            },
            onStderrLine = { line ->
                if (line.contains("showinfo")) {
                    showinfo.find(line)?.let { m -> times[m.groupValues[1].toInt()] = m.groupValues[2].toDouble().seconds }
                }
            },
        )
        try {
            extract(params.hwaccel)
        } catch (e: FfmpegException) {
            if (params.hwaccel == null) throw e
            log.warn { "$id : échec avec -hwaccel ${params.hwaccel} (${e.message}), nouvel essai en décodage logiciel" }
            times.clear()
            scores.forEach { it.clear() }
            extract(null)
        }
        ctx.progress.complete()

        val count = scores.firstOrNull()?.size ?: 0
        if (count == 0) return SignalTrack.missing(id, ctx.grid.count, "aucune image analysée")
        if (times.size < count) log.warn { "$id : ${count - times.size} horodatage(s) manquant(s), estimation par l'intervalle" }
        val sampleTimes = List(count) { i -> times[i] ?: (interval * i) }

        return when (params.mode) {
            HudMode.EVENTS -> eventsTrack(ctx, zones, scores, sampleTimes)
            HudMode.PRESENCE -> presenceTrack(ctx, zones, scores, sampleTimes)
        }
    }

    private fun filterGraph(zones: List<Zone>, frameWidth: Int, keyframes: Boolean): String {
        val head = "[0:v:0]" + (if (keyframes) "" else "fps=${params.fps},") + "setsar=1"
        fun chain(z: Zone, first: Boolean) = buildString {
            append("crop=${z.crop[0]}:${z.crop[1]}:${z.crop[2]}:${z.crop[3]},scale=${z.scaledW}:${z.scaledH}:flags=area,format=gray")
            if (first) append(",showinfo")
            if (z.scaledW < frameWidth) append(",pad=$frameWidth:${z.scaledH}:0:0")
        }
        if (zones.size == 1) return "$head,${chain(zones[0], true)}[out]"
        return buildString {
            append("$head,split=${zones.size}")
            zones.indices.forEach { append("[s$it]") }
            zones.forEachIndexed { k, z -> append(";[s$k]${chain(z, k == 0)}[r$k]") }
            append(";")
            zones.indices.forEach { append("[r$it]") }
            append("vstack=inputs=${zones.size}[out]")
        }
    }

    /** Images clés si leur intervalle (mesuré sur 30 s au milieu de la vidéo) est assez court. */
    private suspend fun chooseSampling(ctx: AnalysisContext): Pair<Boolean, Duration> {
        val fpsInterval = (1.0 / params.fps).seconds
        if (params.sampling == Sampling.FPS) return false to fpsInterval
        val measured = keyframeInterval(ctx, ctx.media)
        if (params.sampling == Sampling.KEYFRAMES) return true to (measured ?: 1.seconds)
        return if (measured != null && measured <= params.maxKeyframeInterval) {
            true to measured
        } else {
            log.warn { "$id : images clés espacées de ${measured ?: "?"}, échantillonnage à ${params.fps} img/s (plus lent)" }
            false to fpsInterval
        }
    }

    private suspend fun keyframeInterval(ctx: AnalysisContext, media: MediaInfo): Duration? {
        val start = (media.duration / 2 - 15.seconds).coerceAtLeast(Duration.ZERO)
        val keyTimes = mutableListOf<Double>()
        runCatching {
            ctx.ffmpeg.runProbe(
                FfmpegCommand(
                    listOf(
                        "-v", "error", "-select_streams", "v:0",
                        "-read_intervals", "${Durations.ffmpegSeconds(start)}%+30",
                        "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", media.path.toString(),
                    ),
                    "$id : intervalle des images clés",
                ),
                StdoutHandler.Lines { line ->
                    val parts = line.split(',')
                    if (parts.size >= 2 && parts[1].startsWith("K")) parts[0].toDoubleOrNull()?.let { keyTimes += it }
                },
            )
        }.onFailure { log.warn { "$id : mesure des images clés impossible : ${it.message}" } }
        if (keyTimes.size < 3) return null
        val gaps = keyTimes.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        return gaps.getOrNull(gaps.size / 2)?.seconds
    }

    private fun eventsTrack(ctx: AnalysisContext, zones: List<Zone>, scores: List<List<Double>>, times: List<Duration>): SignalTrack {
        val events = mutableListOf<SignalEvent>()
        zones.indices.groupBy { zones[it].spec.kind }.forEach { (kind, members) ->
            val first = zones[members.first()].spec
            val minConsecutive = members.minOf { zones[it].spec.minConsecutive }
            var lastActive: Duration? = null
            var start = Duration.ZERO
            var consecutive = 0
            var best = 0.0
            var emitted = false
            for (i in times.indices) {
                val t = times[i]
                val winner = if (params.exclusive) zones.indices.filter { scores[it][i] >= zones[it].spec.threshold }.maxByOrNull { scores[it][i] } else null
                val active = members.filter { scores[it][i] >= zones[it].spec.threshold && (!params.exclusive || it == winner) }
                val matched = if (params.combine == Combine.ALL) active.size == members.size else active.isNotEmpty()
                if (!matched) continue
                if (lastActive == null || t - lastActive > first.gap) {
                    start = t
                    consecutive = 0
                    best = 0.0
                    emitted = false
                }
                consecutive++
                best = maxOf(best, active.maxOf { scores[it][i] })
                lastActive = t
                if (!emitted && consecutive >= minConsecutive) {
                    events += SignalEvent(start, kind, best.coerceIn(0.0, 1.0))
                    emitted = true
                }
            }
        }
        log.info { "$id : ${events.groupingBy { it.kind }.eachCount()} à ${events.joinToString { "${it.at.inWholeSeconds}s" }}" }
        zones.forEachIndexed { k, zone ->
            // Aide au réglage du seuil : meilleurs scores sous le seuil.
            val near = times.indices.filter { scores[k][it] in (zone.spec.threshold - 0.25)..<zone.spec.threshold }
                .sortedByDescending { scores[k][it] }.take(15)
                .joinToString { "${times[it].inWholeSeconds}s=%.2f".format(scores[k][it]) }
            if (near.isNotEmpty()) log.info { "$id : quasi-détections '${zone.spec.name}' : $near" }
        }
        // Valeur brute : meilleure corrélation par fenêtre (utile pour le diagnostic ; le score passe par les événements).
        val raw = DoubleArray(ctx.grid.count) { Double.NaN }
        for (i in times.indices) {
            val v = scores.maxOf { it[i] }
            for (w in ctx.grid.indicesCovering(times[i])) if (raw[w].isNaN() || v > raw[w]) raw[w] = v
        }
        return SignalTrack(id, raw, events.sortedBy { it.at })
    }

    private fun presenceTrack(ctx: AnalysisContext, zones: List<Zone>, scores: List<List<Double>>, times: List<Duration>): SignalTrack {
        val seen = times.indices.filter { i -> zones.indices.any { k -> scores[k][i] >= zones[k].spec.threshold } }.map { times[it] }
        val ratio = seen.size.toDouble() / times.size
        if (ratio < params.minPresenceRatio) {
            val note = "HUD reconnu sur seulement ${(ratio * 100).toInt()} % des images : filtre hors jeu désactivé (zones du profil à vérifier)"
            log.warn { "$id : $note" }
            return SignalTrack.missing(id, ctx.grid.count, note)
        }
        val grid = ctx.grid
        val hold = params.presenceHold
        val lastSample = times.maxOrNull() ?: Duration.ZERO
        val raw = DoubleArray(grid.count) { w ->
            val r = grid.rangeOf(w)
            if (r.start > lastSample + hold) return@DoubleArray Double.NaN
            if (seen.any { it >= r.start - hold && it <= r.end + hold }) 1.0 else 0.0
        }
        log.info { "$id : HUD visible sur ${seen.size}/${times.size} images" }
        return SignalTrack(id, raw)
    }
}

class HudTemplateDetectorFactory : SignalDetectorFactory {
    override val type = "hud-template"

    override fun create(id: String, params: DetectorParams): SignalDetector =
        HudTemplateDetector(id, params.decode(HudTemplateParams.serializer()) { throw ConfigException("$id : paramètres templates requis") })
}
