package dev.highlights.editing

import dev.highlights.core.model.CardBackground
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.IntroOutroSettings
import dev.highlights.core.model.SafeArea
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IntroOutroCardsTest : FunSpec({
    fun logo(dir: Path, w: Int, h: Int): String {
        val file = dir.resolve("logo_${w}x$h.png")
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", file.toFile())
        return file.toString()
    }

    test("ouverture d'un montage : nombre entier de temps, un au moins") {
        IntroOutroCards.beatLength(1500.milliseconds, 500.milliseconds) shouldBe 1500.milliseconds
        // 1,5 s à 90 BPM (0,667 s) : 2,25 temps → 2.
        IntroOutroCards.beatLength(1500.milliseconds, 666_667.microseconds) shouldBe 1_333_334.microseconds
        IntroOutroCards.beatLength(500.milliseconds, 2.seconds) shouldBe 2.seconds
    }

    test("pas de carte : graphe inchangé, rien d'ajouté") {
        val args = mutableListOf<String>()
        val graph = mutableListOf<String>()
        CardRequest.of(EditSettings(), 1920, 1080).shouldBeNull()
        // Activées mais sans logo ni pseudo : rien à montrer, pas de carte.
        CardRequest.of(EditSettings(intro = IntroOutroSettings(enabled = true)), 1920, 1080).shouldBeNull()
        val appended = IntroOutroCards.append(args, graph, "v", "a", null)
        appended shouldBe IntroOutroCards.Appended("v", "a", Duration.ZERO)
        graph shouldBe emptyList()
    }

    test("ouverture et fin concaténées autour du montage, en images entières") {
        val settings = IntroOutroSettings(enabled = true, name = "ZeroX", background = CardBackground.COLOR, introLength = 1500.milliseconds, outroLength = 2.seconds)
        val request = CardRequest.of(EditSettings(fps = 60, intro = settings), 1920, 1080).shouldNotBeNull()
        val args = mutableListOf("-i", "a.mp4")
        val graph = mutableListOf<String>()
        val appended = IntroOutroCards.append(args, graph, "vmain", "amain", request)
        appended.added shouldBe 3500.milliseconds
        val text = graph.joinToString(";\n")
        text shouldContain "trim=end_frame=90"
        text shouldContain "trim=end_frame=120"
        text shouldContain "concat=n=3:v=1:a=1"
        text shouldContain "text='ZeroX'"
        // Fond uni : aucune entrée de plus.
        args.count { it == "-i" } shouldBe 1
    }

    test("seulement l'ouverture, sur l'amorce de la musique") {
        val settings = IntroOutroSettings(enabled = true, name = "ZeroX", outro = false)
        val request = CardRequest.of(EditSettings(fps = 30, intro = settings), 1080, 1920, leadIn = MusicLeadIn(Path.of("m.mp3"), 20.seconds, 0.8)).shouldNotBeNull()
        val args = mutableListOf("-i", "a.mp4", "-i", "m.mp3")
        val graph = mutableListOf<String>()
        IntroOutroCards.append(args, graph, "v", "a", request).added shouldBe 1500.milliseconds
        val text = graph.joinToString(";\n")
        text shouldContain "concat=n=2"
        // L'amorce : les 1,5 s qui précèdent le départ de la musique dans le montage, lues en entrée 2.
        args.takeLast(6) shouldBe listOf("-ss", "18.500000", "-t", "1.500000", "-i", "m.mp3")
        text shouldContain "[2:a]"
        text shouldNotContain "adelay"
    }

    test("musique qui démarre trop tôt : du silence devant son amorce") {
        val settings = IntroOutroSettings(enabled = true, name = "ZeroX", outro = false)
        val request = CardRequest.of(EditSettings(fps = 30, intro = settings), 1920, 1080, leadIn = MusicLeadIn(Path.of("m.mp3"), 500.milliseconds, 1.0)).shouldNotBeNull()
        val graph = mutableListOf<String>()
        IntroOutroCards.append(mutableListOf(), graph, "v", "a", request)
        graph.joinToString() shouldContain "adelay=1000:all=1"
    }

    test("logo et pseudo dans la zone sûre du 9:16") {
        val dir = tempdir().toPath()
        val safe = SafeArea(top = 0.1, bottom = 0.25, left = 0.05, right = 0.15)
        val settings = IntroOutroSettings(enabled = true, logo = logo(dir, 800, 200), name = "UnPseudoAssezLongPourDeborder")
        val request = CardRequest(settings, 1080, 1920, 30, safe)
        val layout = IntroOutroCards.layout(settings, request)
        val logo = layout.logo.shouldNotBeNull()
        val name = layout.name.shouldNotBeNull()
        logo.x shouldBeGreaterThanOrEqual (0.05 * 1080).toInt()
        (logo.x + logo.width) shouldBeLessThanOrEqual (0.85 * 1080).toInt()
        // Proportions du logo gardées (4:1).
        (logo.width / logo.height) shouldBe 4
        logo.y shouldBeGreaterThanOrEqual (0.1 * 1920).toInt()
        name.centerY shouldBeLessThanOrEqual (0.75 * 1920).toInt()
        name.left shouldBe 54
        name.room shouldBe 864
    }

    test("réglages invalides refusés") {
        shouldThrow<IllegalArgumentException> { IntroOutroSettings(introLength = 10.seconds) }
        shouldThrow<IllegalArgumentException> { IntroOutroSettings(color = "rouge") }
    }
})
