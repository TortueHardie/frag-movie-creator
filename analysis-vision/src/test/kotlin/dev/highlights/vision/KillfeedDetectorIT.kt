package dev.highlights.vision

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.testing.TestMedia
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Vidéo synthétique 1280x720 : un killfeed en haut à droite, lignes vertes, cadre jaune du joueur. Kill à 10 s ;
 * second kill à 12 s sous le premier, qui monte à sa place quand le premier disparaît (15 s) ; ligne d'un autre
 * joueur (sans jaune) à 20 s ; mort du joueur à 30 s (cadre jaune au bord droit).
 */
class KillfeedDetectorIT : FunSpec({
    // Images clés toutes les secondes : lecture en deux temps (images clés puis passages repérés) ; à null, toute la
    // vidéo est décodée. Même résultat attendu.
    listOf("2s", "null").forEach { scan ->
        test("kills et morts lus dans le killfeed par la couleur du joueur (keyframeScan: $scan)").config(enabledIf = { TestMedia.available }, timeout = 3.seconds * 60) {
            val dir = tempdir().toPath()
            val ffmpeg = TestMedia.requireFfmpeg()
            val video = dir.resolve("killfeed.mp4")

            /**
             * Ligne verte de 44 px jusqu'au bord droit, avec le cadre jaune du portrait (80x44, contour de 3 px, vide au
             * centre comme derrière un visage) en x.
             */
            fun row(yellowX: Int?, y: Int, enable: String) = buildList {
                add("drawbox=x=880:y=$y:w=390:h=44:color=0x5AC8AA:t=fill:enable='$enable'")
                if (yellowX != null) add("drawbox=x=$yellowX:y=$y:w=80:h=44:color=0xE6E65A:t=3:enable='$enable'")
            }
            val draw = (
                row(900, 60, "between(t,10,15)") +
                    row(940, 112, "between(t,12,15)") + row(940, 60, "between(t,15,17)") +
                    row(null, 60, "between(t,20,25)") +
                    row(1180, 60, "between(t,30,35)")
                ).joinToString(",")
            ffmpeg.run(
                FfmpegCommand(
                    listOf(
                        "-y", "-f", "lavfi", "-i", "color=c=0x303030:s=1280x720:r=30:d=40", "-vf", draw,
                        "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p", video.toString(),
                    ),
                    "vidéo killfeed de test",
                ),
            )

            val media = ffmpeg.probe(video)
            val grid = WindowGrid(1.seconds, 500.milliseconds, media.duration)
            val track = DetectorRegistry.fromServiceLoader().create(
                "killfeed", "killfeed",
                paramsOf(
                    """
                    region: { x: 0.6, y: 0.05, width: 0.4, height: 0.3 }
                    referenceHeight: 720
                    hwaccel: null
                    keyframeScan: $scan
                    """,
                ),
            ).analyze(AnalysisContext(media, grid, ffmpeg, dir, ProgressReporter.NONE, configDir = dir))

            track.events.map { it.kind } shouldBe listOf("kill", "kill", "death")
            track.events.map { it.at.inWholeMilliseconds / 1000.0 }.zip(listOf(10.0, 12.0, 30.0)).forEach { (at, expected) ->
                at shouldBe (expected plusOrMinus 0.3)
            }
        }
    }

    /**
     * Vidéo 854x480 comme les parties téléchargées de YouTube : lignes de 16 px, contour du cadre d'un pixel délavé
     * (185/165/128, mesuré), seul l'aplat jaune pur du portrait (8x10) reste net. Kill à 10 s, mort à 30 s (aplat
     * fini 6 px avant le bord), bordure dorée d'une capacité collée au bord droit pendant 1,5 s à 20 s.
     */
    listOf(true, false).forEach { lowDefinition ->
        test("basse définition : l'aplat du portrait est lu, le contour délavé ne suffit pas (mode aplat : $lowDefinition)").config(enabledIf = { TestMedia.available }, timeout = 3.seconds * 60) {
            val dir = tempdir().toPath()
            val ffmpeg = TestMedia.requireFfmpeg()
            val video = dir.resolve("killfeed480.mp4")
            fun row(fillX: Int, y: Int, enable: String) = listOf(
                "drawbox=x=600:y=$y:w=246:h=16:color=0x5AC8AA:t=fill:enable='$enable'",
                "drawbox=x=${fillX - 2}:y=$y:w=30:h=16:color=0xB9A57E:t=1:enable='$enable'",
                "drawbox=x=$fillX:y=${y + 3}:w=8:h=10:color=0xE9EC80:t=fill:enable='$enable'",
            )
            val draw = (
                row(610, 50, "between(t,10,15)") + row(836, 50, "between(t,30,35)") +
                    listOf("drawbox=x=848:y=40:w=6:h=60:color=0xE6E65A:t=fill:enable='between(t,20,21.5)'")
                ).joinToString(",")
            ffmpeg.run(
                FfmpegCommand(
                    listOf(
                        "-y", "-f", "lavfi", "-i", "color=c=0x303030:s=854x480:r=30:d=40", "-vf", draw,
                        "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p", video.toString(),
                    ),
                    "vidéo killfeed 480p de test",
                ),
            )
            val media = ffmpeg.probe(video)
            val grid = WindowGrid(1.seconds, 500.milliseconds, media.duration)
            val track = DetectorRegistry.fromServiceLoader().create(
                "killfeed", "killfeed",
                paramsOf(
                    """
                    region: { x: 0.6, y: 0.05, width: 0.4, height: 0.3 }
                    referenceHeight: 1440
                    hwaccel: null
                    fillBelow: ${if (lowDefinition) 720 else 0}
                    """,
                ),
            ).analyze(AnalysisContext(media, grid, ffmpeg, dir, ProgressReporter.NONE, configDir = dir))

            if (lowDefinition) {
                track.events.map { it.kind } shouldBe listOf("kill", "death")
                track.events.map { it.at.inWholeMilliseconds / 1000.0 }.zip(listOf(10.0, 30.0)).forEach { (at, expected) ->
                    at shouldBe (expected plusOrMinus 0.3)
                }
            } else {
                // Lecture du contour (ce que faisait le détecteur avant) : rien, comme sur les vraies parties en 480p.
                track.events.map { it.kind } shouldBe emptyList()
            }
        }
    }
})
