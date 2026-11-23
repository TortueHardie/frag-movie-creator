package dev.highlights.core.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

class AtomicFilesTest : FunSpec({
    test("un fichier réécrit sans cesse ne disparaît jamais pour celui qui le lit") {
        val dir = Files.createTempDirectory("atomic")
        val file = dir.resolve("library.json").apply { writeText("0") }
        val done = AtomicBoolean(false)
        val missing = AtomicInteger(0)
        val reader = thread {
            while (!done.get()) if (!file.isRegularFile()) missing.incrementAndGet()
        }
        repeat(2000) { i ->
            val tmp = dir.resolve("library.json.tmp")
            tmp.writeText("$i")
            AtomicFiles.replace(tmp, file)
        }
        done.set(true)
        reader.join()
        missing.get() shouldBe 0
        file.readText() shouldBe "1999"
    }
})
