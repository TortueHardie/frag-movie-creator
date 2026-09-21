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
    /**
     * Mettre en avant les réactions (voix, rires) : plan prolongé jusqu'à la fin d'une phrase ([keepWhole]), jeu et
     * micro montés pendant qu'on parle, musique baissée dessous, son qui déborde sur le plan suivant pour finir la
     * phrase. Désactivé par défaut : un frag movie montre l'écran, le micro reste au niveau du jeu.
     */
    val reactions: Boolean = false,
    val order: MontageOrder = MontageOrder.BUILD_UP,
    /**
     * Accroche : le deuxième meilleur groupe ouvre le montage (le meilleur reste pour la drop). Sans ça, les premières
     * secondes tombent sur un clip quelconque, là où se joue l'essentiel de la rétention.
     */
    val hook: Boolean = true,
    /**
     * Deux clips voisins tirés de la même capture et distants de moins que ça se ressemblent (même endroit, même
     * situation) : à importance égale, on les éloigne l'un de l'autre. Zéro désactive la règle.
     */
    val varietyGap: SerialDuration = 45.seconds,
    /**
     * Score minimal d'un groupe de kills (0..1) pour entrer au montage : au-dessus de zéro, mieux vaut un montage plus
     * court qu'un plan sans intérêt. Les trois meilleurs sont gardés quoi qu'il arrive.
     */
    val minScore: Double = 0.0,
    /** Quantité d'effets : au rythme normal, un plan reçoit un ralenti ou un zoom, jamais les deux. */
    val effectDensity: EffectDensity = EffectDensity.BALANCED,
    val cuts: CutSettings = CutSettings(),
    val zoom: ZoomEffect = ZoomEffect(),
    val flash: FlashEffect = FlashEffect(),
    val slowMotion: SlowMotionEffect = SlowMotionEffect(),
    val speedRamp: SpeedRampEffect = SpeedRampEffect(),
    val text: TextEffect = TextEffect(),
    val audio: MontageAudio = MontageAudio(),
    val formats: List<OutputFormat> = listOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
) {
    init {
        require(minScore in 0.0..1.0) { "montage.minScore doit être entre 0 et 1" }
    }
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
    /**
     * Images d'avance de chaque coupe sur son temps : l'œil met quelques images à enregistrer un nouveau plan, si bien
     * qu'une coupe pile sur le temps paraît en retard. Le kill, lui, ne bouge pas : seule la coupe avance.
     */
    val preBeatFrames: Int = 1,
    /** Dans une montée de la musique, les plans raccourcissent au fur et à mesure : les coupes accélèrent avec elle. */
    val accelerateBuildUp: Boolean = true,
) {
    init {
        require(maxBeats in 4..64) { "montage.cuts.maxBeats doit être entre 4 et 64" }
        require(dropPosition in 0.0..1.0) { "montage.cuts.dropPosition doit être entre 0 et 1" }
        require(preBeatFrames in 0..4) { "montage.cuts.preBeatFrames doit être entre 0 et 4" }
    }
}

/** Ce que devient le son du jeu pendant un ralenti. */
@Serializable
enum class SlowAudio {
    /** Étiré comme l'image : sur un tir ou un impact, le timbre se délite (`atempo`). */
    @SerialName("stretch") STRETCH,
    /** Joué à sa vitesse, puis effacé en fondu : le tir sonne juste et la musique porte la fin du ralenti. */
    @SerialName("natural") NATURAL,
    /** Silence : seule la musique reste. */
    @SerialName("mute") MUTE,
}

/**
 * Quantité d'effets appliqués. Empiler ralenti, zoom, flash et texte sur le même plan surcharge l'image et rend
 * l'action difficile à suivre : au rythme normal, chaque plan ne reçoit qu'une seule emphase.
 */
@Serializable
enum class EffectDensity {
    /** Ralenti sur la drop seulement, aucun zoom, flash aux frontières de section. */
    @SerialName("sober") SOBER,
    /** Ralenti sur les plans forts (drop, multi-kill, accroche), zoom sur les autres, jamais les deux. */
    @SerialName("balanced") BALANCED,
    /** Tous les effets sur tous les plans. */
    @SerialName("heavy") HEAVY,
}

@Serializable
enum class MontageOrder {
    /** Du moins fort au plus fort, le meilleur moment tombe sur la partie la plus intense de la musique. */
    @SerialName("build-up") BUILD_UP,
    @SerialName("chronological") CHRONOLOGICAL,
}

/**
 * Zoom « punch » : l'image bondit sur le kill puis revient. [onEveryKill] décide si les kills intermédiaires d'un
 * multi-kill en reçoivent un aussi, ou si seul celui qui tombe sur le temps y a droit (les libellés DOUBLÉ / TRIPLÉ
 * marquent déjà les autres).
 */
@Serializable
data class ZoomEffect(
    val enabled: Boolean = true,
    val amount: Double = 0.18,
    val decay: SerialDuration = 350.milliseconds,
    val onEveryKill: Boolean = true,
    /** Rééchantillonnage du zoom, appliqué à chaque image : bicubic tient le détail, bilinear coûte moins cher. */
    val scaleFlags: String = "bicubic",
)

/**
 * Flash blanc à la coupe. Par défaut seulement aux coupes fortes (changement de section, drop, multi-kill) : à chaque
 * coupe, l'effet se retourne contre le montage et rend l'action plus difficile à suivre.
 */
@Serializable
data class FlashEffect(
    val enabled: Boolean = true,
    val duration: SerialDuration = 60.milliseconds,
    val onEveryCut: Boolean = false,
)

/**
 * Ralenti sur le kill d'ancrage : la vitesse descend par paliers avant le kill ([rampSteps]) au lieu de changer d'un
 * coup, et le plein régime est retrouvé exactement sur un temps ([snapToBeat]) — la relance tombe alors avec la musique.
 */
@Serializable
data class SlowMotionEffect(
    val enabled: Boolean = true,
    /** 0,5 = deux fois plus lent (minimum : atempo ne descend pas en dessous). */
    val factor: Double = 0.5,
    /** Durée source de la décélération, juste avant le kill. */
    val before: SerialDuration = 300.milliseconds,
    /** Durée source maximale du ralenti après le kill. */
    val after: SerialDuration = 500.milliseconds,
    /** Paliers de décélération avant le kill (1 = changement de vitesse net). */
    val rampSteps: Int = 3,
    /** Retour au plein régime sur un temps plutôt qu'au bout de [after]. */
    val snapToBeat: Boolean = true,
    /**
     * Images intermédiaires calculées par estimation de mouvement pendant le ralenti, au lieu de répéter les images
     * de la source. Le mouvement devient fluide, mais le rendu de ces portions est bien plus lent (`minterpolate`).
     */
    val interpolate: Boolean = false,
) {
    init {
        require(factor in 0.5..1.0) { "slowMotion.factor doit être entre 0,5 et 1" }
        require(rampSteps in 1..8) { "slowMotion.rampSteps doit être entre 1 et 8" }
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
    /** Petit compteur « KILL n » à chaque kill. Désactivé par défaut : il n'apporte rien et surcharge l'image. */
    val killCounter: Boolean = false,
)

/** Ce qu'on entend du son du jeu dans le montage. */
@Serializable
enum class GameAudio {
    /** Tout le son du jeu, plus fort aux kills et pendant les réactions ; la musique baisse sous la voix. */
    @SerialName("full") FULL,
    /** Le son du kill seul (tir, notification) : le reste du jeu et les voix se taisent, la musique baisse sous chaque kill. */
    @SerialName("kills") KILLS,
}

@Serializable
data class MontageAudio(
    /**
     * Équilibre jeu / musique, de -1 (musique devant) à 1 (jeu devant). Chaque cran de 1 monte le jeu de 6 dB et baisse
     * la musique d'autant ; le volume global est ensuite ramené à [loudnessLufs], seul le rapport entre les deux change.
     */
    val balance: Double = 0.0,
    val game: GameAudio = GameAudio.FULL,
    val musicVolume: Double = 1.0,
    /** Son du jeu (et voix) hors kills et réactions. */
    val gameVolume: Double = 0.5,
    /** Son du jeu autour d'un kill (tir, impact). */
    val killVolume: Double = 1.0,
    /** Voix et rires : bien audibles. */
    val voiceVolume: Double = 1.0,
    /** Volume de la musique pendant une réaction (ducking). */
    val musicUnderVoice: Double = 0.45,
    /** Volume de la musique autour d'un kill, en mode [GameAudio.KILLS] : assez bas pour que le son du kill passe devant. */
    val musicUnderKill: Double = 0.3,
    /** Ce que devient le son du jeu pendant un ralenti. */
    val slowMotion: SlowAudio = SlowAudio.NATURAL,
    /** Disparition du son du jeu quand il a fini de jouer avant la fin du ralenti (mode `natural`). */
    val slowFade: SerialDuration = 250.milliseconds,
    /** Montée d'un changement de volume (ducking, son du jeu au kill) : sans elle, la marche s'entend. */
    val duckAttack: SerialDuration = 80.milliseconds,
    /** Retour au volume nominal : plus lent que la montée, comme un compresseur. */
    val duckRelease: SerialDuration = 220.milliseconds,
    /** Le son du jeu peut déborder d'autant sur le plan suivant pour finir un kill ou une phrase (fondu). */
    val bleed: SerialDuration = 300.milliseconds,
    val loudnessLufs: Double = -14.0,
) {
    init {
        require(balance in -1.0..1.0) { "montage.audio.balance doit être entre -1 et 1" }
        require(musicUnderKill in 0.0..1.0) { "montage.audio.musicUnderKill doit être entre 0 et 1" }
    }

    /** Gains appliqués au jeu et à la musique pour [balance] : ±6 dB par cran, en sens opposés. */
    val gameGain: Double get() = Math.pow(2.0, balance)
    val musicGain: Double get() = Math.pow(2.0, -balance)
}
