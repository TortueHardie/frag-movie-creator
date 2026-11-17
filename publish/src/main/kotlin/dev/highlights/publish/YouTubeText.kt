package dev.highlights.publish

import dev.highlights.core.config.YouTubePrivacy
import dev.highlights.core.config.YouTubeSettings
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.Message
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class VideoKind { KILL_MONTAGE, HIGHLIGHTS }

/**
 * Ce qu'on sait d'une vidéo exportée, tiré de son rapport JSON et de la vidéo elle-même : de quoi écrire un titre et une
 * description qui parlent de la partie plutôt que d'un nom de fichier.
 */
data class VideoFacts(
    val kind: VideoKind,
    /** Nom affiché du jeu (profil). */
    val game: String,
    val date: LocalDate?,
    val duration: Duration,
    val width: Int,
    val height: Int,
    val kills: Int = 0,
    val headshots: Int = 0,
    /** Groupes de kills par taille : 2 → nombre de doublés, 3 → de triplés… */
    val multiKills: Map<Int, Int> = emptyMap(),
    val aces: Int = 0,
    val clutches: Int = 0,
    /** Moments montés (highlights). */
    val moments: Int = 0,
    /** Titre de la musique (nom du fichier sans extension). */
    val music: String? = null,
) {
    val vertical: Boolean get() = height > width
}

/** Tout ce qui part avec la vidéo ; chaque champ se modifie avant l'envoi. */
data class YouTubeMetadata(
    val title: String,
    val description: String,
    val tags: List<String>,
    val privacy: YouTubePrivacy,
    val categoryId: String = "20",
    val madeForKids: Boolean = false,
    val language: String = "en",
    /** Mise en ligne programmée : la vidéo reste privée jusque-là. */
    val publishAt: Instant? = null,
    val notifySubscribers: Boolean = true,
) {
    /** Ce qui ferait refuser l'envoi par YouTube, traduisible ; vide : prêt à partir. */
    fun problems(now: Instant = Instant.now()): List<Message> = buildList {
        if (title.isBlank()) add(Message("youtube.problem.titleEmpty"))
        if (title.length > YouTubeText.TITLE_MAX) add(Message("youtube.problem.titleTooLong", "length" to title.length, "max" to YouTubeText.TITLE_MAX))
        if (description.toByteArray().size > YouTubeText.DESCRIPTION_MAX) add(Message("youtube.problem.descriptionTooLong", "max" to YouTubeText.DESCRIPTION_MAX))
        if ('<' in title || '>' in title || '<' in description || '>' in description) add(Message("youtube.problem.angleBrackets"))
        if (YouTubeText.tagsLength(tags) > YouTubeText.TAGS_MAX) add(Message("youtube.problem.tagsTooLong", "max" to YouTubeText.TAGS_MAX))
        if (categoryId.isEmpty() || !categoryId.all { it.isDigit() }) add(Message("youtube.problem.category"))
        publishAt?.let {
            if (privacy != YouTubePrivacy.PRIVATE) add(Message("youtube.problem.scheduleNotPrivate"))
            if (it <= now) add(Message("youtube.problem.schedulePast"))
        }
    }
}

/**
 * Titre, description et tags écrits d'après la vidéo, dans la langue de l'interface (clés `youtube.*` des bundles), ou
 * d'après les modèles de la configuration ([YouTubeSettings.titleTemplate], [YouTubeSettings.descriptionTemplate]).
 */
object YouTubeText {
    const val TITLE_MAX = 100
    const val DESCRIPTION_MAX = 5000
    const val TAGS_MAX = 500

    /** Au-delà, YouTube ne classe plus une vidéo verticale en Short. */
    private val SHORT_MAX = 3.minutes

    /** Langue déclarée à YouTube : celle de la configuration, sinon celle de l'interface. */
    fun language(settings: YouTubeSettings): String =
        settings.language ?: if (I18n.pseudo) "en" else I18n.locale.toLanguageTag()

    fun suggest(facts: VideoFacts, settings: YouTubeSettings): YouTubeMetadata {
        val values = values(facts, settings)
        val title = settings.titleTemplate?.let { fill(it, values) } ?: defaultTitle(facts, values)
        val withTitle = values + mapOf("titre" to title, "title" to title)
        val description = settings.descriptionTemplate?.let { fill(it, withTitle) } ?: defaultDescription(facts, withTitle)
        return YouTubeMetadata(
            title = clip(sanitize(title), TITLE_MAX),
            description = sanitize(description).let { if (it.toByteArray().size > DESCRIPTION_MAX) it.take(DESCRIPTION_MAX / 2) else it },
            tags = tags(facts, settings),
            privacy = settings.privacy,
            categoryId = settings.categoryId,
            madeForKids = settings.madeForKids,
            language = language(settings),
            notifySubscribers = settings.notifySubscribers,
        )
    }

    /**
     * Valeurs des champs des modèles, sous leur nom anglais ({game}, {hook}, {duration}…) et sous leur ancien nom
     * français ({jeu}, {accroche}, {duree}…), que les configurations existantes emploient. Un champ sans valeur (pas de
     * musique, pas de date) vaut "".
     */
    fun values(facts: VideoFacts, settings: YouTubeSettings): Map<String, String> {
        val v = mapOf(
            "game" to facts.game,
            "date" to (facts.date?.let { I18n.date(it, "format.date") } ?: ""),
            "hook" to hook(facts),
            "kills" to if (facts.kills > 0) facts.kills.toString() else "",
            "headshots" to if (facts.headshots > 0) facts.headshots.toString() else "",
            "detail" to detail(facts),
            "moments" to if (facts.moments > 0) facts.moments.toString() else "",
            "duration" to duration(facts.duration),
            "music" to (facts.music ?: ""),
            "hashtags" to hashtags(facts, settings),
        )
        return v + LEGACY_FIELDS.mapValues { (_, english) -> v.getValue(english) }
    }

    /** Anciens noms français des champs → nom anglais. */
    private val LEGACY_FIELDS = mapOf(
        "jeu" to "game", "accroche" to "hook", "duree" to "duration", "musique" to "music",
    )

    /** Remplace les {champs} ; ceux qui manquent laissent des séparateurs orphelins, retirés. */
    fun fill(template: String, values: Map<String, String>): String {
        val filled = Regex("""\{(\w+)}""").replace(template) { m -> values[m.groupValues[1]] ?: m.value }
        return filled.lines().joinToString("\n") { line ->
            line.replace(Regex(""" {2,}"""), " ")
                // « {jeu} | {accroche} | {kills} » sans accroche : un seul séparateur entre ce qui reste.
                .replace(Regex("""\s*([—|·])(\s*[—|·])+\s*"""), " $1 ")
                .replace(Regex("""\s+([,.])"""), "$1")
                .trim().trim('—', '|', '·', '-', ':', ',').trim()
        }.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /**
     * Ce qui fait cliquer : un ace, un clutch, sinon le plus gros multi-kill à partir du triplé. Un doublé ne mérite pas
     * la tête du titre : quatre doublés sur 19 kills donnaient « DOUBLÉ — 19 kills », qui vend moins que les 19 kills.
     * Les libellés sont ceux incrustés dans le montage (`video.*`).
     */
    fun hook(facts: VideoFacts): String = when {
        facts.aces > 1 -> I18n.text("video.aces", mapOf("count" to facts.aces))
        facts.aces == 1 -> I18n.text("video.ace")
        facts.clutches > 0 -> I18n.text("video.clutch")
        else -> facts.multiKills.filterValues { it > 0 }.keys.filter { it >= 3 }.maxOrNull()
            ?.let { I18n.text("video.streak.${it.coerceAtMost(5)}") } ?: ""
    }

    /** « 2 doublés, 1 triplé, 1 ace » : ce qu'il y a dans la vidéo, du plus gros au plus petit. */
    fun detail(facts: VideoFacts): String = buildList {
        if (facts.aces > 0) add(I18n.text("youtube.count.ace", mapOf("count" to facts.aces)))
        if (facts.clutches > 0) add(I18n.text("youtube.count.clutch", mapOf("count" to facts.clutches)))
        facts.multiKills.filterValues { it > 0 }.toSortedMap(reverseOrder()).forEach { (size, n) ->
            add(I18n.text("youtube.count.multi${size.coerceAtMost(5)}", mapOf("count" to n)))
        }
    }.joinToString(I18n.text("format.listComma"))

    private fun defaultTitle(facts: VideoFacts, v: Map<String, String>): String = when (facts.kind) {
        VideoKind.KILL_MONTAGE -> when {
            v.getValue("kills").isEmpty() -> fill(I18n.text("youtube.title.montage"), v)
            v.getValue("hook").isNotEmpty() -> fill(I18n.text("youtube.title.killsHook", mapOf("count" to facts.kills)), v)
            else -> fill(I18n.text("youtube.title.kills", mapOf("count" to facts.kills)), v)
        }
        VideoKind.HIGHLIGHTS -> fill(I18n.text(if (facts.date != null) "youtube.title.highlightsDate" else "youtube.title.highlights"), v)
    }

    private fun defaultDescription(facts: VideoFacts, v: Map<String, String>): String {
        val summary = when (facts.kind) {
            VideoKind.KILL_MONTAGE -> if (facts.kills > 0) {
                val kills = I18n.text("youtube.desc.kills", mapOf("count" to facts.kills))
                val withHeadshots = if (facts.headshots > 0) {
                    I18n.text("youtube.desc.withHeadshots", mapOf("kills" to kills, "headshots" to I18n.text("youtube.desc.headshots", mapOf("count" to facts.headshots))))
                } else {
                    kills
                }
                val detail = v.getValue("detail")
                I18n.text(if (detail.isEmpty()) "youtube.desc.summary" else "youtube.desc.summaryDetail", mapOf("kills" to withHeadshots, "detail" to detail))
            } else {
                ""
            }
            VideoKind.HIGHLIGHTS -> if (facts.moments > 0) I18n.text("youtube.desc.moments", mapOf("count" to facts.moments)) else ""
        }
        val body = listOfNotNull(
            summary.takeIf { it.isNotEmpty() },
            facts.date?.let { I18n.text("youtube.desc.date", mapOf("date" to v.getValue("date"))) },
            facts.music?.let { I18n.text("youtube.desc.music", mapOf("music" to it)) },
        ).joinToString("\n")
        return listOf(body, v.getValue("hashtags")).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** Nom du jeu (sans espace ni ponctuation), plus #Shorts pour une vidéo verticale assez courte. */
    fun hashtags(facts: VideoFacts, settings: YouTubeSettings): String = listOfNotNull(
        facts.game.filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }?.let { "#$it" },
        "#Shorts".takeIf { settings.shortsHashtag && facts.vertical && facts.duration <= SHORT_MAX },
    ).joinToString(" ")

    /**
     * Nom du jeu, tags de la configuration (anglais par défaut : la portée la plus large), puis ceux de la langue du
     * texte (clé `youtube.tags`, séparés par des virgules) pour être trouvé aussi dans sa langue ; sans doublon, dans
     * la limite de YouTube (les derniers tombent les premiers).
     */
    fun tags(facts: VideoFacts, settings: YouTubeSettings): List<String> {
        val local = I18n.text("youtube.tags", locale = if (I18n.pseudo) I18n.ENGLISH else I18n.locale).split(',')
        val all = (listOf(facts.game) + settings.tags + local).map { it.replace("<", "").replace(">", "").trim() }
            .filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val kept = mutableListOf<String>()
        for (tag in all) if (tagsLength(kept + tag) <= TAGS_MAX) kept += tag
        return kept
    }

    /** Longueur comptée par YouTube : les tags avec une espace comptent leurs guillemets, plus une virgule entre deux. */
    fun tagsLength(tags: List<String>): Int =
        tags.sumOf { it.length + if (' ' in it) 2 else 0 } + (tags.size - 1).coerceAtLeast(0)

    /** Heure de mise en ligne programmée : heure locale (2026-10-01T18:00, « 2026-10-01 18:00 ») ou instant ISO (…Z). */
    fun parseSchedule(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val t = text.trim()
        return try {
            Instant.parse(t)
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun duration(d: Duration): String = I18n.duration(d.coerceAtLeast(1.seconds))

    private fun sanitize(text: String) = text.replace("<", "").replace(">", "")

    /** Coupe à la dernière espace avant [max] : pas de mot tronqué dans un titre. */
    private fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: max).trimEnd(' ', '—', '|', ',')
    }
}
