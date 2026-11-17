package dev.highlights.core

import dev.highlights.core.config.YouTubePrivacy
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.PluralRule
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.OutputFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Garde-fous des traductions. Chaque fichier `messages_<tag>.properties` doit avoir toutes les clés de l'anglais, avec
 * les mêmes `{placeholders}`, les formes plurielles que sa famille demande, et aucune clé que l'anglais n'a plus. Toute
 * clé citée dans le code doit exister en anglais. Un message d'échec liste tout ce qui manque, langue par langue.
 */
class BundlesTest : FunSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val dir = root.resolve("core/src/main/resources/i18n")
    val english = I18n.bundle("en")
    val pluralSuffixes = listOf(".one", ".few", ".many", ".other")
    fun placeholders(text: String) = I18n.PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()
    fun pluralBase(key: String) = pluralSuffixes.firstOrNull { key.endsWith(it) }?.let { key.removeSuffix(it) }
        ?.takeIf { english.containsKey("$it.other") }
    val pluralBases = english.keys.mapNotNull(::pluralBase).toSet()
    val tags = Files.list(dir).use { s -> s.map { it.name }.toList() }
        .mapNotNull { Regex("""messages_(\w+)\.properties""").matchEntire(it)?.groupValues?.get(1) }

    test("le fichier anglais existe et déclare une famille de pluriel connue") {
        english.size shouldNotBe 0
        PluralRule.entries.map { it.id }.contains(english["plural.rule"]) shouldNotBe false
    }

    for (tag in tags) {
        test("$tag : mêmes clés, mêmes placeholders, pluriels complets") {
            val bundle = I18n.bundle(tag)
            val rule = PluralRule.entries.firstOrNull { it.id == bundle["plural.rule"] }
            val problems = mutableListOf<String>()
            if (rule == null) problems += "plural.rule absent ou inconnu (${bundle["plural.rule"]}) : ${PluralRule.entries.joinToString { it.id }}"
            for ((key, text) in english) {
                if (pluralBase(key) != null) continue
                val translated = bundle[key]
                when {
                    translated == null -> problems += "clé manquante : $key"
                    placeholders(translated) != placeholders(text) ->
                        problems += "placeholders de $key : ${placeholders(translated)} au lieu de ${placeholders(text)}"
                }
            }
            for (base in pluralBases) {
                val englishOther = placeholders(english.getValue("$base.other"))
                val allowed = pluralSuffixes.mapNotNull { english["$base$it"] }.flatMap(::placeholders).toSet() + "count"
                val needed = rule?.categories ?: listOf("other")
                for (category in needed) {
                    if (bundle["$base.$category"] == null) problems += "forme plurielle manquante : $base.$category"
                }
                bundle["$base.other"]?.let { if (placeholders(it) != englishOther) problems += "placeholders de $base.other : ${placeholders(it)} au lieu de $englishOther" }
                for (suffix in pluralSuffixes) {
                    val form = bundle["$base$suffix"] ?: continue
                    val extra = placeholders(form) - allowed
                    if (extra.isNotEmpty()) problems += "placeholders inconnus dans $base$suffix : $extra"
                }
            }
            for (key in bundle.keys) {
                val known = english.containsKey(key) || pluralBase(key) != null ||
                    pluralSuffixes.any { key.endsWith(it) && key.removeSuffix(it) in pluralBases }
                if (!known) problems += "clé inconnue de l'anglais (à retirer) : $key"
            }
            problems.shouldBeEmpty()
        }
    }

    test("toute clé citée dans le code existe en anglais") {
        val prefixes = "ui|error|notice|progress|youtube|video|format|event|unit|duration|audio|signal|profile"
        val literal = Regex(""""((?:$prefixes)\.[A-Za-z0-9_.-]+)"""")
        val sources = Files.list(root).use { s -> s.toList() }
            .map { it.resolve("src/main/kotlin") }.filter { it.isDirectory() }
            .flatMap { src -> Files.walk(src).use { w -> w.filter { it.extension == "kt" }.toList() } }
        val missing = sources.flatMap { file ->
            literal.findAll(file.readText()).map { it.groupValues[1] }
                .filterNot { it.endsWith(".") || it.endsWith(".kt") }
                .filterNot { english.containsKey(it) || english.containsKey("$it.other") }
                .map { "${root.relativize(file)} : $it" }
        }.distinct()
        missing.shouldBeEmpty()
    }

    test("les clés construites à partir d'une énumération ou d'un nombre existent") {
        val keys = OutputFormat.entries.map { "format.${it.name.lowercase()}.short" } +
            EffectDensity.entries.flatMap { listOf("ui.montage.density.${it.name.lowercase()}", "ui.montage.density.${it.name.lowercase()}.hint") } +
            GameAudio.entries.map { "ui.montage.gameAudio.${it.name.lowercase()}" } +
            YouTubePrivacy.entries.map { "youtube.privacy.${it.apiName}" } +
            (2..5).flatMap { listOf("video.streak.$it", "youtube.count.multi$it") } +
            (2..4).map { "ui.multiKill.$it" } +
            listOf("duration.s", "duration.m", "duration.ms", "duration.hm", "format.percent", "format.listSeparator", "format.listComma", "language.name")
        keys.filterNot { english.containsKey(it) || english.containsKey("$it.other") }.shouldBeEmpty()
    }
})
