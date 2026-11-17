package dev.highlights.pipeline

import dev.highlights.core.model.MontageStyle

/**
 * Les mêmes options, complétées par [style] : un réglage déjà choisi (case de la fenêtre, option de la ligne de
 * commande) garde la priorité, le style ne remplit que ce qui est resté à null. Ce que ni l'un ni l'autre ne dit reste
 * le réglage du profil.
 */
fun MontageOptions.withStyle(style: MontageStyle): MontageOptions = copy(
    fast = fast ?: style.fast,
    oneTaps = oneTaps ?: style.oneTaps,
    order = order ?: style.order,
    effectDensity = effectDensity ?: style.effectDensity,
    hook = hook ?: style.hook,
    zoom = zoom ?: style.zoom,
    flash = flash ?: style.flash,
    flashEveryCut = flashEveryCut ?: style.flashEveryCut,
    whip = whip ?: style.whip,
    whoosh = whoosh ?: style.whoosh,
    boostColors = boostColors ?: style.colors,
    motionBlur = motionBlur ?: style.motionBlur,
    matchCut = matchCut ?: style.matchCut,
    slowMotion = slowMotion ?: style.slowMotion,
    speedRamp = speedRamp ?: style.speedRamp,
    text = text ?: style.text,
    showcase = showcase ?: style.showcase,
    gameAudio = gameAudio ?: style.gameAudio,
    reactions = reactions ?: style.reactions,
)
