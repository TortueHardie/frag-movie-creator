package dev.highlights.editing.story

import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Un sous-titre : quelques mots et l'intervalle où ils sont prononcés. */
data class Caption(
    val range: TimeRange,
    val text: String,
    /** Phrase criée : affichée plus grosse, en couleur. */
    val loud: Boolean = false,
)

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
        if (to - from < MIN_VISIBLE) null else c.copy(range = TimeRange(from - range.start, to - range.start))
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
     * Sous-titre dans l'image : gros, blanc cerné de noir, qui grossit de 70 à 100 % de sa taille en
     * [CaptionSettings.pop] à son apparition. Une phrase criée passe toute en couleur, plus grosse ; sinon les mots
     * forts ([CaptionSettings.emphasis]) sont colorés, chacun dessiné à sa place dans la ligne. [y] : position
     * verticale du centre (part de la hauteur). Renvoie un filtre par morceau de texte.
     */
    fun drawText(caption: Caption, settings: CaptionSettings, height: Int, y: Double): List<String> {
        val base = settings.size * height * (if (caption.loud) settings.shoutScale else 1.0)
        val words = sanitize(caption.text, settings.uppercase).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val t0 = caption.range.start
        val t1 = caption.range.end
        val style = TextStyle(settings.font, base, height, y, t0, t1, settings.pop)
        if (caption.loud) return listOf(style.draw(words.joinToString(" "), settings.shoutColor))
        val marked = emphasized(words, settings.emphasis)
        if (marked.none { it }) return listOf(style.draw(words.joinToString(" "), "white"))

        // Mots placés un à un : largeur de chaque mot et de l'espace, mesurées dans la police, à la taille finale.
        val widths = words.map { TextMeasure.width(settings.font, base, it) }
        val space = TextMeasure.width(settings.font, base, " ")
        if (widths.any { it == null } || space == null) {
            // Police illisible par Java : la phrase entière prend la couleur, faute de pouvoir placer les mots.
            return listOf(style.draw(words.joinToString(" "), settings.emphasisColor))
        }
        val total = widths.sumOf { it!! } + space * (words.size - 1)
        var offset = 0.0
        return words.mapIndexed { i, word ->
            val x = "(w-${num(total)}*${style.scale})/2+${num(offset)}*${style.scale}"
            offset += widths[i]!! + space
            style.draw(word, if (marked[i]) settings.emphasisColor else "white", x)
        }
    }

    /** Libellé d'événement (« DOUBLÉ »…) : même apparition « pop », qui s'efface sur sa fin. */
    fun drawLabel(text: String, at: TimeRange, font: String, size: Double, color: String, height: Int, y: Double, pop: Duration): String =
        TextStyle(font, size * height, height, y, at.start, at.end, pop, fadeOut = 250.milliseconds).draw(sanitize(text, uppercase = true), color)

    /** Pour chaque mot, vrai s'il appartient à une expression de [emphasis] (comparaison sans casse ni accents). */
    internal fun emphasized(words: List<String>, emphasis: List<String>): List<Boolean> {
        val normalized = words.map(::normalize)
        val phrases = emphasis.map { e -> e.split(' ').map(::normalize).filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        val marked = BooleanArray(words.size)
        for (phrase in phrases) {
            for (i in 0..words.size - phrase.size) {
                if (phrase.indices.all { normalized[i + it] == phrase[it] }) phrase.indices.forEach { marked[i + it] = true }
            }
        }
        return marked.toList()
    }

    /** Mot comparable : minuscules, sans accents, apostrophe droite, sans ponctuation autour. */
    private fun normalize(word: String): String =
        Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace('’', '\'').trim { !it.isLetterOrDigit() }

    /** Apparence commune des textes : police, taille finale, position, apparition « pop » et, au besoin, effacement. */
    private class TextStyle(
        val font: String,
        val size: Double,
        val height: Int,
        val y: Double,
        val t0: Duration,
        val t1: Duration,
        pop: Duration,
        val fadeOut: Duration = Duration.ZERO,
    ) {
        private val popSeconds = String.format(Locale.ROOT, "%.3f", (pop.inWholeMicroseconds / 1e6).coerceAtLeast(0.001))

        /** Facteur d'échelle de l'apparition (0,7 → 1), à chaque image. */
        val scale = "(0.7+0.3*min(max((t-${sec(t0)})/$popSeconds\\,0)\\,1))"

        fun draw(text: String, color: String, x: String = "(w-text_w)/2"): String {
            val px = size.roundToInt().coerceAtLeast(8)
            val border = (height * 0.006).roundToInt().coerceAtLeast(3)
            val fontFile = font.replace("\\", "/").replace(":", "\\:")
            val alpha = if (fadeOut.isPositive() && t1 - t0 > fadeOut) {
                val from = sec(t1 - fadeOut)
                ":alpha='if(gt(t\\,$from)\\,(${sec(t1)}-t)/${String.format(Locale.ROOT, "%.3f", fadeOut.inWholeMicroseconds / 1e6)}\\,1)'"
            } else {
                ""
            }
            // Ligne alignée sur la hauteur de ligne de la police (lh), pas sur celle du texte : des mots dessinés
            // séparément gardent la même ligne de base, qu'ils aient des jambages ou non.
            return "drawtext=fontfile='$fontFile':text='$text':expansion=none:fontsize='$px*$scale'" +
                ":fontcolor=$color:borderw=$border:bordercolor=black:shadowx=0:shadowy=${border / 2 + 1}:shadowcolor=black@0.6" +
                ":x='$x':y=h*${String.format(Locale.ROOT, "%.3f", y)}-lh/2:enable='between(t\\,${sec(t0)}\\,${sec(t1)})'$alpha"
        }
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

    private fun num(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun sec(d: Duration) = String.format(Locale.ROOT, "%.3f", d.inWholeMicroseconds / 1e6)
}
