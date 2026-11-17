package dev.highlights.ui

import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.MontageStyle

/**
 * Les cases de la fenêtre de montage qu'un style peut poser. Gardées à part pour savoir ce qu'un style change et
 * lesquelles l'utilisateur a modifiées à la main : celles-là ne bougent plus quand il change de style.
 */
data class StyleKnobs(
    val fast: Boolean = false,
    val oneTaps: Boolean = false,
    val buildUp: Boolean = true,
    val density: EffectDensity = EffectDensity.BALANCED,
    val zoom: Boolean = true,
    val flash: Boolean = true,
    val whip: Boolean = true,
    val whoosh: Boolean = true,
    val showcase: Boolean = false,
    val boostColors: Boolean = false,
    val motionBlur: Boolean = true,
    val matchCut: Boolean = true,
    val slowMotion: Boolean = true,
    val text: Boolean = true,
    val gameAudio: GameAudio = GameAudio.FULL,
    val reactions: Boolean = false,
) {
    /** Ces réglages, avec ce que [style] change par-dessus. */
    fun styled(style: MontageStyle?): StyleKnobs = if (style == null) this else StyleKnobs(
        fast = style.fast ?: fast,
        oneTaps = style.oneTaps ?: oneTaps,
        buildUp = style.order?.let { it == MontageOrder.BUILD_UP } ?: buildUp,
        density = style.effectDensity ?: density,
        zoom = style.zoom ?: zoom,
        flash = style.flash ?: flash,
        whip = style.whip ?: whip,
        whoosh = style.whoosh ?: whoosh,
        showcase = style.showcase ?: showcase,
        boostColors = style.colors ?: boostColors,
        motionBlur = style.motionBlur ?: motionBlur,
        matchCut = style.matchCut ?: matchCut,
        slowMotion = style.slowMotion ?: slowMotion,
        text = style.text ?: text,
        gameAudio = style.gameAudio ?: gameAudio,
        reactions = style.reactions ?: reactions,
    )

    companion object {
        /** Cases telles que le profil les règle (section montage), sans style. */
        fun of(montage: MontageSettings?): StyleKnobs = if (montage == null) StyleKnobs() else StyleKnobs(
            fast = montage.fast.enabled,
            buildUp = montage.order != MontageOrder.CHRONOLOGICAL,
            density = montage.effectDensity,
            zoom = montage.zoom.enabled,
            flash = montage.flash.enabled,
            whip = montage.whip.enabled,
            whoosh = montage.whip.whoosh > 0,
            showcase = montage.showcase.enabled,
            boostColors = montage.colors.enabled,
            motionBlur = montage.motionBlur.enabled,
            matchCut = montage.matchCut.enabled,
            slowMotion = montage.slowMotion.enabled,
            text = montage.text.enabled,
            gameAudio = montage.audio.game,
            reactions = montage.reactions,
        )
    }
}

/** Les cases qu'un style peut poser, telles qu'elles sont dans la fenêtre. */
val MontageUiState.knobs: StyleKnobs
    get() = StyleKnobs(fast, oneTaps, buildUp, density, zoom, flash, whip, whoosh, showcase, boostColors, motionBlur, matchCut, slowMotion, text, gameAudio, reactions)

/** Noms des cases qui diffèrent entre [a] et [b]. */
internal fun changedKnobs(a: StyleKnobs, b: StyleKnobs): Set<String> = buildSet {
    if (a.fast != b.fast) add("fast")
    if (a.oneTaps != b.oneTaps) add("oneTaps")
    if (a.buildUp != b.buildUp) add("buildUp")
    if (a.density != b.density) add("density")
    if (a.zoom != b.zoom) add("zoom")
    if (a.flash != b.flash) add("flash")
    if (a.whip != b.whip) add("whip")
    if (a.whoosh != b.whoosh) add("whoosh")
    if (a.showcase != b.showcase) add("showcase")
    if (a.boostColors != b.boostColors) add("boostColors")
    if (a.motionBlur != b.motionBlur) add("motionBlur")
    if (a.matchCut != b.matchCut) add("matchCut")
    if (a.slowMotion != b.slowMotion) add("slowMotion")
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
        fast = keep("fast", fast, target.fast),
        oneTaps = keep("oneTaps", oneTaps, target.oneTaps),
        buildUp = keep("buildUp", buildUp, target.buildUp),
        density = keep("density", density, target.density),
        zoom = keep("zoom", zoom, target.zoom),
        flash = keep("flash", flash, target.flash),
        whip = keep("whip", whip, target.whip),
        whoosh = keep("whoosh", whoosh, target.whoosh),
        showcase = keep("showcase", showcase, target.showcase),
        boostColors = keep("boostColors", boostColors, target.boostColors),
        motionBlur = keep("motionBlur", motionBlur, target.motionBlur),
        matchCut = keep("matchCut", matchCut, target.matchCut),
        slowMotion = keep("slowMotion", slowMotion, target.slowMotion),
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
