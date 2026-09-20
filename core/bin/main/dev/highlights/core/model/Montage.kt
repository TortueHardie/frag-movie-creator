package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Montage « tous les kills » calé sur une musique. La musique est analysée entièrement (tempo, temps, mesures, sections,
 * drop) et impose la grille de coupes : chaque clip est étendu ou coupé pour remplir exactement un nombre entier de
 * temps, le kill tombant sur un temps accentué, quel que soit le tempo.
 */
@Serializable
data class MontageSettings(
    val killEvent: String = "kill",
    /** Décalage entre la notification et le tir (la notification apparaît après le kill). */
    val killOffset: SerialDuration = (-400).milliseconds,
    /** Kills plus proches que ça : un seul clip (multi-kill). */
    val mergeGap: SerialDuration = 5.seconds,
    /** Fenêtre autour d'un groupe de kills dans laquelle on cherche score et réactions (voix, rires). */
    val preRoll: SerialDuration = 2500.milliseconds,
    val postRoll: SerialDuration = 1200.milliseconds,
    val maxDuration: SerialDuration = 60.seconds,
    /** Segments à ne pas couper (réactions après le kill) : le clip s'étend sur le slot suivant s'il est libre. */
    val keepWhole: List<String> = listOf("laughter", "shout", "speech"),
    val order: MontageOrder = MontageOrder.BUILD_UP,
    val cuts: CutSettings = CutSettings(),
    val zoom: ZoomEffect = ZoomEffect(),
    val flash: FlashEffect = FlashEffect(),
    val slowMotion: SlowMotionEffect = SlowMotionEffect(),
    val speedRamp: SpeedRampEffect = SpeedRampEffect(),
    val text: TextEffect = TextEffect(),
    val audio: MontageAudio = MontageAudio(),
    val formats: List<OutputFormat> = listOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
) {
}

/**
 * Rythme des coupes, dicté par l'intensité de chaque section de la musique. Les durées visées sont converties en temps
 * musicaux (2, 4, 8 ou 16 : demi-mesure, mesure, deux ou quatre mesures) pour que les coupes restent alignées sur les mesures.
 */
@Serializable
data class CutSettings(
    /** Durée visée d'un clip dans une section calme (intro, breakdown). */
    val low: SerialDuration = 4.seconds,
    /** Dans une section moyenne (couplet, montée). */
    val mid: SerialDuration = 2.seconds,
    /** Dans une section intense (drop, refrain). */
    val high: SerialDuration = 1200.milliseconds,
    /** Longueur maximale d'un clip, en temps (multi-kills et réactions fusionnent des slots jusqu'à cette limite). */
    val maxBeats: Int = 32,
    /** Contexte minimal avant le premier kill visible et après le dernier (temps réel, hors ralenti). */
    val minLead: SerialDuration = 700.milliseconds,
    val minTail: SerialDuration = 250.milliseconds,
    /** Position souhaitée de la drop dans le montage (0 = début, 1 = fin) : avant, la montée ; après, la fête. */
    val dropPosition: Double = 0.4,
) {
    init {
        require(maxBeats in 4..64) { "montage.cuts.maxBeats doit être entre 4 et 64" }
        require(dropPosition in 0.0..1.0) { "montage.cuts.dropPosition doit être entre 0 et 1" }
    }
}

@Serializable
enum class MontageOrder {
    /** Du moins fort au plus fort, le meilleur moment tombe sur la partie la plus intense de la musique. */
    @SerialName("build-up") BUILD_UP,
    @SerialName("chronological") CHRONOLOGICAL,
}

@Serializable
data class ZoomEffect(val enabled: Boolean = true, val amount: Double = 0.18, val decay: SerialDuration = 350.milliseconds)

@Serializable
data class FlashEffect(val enabled: Boolean = true, val duration: SerialDuration = 120.milliseconds)

@Serializable
data class SlowMotionEffect(
    val enabled: Boolean = true,
    /** 0,5 = deux fois plus lent (minimum : atempo ne descend pas en dessous). */
    val factor: Double = 0.5,
    val before: SerialDuration = 300.milliseconds,
    val after: SerialDuration = 500.milliseconds,
) {
    init {
        require(factor in 0.5..1.0) { "slowMotion.factor doit être entre 0,5 et 1" }
    }
}

/**
 * Rampe de vitesse : dans un multi-kill, la lecture entre deux kills est légèrement accélérée ou ralentie pour que
 * chaque kill tombe sur un temps (le dernier y est déjà). Au-delà de [maxChange], la vitesse reste à 1.
 */
@Serializable
data class SpeedRampEffect(val enabled: Boolean = true, val maxChange: Double = 0.15) {
    init {
        require(maxChange in 0.0..0.5) { "speedRamp.maxChange doit être entre 0 et 0,5" }
    }
}

@Serializable
data class TextEffect(
    val enabled: Boolean = true,
    val font: String = "C:/Windows/Fonts/impact.ttf",
    /** Texte affiché au 2e, 3e… kill d'un même clip. */
    val multiKillLabels: List<String> = listOf("DOUBLÉ", "TRIPLÉ", "QUADRUPLÉ", "QUINTUPLÉ"),
    /** Petit compteur « KILL n » à chaque kill. */
    val killCounter: Boolean = true,
)

@Serializable
data class MontageAudio(
    val musicVolume: Double = 1.0,
    /** Son du jeu (et voix) hors kills et réactions. */
    val gameVolume: Double = 0.3,
    /** Son du jeu autour d'un kill (tir, impact). */
    val killVolume: Double = 1.0,
    /** Voix et rires : bien audibles. */
    val voiceVolume: Double = 1.0,
    /** Volume de la musique pendant une réaction (ducking). */
    val musicUnderVoice: Double = 0.45,
    /** Le son du jeu peut déborder d'autant sur le plan suivant pour finir un kill ou une phrase (fondu). */
    val bleed: SerialDuration = 300.milliseconds,
    val loudnessLufs: Double = -14.0,
)
