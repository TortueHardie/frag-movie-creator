package dev.highlights.montage

import dev.highlights.core.model.TimeRange
import dev.highlights.editing.story.Caption
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LyricsTextTest : FunSpec({
    fun heard(from: Int, to: Int, text: String) = Caption(TimeRange(from.milliseconds, to.milliseconds), text)

    test("fichier .lrc : en-têtes ignorés, refrain répété à chacun de ses horodatages, lignes dans l'ordre") {
        val lines = LyricsText.parse(
            """
            [ar:Personne]
            [ti:Chanson]
            [00:12.50]Le feu brille, encore !
            [00:20.00][00:05.00]Refrain qui revient
            """.trimIndent(),
        )
        lines.map { it.start } shouldBe listOf(5.seconds, 12.5.seconds, 20.seconds)
        lines[1].words shouldBe listOf("Le", "feu", "brille", "encore")
        lines[0].words shouldBe listOf("Refrain", "qui", "revient")
    }

    test("texte brut : des lignes sans instant") {
        LyricsText.parse("Le feu brille\n\nencore").map { it.start to it.words } shouldBe
            listOf(null to listOf("Le", "feu", "brille"), null to listOf("encore"))
    }

    test("ressemblance : mots mal entendus proches, mots différents loin") {
        LyricsText.similarity("burning", "burnin") shouldBeGreaterThan 0.8
        LyricsText.similarity(LyricsText.normalize("Rome"), LyricsText.normalize("roam")) shouldBeGreaterThan 0.4
        LyricsText.similarity("feu", "maestro") shouldBeLessThan 0.3
    }

    test("texte fourni calé sur les mots entendus, même mal transcrits ; les autres entre leurs voisins") {
        val given = LyricsText.parse("Le feu brille encore dans la nuit")
        val words = listOf(
            heard(1000, 1200, "Le"), heard(1200, 1500, "jeu"), heard(1500, 1900, "brie"),
            // « encore dans la » non entendus ; un mot de trop entendu entre deux.
            heard(2000, 2100, "euh"), heard(3000, 3400, "nuits"),
        )
        val aligned = LyricsText.align(given, words)
        aligned.map { it.text } shouldBe listOf("Le", "feu", "brille", "encore", "dans", "la", "nuit")
        aligned[1].range shouldBe TimeRange(1200.milliseconds, 1500.milliseconds)
        aligned[2].range shouldBe TimeRange(1500.milliseconds, 1900.milliseconds)
        aligned[6].range shouldBe TimeRange(3000.milliseconds, 3400.milliseconds)
        // « encore dans la » : entre la fin de « brille » et le début de « nuit », dans l'ordre, sans chevauchement.
        aligned[3].range.start shouldBe 1900.milliseconds
        // 350 ms par mot au plus : ils finissent à 2,95 s, juste avant « nuit ».
        aligned[5].range.end shouldBe 2950.milliseconds
        aligned.zipWithNext().forEach { (a, b) -> (a.range.end <= b.range.start) shouldBe true }
    }

    test(".lrc : un mot entendu loin de sa ligne ne la prend pas ; une ligne non entendue part de son horodatage") {
        val given = LyricsText.parse("[00:01.00]feu de joie\n[00:10.00]feu encore")
        // Le seul « feu » entendu est à 10 s : il revient à la seconde ligne, pas à la première.
        val aligned = LyricsText.align(given, listOf(heard(10_000, 10_300, "feu"), heard(10_300, 10_700, "encore")))
        aligned.map { it.text to it.range.start } shouldBe listOf(
            "feu" to 1.seconds, "de" to aligned[1].range.start, "joie" to aligned[2].range.start,
            "feu" to 10.seconds, "encore" to 10.3.seconds,
        )
        (aligned[2].range.end <= 10.seconds) shouldBe true
        // Débit chanté normal, pas étiré jusqu'à la ligne suivante.
        (aligned[2].range.end <= 2.2.seconds) shouldBe true
    }

    test("sans transcription : un .lrc répartit ses mots dans ses lignes, un .txt ne se place pas") {
        val spread = LyricsText.spread(LyricsText.parse("[00:02.00]un deux\n[00:04.00]trois"))
        spread.map { it.text } shouldBe listOf("un", "deux", "trois")
        spread[0].range.start shouldBe 2.seconds
        spread[2].range.start shouldBe 4.seconds
        LyricsText.spread(LyricsText.parse("un deux")).shouldBeEmpty()
    }

    test("fichier posé à côté de la musique : .lrc avant .txt, BOM toléré") {
        val dir = tempdir().toPath()
        val music = dir.resolve("Chanson.mp3").also { it.writeText("") }
        LyricsText.sidecar(music) shouldBe null
        dir.resolve("Chanson.txt").writeText("texte")
        LyricsText.sidecar(music) shouldBe dir.resolve("Chanson.txt")
        dir.resolve("Chanson.lrc").writeText("﻿[00:01.00]synchro")
        LyricsText.sidecar(music) shouldBe dir.resolve("Chanson.lrc")
        LyricsText.read(dir.resolve("Chanson.lrc")).single().words shouldBe listOf("synchro")
    }
})
