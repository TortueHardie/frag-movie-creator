package dev.highlights.vision

import dev.highlights.core.ConfigException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.SignalDetector
import dev.highlights.core.analysis.SignalDetectorFactory
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.RegionAnchor
import dev.highlights.core.model.ScreenGeometry
import dev.highlights.core.model.VideoStream
import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.video.FrameSampler
import dev.highlights.core.video.FrameZone
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
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
    /**
     * Largeur de cette même capture (ex. 3440 avec referenceHeight 1440). Renseignée, les zones sont converties au
     * format de la capture analysée : des réglages faits en 21:9 valent alors aussi en 16:9 (voir [ScreenGeometry]).
     */
    val referenceWidth: Int? = null,
    val sampling: Sampling = Sampling.AUTO,
    /**
     * Décodage matériel de l'échantillonnage à [fps], où toutes les images sont décodées : « auto » laisse FFmpeg
     * choisir, null = logiciel. Repli automatique sur le logiciel en cas d'échec. Sans effet sur les images clés : elles sont
     * trop peu nombreuses pour amortir le transfert depuis le GPU, le décodage logiciel y est deux fois plus rapide.
     */
    val hwaccel: String? = Hwaccel.AUTO,
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
    /** Bord de l'écran auquel l'élément est accroché, pour la conversion vers un autre format d'écran. */
    val anchor: RegionAnchor = RegionAnchor.AUTO,
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
    /**
     * Contraste minimal (écart-type des niveaux de gris) de la zone : en dessous, elle est tenue pour uniforme
     * (écran noir, chargement, fondu) et le score est nul. Utile avec la méthode ncc, qui n'a plus de forme à
     * reconnaître sur un écran vide et se met à corréler le dégradé du fond. 0 = pas de garde.
     */
    val minContrast: Double = 0.0,
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
        val score: (ZoneImage) -> Double,
        val area: FrameZone,
        /** Indice de l'image partagée : deux modèles cherchés au même endroit la préparent une seule fois. */
        val slot: Int,
    )

    /**
     * Zone d'une image, préparée au premier besoin : contraste et binarisation servent à tous les modèles
     * qui visent le même endroit.
     */
    internal class ZoneImage(val gray: GrayImage) {
        private var measuredContrast = Double.NaN
        private val binarized = HashMap<Int, BrightRoi>()

        fun contrast(): Double {
            if (measuredContrast.isNaN()) measuredContrast = gray.contrast()
            return measuredContrast
        }

        fun bright(brightness: Int): BrightRoi = binarized.getOrPut(brightness) { BrightRoi(gray, brightness) }
    }

    /** Zones, abonnement au décodage partagé et scores accumulés, préparés avant le démarrage des détecteurs. */
    private class Prepared(
        val zones: List<Zone>,
        val scores: List<MutableList<Double>>,
        val subscription: FrameSampler.Subscription,
    )

    @Volatile
    private var prepared: Prepared? = null

    override suspend fun prepare(ctx: AnalysisContext) {
        val video = ctx.media.video ?: return
        val zones = buildZones(ctx, video)
        val slots = zones.map { it.area }.distinct().size
        val spec = chooseSampling(ctx, id, params.sampling, params.fps, params.hwaccel, params.maxKeyframeInterval)
        log.info { "$id : ${zones.size} modèle(s), ${if (spec.keyframes) "images clés (~${spec.interval})" else "${params.fps} img/s"}" }
        val scores = List(zones.size) { mutableListOf<Double>() }
        val subscription = ctx.frames.subscribe(
            spec = spec,
            zones = zones.map { it.area },
            label = id,
            progress = ctx.progress,
            onReset = { scores.forEach { it.clear() } },
            onFrame = { _, rois ->
                val images = arrayOfNulls<ZoneImage>(slots)
                zones.forEachIndexed { k, zone ->
                    val roi = rois[k]
                    val image = images[zone.slot]
                        ?: ZoneImage(GrayImage(roi.width, roi.height, roi.pixels)).also { images[zone.slot] = it }
                    scores[k] += zone.score(image)
                }
            },
        )
        prepared = Prepared(zones, scores, subscription)
    }

    override suspend fun analyze(ctx: AnalysisContext): SignalTrack {
        if (ctx.media.video == null) return SignalTrack.missing(id, ctx.grid.count, "pas de flux vidéo")
        // Hors pipeline (tests, usage direct), personne n'a préparé le détecteur : il décode alors pour lui seul.
        if (prepared == null) prepare(ctx)
        val state = prepared ?: return SignalTrack.missing(id, ctx.grid.count, "pas de flux vidéo")

        val sampleTimes = state.subscription.await()
        val scores = state.scores
        val count = minOf(sampleTimes.size, scores.firstOrNull()?.size ?: 0)
        if (count == 0) return SignalTrack.missing(id, ctx.grid.count, "aucune image analysée")

        return when (params.mode) {
            HudMode.EVENTS -> eventsTrack(ctx, state.zones, scores, sampleTimes.subList(0, count))
            HudMode.PRESENCE -> presenceTrack(ctx, state.zones, scores, sampleTimes.subList(0, count))
        }
    }

    /** Une zone par modèle : rectangle source, taille après réduction et fonction de score. */
    private fun buildZones(ctx: AnalysisContext, video: VideoStream): List<Zone> {
        val baseScale = params.referenceHeight.toDouble() / video.height
        val referenceAspect = params.referenceWidth?.let { it.toDouble() / params.referenceHeight }
        val targetAspect = video.width.toDouble() / video.height
        if (referenceAspect != null && abs(referenceAspect - targetAspect) > 0.01) {
            log.info {
                "$id : zones mesurées en ${"%.2f".format(referenceAspect)}:1, converties pour cette capture " +
                    "(${video.width}x${video.height}, ${"%.2f".format(targetAspect)}:1)"
            }
        }
        val slotOf = LinkedHashMap<FrameZone, Int>()
        return params.templates.map { spec ->
            val region = referenceAspect?.let { ScreenGeometry.rescale(spec.region, it, targetAspect, spec.anchor) } ?: spec.region
            val source = GrayImage.load(ctx.configDir.resolve(spec.file))
            val matchScale = spec.matchScale ?: params.matchScale
            val scale = baseScale * matchScale
            val variants = spec.scales.map { source.resized(it * matchScale) }
            val largest = variants.maxBy { it.width * it.height }
            val scorer: (ZoneImage) -> Double = when (spec.method) {
                MatchMethod.NCC -> {
                    val prepared = variants.map { PreparedTemplate(it, spec.name) }
                    val scorer: (ZoneImage) -> Double = { image ->
                        prepared.filter { it.width <= image.gray.width && it.height <= image.gray.height }
                            .maxOfOrNull { TemplateMatcher.match(image.gray, it).score } ?: 0.0
                    }
                    scorer
                }
                MatchMethod.BRIGHT -> {
                    val prepared = variants.map { BrightTemplate(it, spec.brightness, spec.name, spec.tolerance) }
                    val scorer: (ZoneImage) -> Double = { image ->
                        val roi = image.bright(spec.brightness)
                        prepared.maxOf { BrightMatcher.match(roi, it).score }
                    }
                    scorer
                }
            }
            val guarded: (ZoneImage) -> Double =
                if (spec.minContrast <= 0.0) scorer else { image -> if (image.contrast() < spec.minContrast) 0.0 else scorer(image) }
            val cx = (region.x * video.width).roundToInt().coerceIn(0, video.width - 1)
            val cy = (region.y * video.height).roundToInt().coerceIn(0, video.height - 1)
            val cw = (region.width * video.width).roundToInt().coerceIn(1, video.width - cx)
            val ch = (region.height * video.height).roundToInt().coerceIn(1, video.height - cy)
            val sw = (cw * scale).roundToInt().coerceAtLeast(1)
            val sh = (ch * scale).roundToInt().coerceAtLeast(1)
            if (sw < largest.width || sh < largest.height) {
                throw ConfigException(
                    "$id : la zone de '${spec.name}' (${sw}x$sh à l'échelle) est plus petite que le modèle (${largest.width}x${largest.height})",
                )
            }
            val area = FrameZone(cx, cy, cw, ch, sw, sh)
            Zone(spec, guarded, area, slotOf.getOrPut(area) { slotOf.size })
        }
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
