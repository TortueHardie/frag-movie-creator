package dev.highlights.montage

import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.WhipPanEffect

/**
 * Les défauts d'avant le 18/11 : sans rythme rapide par partie, sans ouverture vitrine, couleurs naturelles, souffle sur
 * les whip pans. Pour les tests qui vérifient une mécanique précise (grille, ralenti, rampes, filtres) sur une musique
 * de test dont la drop est rapide et intense : les nouveaux défauts y changeraient le plan sans rapport avec ce qui est
 * vérifié. Ceux-ci ont leurs propres tests (`FastSectionsTest`, `MontageDefaultsTest`).
 */
internal fun MontageSettings.earlierDefaults(): MontageSettings = copy(
    fastParts = fastParts.copy(enabled = false),
    showcase = showcase.copy(enabled = false),
    colors = colors.copy(enabled = false),
    whip = whip.copy(whoosh = WhipPanEffect.DEFAULT_WHOOSH),
)
