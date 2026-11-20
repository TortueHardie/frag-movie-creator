package dev.highlights.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parsers.CommandLineParser
import dev.highlights.core.ConfigException
import dev.highlights.core.InputException
import dev.highlights.core.ffmpeg.FfmpegException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.OutputFormat
import dev.highlights.montage.KillRef
import dev.highlights.montage.ExtraMarks
import dev.highlights.montage.IntentRange
import dev.highlights.montage.IntentKind
import dev.highlights.montage.MontagePin
import dev.highlights.montage.ThumbnailStyle
import dev.highlights.pipeline.MomentPick
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException

/**
 * Lecture de la ligne de commande, sans vidéo ni FFmpeg : la commande est analysée par Clikt puis, au lieu de son
 * `run()` (qui lancerait le pipeline), on lit les options qu'elle aurait passées au pipeline.
 */
private fun <T : CliktCommand, R> parse(command: T, vararg args: String, read: (T) -> R): R {
    var result: Result<R>? = null
    CommandLineParser.parseAndRun(HighlightsCli().subcommands(command), args.toList()) {
        if (it === command) result = runCatching { read(command) }
    }
    return result!!.getOrThrow()
}

/** Code de sortie d'une erreur de saisie (option inconnue, valeur invalide…), sans rien lancer. */
private fun usageError(command: CliktCommand, vararg args: String): CliktError =
    shouldThrow<CliktError> { CommandLineParser.parseAndRun(HighlightsCli().subcommands(command), args.toList()) {} }

/** Commande minimale qui fait passer une exception par [PipelineCommand.execute] : le code de sortie qu'elle donne. */
private class Failing(private val error: Throwable) : PipelineCommand("echec") {
    override fun run() {
        execute { throw error }
    }
}

private fun exitCode(error: Throwable): Int =
    shouldThrow<ProgramResult> { CommandLineParser.parseAndRun(HighlightsCli().subcommands(Failing(error)), listOf("echec")) { it.run() } }.statusCode

class CommandOptionsTest : FunSpec({
    lateinit var session: Path
    lateinit var music: Path
    beforeTest {
        val dir = tempdir().toPath()
        session = dir.resolve("partie.session.json").also { it.writeText("{}") }
        music = dir.resolve("musique.mp3").also { it.writeText("") }
    }

    test("montage sans option : tout reste au réglage du profil") {
        val options = parse(MontageCommand(), "montage", session.toString(), "--music", music.toString()) { it.montageOptions() }
        options.formats shouldBe null
        options.maxDuration shouldBe null
        options.fitKills shouldBe null
        options.order shouldBe null
        options.zoom shouldBe null
        options.flash shouldBe null
        options.whip shouldBe null
        options.slowMotion shouldBe null
        options.boostColors shouldBe null
        options.fast shouldBe null
        // null, pas false : sans « --onetaps », le style (s'il y en a un) ou le profil décide.
        options.oneTaps shouldBe null
        options.style shouldBe null
    }

    test("montage : chaque « --no-… » coupe son effet et les choix sont traduits") {
        val options = parse(
            MontageCommand(), "montage", session.toString(), "-m", music.toString(),
            "--max", "45s", "--fill", "--chronological", "--effects", "heavy", "--no-hook", "--from-start",
            "--no-zoom", "--no-flash", "--no-whip", "--no-whoosh", "--no-motion-blur", "--no-match-cut", "--no-rounds",
            "--no-slowmo", "--no-ramp", "--no-text", "--colors", "natural", "--game-audio", "kills", "--balance", "-0,5",
            "--reactions", "--fast", "--onetaps", "--showcase", "--flash-every-cut", "-f", "9:16,16:9", "--platform", "tiktok",
        ) { it.montageOptions() }
        options.maxDuration shouldBe 45.seconds
        options.fitKills shouldBe false
        options.order shouldBe MontageOrder.CHRONOLOGICAL
        options.effectDensity shouldBe EffectDensity.HEAVY
        options.hook shouldBe false
        options.musicFromStart shouldBe true
        options.zoom shouldBe false
        options.flash shouldBe false
        options.flashEveryCut shouldBe true
        options.whip shouldBe false
        options.whoosh shouldBe false
        options.motionBlur shouldBe false
        options.matchCut shouldBe false
        options.rounds shouldBe false
        options.slowMotion shouldBe false
        options.speedRamp shouldBe false
        options.text shouldBe false
        options.boostColors shouldBe false
        options.gameAudio shouldBe GameAudio.KILLS
        options.audioBalance shouldBe -0.5
        options.reactions shouldBe true
        options.fast shouldBe true
        options.oneTaps shouldBe true
        options.showcase shouldBe true
        options.formats shouldBe listOf(OutputFormat.VERTICAL, OutputFormat.LANDSCAPE)
        options.platform shouldBe "tiktok"
    }

    test("kills à la capacité : réglage du profil par défaut, --ability-kills les garde, --skip-ability-kills les écarte") {
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString()) { it.montageOptions().skipAbilityKills } shouldBe null
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--ability-kills") { it.montageOptions().skipAbilityKills } shouldBe false
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--skip-ability-kills") { it.montageOptions().skipAbilityKills } shouldBe true
    }

    test("montage des morts : --fails, rien par défaut") {
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString()) { it.montageOptions().fails } shouldBe null
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--fails") { it.montageOptions().fails } shouldBe true
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString()) { it.montageOptions().reactionSpacing } shouldBe null
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--reaction-montage", "--no-reaction-spacing") { it.montageOptions().reactionSpacing } shouldBe false
    }

    test("montage : --colors boost allume les couleurs vives") {
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--colors", "boost") { it.montageOptions().boostColors } shouldBe true
    }

    test("montage : corrections du plan (--exclude, --force, --pin, --drop) passées au pipeline avec les options") {
        val options = parse(
            MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--no-zoom",
            "--exclude", "partie.mp4@3:12.4", "--exclude", "partie.mp4@5:00", "--force", "autre.mp4@1:02",
            "--pin", "partie.mp4@7:30=0:42", "--drop", "autre.mp4@9:15",
        ) { it.montageOptions(it.constraints()) }
        options.zoom shouldBe false
        val c = options.constraints
        c.excluded shouldBe setOf(KillRef("partie.mp4", 192.4.seconds), KillRef("partie.mp4", 300.seconds))
        c.forced shouldBe setOf(KillRef("autre.mp4", 62.seconds))
        c.pins shouldBe mapOf(KillRef("partie.mp4", 450.seconds) to MontagePin.At(42.seconds), KillRef("autre.mp4", 555.seconds) to MontagePin.Drop)
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString()) { it.montageOptions().constraints.isEmpty } shouldBe true
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--exclude", "partie.mp4").message shouldContain "kill invalide"
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--pin", "partie.mp4@1:00=bientot").message shouldContain "instant du morceau invalide"
    }

    test("montage sur la musique : repères (--mark, --showcase-at, --extra-marks) et kills mis sur un repère (--put)") {
        val c = parse(
            MontageCommand(), "montage", session.toString(), "-m", music.toString(),
            "--mark", "kill@0:36.000", "--mark", "kill@0:30.500", "--mark", "cut@0:33", "--mark", "slow@0:30.500", "--mark", "transition@0:33.2",
            "--mark", "multi@0:40", "--showcase-at", "0:22.900..0:24.810", "--extra-marks", "empty", "--put", "2=partie.mp4@7:00.000",
        ) { it.constraints() }
        val i = c.intents!!
        i.marks.map { it.kind } shouldBe listOf(IntentKind.KILL, IntentKind.SLOW, IntentKind.CUT, IntentKind.TRANSITION, IntentKind.KILL, IntentKind.MULTI)
        i.killMarks.map { it.at } shouldBe listOf(30.5.seconds, 36.seconds, 40.seconds)
        i.showcase shouldBe IntentRange(22.9.seconds, 24.81.seconds, byHand = true)
        i.extras shouldBe ExtraMarks.EMPTY
        i.fixed shouldBe mapOf(i.killMarks[1].id to KillRef("partie.mp4", 420.seconds))
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString()) { it.constraints().intents } shouldBe null
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--mark", "explosion@0:30").message shouldContain "sorte de repère inconnue"
    }

    test("montage : musique obligatoire, équilibre et durée vérifiés avant tout rendu") {
        usageError(MontageCommand(), "montage", session.toString()).statusCode shouldBe 1
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--balance", "2").message shouldContain "équilibre invalide"
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--max", "bientôt").message shouldContain "durée invalide"
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--effects", "enorme").statusCode shouldBe 1
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "-f", "4:3").message shouldContain "format inconnu"
    }

    test("montage : une session qui n'existe pas est refusée") {
        usageError(MontageCommand(), "montage", session.resolveSibling("absente.session.json").toString(), "-m", music.toString()).statusCode shouldBe 1
    }

    test("export : formats, style, plateforme et dossier de sortie") {
        val out = session.parent.resolve("sortie")
        val options = parse(
            ExportCommand(), "export", session.toString(), "-f", "source,9:16", "--style", "story", "--platform", "shorts", "-o", out.toString(),
        ) { it.exportOptions() }
        options.formats shouldBe listOf(OutputFormat.SOURCE, OutputFormat.VERTICAL)
        options.style shouldBe EditStyle.STORY
        options.platform shouldBe "shorts"
        options.outputDir shouldBe out
        options.music shouldBe null
    }

    test("miniature : --thumbnail seul = vif (choix du joueur), --thumbnail-style choisit l'habillage, rien sans option") {
        parse(ExportCommand(), "export", session.toString()) { it.exportOptions().thumbnail } shouldBe null
        parse(ExportCommand(), "export", session.toString(), "--thumbnail") { it.exportOptions().thumbnail } shouldBe ThumbnailStyle.VIVID
        parse(ExportCommand(), "export", session.toString(), "--thumbnail-style", "big") { it.exportOptions().thumbnail } shouldBe ThumbnailStyle.BIG_TEXT
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--thumbnail") { it.montageOptions().thumbnail } shouldBe ThumbnailStyle.VIVID
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--thumbnail-style", "sober") { it.montageOptions().thumbnail } shouldBe ThumbnailStyle.SOBER
        // Avec --pick (rendu final d'une variante) : la miniature suit ; les aperçus (--variants) n'en font jamais.
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--pick", "2", "--thumbnail") { it.montageOptions().thumbnail } shouldBe ThumbnailStyle.VIVID
        parse(ThumbnailCommand(), "thumbnail", session.toString()) { it.chosenStyle() } shouldBe ThumbnailStyle.VIVID
        parse(ThumbnailCommand(), "thumbnail", session.toString(), "--thumbnail-style", "big") { it.chosenStyle() } shouldBe ThumbnailStyle.BIG_TEXT
        usageError(ExportCommand(), "export", session.toString(), "--thumbnail-style", "fluo").statusCode shouldBe 1
    }

    test("export sans option : réglages du profil") {
        val options = parse(ExportCommand(), "export", session.toString()) { it.exportOptions() }
        options.formats shouldBe null
        options.style shouldBe null
        options.platform shouldBe null
        options.outputDir shouldBe null
    }

    test("export : style inconnu et session manquante refusés") {
        usageError(ExportCommand(), "export", session.toString(), "--style", "cinema").statusCode shouldBe 1
        usageError(ExportCommand(), "export").statusCode shouldBe 1
    }

    test("search : critères et montage des moments trouvés") {
        val command = SearchCommand()
        val query = parse(
            command, "search", "-g", "valorant", "--from", "2026-09-20", "--to", "2026-09-27", "--min-kills", "3",
            "--ace", "--clutch", "--headshots", "--onetaps", "--max", "30s", "--fast", "--colors", "boost", "--platform", "reels",
        ) { it.query() }
        query.game shouldBe "valorant"
        query.from shouldBe LocalDate.of(2026, 9, 20)
        query.to shouldBe LocalDate.of(2026, 9, 27)
        query.minKills shouldBe 3
        query.ace shouldBe true
        query.clutch shouldBe true
        query.allHeadshots shouldBe true

        val pick = MomentPick(emptyMap())
        val montage = command.montageOptions(pick)
        montage.onlyKills shouldBe pick
        montage.maxDuration shouldBe 30.seconds
        montage.oneTaps shouldBe true
        montage.fast shouldBe true
        montage.boostColors shouldBe true
        montage.platform shouldBe "reels"
    }

    test("search : --last donne le début de la période quand --from manque, --from l'emporte sinon") {
        val since = LocalDate.of(2026, 9, 1)
        parse(SearchCommand(), "search", "--last", "2") { it.query(since).from } shouldBe since
        parse(SearchCommand(), "search", "--from", "2026-09-20") { it.query(since).from } shouldBe LocalDate.of(2026, 9, 20)
        parse(SearchCommand(), "search") { it.query().minKills } shouldBe 1
    }

    test("search : date et nombres invalides refusés") {
        usageError(SearchCommand(), "search", "--from", "20/09/2026").message shouldContain "date invalide"
        usageError(SearchCommand(), "search", "--min-kills", "0").statusCode shouldBe 1
        usageError(SearchCommand(), "search", "--last", "zéro").statusCode shouldBe 1
    }

    test("option inconnue : erreur de saisie, code 1") {
        usageError(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--turbo").statusCode shouldBe 1
    }

    test("codes de sortie : 1 erreur expliquée, 2 FFmpeg, 3 inattendue, 130 interruption") {
        exitCode(InputException(Message("error.export.noSession"))) shouldBe 1
        exitCode(ConfigException(Message("error.export.noSession"))) shouldBe 1
        exitCode(FfmpegException("rendu 9:16", 1, "ffmpeg -i x", listOf("Invalid argument"))) shouldBe 2
        exitCode(IllegalStateException("bogue")) shouldBe 3
        exitCode(CancellationException("Ctrl+C")) shouldBe 130
    }
})
