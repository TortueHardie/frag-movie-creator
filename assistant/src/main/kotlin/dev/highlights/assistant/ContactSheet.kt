package dev.highlights.assistant

import dev.highlights.core.serialization.Durations
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.math.max
import kotlin.math.min

/**
 * Planche de vérification d'une méthode : une vignette par détection (le repère et ce qui l'entoure, 0,6 s après la
 * détection, agrandi), numérotée et datée. L'utilisateur y dit lesquelles sont de vrais kills.
 */
object ContactSheet {
    private const val COLUMNS = 4
    private const val CELL_WIDTH = 420
    private const val LABEL = 26

    suspend fun write(frames: FrameGrabber, hits: List<Hit>, cue: PixelBox, w: Int, h: Int, out: Path): Path {
        // Contexte : le cadre du repère agrandi à au moins 3 fois sa taille et un quart de l'image en largeur.
        val cw = min(w, max(cue.width * 3, w / 4))
        val ch = min(h, max(cue.height * 3, cw * 9 / 16))
        val cx = (cue.x + cue.width / 2 - cw / 2).coerceIn(0, w - cw)
        val cy = (cue.y + cue.height / 2 - ch / 2).coerceIn(0, h - ch)
        val crop = PixelBox(cx, cy, cw, ch)
        val cellHeight = CELL_WIDTH * ch / cw
        val rows = (hits.size + COLUMNS - 1) / COLUMNS
        val sheet = BufferedImage(COLUMNS * CELL_WIDTH, rows * (cellHeight + LABEL), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(28, 28, 28)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        hits.forEachIndexed { i, hit ->
            val x = (i % COLUMNS) * CELL_WIDTH
            val y = (i / COLUMNS) * (cellHeight + LABEL)
            val frame = frames.at(hit.at + NewGameAssistant.CUE_DELAY)
            g.drawImage(frame.toImage(crop), x, y + LABEL, CELL_WIDTH - 4, cellHeight, null)
            // Le cadre du repère, pour le retrouver dans la vignette.
            val sx = (CELL_WIDTH - 4).toDouble() / cw
            val sy = cellHeight.toDouble() / ch
            g.color = Color(255, 210, 0)
            g.stroke = BasicStroke(2f)
            g.drawRect(x + ((cue.x - cx) * sx).toInt(), y + LABEL + ((cue.y - cy) * sy).toInt(), (cue.width * sx).toInt(), (cue.height * sy).toInt())
            g.color = Color.WHITE
            g.drawString("${i + 1}   ${Durations.format(hit.at)}", x + 6, y + 19)
        }
        g.dispose()
        out.parent?.createDirectories()
        ImageIO.write(sheet, "png", out.toFile())
        return out
    }
}
