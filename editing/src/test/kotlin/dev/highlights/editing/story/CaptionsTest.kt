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

    test("temps d'affichage borné : un mot étiré sur 7 s par whisper ne reste que 3 s") {
        // Clip VALORANT de la QA : « 1 ENNEMI » affiché 7 s.
        Captions.readable(listOf(Caption(ms(10000, 17000), "1 ennemi"))) shouldContainExactly listOf(Caption(ms(10000, 13000), "1 ennemi"))
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
    test("zone sûre : sous-titre centré dans la largeur utile et remonté au-dessus de la légende de l'appli") {
        val safe = dev.highlights.core.model.SafeArea(top = 0.10, bottom = 0.22, left = 0.05, right = 0.14)
        val caption = Caption(ms(0, 1500), "on y va")
        val plain = Captions.drawText(caption, CaptionSettings(), 1080, 1920, 0.90).single()
        plain shouldContain "x='(w-text_w)/2'"
        plain shouldContain "y=h*0.900-lh/2"
        val safeText = Captions.drawText(caption, CaptionSettings(), 1080, 1920, 0.90, safe).single()
        safeText shouldContain "x='w*0.0500+(w*0.8100-text_w)/2'"
        // Centre ramené au-dessus de la marge du bas (0,78), moins une demi-ligne.
        val y = Regex("""y=h\*([0-9.]+)-lh/2""").find(safeText)!!.groupValues[1].toDouble()
        (y < 0.78 && y > 0.70) shouldBe true
        Captions.drawLabel("DOUBLÉ", ms(0, 1000), CaptionSettings().font, 0.08, "yellow", 1080, 1920, 0.3, 200.milliseconds, safe) shouldContain
            "x='w*0.0500+(w*0.8100-text_w)/2'"
    }

    /** Répliques de l'annonceur du profil VALORANT (story.captions.ignore). */
    val gameLines = listOf("enemy", "enemies", "spotted", "spike", "attacker", "remaining", "remains", "ultimates")

    test("son complet VALORANT : répliques du jeu écartées avec leurs morceaux, descriptions de sons aussi") {
        // Sortie réelle de whisper sur « Valorant_game » (piste unique), clip autour de 4:30.
        val raw = listOf(
            Caption(ms(257281, 258281), "- Bravo!"),
            Caption(ms(269281, 270381), "- Enemies spotted."),
            Caption(ms(270381, 270791), "- Spike down,"),
            Caption(ms(270791, 271281), "attacker spawned."),
            Caption(ms(272281, 273821), "- Enemy A. - Ultimates"),
            Caption(ms(273821, 274281), "ready."),
            Caption(ms(274281, 275691), "- Unh! - One"),
            Caption(ms(275691, 277261), "enemy remains."),
            Caption(ms(277268, 279268), "*Bruit de feu*"),
            Caption(ms(279268, 281268), "*Bruit de feu*"),
        )
        // « ready. » et « Unh! - One » ne contiennent aucune réplique, mais finissent une phrase qui en contient une.
        Captions.clean(raw, emptyList(), gameLines).map { it.text } shouldContainExactly listOf("Bravo!")
        // Sans liste de répliques (micro), seules les descriptions de sons sautent.
        Captions.clean(raw, emptyList()).size shouldBe 8
    }

    test("répliques douteuses VALORANT ajoutées au choix du joueur : « Bravo! », « Attention ! », « Vous pensiez que... »") {
        val ignore = gameLines + listOf("bravo", "attention", "vous pensiez")
        val raw = listOf(
            Caption(ms(257281, 258281), "- Bravo!"),
            Caption(ms(994450, 996450), "Attention !"),
            Caption(ms(1524434, 1524434), "- Vous pensiez"),
            Caption(ms(1524434, 1525654), "que..."),
            Caption(ms(1530000, 1531000), "On y va."),
        )
        // « que... » n'est pas une réplique, mais finit la phrase de « Vous pensiez ».
        Captions.clean(raw, emptyList(), ignore).map { it.text } shouldContainExactly listOf("On y va.")
    }

    test("texte sans lettre écarté : points de suspension, nombre seul") {
        Captions.clean(listOf(Caption(ms(0, 2000), "..."), Caption(ms(3000, 4000), "501"), Caption(ms(5000, 6000), "63 la tête")), emptyList())
            .map { it.text } shouldContainExactly listOf("63 la tête")
    }

    test("même texte redit en moins de 10 s : boucle de whisper, gardé une fois") {
        val captions = listOf(
            Caption(ms(0, 1000), "Enemies spotted."), Caption(ms(2000, 3000), "enemies spotted"), Caption(ms(15000, 16000), "Enemies spotted."),
        )
        Captions.withoutRepeats(captions).map { it.range.start } shouldContainExactly listOf(0.milliseconds, 15000.milliseconds)
    }

    test("son complet : un texte redit d'un moment à l'autre est une réplique du jeu") {
        val captions = listOf(Caption(ms(0, 1000), "C'est parti !"), Caption(ms(5000, 6000), "Bravo"), Caption(ms(90000, 91000), "c'est parti"))
        Captions.withoutRecurring(captions).map { it.text } shouldContainExactly listOf("Bravo")
    }

    test("segment étiré sur 14 s : ramené à la parole détectée au lieu d'être jeté") {
        // Micro WARDOGS : « Essaye de ping » de 52:15,465 à 52:29,465, parole détectée de 52:27,634 à 52:31,628.
        val speech = listOf(ms(3147634, 3151628))
        val caption = Captions.clean(listOf(Caption(ms(3135465, 3149465), "Essaye de ping")), speech).single()
        caption.range shouldBe ms(3147334, 3149465)
        // Sans parole détectée, rien ne dit où est la voix : jeté.
        Captions.clean(listOf(Caption(ms(3135465, 3149465), "Essaye de ping")), emptyList()).shouldBeEmpty()
    }

    test("pas affiché après la fin de la parole") {
        // Micro WARDOGS : « moi jean » donné par whisper jusqu'à 52:38,387, parole finie à 52:36,426.
        val speech = listOf(ms(3154547, 3156426))
        Captions.readable(listOf(Caption(ms(3155807, 3158387), "moi jean")), speech).single().range shouldBe ms(3155807, 3156676)
        // Seul, un mot dit plus vite qu'il ne se lit s'efface quand même avec la voix (choix du joueur).
        Captions.readable(listOf(Caption(ms(1000, 1100), "bâtiment.")), listOf(ms(400, 1200))).single().range shouldBe ms(1000, 1450)
    }

    test("morceau trop court pour être lu : fusionné avec le précédent, jusqu'à la fin de la voix") {
        Captions.readable(listOf(Caption(ms(1000, 1100), "bâtiment."), Caption(ms(500, 900), "du")), listOf(ms(400, 1200))) shouldContainExactly
            listOf(Caption(ms(500, 1450), "du bâtiment."))
        // Séparés par une pause : pas la même phrase, pas fusionnés.
        Captions.readable(listOf(Caption(ms(500, 900), "du"), Caption(ms(2000, 2100), "bâtiment.")), listOf(ms(400, 1000), ms(2000, 2200)))
            .map { it.text } shouldContainExactly listOf("du", "bâtiment.")
        // Trop long ensemble pour une ligne : pas fusionnés, le second reste court.
        val long = Captions.readable(
            listOf(Caption(ms(0, 1500), "En vrai, recule, recule."), Caption(ms(1500, 1600), "Il y a trop de gens.")),
            listOf(ms(0, 1700)),
        )
        long.map { it.text } shouldContainExactly listOf("En vrai, recule, recule.", "Il y a trop de gens.")
        long.last().range.end shouldBe 1950.milliseconds
    }

    test("phrase rapide au micro : morceaux réunis deux par deux, aucun perdu, rien après la voix") {
        // Sortie réelle de whisper, micro WARDOGS à partir de 51:00 : quatre morceaux de 0,1 à 1 s, voix de 51:05,057 à 51:07,200.
        val raw = listOf(
            Caption(ms(4988, 5078), "En vrai, recule,"),
            Caption(ms(5078, 6068), "recule, recule."),
            Caption(ms(6328, 6578), "Il y a trop de"),
            Caption(ms(6578, 7208), "gens, là."),
        )
        // Jamais réunis par-dessus la fin d'une phrase (« recule. » | « Il y a »).
        Captions.readable(raw, listOf(ms(5057, 7200))) shouldContainExactly listOf(
            Caption(ms(4988, 6153), "En vrai, recule, recule, recule."),
            Caption(ms(6328, 7383), "Il y a trop de gens, là."),
        )
        // Deux segments au même instant : le premier, de durée nulle, est réuni au second.
        Captions.readable(listOf(Caption(ms(1000, 1000), "Vous pensiez"), Caption(ms(1000, 2200), "que...")), listOf(ms(900, 2000))) shouldContainExactly
            listOf(Caption(ms(1000, 2200), "Vous pensiez que..."))
    }
})
