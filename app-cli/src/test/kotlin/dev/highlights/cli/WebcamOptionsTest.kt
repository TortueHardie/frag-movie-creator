package dev.highlights.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parsers.CommandLineParser
import dev.highlights.core.model.WebcamChoice
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Lecture des options sans rien lancer (voir CommandOptionsTest). */
private fun <T : CliktCommand, R> parse(command: T, vararg args: String, read: (T) -> R): R {
    var result: Result<R>? = null
    CommandLineParser.parseAndRun(HighlightsCli().subcommands(command), args.toList()) {
        if (it === command) result = runCatching { read(command) }
    }
    return result!!.getOrThrow()
}

class WebcamOptionsTest : FunSpec({
    lateinit var session: Path
    lateinit var music: Path
    lateinit var cam: Path
    beforeTest {
        val dir = tempdir().toPath()
        session = dir.resolve("partie.session.json").also { it.writeText("{}") }
        music = dir.resolve("musique.mp3").also { it.writeText("") }
        cam = dir.resolve("webcam.mp4").also { it.writeText("") }
    }

    test("--webcam auto, --webcam FICHIER et --webcam-offset passent au montage kills") {
        val auto = parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--webcam", "auto", "--webcam-offset", "-400ms") { it.montageOptions() }
        auto.webcam shouldBe WebcamChoice.Auto
        auto.webcamOffset shouldBe (-400).milliseconds
        val file = parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--webcam", cam.toString()) { it.montageOptions() }
        file.webcam shouldBe WebcamChoice.File(cam)
        file.webcamOffset shouldBe null
        parse(MontageCommand(), "montage", session.toString(), "-m", music.toString()) { it.montageOptions().webcam } shouldBe null
    }

    test("--webcam passe à l'export") {
        val options = parse(ExportCommand(), "export", session.toString(), "--webcam", "AUTO", "--webcam-offset", "1.2s") { it.exportOptions() }
        options.webcam shouldBe WebcamChoice.Auto
        options.webcamOffset shouldBe 1.2.seconds
    }

    test("webcam introuvable ou décalage illisible : erreur de saisie") {
        shouldThrow<CliktError> {
            CommandLineParser.parseAndRun(HighlightsCli().subcommands(ExportCommand()), listOf("export", session.toString(), "--webcam", "absente.mp4")) {}
        }
        shouldThrow<CliktError> {
            CommandLineParser.parseAndRun(HighlightsCli().subcommands(ExportCommand()), listOf("export", session.toString(), "--webcam-offset", "bientôt")) {}
        }
    }

    test("durées signées") {
        parseSignedDuration("-400ms") shouldBe (-400).milliseconds
        parseSignedDuration("+1.5s") shouldBe 1.5.seconds
        parseSignedDuration("2") shouldBe 2.seconds
        parseSignedDuration("-0.25") shouldBe (-250).milliseconds
        parseSignedDuration("x") shouldBe null
    }
})
