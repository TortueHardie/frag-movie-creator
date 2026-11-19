package dev.highlights.editing.story

import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.SafeArea
import dev.highlights.core.model.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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

    /** Part de la largeur de l'image qu'un texte peut occuper, bordure comprise. */
    private const val MAX_WIDTH = 0.9

    /** Temps de lecture d'un sous-titre : une base, plus un temps par caractère. */
    private val MIN_READ = 400.milliseconds
    private val PER_CHAR = 45.milliseconds

    /**
     * Durée d'affichage maximale d'un sous-titre (au moins son temps de lecture) : whisper étire parfois un mot sur tout
     * le silence qui suit. Mesuré sur un clip VALORANT : « 1 ENNEMI » affiché 7 s.
     */
    private val MAX_SHOWN = 3.seconds

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

    /** Fin de parole tolérée après la prise de parole détectée : la détection coupe parfois la dernière syllabe. */
    private val SPEECH_TAIL = 250.milliseconds

    /**
     * Même texte redit dans cet intervalle : une boucle de whisper (« Sous-titrage ST' », « 501 », trois fois de suite
     * sur 1 min de micro WARDOGS) ou une réplique du jeu répétée (« Enemies spotted » deux fois en 3 s, VALORANT).
     */
    private val REPEAT_WINDOW = 10.seconds

    /** Descriptions de sons que whisper écrit entre astérisques, crochets ou parenthèses (« *Bruit de feu* », « [Musique] »). */
    private val SOUND_TAG = Regex("""\*[^*]*\*|\[[^\]]*]|\([^)]*\)|♪""")

    /**
     * Garde ce qui a vraiment été dit : pas d'hallucination connue, pas de description de son, pas de réplique du jeu
     * ([ignore], comparée mot à mot sans casse ni accents), pas de texte sans lettre (« ... », « 501 »), pas de
     * répétition rapprochée du même texte, et, si l'analyse a détecté des prises de parole ([speech] non vide), un
     * recouvrement avec l'une d'elles. Les bornes sont resserrées sur la parole détectée : un sous-titre commence avec
     * elle et ne dure pas plus qu'elle.
     *
     * Un segment étiré sur plus de [MAX_LENGTH] est une hallucination quand rien ne dit où est la voix ; avec la
     * parole détectée, il est ramené à la dernière prise de parole qu'il recouvre (whisper fait commencer le segment
     * dans le silence qui précède) : « Essaye de ping » durait 14 s sur le micro WARDOGS, pour 1,5 s de parole.
     */
    fun clean(captions: List<Caption>, speech: List<TimeRange>, ignore: List<String> = emptyList()): List<Caption> {
        val around = speech.map { TimeRange((it.start - SPEECH_SLACK).coerceAtLeast(Duration.ZERO), it.end + SPEECH_SLACK) }
        val sorted = captions.sortedBy { it.range.start }
        val ignored = gameLines(sorted, ignore)
        val kept = sorted.mapIndexedNotNull { i, c ->
            val text = c.text.replace(SOUND_TAG, " ").trim().removePrefix("-").trim().replace(Regex("\\s+"), " ")
            val lower = text.lowercase(Locale.ROOT)
            if (text.none { it.isLetter() } || HALLUCINATIONS.any { it in lower } || i in ignored) return@mapIndexedNotNull null
            if (around.isEmpty()) return@mapIndexedNotNull if (c.range.length > MAX_LENGTH) null else c.copy(text = text)
            // whisper rend souvent des segments de durée nulle : le recouvrement se mesure alors sur un minimum de durée
            // à partir de leur début, sans quoi un segment vide ne recouvrirait jamais rien.
            val probe = TimeRange(c.range.start, maxOf(c.range.end, c.range.start + MIN_OVERLAP))
            var overlaps = around.mapNotNull { s -> overlap(s, probe)?.let { s to it } }
            if (c.range.length > MAX_LENGTH) overlaps = overlaps.takeLast(1)
            val total = overlaps.fold(Duration.ZERO) { acc, (_, r) -> acc + r.length }
            if (overlaps.isEmpty() || total < minOf(MIN_OVERLAP, probe.length * 0.4)) return@mapIndexedNotNull null
            // whisper fait souvent commencer un segment dès la fin du précédent : on le cale sur le début de la parole,
            // et il s'arrête avec elle.
            val start = maxOf(c.range.start, overlaps.first().second.start - LEAD)
            val end = minOf(maxOf(start, c.range.end), overlaps.last().first.end - SPEECH_SLACK + SPEECH_TAIL)
            if (end - start > MAX_LENGTH) return@mapIndexedNotNull null
            Caption(TimeRange(start, maxOf(start, end)), text)
        }
        return withoutRepeats(kept)
    }

    /**
     * Index des segments qui appartiennent à une réplique du jeu ([ignore]). whisper coupe une phrase en morceaux de
     * [CaptionSettings.maxChars] caractères : « Enemies spotted à » puis « l'endroit. », « - Unh! - One » puis « enemy
     * remains. ». Les morceaux qui se suivent sans ponctuation finale ni pause forment une phrase, écartée en entier
     * dès qu'un de ses morceaux est une réplique : sinon « l'endroit. » ou « ready. » restaient seuls à l'écran.
     */
    private fun gameLines(sorted: List<Caption>, ignore: List<String>): Set<Int> {
        if (ignore.isEmpty()) return emptySet()
        val sentences = mutableListOf(mutableListOf<Int>())
        sorted.forEachIndexed { i, c ->
            val previous = sorted.getOrNull(i - 1)
            val ended = previous == null || previous.text.trim().lastOrNull() in SENTENCE_END || c.range.start - previous.range.end > SENTENCE_GAP
            if (ended && sentences.last().isNotEmpty()) sentences += mutableListOf<Int>()
            sentences.last() += i
        }
        return sentences.filter { indices -> indices.any { emphasized(sorted[it].text.split(' '), ignore).any { m -> m } } }.flatten().toSet()
    }

    private val SENTENCE_END = setOf('.', '!', '?', '…')

    /** Pause au-delà de laquelle deux segments de whisper ne sont plus la même phrase. */
    private val SENTENCE_GAP = 300.milliseconds

    /** Écarte un sous-titre qui redit le texte d'un sous-titre gardé moins de [REPEAT_WINDOW] avant lui. */
    internal fun withoutRepeats(captions: List<Caption>): List<Caption> {
        val last = mutableMapOf<String, Duration>()
        return captions.filter { c ->
            val key = normalizeText(c.text)
            val seen = last[key]
            if (seen != null && c.range.start - seen < REPEAT_WINDOW) return@filter false
            last[key] = c.range.end
            true
        }
    }

    /**
     * Écarte les textes dits à plusieurs endroits de l'export (plus de [REPEAT_WINDOW] d'écart) : sur le son complet,
     * ce sont les répliques du jeu (« Enemies spotted » à chaque round), pas le joueur. À n'appliquer qu'à une
     * transcription du son complet : sur le micro, le joueur a le droit de redire « ouais ».
     */
    fun withoutRecurring(captions: List<Caption>): List<Caption> {
        val places = captions.groupBy { normalizeText(it.text) }
        return captions.filter { c ->
            val same = places.getValue(normalizeText(c.text))
            same.none { it !== c && (it.range.start - c.range.start).absoluteValue >= REPEAT_WINDOW }
        }
    }

    /**
     * Laisse à chaque sous-titre le temps d'être lu : whisper donne souvent des segments très courts, voire de durée
     * nulle, et parfois deux segments consécutifs au même instant. Les sous-titres sont donc posés l'un après l'autre :
     * chacun commence au plus tôt quand le précédent a fini, et dure au moins [MIN_READ] plus un temps par caractère
     * (à peu près le temps de prononcer la phrase, si bien que le décalage ne s'accumule pas), et au plus
     * [MAX_SHOWN] (ou son temps de lecture, s'il est plus long).
     *
     * Avec la parole détectée ([speech]), un sous-titre ne reste pas affiché après elle : il s'efface [SPEECH_TAIL]
     * après la fin de la dernière prise de parole qu'il recouvre, sauf s'il lui faut plus pour être lu (phrase dite
     * plus vite qu'elle ne se lit : « bâtiment. » 0,4 s après la fin de la phrase, micro WARDOGS). Borner à la parole
     * sans ce plancher laissait 0,4 s aux derniers morceaux d'une phrase rapide ; les poser chacun à son instant, 0,6 s
     * pour lire « de ce côté-là du ».
     */
    fun readable(captions: List<Caption>, speech: List<TimeRange> = emptyList()): List<Caption> {
        var cursor = Duration.ZERO
        return captions.sortedBy { it.range.start }.map { c ->
            val start = maxOf(c.range.start, cursor)
            val read = MIN_READ + PER_CHAR * c.text.length
            var end = minOf(maxOf(c.range.end, start + read), start + maxOf(MAX_SHOWN, read))
            val probe = TimeRange(c.range.start - SPEECH_SLACK, maxOf(c.range.end, c.range.start + MIN_OVERLAP) + SPEECH_SLACK)
            speech.filter { it.isWithin(probe) }.maxOfOrNull { it.end }?.let { spoken ->
                end = minOf(end, maxOf(spoken + SPEECH_TAIL, start + read))
            }
            cursor = end
            c.copy(range = TimeRange(start, end))
        }
    }

    private fun overlap(a: TimeRange, b: TimeRange): TimeRange? {
        val from = maxOf(a.start, b.start)
        val to = minOf(a.end, b.end)
        return if (to > from) TimeRange(from, to) else null
    }

    /** Texte comparable : mots sans casse, accents ni ponctuation. */
    internal fun normalizeText(text: String): String = text.split(' ').map(::normalize).filter { it.isNotEmpty() }.joinToString(" ")

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
            ":max_len=${settings.maxChars}:queue=${String.format(Locale.ROOT, "%.0f", settings.queue.inWholeMilliseconds / 1000.0)}" +
            ":use_gpu=${if (settings.useGpu) 1 else 0}:destination='${filterPath(destination)}'"

    /**
     * Sous-titre dans l'image : gros, blanc cerné de noir, qui grossit de 70 à 100 % de sa taille en
     * [CaptionSettings.pop] à son apparition. Une phrase criée passe toute en couleur, plus grosse ; sinon les mots
     * forts ([CaptionSettings.emphasis]) sont colorés, chacun dessiné à sa place dans la ligne. [y] : position
     * verticale du centre (part de la hauteur). Une phrase trop large pour l'image ([width]) est réduite jusqu'à y
     * tenir : en 9:16, dix-huit caractères à la taille nominale débordent. [safe] : marges recouvertes par l'interface
     * de la plateforme visée, dont le texte reste à l'écart (centré dans la largeur utile). Renvoie un filtre par
     * morceau de texte.
     */
    /** Hauteur d'une ligne, en tailles de police : de quoi garder le texte entier dans la zone sûre. */
    private const val LINE_HEIGHT = 1.2

    fun drawText(caption: Caption, settings: CaptionSettings, width: Int, height: Int, y: Double, safe: SafeArea = SafeArea.NONE): List<String> {
        val words = sanitize(caption.text, settings.uppercase).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val nominal = settings.size * height * (if (caption.loud) settings.shoutScale else 1.0)
        // Police qui a tous les caractères de la phrase (sous-titre japonais avec Impact configuré : Yu Gothic).
        val font = VideoFonts.forText(settings.font, words.joinToString(" "))
        val base = fit(font, nominal, words.joinToString(" "), (width * safe.width).roundToInt())
        val t0 = caption.range.start
        val t1 = caption.range.end
        val style = TextStyle(font, base, height, safe.centerY(y, LINE_HEIGHT * base / height), t0, t1, settings.pop, safe = safe)
        if (caption.loud) return listOf(style.draw(words.joinToString(" "), settings.shoutColor))
        val marked = emphasized(words, settings.emphasis)
        if (marked.none { it }) return listOf(style.draw(words.joinToString(" "), "white"))

        // Mots placés un à un : largeur de chaque mot et de l'espace, mesurées dans la police, à la taille finale.
        val widths = words.map { TextMeasure.width(font, base, it) }
        val space = TextMeasure.width(font, base, " ")
        if (widths.any { it == null } || space == null) {
            // Police illisible par Java : la phrase entière prend la couleur, faute de pouvoir placer les mots.
            return listOf(style.draw(words.joinToString(" "), settings.emphasisColor))
        }
        val total = widths.sumOf { it!! } + space * (words.size - 1)
        var offset = 0.0
        return words.mapIndexed { i, word ->
            val x = style.centered("${num(total)}*${style.scale}") + "+${num(offset)}*${style.scale}"
            offset += widths[i]!! + space
            style.draw(word, if (marked[i]) settings.emphasisColor else "white", x)
        }
    }

    /** Libellé d'événement (« DOUBLÉ »…) : même apparition « pop », qui s'efface sur sa fin. */
    fun drawLabel(
        text: String, at: TimeRange, font: String, size: Double, color: String, width: Int, height: Int, y: Double, pop: Duration,
        safe: SafeArea = SafeArea.NONE,
    ): String {
        val label = sanitize(text, uppercase = true)
        val shown = VideoFonts.forText(font, label)
        val px = fit(shown, size * height, label, (width * safe.width).roundToInt())
        return TextStyle(shown, px, height, safe.centerY(y, LINE_HEIGHT * px / height), at.start, at.end, pop, fadeOut = 250.milliseconds, safe = safe)
            .draw(label, color)
    }

    /**
     * Taille à laquelle [text] tient dans [MAX_WIDTH] de l'image : la taille voulue si elle tient déjà, sinon réduite
     * d'autant. Sans police lisible par Java, la largeur est estimée (Impact : environ un demi-corps par caractère).
     */
    internal fun fit(font: String, size: Double, text: String, width: Int): Double {
        val measured = TextMeasure.width(font, size, text) ?: (size * 0.55 * text.length)
        val room = width * MAX_WIDTH
        // Arrondi vers le bas : la taille finale est entière, un arrondi au plus proche ferait redéborder de quelques pixels.
        return if (measured <= room) size else floor(size * room / measured)
    }

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
        safe: SafeArea = SafeArea.NONE,
    ) {
        private val safe = safe

        /** Position x d'un texte large de [width] (expression drawtext), centré dans la largeur utile. */
        fun centered(width: String): String = if (safe == SafeArea.NONE) {
            "(w-$width)/2"
        } else {
            "w*${String.format(Locale.ROOT, "%.4f", safe.left)}+(w*${String.format(Locale.ROOT, "%.4f", safe.width)}-$width)/2"
        }

        private val popSeconds = String.format(Locale.ROOT, "%.3f", (pop.inWholeMicroseconds / 1e6).coerceAtLeast(0.001))

        /** Facteur d'échelle de l'apparition (0,7 → 1), à chaque image. */
        val scale = "(0.7+0.3*min(max((t-${sec(t0)})/$popSeconds\\,0)\\,1))"

        fun draw(text: String, color: String, x: String = centered("text_w")): String {
            val px = size.roundToInt().coerceAtLeast(8)
            val border = (height * 0.006).roundToInt().coerceAtLeast(3)
            val fontFile = VideoFonts.drawtextPath(font)
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
