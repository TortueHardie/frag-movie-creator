package dev.highlights.publish

import dev.highlights.core.config.YouTubePrivacy
import dev.highlights.core.config.YouTubeSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class YouTubeTextTest : FunSpec({
    val settings = YouTubeSettings()
    val montage = VideoFacts(
        kind = VideoKind.KILL_MONTAGE, game = "VALORANT", date = LocalDate.of(2026, 9, 27), duration = 57.seconds,
        width = 1080, height = 1920, kills = 19, headshots = 13, multiKills = mapOf(2 to 4, 3 to 1), music = "R2D2",
    )

    test("montage kills : accroche sur le triplé, kills, durée et jeu") {
        val m = YouTubeText.suggest(montage, settings)
        m.title shouldBe "TRIPLÉ — 19 kills en 57 s | VALORANT"
        m.description shouldBe "19 kills, dont 13 headshots · 1 triplé, 4 doublés.\nPartie du 27/09/2026.\nMusique : R2D2\n\n#VALORANT #Shorts"
        m.tags shouldContainExactly listOf("VALORANT", "gaming", "highlights")
        m.privacy shouldBe YouTubePrivacy.PRIVATE
        m.categoryId shouldBe "20"
        m.problems() shouldBe emptyList()
    }

    test("un doublé ne fait pas l'accroche ; un ace ou un clutch, si") {
        val doubles = montage.copy(multiKills = mapOf(2 to 4))
        YouTubeText.suggest(doubles, settings).title shouldBe "19 kills en 57 s | VALORANT"
        YouTubeText.hook(doubles.copy(clutches = 1)) shouldBe "CLUTCH"
        YouTubeText.hook(doubles.copy(aces = 1, clutches = 1)) shouldBe "ACE"
        YouTubeText.hook(doubles.copy(aces = 2)) shouldBe "2 ACES"
        YouTubeText.detail(doubles.copy(aces = 1)) shouldBe "1 ace, 4 doublés"
    }

    test("#Shorts seulement pour une vidéo verticale de 3 minutes au plus, et si on le veut") {
        YouTubeText.hashtags(montage, settings) shouldBe "#VALORANT #Shorts"
        YouTubeText.hashtags(montage.copy(width = 1920, height = 1080), settings) shouldBe "#VALORANT"
        YouTubeText.hashtags(montage.copy(duration = 4.minutes), settings) shouldBe "#VALORANT"
        YouTubeText.hashtags(montage, settings.copy(shortsHashtag = false)) shouldBe "#VALORANT"
        YouTubeText.hashtags(montage.copy(game = "League of Legends"), settings) shouldBe "#LeagueofLegends #Shorts"
    }

    test("highlights : moments de la partie") {
        val facts = VideoFacts(VideoKind.HIGHLIGHTS, "League of Legends", LocalDate.of(2026, 9, 20), 4.minutes, 2560, 1080, moments = 8)
        val m = YouTubeText.suggest(facts, settings)
        m.title shouldBe "Meilleurs moments League of Legends du 20/09/2026"
        m.description shouldBe "8 moments de la partie.\nPartie du 20/09/2026.\n\n#LeagueofLegends"
    }

    test("modèles de la configuration : champs remplis, séparateurs orphelins retirés") {
        val custom = settings.copy(
            titleTemplate = "{jeu} | {accroche} | {kills} kills",
            descriptionTemplate = "{titre}\nMusique : {musique}\n{hashtags}",
            tags = listOf("fps", "gaming", "valorant"),
        )
        val m = YouTubeText.suggest(montage.copy(multiKills = emptyMap(), music = null), custom)
        m.title shouldBe "VALORANT | 19 kills"
        m.description shouldBe "VALORANT | 19 kills\nMusique\n#VALORANT #Shorts"
        // Le jeu n'est pas répété, quelle que soit la casse.
        m.tags shouldContainExactly listOf("VALORANT", "fps", "gaming")
    }

    test("titre coupé à 100 caractères sur une espace, < et > retirés") {
        val long = settings.copy(titleTemplate = "<{jeu}> " + "mot ".repeat(40))
        val m = YouTubeText.suggest(montage, long)
        (m.title.length <= YouTubeText.TITLE_MAX) shouldBe true
        m.title shouldNotContain "<"
        m.title.endsWith("mot") shouldBe true
    }

    test("tags dans la limite de 500 caractères, guillemets et virgules compris") {
        val many = settings.copy(tags = List(80) { "tag numero $it" })
        val tags = YouTubeText.suggest(montage, many).tags
        (YouTubeText.tagsLength(tags) <= YouTubeText.TAGS_MAX) shouldBe true
        YouTubeText.tagsLength(listOf("a b", "c")) shouldBe 7
    }

    test("ce que YouTube refuserait est dit en clair") {
        val ok = YouTubeText.suggest(montage, settings)
        val now = Instant.parse("2026-09-28T12:00:00Z")
        ok.copy(title = "").problems(now) shouldHaveSize 1
        ok.copy(title = "a".repeat(101)).problems(now).single() shouldContain "trop long"
        ok.copy(description = "<b>").problems(now).single() shouldContain "< et >"
        ok.copy(categoryId = "jeux").problems(now).single() shouldContain "Catégorie"
        val later = Instant.parse("2026-10-01T16:00:00Z")
        ok.copy(publishAt = later).problems(now) shouldBe emptyList()
        ok.copy(publishAt = later, privacy = YouTubePrivacy.PUBLIC).problems(now).single() shouldContain "privée"
        ok.copy(publishAt = now.minusSeconds(60)).problems(now).single() shouldContain "passée"
    }

    test("heure programmée : locale, avec ou sans T, ou instant ISO") {
        val paris = java.time.ZoneId.of("Europe/Paris")
        YouTubeText.parseSchedule("2026-10-01 18:00", paris) shouldBe Instant.parse("2026-10-01T16:00:00Z")
        YouTubeText.parseSchedule("2026-10-01T18:00", paris) shouldBe Instant.parse("2026-10-01T16:00:00Z")
        YouTubeText.parseSchedule("2026-10-01T16:00:00Z", paris) shouldBe Instant.parse("2026-10-01T16:00:00Z")
        YouTubeText.parseSchedule("demain", paris) shouldBe null
        YouTubeText.parseSchedule(" 2026-10-01 18:00 ", ZoneOffset.UTC) shouldBe Instant.parse("2026-10-01T18:00:00Z")
    }
})
