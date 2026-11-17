package dev.highlights.export

import dev.highlights.core.model.SafeArea
import io.kotest.core.spec.style.FunSpec
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
        f shouldContain "fade=t=in:st=0:d=0.250"
        f shouldContain "fade=t=out:st=1.250:d=0.250"
    }
})
