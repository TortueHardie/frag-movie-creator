package dev.highlights.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = HighlightsCli()
    .subcommands(ProcessCommand(), AnalyzeCommand(), ExportCommand(), MontageCommand(), MusicCommand(), ScoreCommand(), StatsCommand(), EveningCommand(), RecapCommand(), SearchCommand(), PublishCommand(), VeriteCommand(), TasteCommand(), PreviewCommand(), ProbeCommand(), EncodersCommand(), DoctorCommand())
    .main(args)
