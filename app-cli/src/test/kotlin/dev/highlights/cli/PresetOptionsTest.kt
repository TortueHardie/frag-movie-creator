package dev.highlights.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parsers.CommandLineParser
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.ExportPreset
import dev.highlights.core.model.OutputFormat
import dev.highlights.montage.ThumbnailStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Analyse la ligne de commande sans lancer la commande, puis lit ce qu'elle passerait au pipeline. */
private fun <T : CliktCommand, R> parsed(command: T, vararg args: String, read: (T) -> R): R {
    var result: Result<R>? = null
    CommandLineParser.parseAndRun(HighlightsCli().subcommands(command), args.toList()) {
        if (it === command) result = runCatching { read(command) }
    }
    return result!!.getOrThrow()
}

/** `--preset` : le préréglage remplit ce que les autres options laissent libre (préréglage déjà résolu, sans configuration). */
class PresetOptionsTest : FunSpec({
    val tiktok = ExportPreset("tiktok", platform = "tiktok", length = 30.seconds, highlightsStyle = EditStyle.STORY, montageStyle = "tiktok", thumbnail = false)
    val complet = ExportPreset("complet", format = OutputFormat.LANDSCAPE, length = 3.minutes, montageStyle = "cinematique", thumbnail = true)
    lateinit var session: Path
    lateinit var music: Path
    beforeTest {
        val dir = tempdir().toPath()
        session = dir.resolve("partie.session.json").also { it.writeText("{}") }
        music = dir.resolve("musique.mp3").also { it.writeText("") }
    }

    test("montage --preset : plateforme, durée, style du préréglage avant le style par défaut ; --style et --max passent devant") {
        val o = parsed(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--preset", "tiktok") { it.montageOptions(defaultStyle = "cinematique", preset = tiktok) }
        o.platform shouldBe "tiktok"
        o.maxDuration shouldBe 30.seconds
        o.style shouldBe "tiktok"
        o.thumbnail shouldBe null
        val hand = parsed(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--max", "45s", "--style", "propre", "--thumbnail") {
            it.montageOptions(defaultStyle = "cinematique", preset = tiktok)
        }
        hand.maxDuration shouldBe 45.seconds
        hand.style shouldBe "propre"
        hand.thumbnail shouldBe ThumbnailStyle.DEFAULT
        parsed(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--style", "none") { it.montageOptions(defaultStyle = "cinematique", preset = tiktok) }.style shouldBe null
        // Sans préréglage, le style par défaut du fichier, comme avant.
        parsed(MontageCommand(), "montage", session.toString(), "-m", music.toString()) { it.montageOptions(defaultStyle = "cinematique") }.style shouldBe "cinematique"
    }

    test("export --preset : format, style et miniature du préréglage ; un format choisi écarte la plateforme") {
        val o = parsed(ExportCommand(), "export", session.toString(), "--preset", "complet") { it.exportOptions(complet) }
        o.formats shouldBe listOf(OutputFormat.LANDSCAPE)
        o.thumbnail shouldBe ThumbnailStyle.DEFAULT
        val hand = parsed(ExportCommand(), "export", session.toString(), "-f", "source", "--style", "simple") { it.exportOptions(tiktok) }
        hand.platform shouldBe null
        hand.formats shouldBe listOf(OutputFormat.SOURCE)
        hand.style shouldBe EditStyle.SIMPLE
    }

    test("search --preset : le montage des moments trouvés prend durée, plateforme et style du préréglage") {
        val o = parsed(SearchCommand(), "search", "--preset", "tiktok") { it.montageOptions(dev.highlights.pipeline.MomentPick(emptyMap()), tiktok) }
        o.maxDuration shouldBe 30.seconds
        o.platform shouldBe "tiktok"
        o.style shouldBe "tiktok"
    }
})
