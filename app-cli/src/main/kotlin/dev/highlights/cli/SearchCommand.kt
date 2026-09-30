package dev.highlights.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.highlights.core.progress.ProgressTracker
import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.toTimecode
import dev.highlights.core.session.SessionStore
import dev.highlights.pipeline.MomentPick
import dev.highlights.pipeline.MomentQuery
import dev.highlights.pipeline.MontageOptions
import dev.highlights.pipeline.Pipelines
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class SearchCommand : PipelineCommand("search") {
    private val game by option("-g", "--game", help = "Un seul jeu (identifiant de profil : valorant, wardogs…)")
    private val from by option("--from", help = "Depuis la soirée du… (2026-09-20)").convert { parseDate(it) }
    private val to by option("--to", help = "Jusqu'à la soirée du… (2026-09-27)").convert { parseDate(it) }
    private val last by option("--last", help = "Les N dernières soirées seulement").int().restrictTo(min = 1)
    private val minKills by option("--min-kills", help = "Kills au moins dans le moment : 2 = doublés et plus, 3 = triplés…").int().restrictTo(min = 1).default(1)
    private val ace by option("--ace", help = "Aces seulement").flag()
    private val clutch by option("--clutch", help = "Clutchs seulement").flag()
    private val headshots by option("--headshots", help = "Moments dont chaque kill est un tir à la tête").flag()
    private val oneTaps by option("--onetaps", help = "Moments avec au moins un one tap (une balle, à la tête) ; avec --montage, un montage onetaps").flag()
    private val limit by option("--limit", help = "Nombre de moments affichés (défaut : 30)").int().restrictTo(min = 1).default(30)
    private val montage by option("--montage", help = "Montage kills des moments trouvés, sur cette musique (ou un dossier de musiques)").path(mustExist = true)
    private val max by option("--max", help = "Durée maximale du montage, ex. 60s").convert { Durations.parseOrNull(it) ?: throw BadParameterValue("durée invalide '$it'") }
    private val platform by option("--platform", help = "Plateforme visée par le montage : tiktok, shorts, reels, youtube")
    private val out by option("-o", "--out").path(canBeFile = false)

    override fun help(context: Context) =
        "Cherche des moments dans les parties analysées (aces, clutchs, triplés, tout en headshot…) et, avec --montage, en fait un montage kills."

    override fun run() {
        val pipeline = Pipelines.create(env.config)
        val since = last?.let { n -> pipeline.statistics().mapNotNull { it.playedAt }.map { dev.highlights.pipeline.Statistics.eveningOf(it) }.distinct().sortedDescending().take(n).lastOrNull() }
        val query = MomentQuery(game, from ?: since, to, minKills, ace, clutch, headshots, oneTaps)
        val found = execute { pipeline.search(query) }
        if (found.isEmpty()) {
            echo("Aucun moment ne répond à ces critères.")
            return
        }
        echo("${found.size} moment(s), du plus fort au moins fort :")
        val day = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.FRENCH)
        found.take(limit).forEach { m ->
            val labels = listOfNotNull(
                "ACE".takeIf { m.ace }, "CLUTCH".takeIf { m.clutch },
                "${m.kills.size} kills".takeIf { m.kills.size > 1 } ?: "1 kill",
                "${m.headshots} HS".takeIf { m.headshots > 0 },
                "${m.oneTaps} one tap${if (m.oneTaps > 1) "s" else ""}".takeIf { m.oneTaps > 0 },
            ).joinToString(" · ")
            val date = m.playedAt?.atZone(ZoneId.systemDefault())?.format(day) ?: "?"
            echo("  %-20s %-10s %-26s à %s  (%s)".format(date, m.game, labels, m.kills.first().toTimecode(), m.source.fileName))
        }
        if (found.size > limit) echo("  … et ${found.size - limit} autre(s) (--limit)")
        val music = montage ?: return

        val sessions = found.map { it.sessionFile }.distinct().map { SessionStore.load(it) }
        val progress = ConsoleProgress()
        val result = execute {
            try {
                pipeline.killMontage(
                    sessions, music,
                    MontageOptions(maxDuration = max, platform = platform, outputDir = out, onlyKills = MomentPick.of(found), oneTaps = oneTaps),
                    ProgressTracker(listener = progress).root,
                )
            } finally {
                progress.finish()
            }
        }
        printExport(result)
    }

    private fun parseDate(text: String): LocalDate = try {
        LocalDate.parse(text.trim())
    } catch (_: DateTimeParseException) {
        throw BadParameterValue("date invalide '$text' (ex. 2026-09-27)")
    }
}
