package dev.highlights.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WhisperLocatorTest : FunSpec({
    test("chemin configuré, puis dossier whisper de la configuration, puis PATH ; absent : null") {
        val config = tempdir().toPath()
        val onPath = tempdir().toPath()
        val env = mapOf("Path" to onPath.toString())
        WhisperLocator.locate(null, config, env) shouldBe null
        val inPath = onPath.resolve(WhisperLocator.PROGRAM).also { it.writeText("x") }
        WhisperLocator.locate(null, config, env) shouldBe inPath.toAbsolutePath()
        val inConfig = config.resolve("whisper").createDirectories().resolve(WhisperLocator.PROGRAM).also { it.writeText("x") }
        WhisperLocator.locate(null, config, env) shouldBe inConfig.toAbsolutePath()
        val configured = config.resolve("ailleurs.exe").also { it.writeText("x") }
        WhisperLocator.locate(configured, config, env) shouldBe configured.toAbsolutePath()
        // Chemin configuré mais absent : pas de repli silencieux sur un autre programme.
        WhisperLocator.locate(config.resolve("absent.exe"), config, env) shouldBe null
    }
})
