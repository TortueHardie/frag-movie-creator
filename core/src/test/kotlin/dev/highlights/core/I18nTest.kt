package dev.highlights.core

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.Message
import dev.highlights.core.i18n.PluralRule
import dev.highlights.core.session.SessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.time.LocalDate
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class I18nTest : FunSpec({
    val en = Locale.ENGLISH
    val fr = Locale.FRENCH

    test("langues disponibles lues sur le classpath, sous leur propre nom") {
        val tags = I18n.available().map { it.tag }
        tags shouldContain "en"
        tags shouldContain "fr"
        I18n.available().first { it.tag == "fr" }.name shouldBe "Français"
    }

    test("langue la plus proche : variante régionale, langue seule, sinon anglais") {
        I18n.resolve("fr-CA") shouldBe Locale.FRENCH
        I18n.resolve("fr") shouldBe Locale.FRENCH
        I18n.resolve("xx") shouldBe Locale.ENGLISH
        I18n.resolve("en-XA").toLanguageTag() shouldBe I18n.PSEUDO_TAG
    }

    test("placeholders nommés, apostrophes telles quelles, placeholder sans argument laissé intact") {
        I18n.text("ui.error.inFile", mapOf("file" to "a.mp4", "detail" to "l'erreur"), fr) shouldBe "a.mp4 : l'erreur"
        I18n.text("youtube.title.killsHook", mapOf("count" to 19), en) shouldBe "{hook} — 19 kills in {duration} | {game}"
    }

    test("pluriels : français 0 et 1 au singulier, anglais 1 seul") {
        I18n.text("ui.count.kills", mapOf("count" to 0), fr) shouldBe "0 kill"
        I18n.text("ui.count.kills", mapOf("count" to 1), fr) shouldBe "1 kill"
        I18n.text("ui.count.kills", mapOf("count" to 0), en) shouldBe "0 kills"
        I18n.text("ui.count.kills", mapOf("count" to 1), en) shouldBe "1 kill"
        I18n.text("ui.count.deaths", mapOf("count" to 2), fr) shouldBe "2 morts"
    }

    test("familles de pluriel du CLDR") {
        listOf(1, 21, 101).map { PluralRule.EAST_SLAVIC.select(it.toDouble()) } shouldBe listOf("one", "one", "one")
        listOf(2, 4, 22, 104).map { PluralRule.EAST_SLAVIC.select(it.toDouble()) }.toSet() shouldBe setOf("few")
        listOf(5, 11, 12, 14, 25, 111).map { PluralRule.EAST_SLAVIC.select(it.toDouble()) }.toSet() shouldBe setOf("many")
        PluralRule.POLISH.select(1.0) shouldBe "one"
        PluralRule.POLISH.select(21.0) shouldBe "many"
        PluralRule.POLISH.select(23.0) shouldBe "few"
        PluralRule.NONE.select(1.0) shouldBe "other"
        PluralRule.ZERO_ONE.select(0.0) shouldBe "one"
        PluralRule.ONE.select(0.0) shouldBe "other"
        PluralRule.of(null, "pt_BR") shouldBe PluralRule.ZERO_ONE
        PluralRule.of(null, "ja") shouldBe PluralRule.NONE
        PluralRule.of("polish", "xx") shouldBe PluralRule.POLISH
    }

    test("nombres, durées et dates selon la langue") {
        I18n.decimal(1.5, 1, fr) shouldBe "1,5"
        I18n.decimal(1.5, 1, en) shouldBe "1.5"
        I18n.fixed(0.6, 2, fr) shouldBe "0,60"
        I18n.percent(0.42, fr) shouldBe "42 %"
        I18n.percent(0.42, en) shouldBe "42%"
        I18n.duration(57.seconds, fr) shouldBe "57 s"
        I18n.duration(90.seconds, fr) shouldBe "1 min 30 s"
        I18n.duration(90.seconds, en) shouldBe "1m 30s"
        I18n.duration(4.minutes, en) shouldBe "4 min"
        I18n.duration(65.minutes, en) shouldBe "1h 05m"
        I18n.date(LocalDate.of(2026, 9, 27), "format.date", fr) shouldBe "27/09/2026"
        I18n.date(LocalDate.of(2026, 9, 27), "format.date", en) shouldBe "Sep 27, 2026"
        I18n.text("ui.count.kills", mapOf("count" to 1234), en) shouldBe "1,234 kills"
    }

    test("clé absente : repli sur l'anglais, sinon la clé elle-même") {
        I18n.text("pas.une.cle", locale = fr) shouldBe "pas.une.cle"
        I18n.textOrNull("pas.une.cle", locale = fr) shouldBe null
    }

    test("pseudo-langue : accentuée, allongée, placeholders intacts") {
        val text = I18n.text("ui.job.analyze", mapOf("name" to "a.mp4"), Locale.forLanguageTag(I18n.PSEUDO_TAG))
        text shouldStartWith "["
        text shouldContain "a.mp4"
        (text.length > "Analyzing a.mp4".length * 1.3) shouldBe true
    }

    test("message d'exception : anglais pour les journaux, langue de l'interface à l'affichage") {
        val e = InputException(Message("error.input.notFound", "file" to "x.mp4"))
        e.message shouldBe "File not found: x.mp4"
        I18n.withLanguage("fr") { e.localizedMessage } shouldBe "Fichier introuvable : x.mp4"
        I18n.withLanguage("en") { e.localizedMessage } shouldBe "File not found: x.mp4"
        // Messages imbriqués et listes : chacun traduit.
        val nested = Message("error.analysis.noSignal", "file" to "x.mp4", "reasons" to listOf(Message("notice.noVideo"), Message("notice.noFrames")))
        nested.text(fr) shouldBe "Aucun signal exploitable pour x.mp4 : pas de flux vidéo; aucune image analysée"
    }

    test("un message survit à l'aller-retour JSON ; un ancien avertissement en texte se relit tel quel") {
        val m = Message("notice.audio.truncated", "read" to 30.seconds, "total" to 2.minutes)
        val json = SessionStore.json.encodeToString(Message.serializer(), m)
        val back = SessionStore.json.decodeFromString(Message.serializer(), json)
        back.text(fr) shouldBe m.text(fr)
        SessionStore.json.decodeFromString(Message.serializer(), "\"ancien avertissement\"").text(en) shouldBe "ancien avertissement"
    }
})
