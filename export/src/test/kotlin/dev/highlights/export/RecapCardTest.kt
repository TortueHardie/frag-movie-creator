package dev.highlights.export

import dev.highlights.core.model.SafeArea
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds

class RecapCardTest : FunSpec({
    test("carton : image recadrée au format, floutée, titre et sous-titre en majuscules, fondus d'entrée et de sortie") {
        val card = RecapCard("Partie 3 – 14 kills", "Wardogs · 21:09", Path("partie.mp4"), 600.milliseconds, RecapRenderer.CARD)
        val f = RecapRenderer.cardFilter(card, 1080, 1920, 60.0, "C:/Windows/Fonts/impact.ttf", SafeArea(top = 0.10, bottom = 0.22, left = 0.05, right = 0.14))
        f shouldContain "crop=1080:1920"
        f shouldContain "gblur=sigma=48"
        f shouldContain "text='PARTIE 3 – 14 KILLS'"
        f shouldContain "text='WARDOGS · 21\\:09'"
        f shouldContain "fade=t=in:st=0:d=0.150"
        f shouldContain "fade=t=out:st=0.850:d=0.150"
    }

    test("son du carton : le bruitage à son instant puis du silence, sur toute la durée du carton ; sans bruitage, silence") {
        val silent = RecapCard("Partie 1 – 5 kills", "Wardogs · 21:09", Path("partie.mp4"), 600.milliseconds, RecapRenderer.CARD)
        RecapRenderer.cardAudio(silent) shouldBe "anullsrc=r=48000:cl=stereo,atrim=duration=1.000"
        val impact = RecapRenderer.cardAudio(silent.copy(sound = RecapSound("aevalsrc=exprs='sin(t)':s=48000:d=0.6", 0.5, RecapRenderer.SOUND_AT)))
        impact shouldContain "aevalsrc=exprs='sin(t)':s=48000:d=0.6,"
        impact shouldContain "volume=0.5000"
        impact shouldContain "adelay=60|60,apad,atrim=duration=1.000"
    }
})
