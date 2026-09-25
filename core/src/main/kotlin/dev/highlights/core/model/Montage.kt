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
    /** Plafond de durée ; la durée visée vient du nombre de kills (voir [length]). */
    val maxDuration: SerialDuration = 60.seconds,
    val length: MontageLength = MontageLength(),
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
    /**
     * Plusieurs plans sont essayés (échelle de la grille, place de la drop) et seul le mieux noté par le scoreur est
     * rendu. Le calcul d'un plan ne coûte rien face au rendu : autant en comparer une dizaine.
     */
    val variants: Boolean = true,
    val shotAlign: ShotAlign = ShotAlign(),
    val killStyle: KillStyle = KillStyle(),
    val cuts: CutSettings = CutSettings(),
    val zoom: ZoomEffect = ZoomEffect(),
    val flash: FlashEffect = FlashEffect(),
    val whip: WhipPanEffect = WhipPanEffect(),
    val matchCut: MatchCut = MatchCut(),
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
 * Durée du montage tirée de ce qu'il y a à montrer : [perClip] par clip, [perExtraKill] de plus par kill au-delà du
 * premier dans un multi-kill, au moins [min], au plus [MontageSettings.maxDuration]. Sans [fitKills], le montage occupe
 * toute la durée maximale, quitte à étirer quatre kills sur une minute de musique.
 */
@Serializable
data class MontageLength(
    val fitKills: Boolean = true,
    val perClip: SerialDuration = 2500.milliseconds,
    val perExtraKill: SerialDuration = 1.seconds,
    val min: SerialDuration = 12.seconds,
) {
    init {
        require(perClip.isPositive()) { "montage.length.perClip doit être positif" }
        require(!perExtraKill.isNegative()) { "montage.length.perExtraKill ne peut pas être négatif" }
        require(min.isPositive()) { "montage.length.min doit être positif" }
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

/**
 * Recalage de chaque kill sur le son du tir. L'instant d'un kill vient d'une notification (killfeed, journal lu par OCR,
 * événement d'Outplayed) dont le retard sur le tir varie d'un kill à l'autre ; [MontageSettings.killOffset] n'en corrige
 * que la moyenne. On cherche donc, dans le son du jeu autour de cet instant, l'attaque la plus nette : c'est elle que le
 * spectateur entend tomber sur le temps.
 */
@Serializable
data class ShotAlign(
    val enabled: Boolean = true,
    /** Recherche avant l'instant annoncé : le tir précède la notification. */
    val before: SerialDuration = 300.milliseconds,
    /**
     * Après l'instant annoncé, juste la marge d'imprécision de l'annonce : un son qui suit la notification est celui
     * d'après le kill (tir dans le vide, pivot vers la cible suivante), jamais le tir qui tue.
     */
    val after: SerialDuration = 50.milliseconds,
    /** Montée d'énergie minimale (dB) d'une attaque pour y voir un tir ; en dessous, l'instant annoncé est gardé. */
    val minRiseDb: Double = 9.0,
) {
    init {
        require(minRiseDb > 0) { "montage.shotAlign.minRiseDb doit être positif" }
    }
}

/**
 * Ce qui rend un kill spectaculaire au-delà du nombre : tir à la tête, flick (visée qui balaie l'écran puis s'arrête sur
 * la cible), kills enchaînés très vite, ace, clutch. Les bonus s'ajoutent au rang d'un groupe, dont 1 vaut un kill de
 * plus : un one-tap en flick passe devant un double kill ordinaire, et décroche la drop. Un kill aussitôt suivi de sa
 * propre mort recule.
 *
 * Le jeu ne dit ni où commence un round ni combien d'alliés sont encore en vie : les rounds sont déduits des kills et
 * des morts du joueur. Une mort clôt son round ; un silence de plus de [roundGap] (phase d'achat) aussi.
 */
@Serializable
data class KillStyle(
    /** Événement émis par le détecteur pour un tir à la tête (VALORANT via Outplayed). Vide : pas de recherche. */
    val headshotEvent: String = "headshot",
    val headshotBonus: Double = 0.3,
    /** Mesure de la rotation de la caméra juste avant le kill (décodage d'une demi-seconde d'image par kill). */
    val flick: Boolean = true,
    /** Vitesse de balayage (largeurs d'écran par seconde) en dessous de laquelle ce n'est pas un flick… */
    val flickFrom: Double = 1.5,
    /** … et au-dessus de laquelle c'en est un franc. */
    val flickTo: Double = 5.0,
    val flickBonus: Double = 0.8,
    /** Kill qui suit le précédent de moins que ça : enchaînement. */
    val quickGap: SerialDuration = 1.seconds,
    val quickBonus: Double = 0.3,
    /** Événement émis pour une mort du joueur (VALORANT via Outplayed). Vide : ni mort, ni round, ni ace, ni clutch. */
    val deathEvent: String = "death",
    /** Mort qui suit le dernier kill d'un groupe de moins que ça : le kill est aussitôt payé, le groupe recule. */
    val deathGap: SerialDuration = 3.seconds,
    val deathPenalty: Double = 0.5,
    /** Silence (ni kill ni mort) au-delà duquel un nouveau round commence : plus court que la phase d'achat. */
    val roundGap: SerialDuration = 40.seconds,
    /** Kills d'un même round qui font un ace ; le bonus va au groupe du dernier kill du round. */
    val aceKills: Int = 5,
    val aceBonus: Double = 1.5,
    /**
     * Clutch : round survécu, fini sur un groupe d'au moins [clutchKills] kills. Sans le nombre d'alliés en vie, c'est
     * l'approche la plus proche : le joueur termine le round seul face aux derniers adversaires.
     */
    val clutchKills: Int = 2,
    val clutchBonus: Double = 0.5,
) {
    init {
        require(flickTo > flickFrom) { "montage.killStyle.flickTo doit dépasser flickFrom" }
        require(deathPenalty >= 0) { "montage.killStyle.deathPenalty ne peut pas être négatif" }
        require(roundGap.isPositive()) { "montage.killStyle.roundGap doit être positif" }
        require(aceKills >= 2) { "montage.killStyle.aceKills doit valoir au moins 2" }
        require(clutchKills >= 1) { "montage.killStyle.clutchKills doit valoir au moins 1" }
    }
}

/**
 * Coupure de la musique juste avant la drop : le son du jeu reste seul un instant, puis la drop repart sur le meilleur
 * kill. L'attente rend la drop plus forte que n'importe quel effet.
 */
@Serializable
data class DropBreak(
    val enabled: Boolean = true,
    /** Longueur de la coupure, en temps de la musique (0,5 = une croche). */
    val beats: Double = 1.0,
    /** Volume de la musique pendant la coupure (0 = silence). */
    val musicLevel: Double = 0.0,
) {
    init {
        require(beats in 0.25..4.0) { "montage.audio.dropBreak.beats doit être entre 0,25 et 4" }
        require(musicLevel in 0.0..1.0) { "montage.audio.dropBreak.musicLevel doit être entre 0 et 1" }
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
 * Raccord en whip pan dans le sens du flick : le plan qui finit sur un flick file dans la direction où la vue tournait,
 * flouté par la vitesse, et le suivant arrive en continuant le même mouvement. Sans flick de part et d'autre de la
 * coupe, la coupe reste franche. Le whip remplace le flash sur la coupe où il tombe.
 */
@Serializable
data class WhipPanEffect(
    val enabled: Boolean = true,
    /** Durée totale du raccord, partagée de part et d'autre de la coupe. */
    val duration: SerialDuration = 200.milliseconds,
    /** Flou de mouvement, en part de la dimension balayée (largeur pour un flick horizontal). */
    val blur: Double = 0.015,
) {
    init {
        require(duration.inWholeMilliseconds in 40..600) { "montage.whip.duration doit être entre 40 ms et 600 ms" }
        require(blur in 0.0..0.1) { "montage.whip.blur doit être entre 0 et 0,1" }
    }
}

/**
 * Repère du HUD qui n'apparaît qu'avec une arme à feu en main (VALORANT : l'icône du chargeur, entre les munitions du
 * chargeur et la réserve). [region] : où le chercher, mesurée en 16:9 et accrochée au centre comme le HUD ; la zone
 * est réduite à [width] x [height] pixels, l'échelle à laquelle [template] a été découpé. Présent si la corrélation
 * avec le modèle atteint [minScore] quelque part dans la zone.
 */
@Serializable
data class WeaponHud(
    val template: String,
    val region: CropRegion,
    val width: Int = 64,
    val height: Int = 40,
    val minScore: Double = 0.8,
) {
    init {
        require(width in 8..256 && height in 8..256) { "montage.matchCut.weapon : zone réduite entre 8 et 256 pixels" }
        require(minScore in -1.0..1.0) { "montage.matchCut.weapon.minScore doit être entre -1 et 1" }
    }
}

/**
 * Pose de l'arme qu'on raccorde d'un plan à l'autre. [AIM] : la visée, tenue jusqu'au kill et juste après, comparée à
 * l'image du kill (WARDOGS). [REST] : l'arme au repos, comparée à l'image médiane autour du kill ; au moment du kill
 * elle tremble sous le recul, le plan commence et finit donc dans le repos le plus proche (VALORANT).
 */
@Serializable
enum class PoseKind {
    @SerialName("aim") AIM,
    @SerialName("rest") REST,
}

/**
 * Raccord visée sur visée : juste avant un kill, le joueur vise, et le viseur occupe le centre de l'écran. À chaque
 * coupe, le plan sortant s'arrête avant que le joueur ne baisse son arme, et le plan entrant commence quand il a déjà
 * épaulé : le viseur reste au centre par-dessus la coupe. Ces deux portions sont ralenties pour garder la durée de leur
 * slot (les kills restent sur leur temps). La visée se reconnaît à ce que le centre ressemble à ce qu'il était juste
 * avant le kill, en ne comparant que les pixels immobiles à ce moment-là : l'arme, pas le décor qui défile derrière.
 */
@Serializable
data class MatchCut(
    val enabled: Boolean = true,
    /** Pose qui se raccorde : la visée du kill (le viseur au centre), ou l'arme au repos (tir à la hanche). */
    val pose: PoseKind = PoseKind.AIM,
    /**
     * Zone du viseur et du haut de l'arme en visée, mesurée sur une capture 16:9 et accrochée au centre (elle est
     * ramenée au format de chaque capture) : le jeu place l'arme par rapport au centre de l'écran, pas au bord.
     */
    val region: CropRegion = CropRegion(0.35, 0.30, 0.30, 0.55),
    /** Ressemblance minimale (0..1) avec la visée du kill pour qu'une image compte comme visée. */
    val minSimilarity: Double = 0.6,
    /** Images qui servent de référence à la visée : celles qui précèdent le kill, sur cette durée. */
    val reference: SerialDuration = 400.milliseconds,
    /** Part des pixels de la zone comparés : les plus immobiles de la référence (l'arme). */
    val stillShare: Double = 0.3,
    /**
     * Symétrie gauche-droite minimale (-1..1) du bas de la zone au moment du kill : en visée, l'arme descend au centre ;
     * à la hanche, elle est sur le côté et le kill ne se raccorde pas. Mesurée sur WARDOGS : 0,22 et plus en visée (un
     * cas sur quatorze en dessous), 0,09 au plus à la hanche.
     */
    val minSymmetry: Double = 0.15,
    /**
     * Ressemblance minimale (-1..1) des poses de part et d'autre d'une coupe, sur les pixels de l'arme : même arme,
     * tenue de la même façon. -1 : toute pose tenue se raccorde (en visée, le viseur au centre suffit, même si la lunette
     * change) ; à la hanche, un pistolet après un fusil ne se raccorde pas.
     */
    val minPoseMatch: Double = -1.0,
    /**
     * Arme en main, lue dans le HUD : une image ne compte dans la pose que si ce repère y est. Sans lui, une capacité
     * ou un couteau tenus à la même place qu'une arme passent pour elle. Null : pas de vérification.
     */
    val weapon: WeaponHud? = null,
    /**
     * Vitesse minimale des portions ralenties pour tenir dans la visée (0,3 = trois fois plus lent). Le joueur baisse
     * son arme 0 à 0,5 s après le kill, alors que le plan dure encore plus d'une seconde : la fin se ralentit beaucoup.
     */
    val minSpeed: Double = 0.3,
) {
    init {
        require(minSimilarity in 0.0..1.0) { "montage.matchCut.minSimilarity doit être entre 0 et 1" }
        require(reference.inWholeMilliseconds in 100..1000) { "montage.matchCut.reference doit être entre 100 et 1000 ms" }
        require(stillShare > 0.0 && stillShare <= 1.0) { "montage.matchCut.stillShare doit être entre 0 et 1" }
        require(minSymmetry in -1.0..1.0) { "montage.matchCut.minSymmetry doit être entre -1 et 1" }
        require(minPoseMatch in -1.0..1.0) { "montage.matchCut.minPoseMatch doit être entre -1 et 1" }
        require(minSpeed in 0.25..1.0) { "montage.matchCut.minSpeed doit être entre 0,25 et 1" }
    }
}

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
data class SpeedRampEffect(
    val enabled: Boolean = true,
    val maxChange: Double = 0.15,
    /**
     * Les kills d'un multi-kill peuvent aussi tomber sur une frappe forte entre deux temps (caisse claire, contretemps),
     * et la frappe la plus marquée à portée est préférée à la plus proche.
     */
    val onHits: Boolean = true,
) {
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
    val dropBreak: DropBreak = DropBreak(),
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
