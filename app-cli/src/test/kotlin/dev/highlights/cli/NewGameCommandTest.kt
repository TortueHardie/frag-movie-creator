package dev.highlights.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

class NewGameCommandTest : FunSpec({
    test("l'instant d'un kill marqué se lit en minutes:secondes, en secondes ou en durée") {
        NewGameCommand.parseTime("1:32.5") shouldBe 92_500.milliseconds
        NewGameCommand.parseTime("01:02:03") shouldBe 3_723_000.milliseconds
        NewGameCommand.parseTime("92.5") shouldBe 92_500.milliseconds
        NewGameCommand.parseTime("1m32s") shouldBe 92_000.milliseconds
        NewGameCommand.parseTime("abc") shouldBe null
    }
})
