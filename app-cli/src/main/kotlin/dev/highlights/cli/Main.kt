package dev.highlights.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = HighlightsCli()
    .subcommands(ProcessCommand(), AnalyzeCommand(), ExportCommand(), MontageCommand(), MusicCommand(), ScoreCommand(), StatsCommand(), EveningCommand(), SearchCommand(), PublishCommand(), VeriteCommand(), TasteCommand(), ThumbnailCommand(), PreviewCommand(), ProbeCommand(), EncodersCommand(), DoctorCommand())
    .main(args)
