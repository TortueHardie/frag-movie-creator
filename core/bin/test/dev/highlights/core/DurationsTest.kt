package dev.highlights.core

import dev.highlights.core.serialization.Durations
import dev.highlights.core.serialization.toTimecode
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationsTest : FunSpec({
    context("parse") {
        withData(
            "2s" to 2.seconds,
            "1.5s" to 1500.milliseconds,
            "300ms" to 300.milliseconds,
            "1m30s" to 90.seconds,
            "1h" to 1.hours,
            "5m" to 5.minutes,
            "12" to 12.seconds,
            "01:23.5" to 83.5.seconds,
            "1:02:03" to (1.hours + 2.minutes + 3.seconds),
            "PT2S" to 2.seconds,
            "-390ms" to (-390).milliseconds,
            "-1.5s" to (-1500).milliseconds,
        ) { (text, expected) -> Durations.parseOrNull(text) shouldBe expected }
    }

    test("les textes invalides sont refusés") {
        Durations.parseOrNull("").shouldBeNull()
        Durations.parseOrNull("abc").shouldBeNull()
        Durations.parseOrNull("2x").shouldBeNull()
        Durations.parseOrNull("-").shouldBeNull()
        Durations.parseOrNull("--2s").shouldBeNull()
    }

    test("format puis parse est stable") {
        listOf(Duration.ZERO, 2.seconds, 12_345.milliseconds, 90.seconds, (-400).milliseconds).forEach {
            Durations.parseOrNull(Durations.format(it)) shouldBe it
        }
        Durations.format(12_500.milliseconds) shouldBe "12.5s"
    }

    test("arguments ffmpeg indépendants de la locale") {
        Durations.ffmpegSeconds(1_234_567.milliseconds / 1000) shouldBe "1.235"
    }

    test("timecode") {
        83_450.milliseconds.toTimecode() shouldBe "01:23.450"
        (1.hours + 2.minutes + 3.seconds).toTimecode() shouldBe "1:02:03.000"
    }
})
