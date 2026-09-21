package dev.highlights.editing.story

import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Un sous-titre : quelques mots et l'intervalle où ils sont prononcés. */
data class Caption(val range: TimeRange, val text: String)

/**
 * Sous-titres de la voix : lecture de la transcription de whisper.cpp, nettoyage, et texte « pop » dans le rendu.
 *
 * whisper invente du texte sur le silence ou le bruit du jeu (en français, typiquement « Sous-titrage Société
 * Radio-Canada ») : seuls sont gardés les sous-titres qui recouvrent une prise de parole détectée par l'analyse, et
 * les hallucinations connues sont écartées quoi qu'il arrive.
 */
object Captions {
    /** Fragments (en minuscules) des phrases que whisper produit sur le silence, appris des sous-titres de télévision. */
    internal val HALLUCINATIONS = listOf(
        "sous-titr", "sous titr", "radio-canada", "amara.org", "merci d'avoir regardé", "merci d’avoir regardé",
        "abonnez-vous", "thanks for watching", "subtitles by",
    )

    /** Recouvrement minimal avec une prise de parole : les bornes données par whisper débordent souvent sur le silence. */
    private val MIN_OVERLAP = 300.milliseconds

    /** Tolérance autour d'une prise de parole détectée (l'attaque d'un mot est parfois manquée par la détection). */
    private val SPEECH_SLACK = 200.milliseconds

    /** Un sous-titre plus long que ça est une hallucination étirée sur le silence. */
    private val MAX_LENGTH = 8000.milliseconds

    /** Le texte apparaît un peu avant le mot : l'œil lit avant que l'oreille n'entende. */
    private val LEAD = 100.milliseconds

    /** Sous-titre coupé par un plan au point d'être visible moins longtemps que ça : pas affiché. */
    private val MIN_VISIBLE = 250.milliseconds

    private val json = Json { ignoreUnknownKeys = true }

    /** Lignes JSON du filtre `whisper` (`{"start":ms,"end":ms,"text":…}`), instants relatifs à [offset]. */
    fun parse(lines: List<String>, offset: Duration): List<Caption> = lines.mapNotNull { line ->
        val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return@mapNotNull null
        val start = obj["start"]?.jsonPrimitive?.long ?: return@mapNotNull null
        val end = obj["end"]?.jsonPrimitive?.long ?: return@mapNotNull null
        val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (text.isEmpty() || end < start) null else Caption(TimeRange(offset + start.milliseconds, offset + end.milliseconds), text)
    }

    /**
     * Garde ce qui a vraiment été dit : pas d'hallucination connue, pas de texte étiré sur plusieurs secondes, et, si
     * l'analyse a détecté des prises de parole ([speech] non vide), un recouvrement avec l'une d'elles. Les bornes
     * sont resserrées sur la parole détectée.
     */
    fun clean(captions: List<Caption>, speech: List<TimeRange>): List<Caption> = captions.mapNotNull { c ->
        val text = c.text.removePrefix("-").trim()
        val lower = text.lowercase(Locale.ROOT)
        if (text.isEmpty() || HALLUCINATIONS.any { it in lower } || c.range.length > MAX_LENGTH) return@mapNotNull null
        if (text.none { it.isLetterOrDigit() }) return@mapNotNull null
        if (speech.isEmpty()) return@mapNotNull c.copy(text = text)
        val around = speech.map { TimeRange((it.start - SPEECH_SLACK).coerceAtLeast(Duration.ZERO), it.end + SPEECH_SLACK) }
        val overlaps = around.mapNotNull { s ->
            val from = maxOf(s.start, c.range.start)
            val to = minOf(s.end, c.range.end)
            if (to > from) TimeRange(from, to) else null
        }
        val total = overlaps.fold(Duration.ZERO) { acc, r -> acc + r.length }
        if (total < minOf(MIN_OVERLAP, c.range.length * 0.4)) return@mapNotNull null
        // whisper fait souvent commencer un segment dès la fin du précédent : on le cale sur le début de la parole.
        val start = maxOf(c.range.start, overlaps.first().start - LEAD)
        Caption(TimeRange(start, maxOf(start, c.range.end)), text)
    }

    /** Sous-titres visibles dans le plan [range], relatifs à son début. */
    fun inShot(captions: List<Caption>, range: TimeRange): List<Caption> = captions.mapNotNull { c ->
        val from = maxOf(c.range.start - LEAD, range.start)
        val to = minOf(c.range.end, range.end)
        if (to - from < MIN_VISIBLE) null else Caption(TimeRange(from - range.start, to - range.start), c.text)
    }.let { list ->
        // Deux sous-titres ne se chevauchent jamais à l'écran : le précédent s'efface quand le suivant arrive.
        list.mapIndexed { i, c ->
            val next = list.getOrNull(i + 1)
            if (next != null && next.range.start < c.range.end) c.copy(range = TimeRange(c.range.start, maxOf(c.range.start, next.range.start))) else c
        }.filter { it.range.length >= MIN_VISIBLE }
    }

    /**
     * Filtre audio de transcription : rééchantillonnage à 16 kHz (ce qu'attend whisper) puis `whisper` qui écrit ses
     * segments en JSON dans [destination]. [maxChars] découpe le texte en groupes de quelques mots.
     */
    fun transcriptionFilter(model: Path, destination: Path, settings: CaptionSettings): String =
        "aresample=16000,whisper=model='${filterPath(model)}':language=${settings.language}:format=json" +
            ":max_len=${settings.maxChars}:use_gpu=${if (settings.useGpu) 1 else 0}:destination='${filterPath(destination)}'"

    /**
     * Texte d'un sous-titre dans l'image : gros, blanc cerné de noir, qui grossit de 70 à 100 % de sa taille en
     * [CaptionSettings.pop] à son apparition. [y] : position verticale du centre (part de la hauteur).
     */
    fun drawText(caption: Caption, settings: CaptionSettings, height: Int, y: Double): String {
        val size = (settings.size * height).roundToInt().coerceAtLeast(8)
        val border = (height * 0.006).roundToInt().coerceAtLeast(3)
        val t0 = sec(caption.range.start)
        val t1 = sec(caption.range.end)
        val pop = String.format(Locale.ROOT, "%.3f", (settings.pop.inWholeMicroseconds / 1e6).coerceAtLeast(0.001))
        val font = settings.font.replace("\\", "/").replace(":", "\\:")
        val text = sanitize(caption.text, settings.uppercase)
        return "drawtext=fontfile='$font':text='$text':expansion=none" +
            ":fontsize='$size*(0.7+0.3*min(max((t-$t0)/$pop\\,0)\\,1))'" +
            ":fontcolor=white:borderw=$border:bordercolor=black:shadowx=0:shadowy=${border / 2 + 1}:shadowcolor=black@0.6" +
            ":x=(w-text_w)/2:y=h*${String.format(Locale.ROOT, "%.3f", y)}-text_h/2:enable='between(t\\,$t0\\,$t1)'"
    }

    /**
     * Texte sûr dans un graphe FFmpeg entre apostrophes : l'apostrophe droite devient typographique (l'usage en
     * français), les caractères qui ont un sens pour FFmpeg (antislash, deux-points, crochets, point-virgule) sautent.
     */
    internal fun sanitize(text: String, uppercase: Boolean): String {
        val clean = text.replace('\'', '’').replace(Regex("""[\\:;\[\]]"""), " ").replace(Regex("\\s+"), " ").trim()
        return if (uppercase) clean.uppercase(Locale.FRENCH) else clean
    }

    private fun filterPath(path: Path) = path.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:")

    private fun sec(d: Duration) = String.format(Locale.ROOT, "%.3f", d.inWholeMicroseconds / 1e6)
}
