package dev.highlights.export

import dev.highlights.core.ffmpeg.EncoderSelector
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.SafeArea
import dev.highlights.core.model.TimeRange
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.Durations
import dev.highlights.editing.story.Captions
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.moveTo
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

/**
 * Carton du récap de soirée : une image d'une partie, floutée et assombrie, sous un titre et un sous-titre. [source] et
 * [at] : l'image prise dans la capture (le meilleur moment de la partie).
 */
data class RecapCard(val title: String, val subtitle: String, val source: Path, val at: Duration, val length: Duration)

/** Une partie du récap : son carton, puis sa vidéo de moments déjà rendue (export highlights de la seule partie). */
data class RecapPart(val card: RecapCard, val video: Path)

/**
 * Assemble le récap d'une soirée : pour chaque partie, son intercalaire puis ses moments ; un écran de fin. Les moments
 * sont rendus avant par l'export habituel (même montage, mêmes transitions) ; ici on n'ajoute que les cartons, entrés
 * et sortis en fondu au noir comme le fondu final des exports, et on réencode le tout une fois (des vidéos rendues à
 * part n'ont pas forcément les mêmes réglages d'encodeur, un simple collage pourrait ne pas se lire).
 */
class RecapRenderer(private val ffmpeg: FfmpegService, private val encoders: EncoderSelector) {
    /**
     * Rend [parts] puis [end] dans [output] (même taille et cadence que la première partie). [font] : police des
     * cartons ; [safe] : zone sûre de la plateforme ; [maxBitrate] : son plafond de débit.
     */
    suspend fun render(
        parts: List<RecapPart>,
        end: RecapCard,
        output: Path,
        workDir: Path,
        font: String,
        safe: SafeArea,
        maxBitrate: String?,
        audioBitrate: String,
        progress: ProgressReporter,
    ): Duration {
        require(parts.isNotEmpty()) { "récap vide" }
        val first = ffmpeg.probe(parts.first().video)
        val video = first.video ?: error("vidéo sans image : ${parts.first().video}")
        val w = video.width
        val h = video.height
        val fps = video.fps.takeIf { it > 0 } ?: 60.0
        // Images des cartons : une par carton, prise dans la capture de la partie.
        val cards = parts.map { it.card } + end
        val frames = cards.mapIndexed { i, card ->
            val png = workDir.resolve("recap_card_$i.png")
            ffmpeg.run(FfmpegCommand(listOf("-y", "-ss", Durations.ffmpegSeconds(card.at), "-i", card.source.toString(), "-frames:v", "1", png.toString()), "image du carton $i"))
            png
        }
        val partInfo = parts.map { ffmpeg.probe(it.video) }
        val args = mutableListOf("-y")
        // Entrées : chaque carton (image répétée sur sa durée) suivi de sa partie, puis le carton de fin.
        val inputs = mutableListOf<Pair<String, Int>>()
        parts.forEachIndexed { i, part ->
            args += listOf("-loop", "1", "-framerate", num(fps), "-t", sec(part.card.length), "-i", frames[i].toString())
            inputs += "card" to i
            args += listOf("-i", part.video.toString())
            inputs += "part" to i
        }
        args += listOf("-loop", "1", "-framerate", num(fps), "-t", sec(end.length), "-i", frames.last().toString())
        inputs += "card" to parts.size

        val graph = mutableListOf<String>()
        val labels = mutableListOf<String>()
        inputs.forEachIndexed { index, (kind, i) ->
            if (kind == "card") {
                val card = cards[i]
                graph += "[$index:v]" + cardFilter(card, w, h, fps, font, safe) + "[v$index]"
                graph += "anullsrc=r=48000:cl=stereo,atrim=duration=${sec(card.length)}[a$index]"
            } else {
                graph += "[$index:v]fps=${num(fps)},scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p[v$index]"
                graph += if (partInfo[i].audio.isEmpty()) {
                    "anullsrc=r=48000:cl=stereo,atrim=duration=${sec(partInfo[i].duration)}[a$index]"
                } else {
                    "[$index:a]aresample=48000,aformat=channel_layouts=stereo[a$index]"
                }
            }
            labels += "[v$index][a$index]"
        }
        graph += labels.joinToString("") + "concat=n=${inputs.size}:v=1:a=1[v][a]"
        val script = workDir.resolve("recap_graph.txt")
        script.writeText(graph.joinToString(";\n"))
        val encoder = encoders.select().withMaxBitrate(maxBitrate)
        val part = OutputNamer.tempFor(output)
        args += listOf(ffmpeg.filterScriptOption(), script.toString(), "-map", "[v]", "-map", "[a]") + encoder.videoArgs +
            listOf("-c:a", "aac", "-b:a", audioBitrate, "-movflags", "+faststart", part.toString())
        val total = cards.fold(Duration.ZERO) { acc, c -> acc + c.length } + partInfo.fold(Duration.ZERO) { acc, m -> acc + m.duration }
        log.info { "Récap : ${parts.size} partie(s), ${Durations.format(total)}, encodeur ${encoder.name}" }
        progress.update(0.0, "")
        ffmpeg.run(FfmpegCommand(args, "assemblage du récap")) { line ->
            // « time=00:01:02.50 » : avancement de l'encodage.
            TIME.find(line)?.let { m -> Durations.parseOrNull(m.groupValues[1])?.let { progress.update((it / total).coerceIn(0.0, 1.0), "") } }
        }
        part.moveTo(output, overwrite = false)
        progress.complete()
        return total
    }

    companion object {
        private val TIME = Regex("""time=(\d+:\d{2}:\d{2}(?:\.\d+)?)""")

        /** Repère des deux-points d'un libellé (séparateur invisible, que le nettoyage des libellés laisse passer). */
        private const val COLON = "⁣"

        /** Fondu d'entrée et de sortie d'un carton : celui du fondu final des exports, en plus court. */
        val FADE = 250.milliseconds

        /** Durée d'un intercalaire : assez pour lire « Partie 3 – 14 kills », pas assez pour qu'on décroche. */
        val CARD = 1500.milliseconds

        /** Écran de fin : deux lignes de bilan à lire. */
        val END_CARD = 2500.milliseconds

        /**
         * Image du carton : recadrée au format, floutée et assombrie (le texte se lit dessus), titre puis sous-titre
         * centrés dans la zone sûre, fondus au noir à l'entrée et à la sortie.
         */
        internal fun cardFilter(card: RecapCard, w: Int, h: Int, fps: Double, font: String, safe: SafeArea): String {
            val range = TimeRange(Duration.ZERO, card.length)
            // Les libellés perdent leurs deux-points (Captions.sanitize, pour l'échappement de drawtext) : l'heure
            // « 21:09 » devenait « 21 09 ». Gardés sous un repère invisible, puis échappés (« \: » dans le texte cité).
            fun keepColons(text: String) = text.replace(":", COLON)
            fun escapeColons(filter: String) = filter.replace(COLON, "\\:")
            val title = escapeColons(Captions.drawLabel(keepColons(card.title), range, font, 0.075, "white", w, h, 0.44, 120.milliseconds, safe))
            val subtitle = escapeColons(Captions.drawLabel(keepColons(card.subtitle), range, font, 0.038, "0xFFC93C", w, h, 0.55, 120.milliseconds, safe))
            val fadeOut = card.length - FADE
            return "fps=${num(fps)},scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,setsar=1," +
                "gblur=sigma=${(h / 40.0).toInt().coerceAtLeast(8)},eq=brightness=-0.18:saturation=0.8,format=yuv420p," +
                "$title,$subtitle,fade=t=in:st=0:d=${sec(FADE)},fade=t=out:st=${sec(fadeOut)}:d=${sec(FADE)}"
        }

        private fun sec(d: Duration) = Durations.ffmpegSeconds(d)

        private fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v)
    }
}
