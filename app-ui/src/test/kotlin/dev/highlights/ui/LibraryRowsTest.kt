package dev.highlights.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LibraryRowsTest : FunSpec({
    fun item(name: String, at: String, length: Duration, clip: Boolean, profile: String = "valorant") = LibraryItem(
        Path("$name.mp4"), Path("$name.session.json"), profile, Instant.parse(at), Instant.parse(at), length, mapOf("kill" to 1), true, clip,
    )

    test("clips d'une même séance rangés en une ligne ; une partie entière, un autre jeu ou une longue pause les séparent") {
        // La plus récente d'abord, comme la bibliothèque.
        val items = listOf(
            item("c5", "2026-11-22T23:30:00Z", 40.seconds, clip = true),
            item("c4", "2026-11-22T21:20:00Z", 40.seconds, clip = true),
            item("c3", "2026-11-22T21:05:00Z", 40.seconds, clip = true),
            item("w1", "2026-11-22T21:00:00Z", 40.seconds, clip = true, profile = "wardogs"),
            item("c2", "2026-11-22T20:50:00Z", 40.seconds, clip = true),
            item("c1", "2026-11-22T20:45:00Z", 40.seconds, clip = true),
            item("game", "2026-11-22T20:00:00Z", 38.minutes, clip = false),
            item("c0", "2026-11-22T19:55:00Z", 40.seconds, clip = true),
        )
        val rows = LibraryRows.of(items)
        rows.map { r ->
            when (r) {
                is LibraryRow.Single -> r.item.source.toString()
                is LibraryRow.Clips -> r.items.joinToString("+") { it.source.toString() }
            }
        } shouldBe listOf("c5.mp4", "c4.mp4+c3.mp4", "w1.mp4", "c2.mp4+c1.mp4", "game.mp4", "c0.mp4")
        val group = rows[1].shouldBeInstanceOf<LibraryRow.Clips>()
        group.events shouldBe mapOf("kill" to 2)
        group.start shouldBe Instant.parse("2026-11-22T21:05:00Z")
        group.end shouldBe Instant.parse("2026-11-22T21:20:40Z")
        // Clé stable : le plus ancien clip, qui reste quand un clip de plus arrive en tête.
        group.key shouldBe Path("c3.session.json")
    }
})
