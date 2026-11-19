package dev.highlights.export

import dev.highlights.core.model.OutputFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlin.io.path.name
import kotlin.io.path.setLastModifiedTime
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

    test("suffixes successifs _2 puis _3, et une seule vidéo prise décale tous les formats ensemble") {
        val dir = tempdir().toPath()
        dir.resolve("wardogs_2026-09-17_killmontage_9x16.mp4").writeText("")
        dir.resolve("wardogs_2026-09-17_killmontage_2.json").writeText("{}")
        val paths = OutputNamer.reserve(dir, "Wardogs", date, listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL), kind = "killmontage")
        paths.videos.getValue(OutputFormat.SOURCE).name shouldBe "wardogs_2026-09-17_killmontage_3.mp4"
        paths.videos.getValue(OutputFormat.VERTICAL).name shouldBe "wardogs_2026-09-17_killmontage_3_9x16.mp4"
        paths.report.name shouldBe "wardogs_2026-09-17_killmontage_3.json"
    }

    test("le fichier temporaire d'un rendu est à côté de la vidéo, en .part.mp4") {
        val target = tempdir().toPath().resolve("lol_2026-09-17_highlights_9x16.mp4")
        OutputNamer.tempFor(target).name shouldBe "lol_2026-09-17_highlights_9x16.part.mp4"
        OutputNamer.tempFor(target).parent shouldBe target.parent
    }

    test("miniature : à côté de la vidéo, _miniature puis _miniature_2, jamais d'écrasement") {
        val dir = tempdir().toPath()
        val video = dir.resolve("wardogs_2026-09-22_killmontage_9x16.mp4")
        OutputNamer.thumbnailFor(video).name shouldBe "wardogs_2026-09-22_killmontage_9x16_miniature.png"
        dir.resolve("wardogs_2026-09-22_killmontage_9x16_miniature.png").writeText("première")
        OutputNamer.thumbnailFor(video).name shouldBe "wardogs_2026-09-22_killmontage_9x16_miniature_2.png"
        dir.resolve("wardogs_2026-09-22_killmontage_9x16_miniature_2.png").writeText("deuxième")
        OutputNamer.thumbnailFor(video).name shouldBe "wardogs_2026-09-22_killmontage_9x16_miniature_3.png"
        OutputNamer.thumbnailFor(video).parent shouldBe dir
    }

    test("miniature existante : la plus récente des _miniature*.png de la vidéo, pas celles d'une autre") {
        val dir = tempdir().toPath()
        val video = dir.resolve("lol_2026-09-17_highlights.mp4")
        OutputNamer.thumbnailOf(video) shouldBe null
        dir.resolve("lol_2026-09-17_highlights_9x16_miniature.png").writeText("autre vidéo")
        OutputNamer.thumbnailOf(video) shouldBe null
        val first = dir.resolve("lol_2026-09-17_highlights_miniature.png").also { it.writeText("1") }
        val second = dir.resolve("lol_2026-09-17_highlights_miniature_2.png").also { it.writeText("2") }
        first.setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(1_000))
        second.setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(2_000))
        OutputNamer.thumbnailOf(video) shouldBe second
    }

    test("slug") {
        OutputNamer.slug("League of Légendes!") shouldBe "league-of-legendes"
        OutputNamer.slug("***") shouldBe "game"
    }
})
