package dev.highlights.core.model

import dev.highlights.core.i18n.tr
import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
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
    /**
     * Autres événements qui ancrent un plan comme [killEvent] (montage des morts : « downed » quand il compte comme une
     * mort, voir [forFails]). Vide : [killEvent] seul.
     */
    val anchorEvents: Set<String> = emptySet(),
    /** Décalage entre la notification et le tir (la notification apparaît après le kill). */
    val killOffset: SerialDuration = (-400).milliseconds,
    /** Kills plus proches que ça : un seul clip (multi-kill). */
    val mergeGap: SerialDuration = 5.seconds,
    /**
     * Au montage, un multi-kill dont deux kills sont plus éloignés que ça devient plusieurs plans, chacun calé sur la
     * musique (zéro : jamais). Le groupe reste entier pour le classement, les aces et les clutchs. Trois kills d'un
     * combat sur 5 s faisaient un plan de 8 à 16 temps dont seul le dernier tombait sur la musique, et une drop rapide
     * ne montrait plus qu'un kill toutes les 2,9 s.
     */
    val splitGap: SerialDuration = 1500.milliseconds,
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
    val colors: ColorBoost = ColorBoost(),
    val motionBlur: MotionBlur = MotionBlur(),
    val matchCut: MatchCut = MatchCut(),
    val slowMotion: SlowMotionEffect = SlowMotionEffect(),
    val speedRamp: SpeedRampEffect = SpeedRampEffect(),
    val text: TextEffect = TextEffect(),
    /** Ouverture « vitrine » : passages calmes avant le premier kill (voir [ShowcaseOpening]). */
    val showcase: ShowcaseOpening = ShowcaseOpening(),
    val audio: MontageAudio = MontageAudio(),
    val formats: List<OutputFormat> = listOf(OutputFormat.VERTICAL, OutputFormat.SOURCE),
    /** Rythme rapide : un kill par temps de musique (voir [FastMontage]), appliqué par [forFast]. */
    val fast: FastMontage = FastMontage(),
    /** Rythme rapide sur les seules parties rapides et intenses de la musique (voir [FastParts]). */
    val fastParts: FastParts = FastParts(),
    /** Réglages du montage « onetaps », appliqués par [forOneTaps] quand on le demande. */
    val oneTaps: OneTapMontage = OneTapMontage(),
    /** Réglages du montage des morts (« fails »), appliqués par [forFails] quand on le demande. */
    val fails: FailMontage = FailMontage(),
    /** Réglages du montage des réactions au micro, appliqués par [forReactions] quand on le demande. */
    val reactionMontage: ReactionMontage = ReactionMontage(),
    /** Choix automatique de la musique : préférence pour celles qui donnent des plans courts. null : aucune. */
    val musicPace: MusicPace? = null,
    /**
     * Kills écartés quand le joueur meurt moins de ce délai après (zéro : aucun) : son plan ne montrerait que l'écran
     * de mort. Posé par [forFast], dont les plans finissent 0,17 s après le kill.
     */
    val skipDeathWithin: SerialDuration = Duration.ZERO,
    /**
     * Écarter du montage les kills faits avec une capacité (molly, flèche, drone…) : à l'image, on ne voit ni le tir ni
     * la cible tomber sous le réticule. Reconnus à ce que le compteur de munitions ne voit aucune balle autour du kill
     * (voir `AbilityKills`) ; sans compteur lu, rien n'est écarté. Les kills restent dans la session, les statistiques
     * et la recherche ; un moment choisi par l'utilisateur (recherche, kill forcé ou épinglé) est toujours monté.
     */
    val skipAbilityKills: Boolean = false,
    /**
     * En dessous de ce nombre de kills trouvés dans les parties, le montage se fait quand même, mais avec un
     * avertissement : une partie VALORANT de 44 kills en donnait un seul (détection en défaut) et le montage de 2 s
     * sortait sans un mot. Zéro : jamais d'avertissement.
     */
    val minKills: Int = 3,
    /**
     * Apprendre les goûts de l'utilisateur : les poids des critères de la note (`MontageScorer`) sont ajustés d'après
     * ses choix de variantes après les aperçus (`TasteLearner`, à partir de `TasteLearner.MIN_CHOICES` choix). Actif par
     * défaut : sans effet tant qu'il y a moins de `MIN_CHOICES` choix (poids d'origine, même plan, même note) ;
     * `app gouts` montre ce qui a été appris, `app gouts --reset` met l'historique de côté (retour aux poids d'origine).
     */
    val learnTaste: Boolean = true,
    /**
     * Poids des critères de la note qui remplacent ceux d'origine (`MontageScorer.WEIGHTS`), par nom de critère ; null :
     * ceux d'origine. Posé par le pipeline depuis les goûts appris quand [learnTaste] est vrai, pas à écrire à la main.
     */
    val scoreWeights: Map<String, Double>? = null,
) {
    init {
        require(minScore in 0.0..1.0) { "montage.minScore doit être entre 0 et 1" }
        require(minKills >= 0) { "montage.minKills ne peut pas être négatif" }
        require(scoreWeights == null || scoreWeights.values.all { it >= 0.0 }) { "montage.scoreWeights : poids négatif" }
    }

    /**
     * Les mêmes réglages, au rythme rapide : chaque kill est son propre plan, d'un temps quand la musique le permet, sans
     * ralenti ni rampe (ils n'ont pas la place), ni whip pan : ses 200 ms font filer toute l'image sur la moitié d'un
     * plan de 0,37 s, et le montage était jugé illisible. Le changement de plan à chaque temps fait déjà la transition.
     * Zoom, flash, flou et couleurs restent.
     */
    fun forFast(): MontageSettings {
        val f = fast
        return copy(
            mergeGap = Duration.ZERO,
            preRoll = f.preRoll,
            postRoll = f.postRoll,
            reactions = false,
            length = length.copy(perClip = f.perClip, perExtraKill = Duration.ZERO, min = f.min),
            cuts = cuts.copy(low = f.calm, mid = f.mid, high = f.cut, minLead = f.minLead, minTail = f.minTail, singleBeat = true),
            slowMotion = slowMotion.copy(enabled = false),
            speedRamp = speedRamp.copy(enabled = false),
            whip = whip.copy(enabled = false),
            zoom = zoom.copy(approach = f.zoomApproach, approachLead = f.zoomApproachLead),
            musicPace = f.musicPace,
            skipDeathWithin = f.skipDeathWithin,
        )
    }

    /**
     * Les mêmes réglages, pour un montage « onetaps » : chaque kill est son propre plan, très court, coupé juste
     * après l'impact, sans ralenti ni accroche. Le rythme vient des coupes, pas des effets.
     */
    fun forOneTaps(): MontageSettings {
        val o = oneTaps
        return copy(
            mergeGap = Duration.ZERO,
            preRoll = o.preRoll,
            postRoll = o.postRoll,
            hook = false,
            reactions = false,
            length = length.copy(perClip = o.perClip, perExtraKill = Duration.ZERO, min = o.min),
            cuts = cuts.copy(low = o.cut, mid = o.cut, high = o.cut, minLead = o.minLead, minTail = o.minTail, singleBeat = o.singleBeat),
            slowMotion = slowMotion.copy(enabled = false),
            speedRamp = speedRamp.copy(enabled = false),
            musicPace = o.musicPace,
        )
    }

    /**
     * Les mêmes réglages, pour le montage des morts du joueur : chaque mort ancre son plan comme un kill (les secondes qui
     * la précèdent, un ralenti dessus, un arrêt sur image, un petit texte : voir [FailMontage]). Les morts sont celles de
     * [KillStyle.deathEvent], plus « à terre » quand [KillStyle.downedAsDeath] le veut. Ce qui sert à classer les kills
     * (rounds, tirs à la tête, one taps, confirmation au killfeed, flick, pose de l'arme) n'a pas de sens ici : coupé.
     * Une mort juste après un kill (un échange) reste : elle est drôle aussi.
     */
    fun forFails(): MontageSettings {
        val f = fails
        val style = killStyle
        val deaths = style.copy(deathEvent = style.deathEvent.ifEmpty { "death" }).deathKinds
        return copy(
            killEvent = style.deathEvent.ifEmpty { "death" },
            anchorEvents = deaths - style.deathEvent.ifEmpty { "death" },
            fails = f.copy(enabled = true, killEvent = killEvent),
            mergeGap = Duration.ZERO,
            preRoll = f.preRoll,
            postRoll = f.postRoll,
            hook = false,
            reactions = false,
            skipDeathWithin = Duration.ZERO,
            // Une mort sans balle tirée n'est pas une capacité : le montage des morts les garde toutes.
            skipAbilityKills = false,
            effectDensity = EffectDensity.HEAVY,
            length = length.copy(perClip = f.perClip, perExtraKill = Duration.ZERO, min = f.min),
            // Placement de la mort : la place du ralenti et du gel est réservée de part et d'autre (sinon, sur un plan de
            // trois temps, la mort tombait trop près d'une coupe et 6 morts sur 9 perdaient leur ralenti).
            cuts = cuts.copy(
                low = f.perClip, mid = f.perClip, high = f.perClip,
                minLead = f.minLead + f.slowBefore / f.slowFactor, minTail = f.minTail + f.slowAfter / f.slowFactor + f.freeze,
                singleBeat = false, offbeatKills = false, jumpCuts = false,
            ),
            slowMotion = slowMotion.copy(enabled = true, factor = f.slowFactor, before = f.slowBefore, after = f.slowAfter),
            speedRamp = speedRamp.copy(enabled = false),
            matchCut = matchCut.copy(enabled = false),
            killStyle = style.copy(deathEvent = "", headshotEvent = "", shotEvent = "", hudShotEvent = "", confirmEvent = "", clutchEvent = "", allyDeathEvent = "", clutchBannerEvent = "", flick = false),
        )
    }
}

/**
 * Montage des morts (« fails ») : pour chaque mort, les secondes qui la précèdent ([preRoll]), un ralenti qui finit sur
 * elle, un arrêt sur image de [freeze] et un petit texte (OUPS, RIP… : [labels], sinon les libellés `video.fail.*` de la
 * langue de l'interface, tirés au hasard mais toujours les mêmes pour un même montage). Calé sur la musique comme le
 * montage kills : un plan par mort, la mort sur un temps. [enabled] est posé par [MontageSettings.forFails].
 */
@Serializable
data class FailMontage(
    val enabled: Boolean = false,
    val preRoll: SerialDuration = 3.seconds,
    val postRoll: SerialDuration = 800.milliseconds,
    /** Longueur visée d'un plan : l'avant, le ralenti, l'image figée. */
    val perClip: SerialDuration = 4500.milliseconds,
    /** Contexte minimal avant la mort (temps réel, hors ralenti) : de quoi voir venir le drame. */
    val minLead: SerialDuration = 1500.milliseconds,
    /** Après la mort : place du ralenti qui suit et de l'image figée. */
    val minTail: SerialDuration = 300.milliseconds,
    val min: SerialDuration = 15.seconds,
    val slowFactor: Double = 0.5,
    val slowBefore: SerialDuration = 600.milliseconds,
    val slowAfter: SerialDuration = 300.milliseconds,
    /** Image figée juste après la mort ou son ralenti (durée dans le montage) ; zéro : aucune. */
    val freeze: SerialDuration = 700.milliseconds,
    /**
     * Part des morts qui ont le ralenti, les plus fortes (voir `MontagePlanner.failSlows`), au moins une : un ralenti
     * sur chaque mort lassait (choix du joueur, 18/11). Les autres gardent l'image figée et le texte. 1 : toutes.
     */
    val slowShare: Double = 1.0 / 3,
    /** Mort qui suit un kill du joueur de moins que ça : un échange, une mort plus forte (préférée pour le ralenti). */
    val tradeWindow: SerialDuration = 3.seconds,
    /** Événement des kills du joueur, pour reconnaître un échange ; posé par [MontageSettings.forFails]. */
    val killEvent: String = "kill",
    /** Textes affichés sur la mort ; null : ceux de la langue de l'interface (`video.fail.*`). Vide : aucun texte. */
    val labels: List<String>? = null,
) {
    init {
        require(slowFactor in 0.1..1.0) { "montage.fails.slowFactor doit être entre 0,1 et 1" }
        require(!freeze.isNegative()) { "montage.fails.freeze ne peut pas être négatif" }
        require(slowShare in 0.0..1.0) { "montage.fails.slowShare doit être entre 0 et 1" }
    }
}

/**
 * Préférence pour les musiques qui permettent des plans courts, dans le choix automatique de la musique : des plans
 * (hors drop) d'une longueur médiane de [fast] ou moins gagnent [weight], de [slow] ou plus rien. Mesuré sur le plan que
 * chaque musique donne plutôt que sur son tempo : à 130 BPM, un plan d'un temps ne laisse plus assez de contexte autour
 * du kill et redevient un plan de deux temps (0,92 s), plus lent qu'un temps à 105 BPM (0,57 s).
 */
@Serializable
data class MusicPace(
    val fast: SerialDuration = 550.milliseconds,
    val slow: SerialDuration = 1.seconds,
    /** À comparer à la valeur d'une musique : sa note (0,85 environ) × la part des groupes qu'elle garde. */
    val weight: Double = 0.1,
) {
    init {
        require(fast < slow) { "montage.musicPace : fast doit être plus court que slow" }
        require(weight >= 0) { "montage.musicPace.weight doit être positif" }
    }
}

/**
 * Rythme rapide : un kill sur chaque temps de la musique (sur chaque coup de grosse caisse d'une techno), comme les edits
 * TikTok de référence, dont les plans durent 0,37 à 0,67 s en médiane, contre 1,5 s pour nos plans de quatre temps à
 * 161 BPM. Le kill tombe sur le temps qui ouvre son plan, la coupe le précède de [minLead] ; il reste [minTail] au moins
 * pour voir l'ennemi tomber. Leur somme (0,35 s) borne le tempo des plans d'un temps : 171 BPM ; au-delà, deux temps.
 */
@Serializable
data class FastMontage(
    /** Rythme rapide sans qu'on le demande (fenêtre de montage, `--fast`). */
    val enabled: Boolean = false,
    val preRoll: SerialDuration = 600.milliseconds,
    val postRoll: SerialDuration = 300.milliseconds,
    /**
     * Longueur visée d'un plan dans la section la plus intense ([cut], un temps en général), une section moyenne ([mid])
     * et la plus calme ([calm]), ramenée au plus proche nombre de temps. La même longueur partout faisait d'une intro au
     * piano un kill par temps, et d'une drop qui accélère à peine plus.
     */
    val cut: SerialDuration = 400.milliseconds,
    val mid: SerialDuration = 800.milliseconds,
    val calm: SerialDuration = 1600.milliseconds,
    val minLead: SerialDuration = 200.milliseconds,
    val minTail: SerialDuration = 150.milliseconds,
    /** Durée visée par kill, et durée minimale du montage. */
    val perClip: SerialDuration = 400.milliseconds,
    val min: SerialDuration = 8.seconds,
    /** Choix de la musique : les musiques rapides, qui gardent des plans d'un temps, passent devant. */
    val musicPace: MusicPace = MusicPace(),
    /** Approche du zoom vers le réticule avant chaque kill (voir [ZoomEffect.approach]) : les plans courts en ont besoin. */
    val zoomApproach: Double = 0.25,
    val zoomApproachLead: SerialDuration = 200.milliseconds,
    /**
     * Kill écarté si le joueur meurt moins de ce délai après : un échange. Le plan finit 0,17 s après le kill, et
     * montrait l'écran de mort (caméra du tueur, sa carte, son ulti) : 2 kills sur 74 d'un montage de 17 parties.
     */
    val skipDeathWithin: SerialDuration = 600.milliseconds,
) {
    init {
        require(preRoll >= minLead) { "montage.fast.preRoll doit couvrir minLead" }
        require(postRoll >= minTail) { "montage.fast.postRoll doit couvrir minTail" }
        require(cut.isPositive() && perClip.isPositive() && min.isPositive()) { "montage.fast : durées positives attendues" }
    }
}

/**
 * Rythme rapide par partie de la musique, dans un même montage : les parties calmes (intro, montée) gardent le rythme
 * du style, les parties rapides et intenses passent au rythme rapide ([FastMontage] : un kill par temps quand le tempo
 * le permet, ni ralenti ni rampe, zoom d'approche vers le réticule). Demande de l'utilisateur du 18/11 : le rythme
 * rapide « quand la musique s'y prête, par exemple Rome Is Burning après la drop ». Une partie est rapide si elle commence
 * à la drop ou après et si son intensité atteint [minIntensity] (0 à 1 dans le morceau ; la partie après la drop de
 * « Rome Is Burning » : 0,95, son intro : 0,25). Le tempo n'y entre plus (choix de l'utilisateur du 18/11, après des
 * montages côte à côte avec et sans la règle des 120 BPM sur WARDOGS et VALORANT) : une musique lente passe aussi au rythme
 * rapide après sa drop. Un creux net à l'intérieur d'une partie intense ([calmDb] sous le niveau fort du morceau pendant au
 * moins [calmBars] mesures : la basse qui s'arrête deux mesures) revient au rythme du style, puis le rythme rapide reprend.
 * Le rythme rapide forcé ([FastMontage.enabled], `--fast`) l'emporte : tout le montage est rapide.
 */
@Serializable
data class FastParts(
    val enabled: Boolean = true,
    val minIntensity: Double = 0.7,
    /**
     * Ressenti de la musique du montage, dit par l'utilisateur (`music-feel.json`) ou appris de ses réponses : posé par
     * le montage pour sa musique, jamais dans un profil. [MusicFeel.CALM] : jamais de rythme rapide ; [MusicFeel.FAST] :
     * rapide dès la drop, à partir de [fastIntensity] ; [MusicFeel.AUTO] : la règle par défaut ([minIntensity]).
     */
    val feel: MusicFeel = MusicFeel.AUTO,
    /**
     * Intensité suffisante pour une musique ressentie rapide : plus bas que [minIntensity], une partie moyenne qui suit la
     * drop (« Doom Eternal » 1:24, 0,51) passe aussi au rythme rapide, une partie vraiment calme (0,33) non.
     */
    val fastIntensity: Double = 0.45,
    /**
     * Creux d'une partie intense : volume moyen d'une mesure à au moins ce nombre de dB sous le niveau fort du morceau
     * (90e centile des mesures). Mesuré sur 19 musiques : la coupure de basse de « Big Dawgs » (0:20, deux mesures) est
     * à 9,2 dB ; ailleurs, deux mesures de suite d'une partie intense ne descendent jamais à plus de 5,8 dB
     * (« HEAVENLY JUMPSTYLE »), un couplet plus calme de « Lose Yourself » à 5,3 dB.
     */
    val calmDb: Double = 7.0,
    /** Nombre de mesures de suite qu'il faut à un creux : une seule mesure basse est une respiration, pas une partie. */
    val calmBars: Int = 2,
    /**
     * Ancien seuil de tempo (120 BPM), retiré le 18/11. Ignoré : seulement accepté pour qu'une ancienne configuration
     * qui le pose se charge encore.
     */
    val minBpm: Double? = null,
) {
    init {
        require(minIntensity in 0.0..1.0) { "montage.fastParts.minIntensity doit être entre 0 et 1" }
        require(fastIntensity in 0.0..1.0) { "montage.fastParts.fastIntensity doit être entre 0 et 1" }
        require(calmDb > 0) { "montage.fastParts.calmDb doit être positif" }
        require(calmBars >= 1) { "montage.fastParts.calmBars doit valoir au moins 1" }
    }
}

/**
 * Ressenti d'une musique pour le rythme rapide : [FAST] (rapide après la drop), [CALM] (jamais rapide), [AUTO] (la
 * règle par défaut, d'après l'intensité de ses parties).
 */
@Serializable
enum class MusicFeel {
    @SerialName("fast") FAST,
    @SerialName("calm") CALM,
    @SerialName("auto") AUTO,
}

/**
 * Montage « onetaps » : que des kills d'une balle à la tête, enchaînés au rythme de la musique comme les edits TikTok.
 * Un plan dure un temps quand la musique le permet (0,57 s à 105 BPM) : le kill tombe sur le temps qui l'ouvre, la coupe
 * le précède de [minLead]. Sinon (au-delà de 1 / ([minLead] + [minTail]), 109 BPM), deux temps.
 */
@Serializable
data class OneTapMontage(
    /** Contexte gardé avant le kill (la visée qui se pose) et après (l'impact). */
    val preRoll: SerialDuration = 700.milliseconds,
    val postRoll: SerialDuration = 350.milliseconds,
    /** Longueur visée d'un plan, quelle que soit l'intensité de la musique : ramenée au plus proche nombre de temps. */
    val cut: SerialDuration = 500.milliseconds,
    /**
     * Contexte avant le kill et après : la visée qui se pose, puis l'impact et la chute. Avec 0,12 s après la balle, le
     * premier montage coupait avant que l'ennemi tombe. Leur somme borne le tempo des plans d'un temps : 0,55 s, un
     * temps à 109 BPM.
     */
    val minLead: SerialDuration = 250.milliseconds,
    val minTail: SerialDuration = 300.milliseconds,
    /** Plans d'un seul temps permis (voir [CutSettings.singleBeat]) : un kill par temps de musique. */
    val singleBeat: Boolean = true,
    /** Durée visée par kill, et durée minimale du montage. */
    val perClip: SerialDuration = 500.milliseconds,
    val min: SerialDuration = 6.seconds,
    /**
     * Sans source de tirs à la tête (ni Outplayed, ni gabarit du son), garder les kills d'une seule balle plutôt que ne
     * rien monter : un kill au corps à l'Operator passe alors pour un one tap.
     */
    val allowWithoutHeadshots: Boolean = true,
    /** Choix de la musique : les musiques rapides, qui gardent des plans d'un temps, passent devant. */
    val musicPace: MusicPace = MusicPace(),
) {
    init {
        require(preRoll >= minLead) { "montage.oneTaps.preRoll doit couvrir minLead" }
        require(postRoll >= minTail) { "montage.oneTaps.postRoll doit couvrir minTail" }
        require(cut.isPositive() && perClip.isPositive() && min.isPositive()) { "montage.oneTaps : durées positives attendues" }
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
    /**
     * Musique prise depuis son premier temps, au lieu du passage le plus intense autour de la drop : pour les musiques
     * dont l'intro fait partie du montage (celles qu'on reconnaît à leurs premières notes).
     */
    val fromStart: Boolean = false,
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
     * Montée minimale avant la drop (au plus [dropPosition] du montage) : sans elle, le passage le plus intense
     * commençait parfois 2,6 s avant la drop (« life kinda sucks », drop sur le premier plan), faute de vouloir de
     * l'intro calme qui y mène. C'est pourtant elle qui fait attendre le meilleur kill.
     */
    val dropLead: SerialDuration = 6.seconds,
    /**
     * Images d'avance de chaque coupe sur son temps : l'œil met quelques images à enregistrer un nouveau plan, si bien
     * qu'une coupe pile sur le temps paraît en retard. Le kill, lui, ne bouge pas : seule la coupe avance.
     */
    val preBeatFrames: Int = 1,
    /** Dans une montée de la musique, les plans raccourcissent au fur et à mesure : les coupes accélèrent avec elle. */
    val accelerateBuildUp: Boolean = true,
    /**
     * Plans d'un seul temps permis, quand un temps couvre [minLead] + [minTail] : le kill tombe sur le temps qui ouvre le
     * plan, et la coupe qui y mène le précède de [minLead], pris sur la fin du plan précédent. Sans cela, un plan dure au
     * moins deux temps, le kill tombant à l'intérieur.
     */
    val singleBeat: Boolean = false,
    /**
     * Le kill d'ancrage peut tomber sur un contretemps qui frappe plus fort que les deux temps qui l'entourent (une
     * musique syncopée), au lieu du temps : les coupes, elles, restent sur les temps. Mesuré sur 17 musiques : de tels
     * contretemps font 0 à 5 % des temps, 11 % sur « Big Dawgs » (hip-hop à 90 BPM, un demi-temps dure 0,33 s).
     */
    val offbeatKills: Boolean = true,
    /**
     * Plusieurs plans par kill dans les plans longs (au moins [jumpMinBeats] temps, sections calmes) : l'attente avant le
     * kill est coupée sur un temps, sa première partie prise [jumpBack] plus tôt dans la partie (le joueur qui se
     * déplace), puis la suite mène au kill. Les références coupent aussi sans kill (plans de 0,37 à 0,67 s en médiane) ;
     * les nôtres duraient jusqu'à 16 temps d'un seul tenant.
     */
    val jumpCuts: Boolean = true,
    val jumpMinBeats: Int = 8,
    val jumpBack: SerialDuration = 4.seconds,
) {
    init {
        require(jumpMinBeats >= 4) { "montage.cuts.jumpMinBeats doit valoir au moins 4" }
        require(jumpBack.isPositive()) { "montage.cuts.jumpBack doit être positif" }
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
    /**
     * Événement émis pour chaque tir du joueur entendu dans le son du jeu (détecteur `game-sounds`). Vide : les tirs ne
     * sont pas comptés, et aucun kill n'est un one tap.
     */
    val shotEvent: String = "shot",
    /**
     * Temps pendant lequel un tir avant celui qui tue empêche le one tap : c'est la même cible qu'on arrosait. Un kill
     * plus proche que ça coupe la fenêtre (deux cibles tuées d'une balle chacune font deux one taps).
     */
    val oneTapWindow: SerialDuration = 800.milliseconds,
    /**
     * Événement émis pour chaque balle vue sur le compteur de munitions du HUD (détecteur `ammo-counter`). Préféré aux
     * tirs entendus quand la partie en a : le son manque des balles dans les rafales et en invente (son du kill, de
     * l'impact à la tête). Vide : pas de recherche.
     */
    val hudShotEvent: String = "hud-shot",
    /**
     * Confirmation de chaque kill à l'image, au moment où le jeu l'enregistre (VALORANT : la ligne du joueur qui apparaît
     * dans le killfeed, lue à 30 img/s). Vide : pas de confirmation. Mesuré contre l'icône de kill sous le réticule : le
     * killfeed la suit de 0,02 à 0,10 s, quand l'annonce d'Outplayed s'en écarte de -0,55 à +0,47 s. La balle qui tue est
     * alors la dernière vue au compteur jusqu'à 0,12 s après la confirmation (à ±0,07 s de l'icône sur 13 kills) ; sans
     * confirmation, jusqu'à 0,3 s après l'annonce, ce qui prenait sur 18 kills sur 57 une balle de la rafale qui continue
     * après le kill : le plan commençait après lui.
     */
    val confirmEvent: String = "",
    /**
     * Balles du compteur qui suivent celle qui tue et comptent avec elle : une rafale qui continue après le kill n'est
     * pas un one tap. Au-delà, le joueur a relâché la détente (un tap au Vandal : 0,3 s et plus entre deux balles).
     */
    val oneTapAfter: SerialDuration = 250.milliseconds,
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
    /**
     * Événement émis quand le joueur est à terre, en attente de réanimation (Wardogs : « EN ATTENTE DE RÉANIMATION »
     * dans le journal des gains). Il peut encore être relevé : ce n'est pas une mort, sauf avec [downedAsDeath].
     */
    val downedEvent: String = "downed",
    /**
     * À terre compte comme une mort (rounds, kill aussitôt payé, enchaînements écartés du rythme rapide, statistiques).
     * Désactivé : le joueur relevé n'est pas mort, et dans Wardogs il l'est souvent.
     */
    val downedAsDeath: Boolean = false,
    /** Mort qui suit le dernier kill d'un groupe de moins que ça : le kill est aussitôt payé, le groupe recule. */
    val deathGap: SerialDuration = 3.seconds,
    val deathPenalty: Double = 0.5,
    /** Silence (ni kill ni mort) au-delà duquel un nouveau round commence : plus court que la phase d'achat. */
    val roundGap: SerialDuration = 40.seconds,
    /** Kills d'un même round qui font un ace ; le bonus va au groupe du dernier kill du round. */
    val aceKills: Int = 5,
    val aceBonus: Double = 1.5,
    /**
     * Clutch déduit, faute de mieux : round survécu, fini sur un groupe d'au moins [clutchKills] kills. Sans le nombre
     * d'alliés en vie, c'est l'approche la plus proche, mais elle en compte trop (3 déduits contre 1 réel sur une
     * partie de VALORANT). Ignoré quand le jeu annonce ses clutchs ([clutchEvent]).
     */
    val clutchKills: Int = 2,
    val clutchBonus: Double = 0.5,
    /**
     * Événement de clutch annoncé par le jeu (VALORANT via Outplayed : « clutch ») ; vide : clutch déduit. Il arrive
     * 15 à 30 ms après le kill qui conclut le round, ou au désamorçage du spike (10 s plus tard) : il revient au groupe
     * dont le dernier kill le précède, sans kill ni mort entre les deux, à moins de [roundGap].
     */
    val clutchEvent: String = "",
    /**
     * Mort d'un allié lue dans le killfeed (VALORANT : détecteur `killfeed-teams`, le joueur compris) et mort d'un
     * adversaire. Quand une partie en a, le clutch est réel : le joueur, dernier de son équipe en vie face à au moins
     * [clutchMinEnemies] adversaires, gagne le round en éliminant les autres (voir `TeamRounds`). Un clutch annoncé par
     * le jeu ([clutchEvent]) reste prioritaire. Vide : pas de recherche.
     */
    val allyDeathEvent: String = "ally-death",
    val enemyDeathEvent: String = "enemy-death",
    /** Joueurs par équipe (5 dans VALORANT). */
    val teamSize: Int = 5,
    /**
     * Adversaires encore en vie quand le joueur se retrouve seul : à partir de combien c'est un clutch. 1 : un 1 contre
     * 1 gagné en est un (choix de l'utilisateur, 19/11), bien que le jeu n'affiche « CLUTCH » qu'à partir d'un 1 contre 2
     * (un 1 contre 1 gagné y reste « WON », vu sur deux parties). 2 pour suivre le jeu.
     */
    val clutchMinEnemies: Int = 1,
    /**
     * Ligne du killfeed où le joueur (ou un allié) meurt pendant l'ultime de Phoenix (« Run it Back ») : il revient
     * aussitôt, ce n'est pas une mort. Une mort du joueur lue à moins de 1,5 s de cet événement est retirée partout
     * (rounds, clutchs, morts aussitôt payées, montage des morts, statistiques). Vide : gardée.
     */
    val revivedEvent: String = "ally-run-it-back",
    /**
     * Début de round lu à l'image (VALORANT : barres de vie des alliés revenues pleines, détecteur `killfeed-teams`).
     * Sépare les rounds du clutch réel même sans silence entre eux (vidéo montée, phases d'achat coupées).
     */
    val roundStartEvent: String = "round-start",
    /**
     * Bandeau « CLUTCH » de fin de round lu à l'image (détecteur `killfeed-teams`) : un clutch annoncé de plus, qui
     * attrape ceux gagnés au spike. Le jeu ne l'affiche qu'à partir d'un 1 contre 2, et pour les deux équipes.
     */
    val clutchBannerEvent: String = "clutch-banner",
) {
    init {
        require(flickTo > flickFrom) { "montage.killStyle.flickTo doit dépasser flickFrom" }
        require(deathPenalty >= 0) { "montage.killStyle.deathPenalty ne peut pas être négatif" }
        require(roundGap.isPositive()) { "montage.killStyle.roundGap doit être positif" }
        require(aceKills >= 2) { "montage.killStyle.aceKills doit valoir au moins 2" }
        require(clutchKills >= 1) { "montage.killStyle.clutchKills doit valoir au moins 1" }
        require(teamSize >= 2 && clutchMinEnemies in 1..teamSize) { "montage.killStyle : teamSize ≥ 2, clutchMinEnemies entre 1 et teamSize" }
        require(oneTapWindow.isPositive()) { "montage.killStyle.oneTapWindow doit être positif" }
    }

    /** Événements comptés comme une mort : [deathEvent], plus [downedEvent] avec [downedAsDeath]. Vide : morts ignorées. */
    val deathKinds: Set<String>
        get() = if (deathEvent.isEmpty()) emptySet() else setOfNotNull(deathEvent, downedEvent.takeIf { downedAsDeath && it.isNotEmpty() })
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

/** Compteur de kills : aucun, compté dans chaque plan (un multi-kill fait 1, 2, 3), ou cumulé sur tout le montage. */
@Serializable
enum class KillCounterMode {
    @SerialName("off") OFF,
    @SerialName("clip") CLIP,
    @SerialName("montage") MONTAGE,
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
    /**
     * Approche : l'image se resserre déjà vers le centre (le réticule) pendant [approachLead] avant le kill, jusqu'à
     * [approach] de plus au moment du tir, puis retombe avec le punch. En 21:9, l'ennemi n'est souvent que quelques
     * pixels au fond d'un couloir, et un plan de 0,37 s (rythme rapide) ne laisse pas le temps de le trouver. 0 : sans.
     */
    val approach: Double = 0.0,
    val approachLead: SerialDuration = 300.milliseconds,
) {
    init {
        require(amount in 0.0..1.0 && approach in 0.0..1.0) { "montage.zoom : amount et approach entre 0 et 1" }
        require(approachLead.isPositive()) { "montage.zoom.approachLead doit être positif" }
    }
}

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
    /**
     * Volume du souffle (whoosh) qui culmine sur la coupe, celui des highlights story (bruit filtré qui enfle puis
     * retombe) ; 0 : whip pan muet. Sous le son du jeu (1) et la musique (0,8) : il accompagne le mouvement sans
     * couvrir le tir qui suit. Muet par défaut depuis le 18/11 (choix de l'utilisateur après les rendus de comparaison) ;
     * le réactiver (case, `--whoosh`) donne [DEFAULT_WHOOSH].
     */
    val whoosh: Double = 0.0,
    /**
     * Dossier de souffles (wav, mp3…) qui remplacent les souffles générés, chacun à son tour dans un ordre mélangé mais
     * toujours le même, comme les bruitages des highlights story (`SfxBank`). Chemin relatif au fichier de configuration ;
     * null ou dossier vide : souffles générés.
     */
    val sounds: String? = null,
) {
    init {
        require(duration.inWholeMilliseconds in 40..600) { "montage.whip.duration doit être entre 40 ms et 600 ms" }
        require(blur in 0.0..0.1) { "montage.whip.blur doit être entre 0 et 0,1" }
        require(whoosh in 0.0..2.0) { "montage.whip.whoosh doit être entre 0 et 2" }
    }

    companion object {
        /** Volume du souffle quand on le réactive : sous le son du jeu (1) et la musique (0,8). */
        const val DEFAULT_WHOOSH = 0.5
    }
}

/**
 * Flou de mouvement, comme les kill montages de TikTok. Mesuré sur trois montages VALORANT de référence : 12 à 21 %
 * d'images floues contre 5 % pour les nôtres, et un flou sur presque chaque coupe (50 sur 54, 29 sur 39, 15 sur 31),
 * bref (une ou deux images à 30 img/s), plus des traînées pendant les flicks.
 *
 * [blend] (désactivé par défaut) : chaque image mélange celles des [shutter] précédents, comme un obturateur resté ouvert ; seul ce qui bouge
 * vite traîne (flick, rampe de vitesse, zoom punch), le HUD et le décor immobile restent nets, les textes (posés après)
 * aussi. Zéro : pas de mélange. 17 ms à 60 img/s : deux images, l'exposition couvre l'intervalle entre elles (360°),
 * comme les références (30 img/s, deux images mélangées). À 33 ms (trois images), une arme qui bouge vite se voyait en
 * copies distinctes plutôt qu'en traînée. Dans un FPS la caméra bouge presque toujours un peu : le mélange dédoublait
 * légèrement presque chaque image (-17 % de netteté sur tout un montage, jugé moins net) ; les vecteurs le remplacent.
 * [shutter] reste la durée d'obturation des vecteurs.
 *
 * [vectors] : flou par vecteurs de mouvement. La rotation de la caméra est mesurée image par image sur chaque plan
 * (dans un FPS, tout le décor glisse d'un bloc), et chaque image est floutée dans le sens de ce glissement, sur la
 * longueur parcourue pendant l'obturateur ([shutter], 17 ms à 60 img/s : la distance entre deux images), plafonnée à
 * [maxLength] (part de la largeur). Une vraie traînée continue, là où le mélange d'images laisse des copies ; ralentis
 * et rampes en tiennent compte. Calculer les images intermédiaires (`minterpolate`) aurait donné le même résultat en
 * 180 fois le temps réel : 6 minutes pour 2 s de 1080p.
 *
 * [cuts] : flou radial sur [cutDuration] de part et d'autre de chaque coupe franche, plus fort sur la coupe, l'image
 * agrandie jusqu'à [cutZoom] (part de sa taille) dans ses copies les plus floues. Pas sur les coupes en whip pan, déjà
 * floutées dans le sens du flick. Au plus [cutShare] du plan de chaque côté : dans un plan d'un temps (0,37 s à
 * 161 BPM), 50 ms par côté floutaient plus d'un quart du plan.
 *
 * Autour de chaque kill, de [clearBefore] avant à [clearAfter] après, le flou par vecteurs est ramené à [clearStrength]
 * de sa longueur : c'est là qu'on lit le kill (l'ennemi qui tombe). Mesuré sur un montage au rythme rapide (R2D2,
 * 73 kills en 30 s) : 40 % des images floues dans les 170 ms après le kill, et le montage paraissait illisible.
 */
@Serializable
data class MotionBlur(
    val enabled: Boolean = true,
    val blend: Boolean = false,
    val shutter: SerialDuration = 17.milliseconds,
    val vectors: Boolean = true,
    val maxLength: Double = 0.08,
    val cuts: Boolean = true,
    val cutDuration: SerialDuration = 50.milliseconds,
    val cutZoom: Double = 0.08,
    val cutShare: Double = 0.08,
    val clearBefore: SerialDuration = 100.milliseconds,
    val clearAfter: SerialDuration = 250.milliseconds,
    val clearStrength: Double = 0.25,
) {
    init {
        require(shutter.inWholeMilliseconds in 0..100) { "montage.motionBlur.shutter doit être entre 0 et 100 ms" }
        require(cutDuration.inWholeMilliseconds in 10..200) { "montage.motionBlur.cutDuration doit être entre 10 et 200 ms" }
        require(cutZoom in 0.0..0.3) { "montage.motionBlur.cutZoom doit être entre 0 et 0,3" }
        require(maxLength in 0.0..0.3) { "montage.motionBlur.maxLength doit être entre 0 et 0,3" }
        require(cutShare in 0.0..0.5) { "montage.motionBlur.cutShare doit être entre 0 et 0,5" }
        require(clearStrength in 0.0..1.0) { "montage.motionBlur.clearStrength doit être entre 0 et 1" }
    }

    /** Images mélangées à [fps] images/s : l'image courante et celles que l'obturateur couvre ; 1 = aucun mélange. */
    fun blendFrames(fps: Int): Int = if (!enabled || !blend) 1 else 1 + Math.round(shutter.inWholeMicroseconds * fps / 1e6).toInt()

    /** Vrai si la rotation de la caméra doit être mesurée pour le flou par vecteurs. */
    val usesVectors: Boolean get() = enabled && vectors && maxLength > 0 && shutter.isPositive()

    /** Images floutées de chaque côté d'une coupe à [fps] images/s ; 0 = aucun flou de coupe. */
    fun cutFrames(fps: Int): Int =
        if (!enabled || !cuts || cutZoom == 0.0) 0 else Math.round(cutDuration.inWholeMicroseconds * fps / 1e6).toInt().coerceAtLeast(1)

    /** Images floutées de chaque côté d'une coupe d'un plan de [frames] images : au plus [cutShare] du plan, au moins une. */
    fun cutFrames(fps: Int, frames: Int): Int {
        val k = cutFrames(fps)
        return if (k == 0) 0 else minOf(k, Math.round(frames * cutShare).toInt().coerceAtLeast(1))
    }
}

/**
 * Couleurs boostées, comme les kill montages de TikTok : le jeu « ressort », couleurs vives accordées au skin, noirs
 * francs. Mesuré sur quatre montages VALORANT de référence : 12 à 38 % de pixels très saturés (saturation HSV > 0,6),
 * 3 à 8 % de noirs, contre 7,5 % et 1,7 % pour un montage sans étalonnage. Les valeurs par défaut amènent ce montage
 * à 38 % et 5,4 %, le haut de la fourchette ; la vibrance pousse d'abord les couleurs ternes, sans brûler le rouge d'un
 * skin. Appliqué à chaque plan avant flashs et textes, qui gardent leur blanc.
 */
@Serializable
data class ColorBoost(
    /** Activées par défaut depuis le 18/11 (« always by default » après les rendus de comparaison) ; on peut les couper. */
    val enabled: Boolean = true,
    /** Vibrance, -2 à 2 : renforce les couleurs ternes plus que les vives. */
    val vibrance: Double = 0.5,
    /** Saturation (1 = inchangée). */
    val saturation: Double = 1.2,
    /** Contraste (1 = inchangé). */
    val contrast: Double = 1.12,
    /** Luminosité ajoutée, -1 à 1 : un peu en dessous de zéro, les noirs deviennent francs. */
    val brightness: Double = -0.02,
    /** Assombrissement des bords, 0 à 1. */
    val vignette: Double = 0.0,
    /** Table de correspondance .cube (ou .3dl), appliquée avant le reste. Chemin relatif au fichier de configuration. */
    val lut: String? = null,
) {
    /** Étalonnage à appliquer ; neutre quand les couleurs ne sont pas boostées. */
    fun grade(): GradeSettings = if (!enabled) GradeSettings()
    else GradeSettings(lut = lut, saturation = saturation, contrast = contrast, vignette = vignette, vibrance = vibrance, brightness = brightness)

    init {
        // Mêmes bornes que l'étalonnage des highlights : une valeur hors bornes est refusée au chargement du profil.
        GradeSettings(lut = lut, saturation = saturation, contrast = contrast, vignette = vignette, vibrance = vibrance, brightness = brightness)
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
    /**
     * Police des libellés, gardée tant qu'elle a tous leurs caractères ; sinon (ダブルキル, 더블 킬, 双杀 en Impact)
     * un repli de Windows qui les a (`VideoFonts`, module editing).
     */
    val font: String = "C:/Windows/Fonts/impact.ttf",
    /**
     * Texte affiché au 2e, 3e… kill d'un même clip ; null = libellés de la langue de l'interface (DOUBLE KILL, TRIPLE
     * KILL… ou DOUBLÉ, TRIPLÉ…, clés `video.streak.*`). Le dernier vaut pour les séries plus longues.
     */
    val multiKillLabels: List<String>? = null,
    /**
     * Compteur de kills à l'écran (« 1… 2… 3… », puis ACE), compté dans chaque plan ou cumulé sur le montage. Désactivé
     * par défaut : il charge l'image, à réserver aux montages qui veulent l'afficher.
     */
    val killCounter: KillCounterMode = KillCounterMode.OFF,
    /**
     * Bandeau du score des rounds (« 7 – 5 », et le numéro du round) pendant chaque plan, lu dans le HUD au moment du
     * kill (événements `round-won`/`round-lost` du détecteur `round-score`, VALORANT). Désactivé par défaut, comme le
     * compteur : il dit où en est la partie, utile pour un montage d'une seule partie, du bruit pour une soirée mêlée.
     * Rien n'est affiché pour une capture sans score lu.
     */
    val roundScore: Boolean = false,
) {
    /** Libellés à incruster : ceux de la configuration, sinon ceux de la langue de l'interface. */
    val labels: List<String> get() = multiKillLabels ?: (2..5).map { tr("video.streak.$it") }
}

/**
 * Ouverture « vitrine », comme les kill montages de référence (12 à 15 s sans kill avant la partie kills) : des plans de
 * [shotBeats] temps pris dans les passages calmes des parties montées, sur les temps de musique qui précèdent le
 * montage (la musique démarre d'autant plus tôt). Chaque passage est pris juste avant le plan d'un kill du montage
 * ([beforeClip] avant ce qu'il montre), au moins [afterEvent] après le kill ou la mort d'avant (l'écran de mort, la
 * fin de round) : le joueur qui avance, l'arme en main, sans combat. Au plus [length], dans la durée maximale.
 */
@Serializable
data class ShowcaseOpening(
    /** Activée par défaut depuis le 18/11 : l'utilisateur l'a retenue après avoir vu les rendus de comparaison. */
    val enabled: Boolean = true,
    val length: SerialDuration = 8.seconds,
    val shotBeats: Int = 2,
    val afterEvent: SerialDuration = 5.seconds,
    val beforeClip: SerialDuration = 1.seconds,
) {
    init {
        require(length.isPositive() && shotBeats >= 1) { "montage.showcase : length positive, shotBeats au moins 1" }
    }
}

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
