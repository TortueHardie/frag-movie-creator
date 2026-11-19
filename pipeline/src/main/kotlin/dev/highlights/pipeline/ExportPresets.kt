package dev.highlights.pipeline

import dev.highlights.core.InputException
import dev.highlights.core.i18n.Message
import dev.highlights.core.model.ExportPreset
import dev.highlights.core.model.ExportPresets
import dev.highlights.core.model.MontageStyles
import dev.highlights.core.model.PlatformProfile
import dev.highlights.core.model.SelectionTarget
import dev.highlights.montage.ThumbnailStyle

/**
 * Préréglages d'export ([ExportPreset]) posés sur les options des traitements. Même règle partout : ce que l'utilisateur
 * a déjà choisi (option de la ligne de commande, champ de l'application) passe devant ; le préréglage ne remplit que ce
 * qui est resté à null. Un format choisi à la main écarte aussi la plateforme du préréglage, qui imposerait le sien.
 */
object ExportPresetRules {
    /**
     * Préréglage [id] de [all], vérifié contre les plateformes et les styles de montage connus : une faute de frappe
     * dans le YAML se voit au choix du préréglage, pas au milieu d'un rendu.
     */
    fun resolve(id: String, all: ExportPresets, platforms: Map<String, PlatformProfile>, styles: MontageStyles): ExportPreset {
        val preset = all.byId(id)
            ?: throw InputException(Message("error.exportPreset.unknown", "id" to id, "known" to all.presets.joinToString { it.id }))
        preset.platform?.let { p ->
            if (p !in platforms) throw InputException(Message("error.exportPreset.platform", "id" to id, "platform" to p, "known" to platforms.keys.joinToString()))
        }
        preset.montageStyle?.let { s ->
            if (styles.byId(s) == null) throw InputException(Message("error.exportPreset.style", "id" to id, "style" to s, "known" to styles.styles.joinToString { it.id }))
        }
        return preset
    }
}

/** Highlights : plateforme ou format, style simple/story et miniature du préréglage, là où rien n'est choisi. */
fun ExportOptions.withPreset(preset: ExportPreset): ExportOptions {
    val handFormats = formats != null
    return copy(
        platform = platform ?: preset.platform.takeUnless { handFormats },
        formats = formats ?: preset.format?.takeIf { platform == null }?.let(::listOf),
        style = style ?: preset.highlightsStyle,
        thumbnail = thumbnail ?: ThumbnailStyle.DEFAULT.takeIf { preset.thumbnail == true },
    )
}

/** Analyse (commande `process`) : la durée du préréglage devient la durée cible des moments, sauf cible déjà donnée. */
fun AnalyzeOptions.withPreset(preset: ExportPreset): AnalyzeOptions =
    copy(target = target ?: preset.length?.let { SelectionTarget(totalDuration = it) })

/**
 * Montage kills : plateforme ou format, durée maximale, style de montage et miniature du préréglage, là où rien n'est
 * choisi. Le style du préréglage passe devant le style par défaut de `montage-styles.yaml` : c'est à l'appelant de ne
 * poser ce dernier qu'après.
 */
fun MontageOptions.withPreset(preset: ExportPreset): MontageOptions {
    val handFormats = formats != null
    return copy(
        platform = platform ?: preset.platform.takeUnless { handFormats },
        formats = formats ?: preset.format?.takeIf { platform == null }?.let(::listOf),
        maxDuration = maxDuration ?: preset.length,
        style = style ?: preset.montageStyle,
        thumbnail = thumbnail ?: ThumbnailStyle.DEFAULT.takeIf { preset.thumbnail == true },
    )
}
