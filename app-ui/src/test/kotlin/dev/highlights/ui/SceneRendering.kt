package dev.highlights.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Rendu hors écran d'un composable pour les tests de captures : la scène est rendue jusqu'au repos avant l'image et la
 * fermeture. Mesuré le 18/11 sur UiSnapshotTest : rendue 500 ms comme avant, une scène sur trois était fermée avec du
 * travail en cours (22 trames de défilement de la liste pour « plusieurs captures », 20 pour la relecture et le
 * montage, une barre de progression sans fin pour la bibliothèque), et le seul échec vu de ce test (« LayoutNode … not
 * found in RectList », dans `close()`) venait de l'écran fermé en plein défilement. Au repos, rien ne bouge plus quand
 * la composition est libérée, et la capture montre l'écran tel que l'utilisateur le voit (liste arrivée sur le moment
 * sélectionné), pas une image prise au milieu d'une animation.
 */
object SceneRendering {
    /** Pas d'une trame (60 img/s). */
    private val FRAME = 16.milliseconds

    /**
     * Au-delà, l'animation ne finit jamais (indicateur de progression) : on s'arrête là. Deux secondes couvrent tout
     * défilement ou fondu de l'interface (le plus long mesuré : 22 trames, 0,35 s).
     */
    val SETTLE_LIMIT: Duration = 2.seconds

    /** Image de [content] au repos (ou au bout de [SETTLE_LIMIT] pour une animation sans fin). */
    fun image(width: Int, height: Int, content: @Composable () -> Unit): Image {
        val scene = ImageComposeScene(width, height, Density(1f), content = content)
        try {
            scene.render(0)
            var time = 500.milliseconds
            var image = scene.render(time.inWholeNanoseconds)
            while (scene.hasInvalidations() && time < SETTLE_LIMIT) {
                time += FRAME
                image = scene.render(time.inWholeNanoseconds)
            }
            return image
        } finally {
            scene.close()
        }
    }
}
