package dev.highlights.montage

import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.FailMontage
import java.util.Locale

/**
 * Textes du montage des morts (OUPS, RIP, AÏE…), un par plan. « Au hasard mais reproductible » : le texte d'un plan est
 * tiré d'une empreinte de la mort elle-même (nom de la capture et instant à la milliseconde), pas d'un générateur
 * aléatoire. Le même montage, rendu deux fois ou après un changement de musique, garde les mêmes textes sur les mêmes
 * morts. Deux plans voisins n'ont jamais le même texte.
 */
object FailLabels {
    /** Libellés : ceux du profil s'il en donne, sinon `video.fail.1`, `video.fail.2`… de la langue de l'interface. */
    fun available(settings: FailMontage, locale: Locale = I18n.locale): List<String> =
        settings.labels ?: generateSequence(1) { it + 1 }
            .map { I18n.textOrNull("video.fail.$it", emptyMap(), locale) }
            .takeWhile { it != null }.filterNotNull().toList()

    /** Texte de chaque plan, dans l'ordre du montage ; vide sans libellés. */
    fun assign(clips: List<MontageClip>, labels: List<String>): List<String?> {
        if (labels.isEmpty()) return clips.map { null }
        var previous = -1
        return clips.map { clip ->
            if (clip.showcase) return@map null
            var index = Math.floorMod(seed(clip), labels.size)
            if (index == previous && labels.size > 1) index = (index + 1) % labels.size
            previous = index
            labels[index]
        }
    }

    /** Empreinte stable d'une mort : la capture (nom de fichier) et l'instant de la mort. */
    fun seed(clip: MontageClip): Int =
        "${clip.group.media.path.fileName}@${clip.anchor.inWholeMilliseconds}".hashCode() * 31 + 17
}
