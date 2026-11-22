package dev.highlights.export

import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.TimeRange
import dev.highlights.editing.story.Caption
import dev.highlights.editing.story.Captions
import dev.highlights.editing.story.Speaker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ChatVoiceTest : FunSpec({
    /** Mono 8 kHz : une note forte sur [on] (secondes), silence ailleurs. */
    fun audio(seconds: Double, vararg on: ClosedFloatingPointRange<Double>) =
        FloatArray((seconds * ChatVoice.RATE).toInt()) { i ->
            val t = i.toDouble() / ChatVoice.RATE
            if (on.any { t in it }) (0.3 * sin(2 * Math.PI * 220 * t)).toFloat() else 0f
        }

    test("chat vocal : prises de parole lues au niveau, petits trous comblés, clics écartés") {
        val spans = ChatVoice.spans(audio(6.0, 1.0..1.8, 2.0..2.6, 4.0..4.05), 10.seconds)
        // 1,0–1,8 et 2,0–2,6 : 200 ms d'écart, une seule phrase ; le clic de 50 ms à 4 s ne compte pas.
        spans.size shouldBe 1
        (spans[0].start - 11.seconds).absoluteValue shouldBe (spans[0].start - 11.seconds).absoluteValue.coerceAtMost(20.milliseconds)
        (spans[0].end - 12.6.seconds).absoluteValue shouldBe (spans[0].end - 12.6.seconds).absoluteValue.coerceAtMost(20.milliseconds)
    }

    test("sous-titre d'un coéquipier : dans sa couleur, relevé s'il parle en même temps que le joueur") {
        val settings = CaptionSettings()
        val player = Caption(TimeRange(1.seconds, 3.seconds), "on push A")
        val friend = Caption(TimeRange(2.seconds, 4.seconds), "je suis là", speaker = Speaker.FRIEND)
        val later = Caption(TimeRange(5.seconds, 6.seconds), "gg", speaker = Speaker.FRIEND)
        val stacked = Captions.stack(listOf(player), listOf(friend, later))
        stacked.map { it.text } shouldBe listOf("on push A", "je suis là", "gg")
        stacked.map { it.raised } shouldBe listOf(false, true, false)

        val drawnFriend = Captions.drawText(stacked[1], settings, 1920, 1080, settings.y).joinToString()
        drawnFriend shouldContain settings.friendColor
        val drawnPlayer = Captions.drawText(player, settings, 1920, 1080, settings.y).joinToString()
        drawnPlayer shouldNotContain settings.friendColor
        // Relevé : dessiné plus haut que la même phrase non relevée.
        val at = Regex(""":y=h\*([0-9.]+)""")
        val y = at.find(drawnFriend)!!.groupValues[1].toDouble()
        val yFlat = at.find(Captions.drawText(stacked[1].copy(raised = false), settings, 1920, 1080, settings.y).joinToString())!!.groupValues[1].toDouble()
        (y < yFlat) shouldBe true
    }
})
