package dev.highlights.publish

import dev.highlights.core.config.YouTubePrivacy
import dev.highlights.core.config.YouTubeSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
    val language: String = "fr",
    /** Mise en ligne programmée : la vidéo reste privée jusque-là. */
    val publishAt: Instant? = null,
    val notifySubscribers: Boolean = true,
) {
    /** Ce qui ferait refuser l'envoi par YouTube, en clair ; vide : prêt à partir. */
    fun problems(now: Instant = Instant.now()): List<String> = buildList {
        if (title.isBlank()) add("Le titre est vide")
        if (title.length > YouTubeText.TITLE_MAX) add("Titre trop long (${title.length} caractères, ${YouTubeText.TITLE_MAX} au plus)")
        if (description.toByteArray().size > YouTubeText.DESCRIPTION_MAX) add("Description trop longue (${YouTubeText.DESCRIPTION_MAX} octets au plus)")
        if ('<' in title || '>' in title || '<' in description || '>' in description) add("YouTube refuse les caractères < et > dans le titre et la description")
        if (YouTubeText.tagsLength(tags) > YouTubeText.TAGS_MAX) add("Tags trop longs (${YouTubeText.TAGS_MAX} caractères au plus en tout)")
        if (categoryId.isEmpty() || !categoryId.all { it.isDigit() }) add("Catégorie invalide : un numéro (20 = Jeux vidéo)")
        publishAt?.let {
            if (privacy != YouTubePrivacy.PRIVATE) add("Une mise en ligne programmée se fait depuis une vidéo privée")
            if (it <= now) add("L'heure de mise en ligne programmée est passée")
        }
    }
}

/**
 * Titre, description et tags écrits d'après la vidéo, ou d'après les modèles de la configuration
 * ([YouTubeSettings.titleTemplate], [YouTubeSettings.descriptionTemplate]).
 */
object YouTubeText {
    const val TITLE_MAX = 100
    const val DESCRIPTION_MAX = 5000
    const val TAGS_MAX = 500

    /** Au-delà, YouTube ne classe plus une vidéo verticale en Short. */
    private val SHORT_MAX = 3.minutes

    /** Les mêmes libellés qu'à l'écran dans le montage (voir `TextEffect.multiKillLabels`). */
    private val MULTI_KILLS = mapOf(2 to "DOUBLÉ", 3 to "TRIPLÉ", 4 to "QUADRUPLÉ", 5 to "QUINTUPLÉ")

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun suggest(facts: VideoFacts, settings: YouTubeSettings): YouTubeMetadata {
        val values = values(facts, settings)
        val title = settings.titleTemplate?.let { fill(it, values) } ?: defaultTitle(facts, values)
        val withTitle = values + ("titre" to title)
        val description = settings.descriptionTemplate?.let { fill(it, withTitle) } ?: defaultDescription(facts, withTitle)
        return YouTubeMetadata(
            title = clip(sanitize(title), TITLE_MAX),
            description = sanitize(description).let { if (it.toByteArray().size > DESCRIPTION_MAX) it.take(DESCRIPTION_MAX / 2) else it },
            tags = tags(facts, settings),
            privacy = settings.privacy,
            categoryId = settings.categoryId,
            madeForKids = settings.madeForKids,
            language = settings.language,
            notifySubscribers = settings.notifySubscribers,
        )
    }

    /** Valeurs des champs des modèles. Un champ sans valeur (pas de musique, pas de date) vaut "". */
    fun values(facts: VideoFacts, settings: YouTubeSettings): Map<String, String> = mapOf(
        "jeu" to facts.game,
        "date" to (facts.date?.format(DATE) ?: ""),
        "accroche" to hook(facts),
        "kills" to if (facts.kills > 0) facts.kills.toString() else "",
        "headshots" to if (facts.headshots > 0) facts.headshots.toString() else "",
        "detail" to detail(facts),
        "moments" to if (facts.moments > 0) facts.moments.toString() else "",
        "duree" to duration(facts.duration),
        "musique" to (facts.music ?: ""),
        "hashtags" to hashtags(facts, settings),
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
     */
    fun hook(facts: VideoFacts): String = when {
        facts.aces > 1 -> "${facts.aces} ACES"
        facts.aces == 1 -> "ACE"
        facts.clutches > 0 -> "CLUTCH"
        else -> facts.multiKills.filterValues { it > 0 }.keys.filter { it >= 3 }.maxOrNull()?.let { MULTI_KILLS[it.coerceAtMost(5)] } ?: ""
    }

    /** « 2 doublés, 1 triplé, 1 ace » : ce qu'il y a dans la vidéo, du plus gros au plus petit. */
    fun detail(facts: VideoFacts): String = buildList {
        if (facts.aces > 0) add(plural(facts.aces, "ace", "aces"))
        if (facts.clutches > 0) add(plural(facts.clutches, "clutch", "clutchs"))
        facts.multiKills.filterValues { it > 0 }.toSortedMap(reverseOrder()).forEach { (size, n) ->
            val label = MULTI_KILLS[size.coerceAtMost(5)]!!.lowercase()
            add(plural(n, label, label + "s"))
        }
    }.joinToString(", ")

    private fun defaultTitle(facts: VideoFacts, v: Map<String, String>): String = when (facts.kind) {
        VideoKind.KILL_MONTAGE -> when {
            v.getValue("kills").isEmpty() -> "Montage ${facts.game}"
            v.getValue("accroche").isNotEmpty() -> fill("{accroche} — {kills} kills en {duree} | {jeu}", v)
            else -> fill("{kills} kills en {duree} | {jeu}", v)
        }
        VideoKind.HIGHLIGHTS -> if (facts.date != null) fill("Meilleurs moments {jeu} du {date}", v) else fill("Meilleurs moments {jeu}", v)
    }

    private fun defaultDescription(facts: VideoFacts, v: Map<String, String>): String {
        val summary = when (facts.kind) {
            VideoKind.KILL_MONTAGE -> buildString {
                if (facts.kills > 0) {
                    append(plural(facts.kills, "kill", "kills"))
                    if (facts.headshots > 0) append(", dont ${plural(facts.headshots, "headshot", "headshots")}")
                    v.getValue("detail").takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                    append('.')
                }
            }
            VideoKind.HIGHLIGHTS -> if (facts.moments > 0) "${plural(facts.moments, "moment", "moments")} de la partie." else ""
        }
        val body = listOfNotNull(
            summary.takeIf { it.isNotEmpty() },
            facts.date?.let { "Partie du ${it.format(DATE)}." },
            facts.music?.let { "Musique : $it" },
        ).joinToString("\n")
        return listOf(body, v.getValue("hashtags")).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** Nom du jeu (sans espace ni ponctuation), plus #Shorts pour une vidéo verticale assez courte. */
    fun hashtags(facts: VideoFacts, settings: YouTubeSettings): String = listOfNotNull(
        facts.game.filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }?.let { "#$it" },
        "#Shorts".takeIf { settings.shortsHashtag && facts.vertical && facts.duration <= SHORT_MAX },
    ).joinToString(" ")

    /** Tags de la configuration plus le nom du jeu, sans doublon, dans la limite de YouTube. */
    fun tags(facts: VideoFacts, settings: YouTubeSettings): List<String> {
        val all = (listOf(facts.game) + settings.tags).map { it.replace("<", "").replace(">", "").trim() }
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

    private fun duration(d: Duration): String {
        val s = d.inWholeSeconds.coerceAtLeast(1)
        return if (s < 60) "$s s" else "${s / 60} min${if (s % 60 > 0) " ${s % 60} s" else ""}"
    }

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

    private fun sanitize(text: String) = text.replace("<", "").replace(">", "")

    /** Coupe à la dernière espace avant [max] : pas de mot tronqué dans un titre. */
    private fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: max).trimEnd(' ', '—', '|', ',')
    }
}
