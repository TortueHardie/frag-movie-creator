package dev.highlights.core.i18n

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStreamReader
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Traduction de tout ce que l'utilisateur lit : interface, messages d'erreur, textes YouTube proposés, textes incrustés
 * dans les vidéos. Les journaux et le code restent en français, hors de ce mécanisme.
 *
 * **Fichiers** : un seul fichier par langue, `core/src/main/resources/i18n/messages_<tag>.properties` (UTF-8, tag
 * BCP 47 avec « _ » : `fr`, `pt_BR`, `zh_CN`). `messages.properties` est l'anglais, langue de repli de toute clé
 * absente. Ajouter une langue = déposer son fichier : la liste des langues ([available]) est lue sur le classpath, et
 * le nom affiché dans le sélecteur vient de sa clé `language.name`. Pourquoi un seul fichier pour tous les modules
 * (interface comprise) plutôt qu'un par module ou les ressources Compose : un traducteur n'a qu'un fichier à copier, le
 * test de complétude n'a qu'une paire de fichiers à comparer, et le cœur (exceptions, textes YouTube, textes des
 * vidéos) en a besoin de toute façon.
 *
 * **Syntaxe** : `{nom}` est remplacé par l'argument du même nom (pas de MessageFormat : ses apostrophes à doubler
 * piégeaient tout texte français). Un `{nom}` sans argument reste tel quel : les modèles YouTube (`{game}`, `{kills}`)
 * traversent le bundle intacts. Les nombres entiers sont formatés selon la langue, les [Duration] par [duration], les
 * [Message] traduits à leur tour.
 *
 * **Pluriels** : une clé plurielle n'existe que par ses formes `clé.one`, `clé.few`, `clé.many`, `clé.other` ; la forme
 * est choisie d'après l'argument `count` par la famille de règles de la langue (`plural.rule` du fichier, voir
 * [PluralRule]). `.other` est obligatoire, les autres selon la famille.
 *
 * **Langue** : choisie par [setLanguage] (préférence de l'utilisateur, ou null = langue d'affichage de Windows), puis
 * ramenée à la meilleure disponible (`pt_BR` → `pt` → anglais). `-Dhighlights.lang=…` la force (tests, captures
 * d'écran) ; `en-XA` est une pseudo-langue : l'anglais allongé de 40 % et accentué, pour repérer les textes coupés et
 * ceux qui ne passent pas par les bundles.
 */
object I18n {
    const val BUNDLE_DIR = "i18n"
    const val BASE = "messages"
    const val PSEUDO_TAG = "en-XA"
    val ENGLISH: Locale = Locale.ENGLISH

    // Avant [locale] : sa valeur initiale lit déjà les fichiers.
    private val bundles = ConcurrentHashMap<String, Map<String, String>>()
    private val languages: List<Language> by lazy {
        val tags = scanTags() + "en"
        tags.distinct().map { t -> Language(t, bundle(t)["language.name"] ?: t) }.sortedBy { it.name.lowercase() }
    }

    /** Langue en cours (celle d'un fichier existant, ou [PSEUDO_TAG]). */
    @Volatile
    var locale: Locale = resolve(System.getProperty("highlights.lang"))
        private set

    val pseudo: Boolean get() = locale.toLanguageTag() == PSEUDO_TAG

    /**
     * Change la langue : [tag] null = langue d'affichage du système. La propriété `highlights.lang` reste prioritaire.
     * Rend la langue retenue.
     */
    fun setLanguage(tag: String?): Locale {
        locale = resolve(System.getProperty("highlights.lang") ?: tag)
        log.info { "Langue de l'interface : ${locale.toLanguageTag()}" }
        return locale
    }

    /**
     * Impose une langue sans tenir compte de `highlights.lang` : tests et captures d'écran, qui rendent la même
     * interface dans plusieurs langues. Rend la langue retenue.
     */
    fun forceLanguage(tag: String): Locale {
        locale = resolve(tag)
        return locale
    }

    /** Exécute [block] dans la langue [tag], puis revient à la précédente. */
    fun <T> withLanguage(tag: String, block: () -> T): T {
        val previous = locale
        locale = resolve(tag)
        try {
            return block()
        } finally {
            locale = previous
        }
    }

    /** Langue disponible la plus proche de [tag] (null = système), anglais à défaut. */
    fun resolve(tag: String?): Locale {
        if (tag == PSEUDO_TAG) return Locale.forLanguageTag(PSEUDO_TAG)
        val wanted = tag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it.replace('_', '-')) }
            ?: Locale.getDefault(Locale.Category.DISPLAY)
        val codes = available().map { it.tag }.toSet()
        return listOf(fileTag(wanted), wanted.language).firstOrNull { it in codes }
            ?.let { Locale.forLanguageTag(it.replace('_', '-')) } ?: ENGLISH
    }

    /** Une langue proposée dans le sélecteur : [tag] du fichier (`pt_BR`) et nom dans cette langue (`Português (Brasil)`). */
    data class Language(val tag: String, val name: String)

    /** Langues dont un fichier existe sur le classpath, anglais compris (fichier de base), triées par nom. */
    fun available(): List<Language> = languages

    /** Tag du fichier pour une langue : `fr`, `pt_BR`, `zh_CN` ; l'anglais est le fichier de base. */
    fun fileTag(locale: Locale): String = if (locale.country.isEmpty()) locale.language else "${locale.language}_${locale.country}"

    // ------------------------------------------------------------------------------------------------ résolution

    /** Texte de [key] dans la langue en cours, [args] remplacés ; la clé elle-même si elle n'existe nulle part. */
    fun text(key: String, args: Map<String, Any?> = emptyMap(), locale: Locale = this.locale): String {
        val template = template(key, args["count"], locale) ?: return key.also { log.warn { "Clé de traduction absente : $key" } }
        return format(template, args, locale)
    }

    /** Comme [text], mais null si la clé n'existe pas (libellés facultatifs : nom traduit d'un profil). */
    fun textOrNull(key: String, args: Map<String, Any?> = emptyMap(), locale: Locale = this.locale): String? =
        template(key, args["count"], locale)?.let { format(it, args, locale) }

    /** Modèle brut de [key] (forme plurielle choisie d'après [count]), dans la langue puis en anglais. */
    private fun template(key: String, count: Any?, locale: Locale): String? {
        val pseudo = locale.toLanguageTag() == PSEUDO_TAG
        val chain = if (pseudo) listOf("en") else chain(locale)
        for (tag in chain) {
            val bundle = bundle(tag)
            val found = bundle[key] ?: pluralForm(bundle, key, count, tag)
            if (found != null) return if (pseudo) Pseudo.expand(found) else found
        }
        return null
    }

    private fun pluralForm(bundle: Map<String, String>, key: String, count: Any?, tag: String): String? {
        if (bundle["$key.other"] == null) return null
        val n = (count as? Number)?.toDouble() ?: return bundle["$key.other"]
        val category = PluralRule.of(bundle["plural.rule"], tag).select(n)
        return bundle["$key.$category"] ?: bundle["$key.other"]
    }

    /** Fichiers à consulter, du plus précis à l'anglais : pt_BR, pt, en. */
    private fun chain(locale: Locale): List<String> = listOf(fileTag(locale), locale.language, "en").distinct()

    /** Remplace les `{nom}` connus de [args] ; les autres restent tels quels. */
    fun format(template: String, args: Map<String, Any?>, locale: Locale = this.locale): String {
        if (args.isEmpty() || '{' !in template) return template
        return PLACEHOLDER.replace(template) { m ->
            val name = m.groupValues[1]
            if (args.containsKey(name)) render(args[name], locale) else m.value
        }
    }

    private fun render(value: Any?, locale: Locale): String = when (value) {
        null -> ""
        is Message -> value.text(locale)
        is Int, is Long, is Short -> NumberFormat.getIntegerInstance(numberLocale(locale)).format(value)
        is Double, is Float -> decimal((value as Number).toDouble(), 2, locale)
        is Duration -> duration(value, locale)
        is LocalDate -> value.format(dateFormatter("format.date", locale))
        is Path -> value.toString()
        is Collection<*> -> value.joinToString(text("format.listSeparator", locale = locale)) { render(it, locale) }
        is Enum<*> -> value.name.lowercase()
        else -> value.toString()
    }

    /** Placeholder : `{nom}` (lettres, chiffres, « _ »). */
    val PLACEHOLDER = Regex("""\{(\w+)}""")

    // ------------------------------------------------------------------------------------------------ formats

    private fun numberLocale(locale: Locale) = if (locale.toLanguageTag() == PSEUDO_TAG) ENGLISH else locale

    /** Nombre à au plus [digits] décimales (1,5 en français, 1.5 en anglais). */
    fun decimal(value: Double, digits: Int = 1, locale: Locale = this.locale): String =
        NumberFormat.getNumberInstance(numberLocale(locale)).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = 0
        }.format(value)

    /** Nombre à exactement [digits] décimales (scores : 0,60). */
    fun fixed(value: Double, digits: Int, locale: Locale = this.locale): String =
        NumberFormat.getNumberInstance(numberLocale(locale)).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = digits
            isGroupingUsed = false
        }.format(value)

    /** Pourcentage entier : « 42 % » en français, « 42% » en anglais (clé `format.percent`). */
    fun percent(fraction: Double, locale: Locale = this.locale): String =
        text("format.percent", mapOf("value" to Math.round(fraction * 100).toInt()), locale)

    /** Durée courte et lisible : « 45 s », « 1 min 30 s », « 1 h 05 » en français (clés `duration.*`). */
    fun duration(d: Duration, locale: Locale = this.locale): String {
        val total = d.inWholeSeconds.coerceAtLeast(0)
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        val pad = { v: Long -> v.toString().padStart(2, '0') }
        return when {
            h > 0 -> text("duration.hm", mapOf("h" to h.toString(), "m" to pad(m)), locale)
            m > 0 && s == 0L -> text("duration.m", mapOf("m" to m.toString()), locale)
            m > 0 -> text("duration.ms", mapOf("m" to m.toString(), "s" to s.toString()), locale)
            else -> text("duration.s", mapOf("s" to s.toString()), locale)
        }
    }

    /** Date ou heure formatée selon le motif (DateTimeFormatter) que la langue donne à [patternKey]. */
    fun date(value: TemporalAccessor, patternKey: String, locale: Locale = this.locale): String =
        dateFormatter(patternKey, locale).format(value)

    private fun dateFormatter(patternKey: String, locale: Locale): DateTimeFormatter {
        val pattern = template(patternKey, null, Locale.ENGLISH.takeIf { locale.toLanguageTag() == PSEUDO_TAG } ?: locale) ?: "yyyy-MM-dd"
        return DateTimeFormatter.ofPattern(pattern, numberLocale(locale))
    }

    // ------------------------------------------------------------------------------------------------ fichiers

    /** Clés et textes d'une langue ([tag] « en » = fichier de base), sans repli. */
    fun bundle(tag: String): Map<String, String> = bundles.getOrPut(tag) {
        val name = if (tag == "en") "$BUNDLE_DIR/$BASE.properties" else "$BUNDLE_DIR/${BASE}_$tag.properties"
        val props = Properties()
        I18n::class.java.classLoader.getResources(name).toList().forEach { url ->
            url.openStream().use { props.load(InputStreamReader(it, Charsets.UTF_8)) }
        }
        props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    /** Tags des fichiers `messages_<tag>.properties` présents, dans un dossier (développement) comme dans un JAR. */
    private fun scanTags(): List<String> {
        val pattern = Regex("""${BASE}_(\w+)\.properties""")
        val names = mutableSetOf<String>()
        I18n::class.java.classLoader.getResources(BUNDLE_DIR).toList().forEach { url ->
            runCatching {
                when (url.protocol) {
                    "file" -> Files.list(Path.of(url.toURI())).use { s -> s.forEach { names += it.name } }
                    "jar" -> (url.openConnection() as JarURLConnection).jarFile.use { jar ->
                        jar.entries().asSequence().map { it.name }.filter { it.startsWith("$BUNDLE_DIR/") }
                            .forEach { names += it.substringAfterLast('/') }
                    }
                }
            }.onFailure { log.warn { "Langues illisibles dans $url : ${it.message}" } }
        }
        return names.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1) }.sorted()
    }
}

/** Texte de [key] dans la langue de l'interface : `tr("ui.settings.analyze")`, `tr("ui.count.kills", "count" to 3)`. */
fun tr(key: String, vararg args: Pair<String, Any?>): String = I18n.text(key, args.toMap())

/**
 * Pseudo-langue `en-XA` : chaque lettre accentuée et le texte allongé d'environ 40 % (l'allemand dépasse l'anglais
 * d'un tiers), entre crochets pour voir où il est coupé. Les `{placeholders}` restent intacts.
 */
internal object Pseudo {
    private const val FROM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val TO = "áƀçđéƒĝĥíĵķĺɱñóƥǫŕšţúṽŵẋýžÅƁÇĐÉƑĜĤÍĴĶĹṀÑÖƤǪŔŠŢÚṼŴẊÝŽ"

    fun expand(text: String): String {
        val out = StringBuilder("[")
        var i = 0
        var letters = 0
        while (i < text.length) {
            val m = I18n.PLACEHOLDER.find(text, i)?.takeIf { it.range.first == i }
            if (m != null) {
                out.append(m.value)
                i = m.range.last + 1
                continue
            }
            val c = text[i]
            // Les motifs de date ne passent pas par ici (I18n.dateFormatter lit l'anglais) ; « \n » reste un saut de ligne.
            val k = FROM.indexOf(c)
            out.append(if (k >= 0) TO[k] else c)
            if (c.isLetter()) letters++
            i++
        }
        val padding = (letters * 0.4).toInt().coerceAtLeast(2)
        out.append(' ').append("~".repeat(padding)).append(']')
        return out.toString()
    }
}
