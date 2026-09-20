package dev.highlights.export

import dev.highlights.core.model.OutputFormat
import java.nio.file.Path
import java.text.Normalizer
import java.time.LocalDate
import kotlin.io.path.exists

data class OutputPaths(val videos: Map<OutputFormat, Path>, val report: Path) {
    val all: List<Path> get() = videos.values + listOf(report) // pas de "+ report" : Path est un Iterable<Path>
}

/** `jeu_date_highlights.mp4`, `jeu_date_highlights_9x16.mp4`, `jeu_date_highlights.json` ; suffixe _2, _3… si déjà pris. */
object OutputNamer {
    fun reserve(dir: Path, game: String, date: LocalDate, formats: List<OutputFormat>, kind: String = "highlights"): OutputPaths {
        val base = "${slug(game)}_${date}_$kind"
        var n = 1
        while (true) {
            val stem = if (n == 1) base else "${base}_$n"
            val paths = OutputPaths(
                videos = formats.associateWith { dir.resolve("$stem${it.fileSuffix}.mp4") },
                report = dir.resolve("$stem.json"),
            )
            if (paths.all.none { it.exists() || tempFor(it).exists() }) return paths
            n++
        }
    }

    fun tempFor(target: Path): Path = target.resolveSibling(target.fileName.toString().removeSuffix(".mp4") + ".part.mp4")

    fun slug(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "game" }
}
