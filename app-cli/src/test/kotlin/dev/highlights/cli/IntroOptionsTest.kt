package dev.highlights.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parsers.CommandLineParser
import dev.highlights.core.model.CardAnimation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.writeText

/** Lecture des options sans rien lancer (voir CommandOptionsTest). */
private fun <T : CliktCommand, R> parse(command: T, vararg args: String, read: (T) -> R): R {
    var result: Result<R>? = null
    CommandLineParser.parseAndRun(HighlightsCli().subcommands(command), args.toList()) {
        if (it === command) result = runCatching { read(command) }
    }
    return result!!.getOrThrow()
}

class IntroOptionsTest : FunSpec({
    lateinit var session: Path
    lateinit var music: Path
    beforeTest {
        val dir = tempdir().toPath()
        session = dir.resolve("partie.session.json").also { it.writeText("{}") }
        music = dir.resolve("musique.mp3").also { it.writeText("") }
    }

    test("--intro et --intro-animation passent au montage kills et à l'export") {
        val montage = parse(MontageCommand(), "montage", session.toString(), "-m", music.toString(), "--intro", "--intro-animation", "flash") { it.montageOptions() }
        montage.intro shouldBe true
        montage.introAnimation shouldBe CardAnimation.FLASH
        val export = parse(ExportCommand(), "export", session.toString(), "--no-intro", "--intro-animation", "zoom") { it.exportOptions() }
        export.intro shouldBe false
        export.introAnimation shouldBe CardAnimation.ZOOM
        parse(ExportCommand(), "export", session.toString()) { it.exportOptions().introAnimation } shouldBe null
    }

    test("animation inconnue : erreur de saisie") {
        shouldThrow<CliktError> { parse(ExportCommand(), "export", session.toString(), "--intro-animation", "spin") { it.exportOptions() } }
    }
})
