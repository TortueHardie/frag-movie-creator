package dev.highlights.editing.story

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Largeur d'un texte dans une police TrueType, pour placer côte à côte des mots dessinés séparément (un mot en couleur
 * au milieu d'une phrase blanche : `drawtext` n'a qu'une couleur par appel). Java2D et FreeType (celui de FFmpeg)
 * mesurent à quelques pixels près : assez pour aligner des mots, pas pour du crénage fin.
 */
object TextMeasure {
    private val context = FontRenderContext(null, true, true)
    private val fonts = ConcurrentHashMap<String, Result<Font>>()

    /** Largeur en pixels de [text] à la taille [size], ou null si la police est illisible. */
    fun width(fontFile: String, size: Double, text: String): Double? {
        // L'échec est mémorisé aussi : une police absente n'est signalée qu'une fois.
        val font = fonts.computeIfAbsent(fontFile) { path ->
            runCatching { Font.createFont(Font.TRUETYPE_FONT, File(path)) }
                .onFailure { log.warn { "Police illisible pour la mise en page des sous-titres ($path) : ${it.message}" } }
        }.getOrNull() ?: return null
        return font.deriveFont(size.toFloat()).getStringBounds(text, context).width
    }
}
