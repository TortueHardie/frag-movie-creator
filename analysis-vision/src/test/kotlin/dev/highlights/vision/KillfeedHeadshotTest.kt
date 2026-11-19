package dev.highlights.vision

import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.video.ZoneFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds

class KillfeedHeadshotTest : FunSpec({

    // Cible de 12 px : un anneau et sa croix, comme l'icône du killfeed en 480p.
    fun target(size: Int): ByteArray = ByteArray(size * size) { i ->
        val x = i % size - (size - 1) / 2.0
        val y = i / size - (size - 1) / 2.0
        val d = hypot(x, y)
        (if (d in 3.5..5.5 || (d < 5.5 && (x == 0.5 || y == 0.5))) 220 else 100).toByte()
    }
    val template = PreparedTemplate(GrayImage(12, 12, target(12)), "cible")
    val icon = HeadshotIcon(template, half = 8, search = 24, minVictim = 12, tolerance = 4)

    // Zone de 200x60 : une ligne centrée en y = 20, portrait jusqu'en x = 30, victime rouge à partir de x = 150.
    val w = 200
    val h = 60
    val row = FeedRow(KillfeedRole.KILL, top = 14, bottom = 26, left = 10, right = 30)
    fun red(start: Int) = ZoneFrame(w, h, ByteArray(w * h) { i -> if (i % w >= start && i / w in 12..28) 255.toByte() else 0 })
    fun dark(withIcon: Boolean, at: Int = 134) = ZoneFrame(w, h, ByteArray(w * h) { 100 }.also { px ->
        // Arme : un trait clair plus à gauche, toujours là.
        for (x in 60..110) for (y in 18..21) px[y * w + x] = 230.toByte()
        if (withIcon) {
            val t = target(12)
            for (y in 0 until 12) for (x in 0 until 12) px[(14 + y) * w + at + x] = t[y * 12 + x]
        }
    })

    test("le fond rouge de la victime commence là où il couvre la moitié de la ligne sur assez de colonnes") {
        icon.victimStart(red(150), 12, 28, 31) shouldBe 150
        icon.victimStart(red(195), 12, 28, 31) shouldBe null
    }

    test("l'icône juste avant la victime est reconnue, l'arme seule ne l'est pas") {
        icon.score(row, dark(withIcon = true), red(150))!! shouldBeGreaterThan 0.9
        icon.score(row, dark(withIcon = false), red(150))!! shouldBeLessThan 0.5
    }

    test("sans victime rouge, pas de note") {
        icon.score(row, dark(withIcon = true), red(w)) shouldBe null
    }

    test("chaque kill prend la nouvelle ligne apparue le plus près de lui ; une ancienne ligne ne compte pas") {
        val times = (0 until 8).map { (it * 100).milliseconds }
        // Une ancienne ligne à la tête en y = 10 ; deux nouvelles : y = 30 (sans icône) à 0,2 s, y = 45 (icône) à 0,6 s.
        val frames = times.mapIndexed { i, t ->
            IconFrame(
                listOfNotNull(
                    10.0 to 0.9,
                    (30.0 to 0.3).takeIf { t >= 200.milliseconds },
                    (45.0 to (if (i == 7) 0.8 else 0.5)).takeIf { t >= 600.milliseconds },
                ),
            )
        }
        icon.pick(times, frames, 50.milliseconds) shouldBe 0.3
        icon.pick(times, frames, 450.milliseconds) shouldBe 0.8
        icon.pick(times, frames.map { IconFrame(it.rows.take(1)) }, 50.milliseconds) shouldBe null
    }

    test("profil VALORANT : l'icône est lue après les kills, avec le killfeed du profil et son gabarit") {
        val profile = ProfileRepository.loadDirectory(Path("../config/profiles")).byId("valorant")
        val cfg = profile.detectors.single { it.type == "killfeed-headshot" }
        cfg.dependsOn shouldBe listOf("game-events", "killfeed")
        val params = cfg.detectorParams().decode(KillfeedHeadshotParams.serializer()) { error("params") }
        val feed = profile.detectors.single { it.id == "killfeed" }.detectorParams().decode(KillfeedParams.serializer()) { error("params") }
        params.killfeed.region shouldBe feed.region
        Path("../config").resolve(params.template).isRegularFile() shouldBe true
        DetectorRegistry.fromServiceLoader().create(cfg.type, cfg.id, cfg.detectorParams()).id shouldBe cfg.id
    }
})
