package dev.highlights.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) = HighlightsCli()
    .subcommands(ProcessCommand(), AnalyzeCommand(), ExportCommand(), MontageCommand(), MusicCommand(), PreviewCommand(), ProbeCommand(), EncodersCommand(), DoctorCommand())
    .main(args)
