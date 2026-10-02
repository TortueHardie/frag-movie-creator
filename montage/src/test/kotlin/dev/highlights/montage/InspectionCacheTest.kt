package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.KillStyle
import dev.highlights.core.model.MatchCut
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.ShotAlign
import dev.highlights.core.model.TimeRange
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.io.path.writeText
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InspectionCacheTest : FunSpec({
    fun media(dir: java.nio.file.Path): MediaInfo {
        val file = dir.resolve("partie.mp4").also { it.writeText("capture") }
        return MediaInfo(path = file, sizeBytes = 7, duration = 60.seconds, creationTime = null, container = "mp4", video = null, audio = emptyList())
    }

    fun group(media: MediaInfo, vararg kills: Int) = KillGroup(media, kills.map { it.seconds }, 1.0, emptyList(), emptyList())

    test("kill : mesure retrouvée telle quelle, d'un montage à l'autre") {
        val dir = tempdir().toPath()
        val m = media(dir)
        val traits = KillTraits(headshot = true)
        val measured = 12.seconds - 47_123.milliseconds / 1000 to traits.copy(shift = (-47_123).milliseconds / 1000, flick = 0.73, direction = FlickDirection.LEFT)
        InspectionCache(dir.resolve("cache")).apply {
            putKill(m, 12.seconds, traits, ShotAlign(), KillStyle(), AudioLayout(), measured)
            flush()
        }
        val again = InspectionCache(dir.resolve("cache"))
        again.kill(m, 12.seconds, traits, ShotAlign(), KillStyle(), AudioLayout()) shouldBe measured
        // Autre réglage, autre instant, autres traits : à mesurer.
        again.kill(m, 12.seconds, traits, ShotAlign(enabled = false), KillStyle(), AudioLayout()) shouldBe null
        again.kill(m, 13.seconds, traits, ShotAlign(), KillStyle(), AudioLayout()) shouldBe null
        again.kill(m, 12.seconds, KillTraits(), ShotAlign(), KillStyle(), AudioLayout()) shouldBe null
    }

    test("pose : référence et pixels comparés retrouvés au bit près ; capture modifiée, tout est remesuré") {
        val dir = tempdir().toPath()
        val m = media(dir)
        val random = Random(5)
        val pose = Pose(DoubleArray(48 * 48) { random.nextDouble() * 255 / 7 }, BooleanArray(48 * 48) { random.nextBoolean() })
        val aim = Aim(head = TimeRange(9.seconds, 10.seconds), tail = null, headPose = pose, tailPose = null)
        val g = group(m, 10, 11)
        InspectionCache(dir.resolve("cache")).apply {
            putAim(g, MatchCut(), aim)
            flush()
        }
        val back = InspectionCache(dir.resolve("cache")).aim(g, MatchCut())!!
        back.head shouldBe aim.head
        back.tail shouldBe null
        back.headPose!!.mean.toList() shouldBe pose.mean.toList()
        back.headPose!!.still.toList() shouldBe pose.still.toList()
        InspectionCache(dir.resolve("cache")).aim(g, MatchCut(minPoseMatch = 0.5)) shouldBe null

        m.path.writeText("capture remplacée")
        InspectionCache(dir.resolve("cache")).aim(g, MatchCut()) shouldBe null
    }

    test("repère d'arme : même corrélation qu'en recentrant chaque position") {
        val random = Random(11)
        val tw = 23
        val th = 20
        val template = WeaponTemplate(tw, th, FloatArray(tw * th) { random.nextInt(256).toFloat() })
        val icon = FloatArray(tw * th) { random.nextInt(256).toFloat() }
        val zw = 64
        val zh = 40
        val zone = FloatArray(zw * zh) { random.nextInt(256).toFloat() }
        // Une zone uniforme par endroits : énergie nulle, position ignorée.
        for (y in 0 until 20) for (x in 0 until 30) zone[y * zw + x] = 80f

        fun naive(z: FloatArray): Double {
            val mean = icon.average()
            val centered = DoubleArray(icon.size) { icon[it] - mean }
            val norm = sqrt(centered.sumOf { it * it })
            val t = DoubleArray(centered.size) { centered[it] / norm }
            var best = -1.0
            for (y0 in 0..zh - th) for (x0 in 0..zw - tw) {
                val patch = DoubleArray(tw * th) { z[(y0 + it / tw) * zw + x0 + it % tw].toDouble() }
                val m = patch.average()
                var dot = 0.0
                var sq = 0.0
                for (i in patch.indices) {
                    dot += (patch[i] - m) * t[i]
                    sq += (patch[i] - m) * (patch[i] - m)
                }
                if (sq > 1e-6) best = maxOf(best, dot / sqrt(sq))
            }
            return best
        }
        val withIcon = zone.copyOf().also { z -> for (y in 0 until th) for (x in 0 until tw) z[(13 + y) * zw + 31 + x] = icon[y * tw + x] }
        val reference = WeaponTemplate(tw, th, icon)
        reference.score(zone, zw, zh) shouldBe (naive(zone) plusOrMinus 1e-9)
        reference.score(withIcon, zw, zh) shouldBe (naive(withIcon) plusOrMinus 1e-9)
        reference.score(withIcon, zw, zh) shouldBe (1.0 plusOrMinus 1e-9)
        (template.score(zone, zw, zh) < 0.5) shouldBe true
    }

    test("lecture de plusieurs zones d'un seul décodage : les mêmes images que zone par zone").config(enabledIf = { TestMedia.available }, timeout = 2.seconds * 60) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val video = TestMedia.generate(tempdir().toPath().resolve("partie.mp4"), durationSeconds = 6, size = "1280x720")
        val zones = listOf(
            FrameZoneSpec("crop=iw*0.3000:ih*0.4000:iw*0.3500:ih*0.3000,scale=48:48:flags=area", 48, 48),
            FrameZoneSpec("crop=iw*0.0400:ih*0.0450:iw*0.6780:ih*0.9250,scale=64:40:flags=area", 64, 40),
        )
        val range = TimeRange(1_234.milliseconds, 3_500.milliseconds)
        val together = SourceFrames.read(ffmpeg, video, range, 30, zones, "deux zones")
        val alone = zones.map { SourceFrames.read(ffmpeg, video, range, 30, listOf(it), "une zone").single() }
        together.size shouldBe 2
        together.forEachIndexed { i, frames ->
            frames.size shouldBe alone[i].size
            frames.zip(alone[i]).all { (a, b) -> a.contentEquals(b) } shouldBe true
        }
        // Et comme l'ancienne lecture, filtres écrits à la main.
        val raw = mutableListOf<ByteArray>()
        ffmpeg.run(
            FfmpegCommand(
                listOf("-ss", "1.234000", "-t", "2.266", "-i", video.toString(), "-an", "-vf", "fps=30,${zones[0].filters},format=gray", "-f", "rawvideo", "pipe:1"),
                "lecture directe",
            ),
            StdoutHandler.Binary { input -> input.readAllBytes().toList().chunked(48 * 48).forEach { raw += it.toByteArray() } },
        )
        together[0].zip(raw).all { (a, b) -> a.contentEquals(b) } shouldBe true
    }
})
