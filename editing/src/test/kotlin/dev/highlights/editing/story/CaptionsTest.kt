package dev.highlights.editing.story

import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.TimeRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CaptionsTest : FunSpec({
    fun ms(start: Long, end: Long) = TimeRange(start.milliseconds, end.milliseconds)

    /** Sortie réelle du filtre whisper sur 35 s de micro (extrait débutant à 95 s d'une partie de VALORANT). */
    val whisper = listOf(
        """{"start":0,"end":1780,"text":"Sous-titrage Société"}""",
        """{"start":1780,"end":2980,"text":"Radio-Canada"}""",
        """{"start":2983,"end":5743,"text":"Il y a de roche"}""",
        """{"start":5743,"end":5983,"text":"là."}""",
        """{"start":5970,"end":7490,"text":"et..."}""",
        """{"start":8956,"end":10736,"text":"Sous-titrage Société"}""",
        """{"start":20903,"end":23883,"text":"- Oui."}""",
        """{"start":23890,"end":24670,"text":"fort devant."}""",
        """{"start":26876,"end":28616,"text":"63 la tête au"}""",
        """{"start":29863,"end":31123,"text":"Oulà !"}""",
        """{"start":32850,"end":34290,"text":"Sous-titrage Société"}""",
        """{"start":34290,"end":34990,"text":"Radio-Canada"}""",
        "",
        "pas du json",
    )
    /** Prises de parole détectées par l'analyse sur le même extrait, en temps de la capture. */
    val speech = listOf(
        ms(100263, 100945), ms(101831, 102579), ms(118349, 119944), ms(122937, 124364), ms(125209, 126127), ms(129303, 129709),
    )

    test("lecture de la sortie JSON de whisper, instants ramenés dans la capture") {
        val parsed = Captions.parse(whisper, 95.seconds)
        parsed.size shouldBe 12
        parsed[2] shouldBe Caption(ms(97983, 100743), "Il y a de roche")
    }

    test("nettoyage : hallucinations écartées, seule la parole détectée reste, calée sur son début") {
        val clean = Captions.clean(Captions.parse(whisper, 95.seconds), speech)
        clean.map { it.text } shouldContainExactly listOf("Il y a de roche", "là.", "et...", "Oui.", "fort devant.", "63 la tête au", "Oulà !")
        // « Il y a de roche » commence 2,3 s avant la parole détectée : il est recalé 100 ms avant elle.
        clean.first().range.start shouldBe 99963.milliseconds
    }

    test("sans détection de voix, seules les hallucinations connues sautent") {
        val clean = Captions.clean(Captions.parse(whisper, 0.seconds), emptyList())
        clean.map { it.text }.none { "Radio" in it || "Sous-titrage" in it } shouldBe true
        clean.size shouldBe 7
    }

    test("segment de durée nulle : gardé s'il tombe sur de la parole, écarté sinon, sans planter") {
        // whisper en tranches de 20 s rend des segments sans durée ; l'un d'eux, dans un silence, faisait planter l'export.
        val speech = listOf(ms(215000, 216600))
        val captions = listOf(Caption(ms(215000, 215000), "Ton ami il est"), Caption(ms(230000, 230000), "Merci."))
        Captions.clean(captions, speech).map { it.text } shouldContainExactly listOf("Ton ami il est")
    }

    test("un texte étiré sur plusieurs secondes est une hallucination") {
        Captions.clean(listOf(Caption(ms(0, 27000), "Merci")), emptyList()).shouldBeEmpty()
    }

    test("temps de lecture : segments de durée nulle ou au même instant posés l'un après l'autre") {
        // Sortie réelle de whisper en tranches de 20 s : durées nulles, deux segments qui démarrent ensemble.
        val captions = listOf(
            Caption(ms(215000, 215000), "Ton ami il est"),
            Caption(ms(215000, 216000), "passé par le mid"),
            Caption(ms(225000, 225000), "Camille !"),
        )
        Captions.readable(captions) shouldContainExactly listOf(
            // 0,4 s + 14 × 45 ms = 1,03 s de lecture.
            Caption(ms(215000, 216030), "Ton ami il est"),
            // Commence quand le précédent a fini : 0,4 + 16 × 0,045 = 1,12 s.
            Caption(ms(216030, 217150), "passé par le mid"),
            Caption(ms(225000, 225805), "Camille !"),
        )
    }

    test("temps de lecture : un segment déjà assez long garde ses bornes") {
        Captions.readable(listOf(Caption(ms(1000, 4000), "ok"))) shouldContainExactly listOf(Caption(ms(1000, 4000), "ok"))
    }

    test("sous-titres d'un plan : relatifs au plan, coupés à ses bords, jamais superposés") {
        val captions = listOf(Caption(ms(9000, 10500), "avant"), Caption(ms(11000, 12000), "un"), Caption(ms(11800, 13000), "deux"), Caption(ms(19900, 21000), "fin"))
        Captions.inShot(captions, ms(10000, 20000)) shouldContainExactly listOf(
            Caption(ms(0, 500), "avant"),
            Caption(ms(900, 1700), "un"),
            Caption(ms(1700, 3000), "deux"),
        )
    }

    test("texte sûr pour FFmpeg : apostrophe typographique, caractères spéciaux retirés, majuscules") {
        Captions.sanitize("c'est: [bon]; vas-y", uppercase = true) shouldBe "C’EST BON VAS-Y"
        Captions.sanitize("à côté", uppercase = true) shouldBe "À CÔTÉ"
    }

    test("texte « pop » : taille qui grossit à l'apparition, affiché le temps du sous-titre") {
        val text = Captions.drawText(Caption(ms(1200, 2000), "oulà !"), CaptionSettings(), 1920, 1080, 0.78).single()
        text shouldContain "text='OULÀ !':expansion=none"
        text shouldContain "fontsize='81*(0.7+0.3*min(max((t-1.200)/0.120\\,0)\\,1))'"
        text shouldContain "fontcolor=white"
        text shouldContain "enable='between(t\\,1.200\\,2.000)'"
        // Centré sur la hauteur de ligne de la police : des mots dessinés à part gardent la même ligne de base.
        text shouldContain "y=h*0.780-lh/2"
        text shouldContain "fontfile='C\\:/Windows/Fonts/impact.ttf'"
    }

    test("mots forts : chacun dessiné à sa place, en couleur, les autres en blanc") {
        val settings = CaptionSettings(emphasis = listOf("gg", "let's go"))
        val parts = Captions.drawText(Caption(ms(0, 1000), "GG, let’s go les gars"), settings, 1920, 1080, 0.78)
        parts.map { it.substringAfter("text='").substringBefore("'") } shouldContainExactly listOf("GG,", "LET’S", "GO", "LES", "GARS")
        parts.map { it.substringAfter("fontcolor=").substringBefore(":") } shouldContainExactly
            listOf("0xFFD21F", "0xFFD21F", "0xFFD21F", "white", "white")
        // Placés de gauche à droite autour du centre, à l'échelle de l'apparition.
        parts.first() shouldContain "x='(w-"
        parts[1] shouldContain ")/2+"
    }

    test("comparaison des mots forts sans casse, accents ni ponctuation") {
        Captions.emphasized(listOf("TUÉ !", "par", "LUI"), listOf("tue")) shouldContainExactly listOf(true, false, false)
        Captions.emphasized(listOf("GO", "LET'S"), listOf("let's go")) shouldContainExactly listOf(false, false)
    }

    test("9:16 : une phrase trop large est réduite jusqu'à tenir dans l'image") {
        // Sous-titre réel qui débordait à gauche et à droite d'une image verticale de 1080 px.
        val settings = CaptionSettings()
        val text = Captions.drawText(Caption(ms(0, 1000), "mais, et la krautch"), settings, 1080, 1920, 0.66).single()
        val size = text.substringAfter("fontsize='").substringBefore("*").toInt()
        (size < 144) shouldBe true
        val width = TextMeasure.width(settings.font, size.toDouble(), "MAIS, ET LA KRAUTCH")
        if (width != null) (width <= 1080 * 0.9 + 1) shouldBe true
        // Une phrase courte garde sa taille.
        Captions.drawText(Caption(ms(0, 1000), "gg"), settings, 1080, 1920, 0.66).single() shouldContain "fontsize='144*"
    }

    test("phrase criée : toute en rouge, plus grosse, d'un seul bloc") {
        val text = Captions.drawText(Caption(ms(0, 1000), "vas-y gg", loud = true), CaptionSettings(), 1920, 1080, 0.78).single()
        text shouldContain "text='VAS-Y GG'"
        text shouldContain "fontcolor=0xFF4538"
        text shouldContain "fontsize='101*"
    }

    test("libellé d'événement : même apparition, puis s'efface sur sa fin") {
        val text = Captions.drawLabel("Triplé", ms(2000, 3100), "C:/Windows/Fonts/impact.ttf", 0.09, "0xFFD21F", 1920, 1080, 0.2, 120.milliseconds)
        text shouldContain "text='TRIPLÉ'"
        text shouldContain "fontsize='97*"
        text shouldContain "alpha='if(gt(t\\,2.850)\\,(3.100-t)/0.250\\,1)'"
        text shouldContain "y=h*0.200-lh/2"
    }

    test("filtre de transcription : 16 kHz, chemins échappés, JSON par groupes de quelques mots") {
        val filter = Captions.transcriptionFilter(Path("D:/modeles/m.bin"), Path("D:/tmp/out.jsonl"), CaptionSettings(language = "fr"))
        filter shouldContain "aresample=16000,whisper=model='D\\:/modeles/m.bin'"
        // Tranches de 20 s : avec les 3 s par défaut de FFmpeg, les phrases étaient coupées et mal transcrites.
        filter shouldContain ":language=fr:format=json:max_len=18:queue=20:use_gpu=1:destination='D\\:/tmp/out.jsonl'"
        filter shouldNotContain "\\\\"
    }
})
