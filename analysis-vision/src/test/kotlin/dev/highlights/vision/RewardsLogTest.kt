package dev.highlights.vision

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.seconds

/** Lectures tirées d'une vraie partie de Wardogs (erreurs de l'OCR comprises). */
class RewardsLogTest : FunSpec({
    val rules = listOf(
        LogRule(null, listOf("REPEREE", "SOIGNE", "JOUEURREANIME", "VEHICULEDESECTION", "PRESENCE")),
        LogRule("assist", listOf("AIDEELIMINATION", "AIDEVEHICULEDETRUIT")),
        LogRule("kill", listOf("ELIMINATION", "SHUTDOWNKILL", "TIRALATETE")),
        LogRule("revive", listOf("COEQUIPIERREANIME", "EQUIPIERREANIM")),
    )

    test("libellés reconnus malgré les erreurs de lecture, règles dans l'ordre") {
        RewardsLog.classify("ÉLIMINATION +\$1500", rules) shouldBe "kill"
        RewardsLog.classify("ELIMINATION D'UN TIYA*'AfiÉTE", rules) shouldBe "kill"
        RewardsLog.classify("SHUTDOWN KILL oon", rules) shouldBe "kill"
        RewardsLog.classify("AIDE : ÉLIMINATION •\$812", rules) shouldBe "assist"
        RewardsLog.classify("COÉOUIPIER RÉANIME +900", rules) shouldBe "revive"
        RewardsLog.classify("COEOUIP'ER RÉANIMÉ 25pxp +", rules) shouldBe "revive"
        // Ignorées : pénalité, bonus d'XP, aide à la réanimation d'un autre.
        RewardsLog.classify("AIDE : VÉHICULE DE SECTION DÉTRUIT -\$6", rules) shouldBe ""
        RewardsLog.classify("CIBLE REPÉRÉE DÉTRUITE 75XP", rules) shouldBe ""
        RewardsLog.classify("AIDE : JOUEUR RÉANIMÉ exp", rules) shouldBe ""
        // Inconnues : fragments, autres lignes.
        RewardsLog.classify("TIP - NOOB.EXE +\$10", rules) shouldBe null
        RewardsLog.classify("INATI", rules) shouldBe null
    }

    test("libellé et montant lus séparément : une seule ligne") {
        val rows = RewardsLog.rows(
            listOf(OcrLine("+\$160", 700, 66, 60, 20), OcrLine("PRÉSENCE DANS LA CONTROL ZONE", 300, 64, 380, 20), OcrLine("ÉLIMINATION 250XP", 500, 32, 200, 20)),
            tolerance = 10,
        )
        rows shouldBe listOf(32 to "ÉLIMINATION 250XP", 64 to "PRÉSENCE DANS LA CONTROL ZONE +\$160")
    }

    test("suivi : deux lectures pour compter, une ligne qui remonte ou manque n'est comptée qu'une fois") {
        val tracker = LogTracker(hold = 6.seconds, rowPitch = 32, minSightings = 2)
        fun row(y: Int, kind: String) = Triple(y, kind, kind)

        tracker.update(10.seconds, listOf(row(32, "kill"))).shouldBeEmpty()
        // Deuxième lecture : comptée, datée de la première.
        tracker.update(11.seconds, listOf(row(33, "kill"))).map { it.at } shouldBe listOf(10.seconds)
        // Deux images illisibles, puis la ligne remonte d'un cran sous un nouveau kill.
        tracker.update(14.seconds, listOf(row(0, "kill"), row(32, "kill"))).shouldBeEmpty()
        tracker.update(15.seconds, listOf(row(0, "kill"), row(32, "kill"))).map { it.at } shouldBe listOf(14.seconds)
        // Une lecture isolée (confusion de l'OCR) n'est jamais comptée.
        tracker.update(16.seconds, listOf(row(0, "kill"), row(32, "kill"), row(64, "revive"))).shouldBeEmpty()
        tracker.update(17.seconds, listOf(row(0, "kill"), row(32, "kill"))).shouldBeEmpty()
        // Oubliée après le délai : la même position redevient une nouvelle ligne.
        tracker.update(30.seconds, listOf(row(0, "kill"))).shouldBeEmpty()
        tracker.update(31.seconds, listOf(row(0, "kill"))).map { it.at } shouldBe listOf(30.seconds)
    }

    test("notifications empilées (Apex) : la ligne poussée d'un cran vers le bas n'est pas recomptée") {
        fun row(y: Int, kind: String) = Triple(y, kind, kind)
        // Relevé de « Apex 53 kills octane » à 1:30-1:32 : « ELIMINATED » à y 99, puis « KNOCKED DOWN » s'insère
        // au-dessus et le pousse à y 239 (pas de 130).
        val reads = listOf(
            90.5.seconds to listOf(row(99, "kill")),
            91.seconds to listOf(row(102, "kill")),
            92.seconds to listOf(row(95, "knock"), row(239, "kill")),
            92.5.seconds to listOf(row(101, "knock"), row(240, "kill")),
        )
        fun events(tracker: LogTracker) = reads.flatMap { (at, rows) -> tracker.update(at, rows) }.map { it.kind to it.at }

        events(LogTracker(hold = 1.seconds, rowPitch = 130, minSightings = 1, maxDrop = 1)) shouldBe
            listOf("kill" to 90.5.seconds, "knock" to 92.seconds)
        // Sans maxDrop (journal qui ne fait que remonter), l'élimination poussée comptait deux fois.
        events(LogTracker(hold = 1.seconds, rowPitch = 130, minSightings = 1)).count { it.first == "kill" } shouldBe 2
    }

    test("fusion : l'icône date, le journal donne le type, chacun comble les trous de l'autre") {
        val log = listOf(
            LogEvent(100.seconds, "kill", ""), // lu avec l'icône
            LogEvent(106.seconds, "kill", ""), // kill enchaîné : l'icône ne s'est pas réaffichée
            LogEvent(201.seconds, "assist", ""), // « Aide : Élimination » : même tête de mort qu'un kill
            LogEvent(400.seconds, "revive", ""), // réanimation sans icône reconnue
        )
        val icons = listOf(
            TimedKind(98.seconds, "kill"),
            TimedKind(200.seconds, "kill"),
            TimedKind(300.seconds, "kill"), // texte illisible sur ciel clair
        )
        EventFusion.fuse(log, icons, before = 3.seconds, after = 8.seconds) shouldBe listOf(
            TimedKind(98.seconds, "kill"),
            TimedKind(106.seconds, "kill"),
            TimedKind(201.seconds, "assist"),
            TimedKind(300.seconds, "kill"),
            TimedKind(400.seconds, "revive"),
        )
    }

    test("joueur à terre : « EN ATTENTE DE RÉANIMATION » sort en downed, avant les lignes ignorées") {
        val withDowned = listOf(LogRule("downed", listOf("ATTENTEDEREANIMATION"))) + rules
        // Lectures réelles (22-55-31), montant compris.
        RewardsLog.classify("EN ATTENTE DE RÉANIMATION *Sloo", withDowned) shouldBe "downed"
        RewardsLog.classify("EN ATTENTE DEREANIMATION *Sloo", withDowned) shouldBe "downed"
        RewardsLog.classify("EN ATTENTE OEREANIMATION", withDowned) shouldBe "downed"
        RewardsLog.classify("COÉQUIPIER RÉANIMÉ 250XP +", withDowned) shouldBe "revive"
    }

    test("libellé de la notification : lectures réelles classées par les règles du profil wardogs") {
        val notice = listOf(
            LogRule(null, listOf("PASSAGER", "SAGERA", "SURVECU", "FOURNITURESLIVREES")),
            LogRule("assist", listOf("AIDEELIMINATION", "ALELIMINATIONREPEREE")),
            LogRule("kill", listOf("ELIMINATIONCONFIRMEE", "SHUTDOWNKILL", "TIRALATETE")),
            LogRule("revive", listOf("REANIMATIONDEJOUEUR")),
        )
        RewardsLog.classify("AIDE : ÉLIMINATION", notice) shouldBe "assist"
        RewardsLog.classify("+\$800 À L'ÉLIMINATION REPÉRÉE", notice) shouldBe "assist"
        RewardsLog.classify("ÉLIMINATION CONFIRMÉE +\$1750", notice) shouldBe "kill"
        RewardsLog.classify("ÉLIMINATION COYFIRMÉE +\$2500", notice) shouldBe "kill"
        RewardsLog.classify("IANIMATION DE JOUEUR", notice) shouldBe "revive"
        RewardsLog.classify("iljNbA SAGERRÉRVÉCU", notice) shouldBe ""
        RewardsLog.classify("ASSAGÉRÉ", notice) shouldBe ""
        RewardsLog.classify("FOURNITURES LIVRÉES +\$2500", notice) shouldBe ""
        // « ÉLIMINATION » seul (fin d'« AIDE : ÉLIMINATION » mal lue) ne tranche pas : l'icône garde son type.
        RewardsLog.classify("ÉLIMINATION", notice) shouldBe null
        RewardsLog.classify("+\$1500", notice) shouldBe null
    }

    test("libellé : corrige le type de l'icône, l'écarte, ou la laisse quand il est illisible") {
        val icons = listOf(
            TimedKind(100.seconds, "kill"), // « AIDE : ÉLIMINATION » sous la tête de mort
            TimedKind(200.seconds, "assist"), // « UN PASSAGER A SURVÉCU » sous la poignée de main
            TimedKind(300.seconds, "kill"), // libellé illisible sur fond clair
            TimedKind(400.seconds, "kill"), // vrai kill, libellé lu 2 s plus tard
        )
        val reads = listOf(
            NoticeRead(101.seconds, "assist", "AIDE : ÉLIMINATION"),
            NoticeRead(200.seconds, "", "UN PASSAGER A SURVÉCU"),
            NoticeRead(306.seconds, "revive", "RÉANIMATION DE JOUEUR"), // trop tard : une autre notification
            NoticeRead(402.seconds, "kill", "ÉLIMINATION CONFIRMÉE"),
        )
        NoticeLabels.relabel(icons, reads, before = 1.seconds, after = 3.seconds) shouldBe listOf(
            TimedKind(100.seconds, "assist"),
            TimedKind(300.seconds, "kill"),
            TimedKind(400.seconds, "kill"),
        )
    }

    test("OCR Windows : texte du journal lu avec sa position").config(enabledIf = { WindowsOcr.supported }) {
        val dir = tempdir().toPath()
        val image = BufferedImage(800, 120, BufferedImage.TYPE_BYTE_GRAY)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color(60, 60, 60)
            fillRect(0, 0, 800, 120)
            color = Color.WHITE
            font = Font(Font.SANS_SERIF, Font.BOLD, 22)
            drawString("ÉLIMINATION +\$1500", 450, 40)
            drawString("COÉQUIPIER RÉANIMÉ 250XP", 380, 90)
            dispose()
        }
        val file = dir.resolve("f.png")
        ImageIO.write(image, "png", file.toFile())
        val lines = WindowsOcr(dir.resolve("ocr"), "fr-FR", parallelism = 1).use { ocr ->
            ocr.submit(file)
            ocr.results().getValue(file)
        }
        val rows = RewardsLog.rows(lines, 10)
        rows.map { RewardsLog.classify(it.second, rules) } shouldBe listOf("kill", "revive")
        rows[0].second.uppercase() shouldContain "LIMINATION"
        (rows[1].first > rows[0].first) shouldBe true
    }
})
