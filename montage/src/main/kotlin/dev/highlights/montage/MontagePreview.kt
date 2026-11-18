package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.FrameSize
import dev.highlights.core.model.OutputFormat
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Aperçu d'un montage kills : le même montage que le rendu final, en petit et vite, pour juger le rythme et le choix des
 * plans sans attendre un rendu pleine qualité.
 *
 * Ce qui ne change pas : la cadence (images par seconde), donc le nombre d'images de chaque plan, les coupes, la place de
 * chaque kill sur son temps, le son (même mixage). L'aperçu se regarde comme le montage final, en flou.
 * Ce qui change : la taille (petit côté à [HEIGHT] pixels), l'encodeur (x264 `ultrafast`), le décodage ([DECODE]) et
 * la source (copies réduites des captures, [PreviewProxies]), le flou par vecteurs, qui demande un décodage de plus de
 * chaque plan pour mesurer la caméra ([CameraMotion]) et ne se voit pas en 360p.
 * Plusieurs aperçus se rendent en même temps ([PARALLELISM]).
 */
object MontagePreview {
    /** Petit côté de l'image d'aperçu : 360p, lisible dans une vignette comme en plein écran sur un téléphone. */
    const val HEIGHT = 360

    /** Format rendu en aperçu parmi ceux demandés : le vertical d'abord (un montage kills part sur TikTok), sinon le premier. */
    fun format(formats: List<OutputFormat>): OutputFormat = formats.firstOrNull { it == OutputFormat.VERTICAL } ?: formats.first()

    /** Réglages de rendu ramenés à l'aperçu : chaque taille de sortie réduite à [height] sur son petit côté, mêmes proportions. */
    fun edit(edit: EditSettings, height: Int = HEIGHT): EditSettings = edit.copy(
        sourceHeight = minOf(height, edit.sourceHeight),
        landscape = shrink(edit.landscape, height),
        vertical = edit.vertical.copy(size = shrink(edit.vertical.size, height)),
        // Un plafond de débit de plateforme vise le rendu final, pas un aperçu de quelques centaines de pixels.
        maxBitrate = null,
    )

    /**
     * Plan ramené à l'aperçu : sans flou par vecteurs ni images calculées au ralenti (même nombre d'images, rendu bien
     * plus court). Le flou radial des coupes reste : il coûte cher (chacune de ses images passe par un `scale` recalculé
     * à chaque image ; aperçu WARDOGS de 19 plans sur proxies : 10,7 s avec, 7,0 s sans), mais il se voit, même en 360p :
     * dans un montage rapide, il couvre une bonne part des images (5 sur 6 prises au hasard sur WARDOGS 22-55-31).
     */
    fun plan(plan: MontagePlan): MontagePlan = plan.copy(
        settings = plan.settings.copy(
            motionBlur = plan.settings.motionBlur.copy(vectors = false),
            slowMotion = plan.settings.slowMotion.copy(interpolate = false),
        ),
    )

    /** Encodeur de l'aperçu : x264 au plus rapide, qualité constante basse ; logiciel, présent sur toute machine. */
    val ENCODER = EncoderProfile(
        "libx264 (aperçu)",
        listOf("-c:v", "libx264", "-preset", "ultrafast", "-crf", "28", "-pix_fmt", "yuv420p"),
        hardware = false,
    )

    /**
     * Décodage des extraits en aperçu : en logiciel (pas de `-hwaccel`), sans filtre de boucle H.264. Mesuré sur 20 s
     * d'une capture WARDOGS 3440x1440 à 60 img/s, recadrée et réduite en 360x640 : 5,0 s avec `-hwaccel auto` (chaque
     * image pleine taille redescend de la carte graphique), 3,0 s en logiciel, 2,35 s sans filtre de boucle. Ce filtre
     * lisse les blocs de compression : à 360 pixels, ils ne se voient pas.
     */
    val DECODE = listOf("-skip_loop_filter", "all")

    /**
     * Aperçus rendus en même temps. Le graphe de filtres d'un montage s'exécute sur un seul cœur (FFmpeg ne parallélise
     * pas l'intérieur d'un graphe) : mesuré sur un aperçu VALORANT de 25 plans (23,7 s de montage, capture 854x480),
     * 12,6 s seul, trois en même temps 15 à 17 s au lieu de 38 s l'un après l'autre (16 fils). Aucun filtre ne dominait :
     * sans `loudnorm`, sans couleurs, sans flou de coupe ou en bilinéaire, chacun ne gagnait que 1 à 2 s.
     */
    const val PARALLELISM = 3

    /**
     * Pixels par image lue au-delà desquels les aperçus se rendent l'un après l'autre : un rendu ouvre un décodeur par plan,
     * et trois rendus de 50 plans en 3440x1440 se disputeraient mémoire et cœurs. Proxy WARDOGS (1528x640) et capture
     * VALORANT 480p en dessous ; une capture 1440p lue sans proxy au-dessus.
     */
    const val PARALLEL_MAX_PIXELS = 1920 * 1080

    /** Aperçus de [plans] à rendre en même temps, une fois branchés sur leurs [proxies]. */
    fun parallelism(plans: List<MontagePlan>, proxies: ProxySources, fps: Int): Int {
        val largest = plans.maxOfOrNull { plan ->
            proxies.apply(plan(plan), fps).first.clips.maxOfOrNull { c -> c.group.media.video?.let { it.width.toLong() * it.height } ?: 0L } ?: 0L
        } ?: 0L
        return if (largest <= PARALLEL_MAX_PIXELS) PARALLELISM else 1
    }

    /** Débit audio de l'aperçu : on y juge le calage, pas la qualité du son. */
    const val AUDIO_BITRATE = "96k"

    /** Images d'une vignette animée : une toutes les [FRAME_STEP] (0,5 s), le montage défile en accéléré dans la vignette. */
    private const val FRAME_STEP = 0.5

    /** Hauteur des images de vignette, assez pour reconnaître une scène. */
    private const val FRAME_HEIGHT = 240

    /**
     * Images de la vignette animée d'un aperçu [video] de [duration], dans [dir] (`f001.jpg`…), dans l'ordre. Un seul
     * décodage de l'aperçu (360p, quelques secondes). Reprises telles quelles si déjà extraites.
     */
    suspend fun frames(ffmpeg: FfmpegService, video: Path, duration: Duration, dir: Path): List<Path> {
        fun existing() = dir.takeIf { it.isDirectory() }?.listDirectoryEntries("f*.jpg")?.sorted().orEmpty()
        existing().takeIf { it.isNotEmpty() }?.let { return it }
        dir.createDirectories()
        val count = (duration.inWholeMilliseconds / 1000.0 / FRAME_STEP).toInt().coerceIn(1, 240)
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-i", video.toString(), "-vf", "fps=${(1 / FRAME_STEP).toInt()},scale=-2:$FRAME_HEIGHT", "-frames:v", count.toString(),
                    "-q:v", "5", "-y", dir.resolve("f%03d.jpg").toString(),
                ),
                "vignette animée ${video.fileName}",
            ),
        )
        return existing()
    }

    private fun shrink(size: FrameSize, height: Int): FrameSize {
        val scale = height.toDouble() / minOf(size.width, size.height)
        if (scale >= 1.0) return size
        return FrameSize(even(size.width * scale), even(size.height * scale))
    }

    private fun even(value: Double): Int = ((value / 2).roundToInt() * 2).coerceAtLeast(2)
}
