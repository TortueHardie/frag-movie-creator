package dev.highlights.montage

import dev.highlights.core.model.CropRegion
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.HudOverlay
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.OverlayTarget
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VerticalSettings
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import dev.highlights.editing.RenderCommandBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PreviewProxiesTest : FunSpec({
    val wardogs = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))
    // Réglages WARDOGS : zones mesurées en 3440x1440, killfeed et minimap recollés en 9:16.
    val edit = EditSettings(
        vertical = VerticalSettings(
            size = FrameSize(1080, 1920),
            reference = FrameSize(3440, 1440),
            hud = listOf(
                HudOverlay("killfeed", CropRegion(0.008, 0.445, 0.13, 0.12), OverlayTarget(0.02, 0.07, 0.60)),
                HudOverlay("minimap", CropRegion(0.010, 0.683, 0.105, 0.28), OverlayTarget(0.02, 0.70, 0.34)),
            ),
        ),
    )
    val preview = MontagePreview.edit(edit)

    fun proxyOf(media: MediaInfo, height: Int): MediaInfo {
        val v = media.video!!
        return media.copy(video = v.copy(width = ((v.width.toDouble() * height / v.height / 2).let { Math.round(it) } * 2).toInt(), height = height))
    }

    test("hauteur du proxy : autant de pixels de capture que l'aperçu en montre, rien quand la capture est déjà petite") {
        PreviewProxies.height(OutputFormat.VERTICAL, wardogs, preview) shouldBe 640
        PreviewProxies.height(OutputFormat.LANDSCAPE, wardogs.copy(video = wardogs.video!!.copy(width = 1920, height = 1080)), preview) shouldBe 360
        // VALORANT 854x480, 9:16 recadré sur 79,5 % de la hauteur : il faudrait 805 lignes, la capture en a 480.
        val valorant = MediaInfo(Path("v.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 854, 480, 30.0))
        val cropped = preview.copy(vertical = preview.vertical.copy(cropRegion = CropRegion(0.0, 0.09, 1.0, 0.795), reference = FrameSize(1920, 1080)))
        PreviewProxies.height(OutputFormat.VERTICAL, valorant, cropped) shouldBe null
    }

    test("la géométrie 9:16 sur le proxy recadre les mêmes zones, à l'échelle du proxy") {
        val proxy = proxyOf(wardogs, 640)
        val scale = 640.0 / 1440
        fun crops(media: MediaInfo) = RenderCommandBuilder.videoChain(0, media, OutputFormat.VERTICAL, preview, "g0", pinHud = true)
            .flatMap { Regex("crop=(\\d+):(\\d+):(\\d+):(\\d+)").findAll(it).map { m -> m.groupValues.drop(1).map(String::toInt) }.toList() }
        val original = crops(wardogs)
        val scaled = crops(proxy)
        original.size shouldBe 3 // image 9:16, killfeed, minimap
        scaled.size shouldBe original.size
        original.zip(scaled).forEach { (o, p) ->
            o.zip(p).forEach { (a, b) -> abs(a * scale - b) shouldBe (0.0 plusOrMinus 2.5) }
        }
        // Les zones recollées tombent au même endroit de l'image de sortie, et le zoom vise le même point.
        val targets = RenderCommandBuilder.hudTargets(OutputFormat.VERTICAL, wardogs, preview)
        RenderCommandBuilder.hudTargets(OutputFormat.VERTICAL, proxy, preview).zip(targets).forEach { (p, o) ->
            (abs(p.x - o.x) + abs(p.y - o.y) + abs(p.w - o.w) + abs(p.h - o.h)).toDouble() shouldBe (0.0 plusOrMinus 4.0)
        }
        val (fx, fy) = RenderCommandBuilder.zoomFocus(OutputFormat.VERTICAL, wardogs, preview)
        val (px, py) = RenderCommandBuilder.zoomFocus(OutputFormat.VERTICAL, proxy, preview)
        px shouldBe (fx plusOrMinus 0.005)
        py shouldBe (fy plusOrMinus 0.005)
        RenderCommandBuilder.outputSize(OutputFormat.VERTICAL, proxy, preview) shouldBe RenderCommandBuilder.outputSize(OutputFormat.VERTICAL, wardogs, preview)
        // Le proxy n'agrandit pas : un pixel de proxy par pixel de sortie (la capture en donnait 2,25).
        RenderCommandBuilder.pixelScale(OutputFormat.VERTICAL, proxy, preview) shouldBe (1.0 plusOrMinus 0.01)
    }

    test("les plans servis par un proxy le lisent à sa taille ; l'empreinte du montage ne change pas") {
        val grid = WindowGrid(1.seconds, 1.seconds, wardogs.duration)
        val session = Session(
            createdAt = Instant.EPOCH, media = wardogs, profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = listOf(60, 180, 300, 420, 540, 660, 780, 900).map { TimelineEvent(it.seconds, "kill", 1.0, "n") }),
            highlights = emptyList(),
        )
        val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 30.seconds)
        val n = 240
        val music = MusicAnalysis(
            Path("musique.mp3"), 120.seconds, 120.0, List(n) { (it * 0.5).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
            sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, n, -8.0, 1.0)), dropBeat = 32,
        )
        val plan = MontagePlanner.best(MontagePlanner.groups(listOf(session), settings), music, settings)
        val cuts = MontageRenderBuilder.sourceCuts(plan, 60)
        (cuts.size shouldBeGreaterThan 1)
        val proxy = proxyOf(wardogs, 640)
        // Un passage couvre tous les extraits sauf le premier, qui reste lu sur la capture, avec sa géométrie.
        val fileStart = 1.5.seconds
        val segments = cuts.drop(1).map { LiveSegment(Path("p_${it.range.start.inWholeMilliseconds}.mp4"), TimeRange(it.range.start - 1.seconds, it.range.end + 1.seconds), fileStart) }
        val sources = ProxySources.of(mapOf(wardogs.path.toAbsolutePath().normalize() to Proxy(proxy, segments)), cuts)
        sources.size shouldBe cuts.size - 1

        val (proxied, inputs) = sources.apply(plan, 60)
        MontageVariants.signature(proxied) shouldBe MontageVariants.signature(plan)
        proxied.clips.first().group.media.video!!.width shouldBe 3440
        proxied.clips.drop(1).forEach { it.group.media.video!!.width shouldBe proxy.video!!.width }
        proxied.clips.forEach { it.group.media.path shouldBe wardogs.path }
        MontageRenderBuilder.sourceCuts(proxied, 60).map { it.range } shouldBe cuts.map { it.range }
        cuts.drop(1).forEachIndexed { i, cut ->
            val input = inputs.input(proxy, cut.range)
            input.path shouldBe segments[i].file
            input.start shouldBe cut.range.start - fileStart
        }
        inputs.input(wardogs, cuts.first().range).path shouldBe wardogs.path

        // Rendus simultanés seulement quand toutes les images lues sont petites : un plan lu en 3440x1440 suffit à les empêcher.
        MontagePreview.parallelism(listOf(plan), sources, 60) shouldBe 1
        val all = ProxySources.of(mapOf(wardogs.path.toAbsolutePath().normalize() to Proxy(proxy, segments + LiveSegment(Path("p0.mp4"), cuts.first().range, Duration.ZERO))), cuts)
        MontagePreview.parallelism(listOf(plan), all, 60) shouldBe MontagePreview.PARALLELISM
        MontagePreview.parallelism(listOf(plan), ProxySources.NONE, 60) shouldBe 1
        MontagePreview.plan(plan).settings.motionBlur.cuts shouldBe plan.settings.motionBlur.cuts
    }

    test("passages proches regroupés, éloignés gardés à part") {
        val merged = PreviewProxies.merge(listOf(TimeRange(10.seconds, 14.seconds), TimeRange(0.seconds, 5.seconds), TimeRange(17.seconds, 20.seconds), TimeRange(40.seconds, 41.seconds)))
        merged shouldBe listOf(TimeRange(0.seconds, 5.seconds), TimeRange(10.seconds, 20.seconds), TimeRange(40.seconds, 41.seconds))
    }

    test("les proxies d'une capture modifiée ou disparue sont effacés, ceux d'une capture intacte gardés") {
        val dir = Files.createTempDirectory("proxies")
        val source = dir.resolve("capture.mp4").also { it.writeText("vidéo") }
        val gone = dir.resolve("effacée.mp4")
        val cache = dir.resolve("cache")
        fun folder(name: String, src: java.nio.file.Path, size: Long, modified: Long) = cache.resolve(name).also { f ->
            f.createDirectories()
            f.resolve("p_0_1000.mp4").writeText("proxy")
            val index = ProxyIndex(PreviewProxies.VERSION, src.toString(), size, modified, 1528, 640, listOf(ProxySegment("p_0_1000.mp4", 0, 1_000_000_000, 0, 5, 0)))
            f.resolve("index.json").writeText(Json.encodeToString(ProxyIndex.serializer(), index))
        }
        val intact = folder("a_640", source, source.fileSize(), source.getLastModifiedTime().toMillis())
        val missing = folder("b_640", gone, 10, 10)
        val proxies = PreviewProxies(cache)
        proxies.sweep()
        intact.exists() shouldBe true
        missing.exists() shouldBe false
        proxies.usage().segments shouldBe 1

        source.appendText(" modifiée")
        proxies.sweep()
        intact.exists() shouldBe false
        proxies.usage().bytes shouldBe 0
    }
})
