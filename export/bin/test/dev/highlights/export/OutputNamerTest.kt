package dev.highlights.export

import dev.highlights.core.model.OutputFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlin.io.path.name
import kotlin.io.path.writeText

class OutputNamerTest : FunSpec({
    val date = LocalDate.of(2026, 9, 17)

    test("nommage jeu_date_highlights et suffixe 9x16") {
        val dir = tempdir().toPath()
        val paths = OutputNamer.reserve(dir, "lol", date, listOf(OutputFormat.SOURCE, OutputFormat.LANDSCAPE, OutputFormat.VERTICAL))
        paths.videos.getValue(OutputFormat.SOURCE).name shouldBe "lol_2026-09-17_highlights.mp4"
        paths.videos.getValue(OutputFormat.LANDSCAPE).name shouldBe "lol_2026-09-17_highlights_16x9.mp4"
        paths.videos.getValue(OutputFormat.VERTICAL).name shouldBe "lol_2026-09-17_highlights_9x16.mp4"
        paths.report.name shouldBe "lol_2026-09-17_highlights.json"
    }

    test("jamais d'écrasement : numéro ajouté si un des fichiers existe") {
        val dir = tempdir().toPath()
        dir.resolve("valorant_2026-09-17_highlights.json").writeText("{}")
        OutputNamer.reserve(dir, "valorant", date, listOf(OutputFormat.SOURCE)).videos.values.single().name shouldBe
            "valorant_2026-09-17_highlights_2.mp4"
    }

    test("slug") {
        OutputNamer.slug("League of Légendes!") shouldBe "league-of-legendes"
        OutputNamer.slug("***") shouldBe "game"
    }
})
