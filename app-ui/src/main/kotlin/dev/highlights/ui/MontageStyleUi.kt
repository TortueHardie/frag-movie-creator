package dev.highlights.ui

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.MontageStyle

/**
 * Les cases de la fenêtre de montage qu'un style peut poser (sans celles que les repères de l'étape 1 remplacent :
 * montée en puissance, rythme rapide, whip pan, ouverture vitrine, ralenti ; le style les pose seul, 20/11). Gardées à part pour savoir ce qu'un style change et
 * lesquelles l'utilisateur a modifiées à la main : celles-là ne bougent plus quand il change de style.
 */
data class StyleKnobs(
    val oneTaps: Boolean = false,
    val density: EffectDensity = EffectDensity.BALANCED,
    val zoom: Boolean = true,
    val flash: Boolean = true,
    val whoosh: Boolean = false,
    val boostColors: Boolean = true,
    val motionBlur: Boolean = true,
    val matchCut: Boolean = true,
    val text: Boolean = true,
    val gameAudio: GameAudio = GameAudio.FULL,
    val reactions: Boolean = false,
) {
    /** Ces réglages, avec ce que [style] change par-dessus. */
    fun styled(style: MontageStyle?): StyleKnobs = if (style == null) this else StyleKnobs(
        oneTaps = style.oneTaps ?: oneTaps,
        density = style.effectDensity ?: density,
        zoom = style.zoom ?: zoom,
        flash = style.flash ?: flash,
        whoosh = style.whoosh ?: whoosh,
        boostColors = style.colors ?: boostColors,
        motionBlur = style.motionBlur ?: motionBlur,
        matchCut = style.matchCut ?: matchCut,
        text = style.text ?: text,
        gameAudio = style.gameAudio ?: gameAudio,
        reactions = style.reactions ?: reactions,
    )

    companion object {
        /** Cases telles que le profil les règle (section montage), sans style. */
        fun of(montage: MontageSettings?): StyleKnobs = if (montage == null) StyleKnobs() else StyleKnobs(
            density = montage.effectDensity,
            zoom = montage.zoom.enabled,
            flash = montage.flash.enabled,
            whoosh = montage.whip.whoosh > 0,
            boostColors = montage.colors.enabled,
            motionBlur = montage.motionBlur.enabled,
            matchCut = montage.matchCut.enabled,
            text = montage.text.enabled,
            gameAudio = montage.audio.game,
            reactions = montage.reactions,
        )
    }
}

/** Les cases qu'un style peut poser, telles qu'elles sont dans la fenêtre. */
val MontageUiState.knobs: StyleKnobs
    get() = StyleKnobs(oneTaps, density, zoom, flash, whoosh, boostColors, motionBlur, matchCut, text, gameAudio, reactions)

/** Noms des cases qui diffèrent entre [a] et [b]. */
internal fun changedKnobs(a: StyleKnobs, b: StyleKnobs): Set<String> = buildSet {
    if (a.oneTaps != b.oneTaps) add("oneTaps")
    if (a.density != b.density) add("density")
    if (a.zoom != b.zoom) add("zoom")
    if (a.flash != b.flash) add("flash")
    if (a.whoosh != b.whoosh) add("whoosh")
    if (a.boostColors != b.boostColors) add("boostColors")
    if (a.motionBlur != b.motionBlur) add("motionBlur")
    if (a.matchCut != b.matchCut) add("matchCut")
    if (a.text != b.text) add("text")
    if (a.gameAudio != b.gameAudio) add("gameAudio")
    if (a.reactions != b.reactions) add("reactions")
}

/** Le style choisi : celui de la liste qui porte l'identifiant [MontageUiState.style], ou aucun. */
val MontageUiState.chosenStyle: MontageStyle? get() = style?.let { id -> styles.find { it.id == id } }

/**
 * Choisit le style [id] (null : réglages du profil seuls) : chaque case prend la valeur du style, sinon celle du profil,
 * sauf celles modifiées à la main, qui gardent la leur.
 */
fun MontageUiState.chooseStyle(id: String?): MontageUiState {
    val next = copy(style = id?.takeIf { s -> styles.any { it.id == s } })
    val target = profileKnobs.styled(next.chosenStyle)
    fun <T> keep(name: String, current: T, styled: T): T = if (name in touched) current else styled
    return next.copy(
        oneTaps = keep("oneTaps", oneTaps, target.oneTaps),
        density = keep("density", density, target.density),
        zoom = keep("zoom", zoom, target.zoom),
        flash = keep("flash", flash, target.flash),
        whoosh = keep("whoosh", whoosh, target.whoosh),
        boostColors = keep("boostColors", boostColors, target.boostColors),
        motionBlur = keep("motionBlur", motionBlur, target.motionBlur),
        matchCut = keep("matchCut", matchCut, target.matchCut),
        text = keep("text", text, target.text),
        gameAudio = keep("gameAudio", gameAudio, target.gameAudio),
        reactions = keep("reactions", reactions, target.reactions),
    )
}

/** Oublie les cases modifiées à la main : toutes reprennent la valeur du style choisi. */
fun MontageUiState.resetToStyle(): MontageUiState = copy(touched = emptySet()).chooseStyle(style)

/**
 * Après une modification de la fenêtre partie de [before] : les cases changées sans changer de style sont notées comme
 * modifiées à la main (un changement de style, lui, les a posées par [chooseStyle]).
 */
fun MontageUiState.trackedFrom(before: MontageUiState): MontageUiState =
    // Style changé par une simple copie (pont de pilotage) : ses cases sont posées comme par la puce. Rejouer
    // [chooseStyle] sur ce qu'il a déjà posé ne change rien.
    if (style != before.style) chooseStyle(style)
    else if (touched != before.touched) this
    else changedKnobs(before.knobs, knobs).takeIf { it.isNotEmpty() }?.let { copy(touched = touched + it) } ?: this
