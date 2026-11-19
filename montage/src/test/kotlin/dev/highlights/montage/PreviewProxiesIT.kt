package dev.highlights.montage

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.TimeRange
import dev.highlights.editing.SourceCut
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.setLastModifiedTime
import kotlin.time.Duration.Companion.seconds

/**
 * Proxies faits par FFmpeg sur une vidéo de synthèse 1920x1080 : mêmes horodatages d'images que la source, réduits à
 * la hauteur demandée, refaits seulement quand il le faut, limités en place.
 */
class PreviewProxiesIT : FunSpec({
    test("un proxy garde les horodatages de la source, se réutilise, et disparaît quand la capture change").config(enabledIf = { TestMedia.available }, timeout = 120.seconds) {
        val ffmpeg = TestMedia.requireFfmpeg()
        val dir = Files.createTempDirectory("proxies-it")
        val source = TestMedia.generate(dir.resolve("capture.mp4"), durationSeconds = 14, size = "1920x1080")
        val media = ffmpeg.probe(source)
        val cache = dir.resolve("cache")
        val proxies = PreviewProxies(cache)
        val cut = SourceCut(media, TimeRange(2.seconds, 4.seconds))

        val first = proxies.prepare(ffmpeg, listOf(cut), { 360 })
        first.size shouldBe 1
        val segment = first.segment(media, cut.range)!!
        val made = segment.file.getLastModifiedTime()
        ffmpeg.probe(segment.file).video!!.height shouldBe 360

        // Horodatages : ceux de la source, image pour image, sur tout l'extrait.
        suspend fun pts(file: java.nio.file.Path): List<String> {
            val out = mutableListOf<String>()
            ffmpeg.runProbe(
                FfmpegCommand(listOf("-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time", "-of", "csv=p=0", file.toString()), "horodatages"),
                StdoutHandler.Lines { l -> l.substringBefore(',').trim().toDoubleOrNull()?.let { if (it >= 2.0 && it < 4.0) out += "%.4f".format(it) } },
            )
            return out
        }
        pts(segment.file) shouldBe pts(source)

        // Même extrait, autre aperçu : rien n'est refait.
        val again = proxies.prepare(ffmpeg, listOf(cut), { 360 })
        again.segment(media, cut.range)!!.file shouldBe segment.file
        segment.file.getLastModifiedTime() shouldBe made

        // Au-delà de la place permise, le passage le moins récemment servi part, jamais celui de l'aperçu en cours.
        val tight = PreviewProxies(cache, maxBytes = 1)
        val late = SourceCut(media, TimeRange(11.seconds, 12.seconds))
        val other = tight.prepare(ffmpeg, listOf(late), { 360 })
        other.size shouldBe 1
        segment.file.exists() shouldBe false
        other.segment(media, late.range)!!.file.exists() shouldBe true

        // Capture modifiée : ses proxies sont effacés et refaits.
        source.setLastModifiedTime(FileTime.fromMillis(source.getLastModifiedTime().toMillis() + 60_000))
        val redone = proxies.prepare(ffmpeg, listOf(late), { 360 })
        redone.size shouldBe 1
        proxies.usage().segments shouldBe 1
    }
})
