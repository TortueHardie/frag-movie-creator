# À faire

Priorité : un moteur de montage kills irréprochable et complet, capable de sortir des montages de niveau
professionnel. Le reste (interface, nouveaux types de montage, publication) passe après.

Pistes pour le montage « tous les kills », dans l'ordre où on compte les traiter.

## 1. Mieux classer les kills (fait)

Un kill suivi de sa propre mort recule dans le classement ; les aces et les clutchs montent. Voir `RoundOutcome` et
`MontagePlanner.rounds` ; réglages dans `KillStyle` (`deathPenalty`, `aceBonus`, `clutchBonus`, `roundGap`).

- Clutch : annoncé par le jeu quand il le transmet (VALORANT via Outplayed, `killStyle.clutchEvent`) ; sinon déduit
  (round survécu, fini sur un multi-kill), qui en compte trop.

## 2. Transitions dans le sens du mouvement (fait)

Un raccord en whip pan qui reprend la direction du flick déjà mesurée par `KillInspector`. `FlickMeter.direction`
garde le sens du balayage dans `KillTraits.direction` ; `MontageRenderBuilder.whips` choisit les coupes et
`whipStages` construit le raccord (réglages : `montage.whip`).

- Piste : un bruitage de souffle (whoosh) sur la coupe renforcerait l'effet.

## 3. Raccords sur la pose de l'arme (fait)

Les rechargements et changements d'arme n'apparaissent presque jamais autour des kills : ce qui revient, c'est la pose
de l'arme. Une coupe se raccorde quand les deux plans la tiennent : l'arme reste en place, le décor change. Voir
`MatchCutter` et `ScopeCuts` ; réglages dans `montage.matchCut`.

- WARDOGS (`pose: aim`) : la visée, le viseur au centre. Kill à la hanche écarté (symétrie, `minSymmetry`) : 5 sur 5
  écartés, 1 kill visé sur 14 écarté à tort. La symétrie est aussi vérifiée image par image : caméra immobile, le décor
  ressemblait encore à la visée alors que le joueur avait incliné son arme. Dernier montage : 5 raccords sur 18, note
  0,908 (0,910 sans raccord), tous vérifiés à l'image.
- VALORANT (`pose: rest`, profil `valorant.yaml`) : l'arme au repos à la hanche, zone serrée sur l'arme et comparée en
  entier. Une capacité (les mains) se raccordait à un couteau, ressemblance 0,62, plus que des raccords justes (0,53 à
  0,57) : l'arme en main se lit désormais dans le HUD (icône du chargeur, 0,86 à 1 avec une arme, 0,68 au plus sans).
  Dernier montage : 6 raccords sur 19, tous justes (fusil sur fusil, une fois la même arme), note inchangée.
- La pose est mesurée avant la planification (`MatchCutter.inspect`). Les groupes d'importance voisine échangent leurs
  places pour mettre côte à côte ceux qui se raccordent (`MontagePlanner.pairUp`, hors drop et accroche), puis le kill
  peut changer de temps dans son plan (`MontagePlanner.aimFit`) si la note n'y perd pas plus que les raccords ne valent
  (`MATCH_VALUE`, 0,005 par raccord).
- Limite : la fin du plan sortant. Le joueur baisse son arme (ou rejoue une capacité) 0 à 0,8 s après le kill ; il
  faudrait des plans plus courts après le kill, ce qui allonge l'attente avant le kill suivant.
- L'échange de places cherchait un échange à la fois et s'arrêtait vite (5 à 6 coupes raccordables côte à côte) : il
  essaie aussi les paires d'échanges quand aucun n'aide seul.
- Piste : l'icône du chargeur est mesurée sur des captures 21:9 ; à vérifier sur une capture 16:9.

## 4. Scoreur sensible aux temps morts (fait)

Trois critères de `MontageScorer` : `opening` (délai avant le premier kill), `lull` (plus long passage sans kill, fin
comprise) et `action` (part de chaque plan hors de l'action). Ils comptent dans le choix des variantes du plan, et
`highlights score` les affiche (`ouverture`, `trou`, `action`).

- Constat : quatre kills sur une minute de musique laissent des trous de plus de 10 s, le critère `lull` tombe à 0.
  C'est l'étirement que le point 5 doit corriger.

## 5. Longueur du montage adaptée au nombre de kills (fait)

Au lieu d'étirer les clips pour remplir la musique. `MontagePlanner.targetDuration` tire une durée visée des groupes
retenus (réglages : `montage.length`), `maxDuration` ne restant qu'un plafond ; `CutGrid.select` et le critère `fill`
visent cette durée. Si elle ferait perdre un clip, le début d'un multi-kill, une réaction ou un ralenti de flick face
au montage plein, elle s'allonge par paliers.

- Mesuré sur deux musiques de test : 6 groupes passent de 48 s (trou de 10 s) à 20 s (trou de 3,5 s), et le montage
  adapté garde tous les groupes là où le montage plein en perdait jusqu'à 5 sur 20.
- À vérifier sur de vraies parties : `perClip` (2,5 s) et `min` (12 s) sont des estimations.

## 7. Monter sur la musique, pas sur la grille (en cours, branche `montage-sur-la-musique`)

Demande : « le moteur devrait reconnaître au début les notes de piano et caler les kills par-dessus ». Fait : saillance
de chaque temps (`MusicAnalysis.salience`) et découpage sur les notes qui ressortent (`CutGrid.tileOnNotes`).

- Fausse piste écartée : la grille n'était pas décalée. Le piano de « Rome Is Burning » frappe sur le temps et au
  contretemps ; les basses sur le temps. Recaler la grille sur l'attaque dominante d'une section ne se déclenchait pas.
- Fait : cadence continue par section (`CutGrid.cadence`), coupes calées sur les notes (`tileOnNotes`), ralenti sans
  place réservée hors drop, multi-kills étalés séparés au montage (`MontagePlanner.split`, `splitGap`). Drop de
  « Rome Is Burning » : un kill par temps au rythme rapide (avant : un toutes les 3,8 s), un toutes les 1,6 s au normal.
- [ ] Rythme normal : les doublés d'environ 1 s prennent 8 temps dans la drop (5 nécessaires), et le critère « rythme »
  du scoreur reste bas (0,13) : intro et drop ont encore des plans de longueurs voisines.
- Fait : changements de son (`MusicAnalyzer.timbreChanges` : instrument qui entre, voix, coupure) ajoutés aux notes
  dans `MusicAnalysis.salience` ; rapport `onMusicMoment`. Couverture au rythme rapide : 9/10, 16/19, 25/38 moments.
- [ ] Notes entre les temps : mesurées sur six morceaux, 0 à 3 contretemps qui ressortent par morceau. Pas de coupe ni
  de kill au demi-temps pour l'instant (la grille est en temps entiers) ; à reprendre sur une musique syncopée.

## 6. Se mesurer à des montages de référence (en cours)

Quatre kill montages VALORANT de TikTok dans `exemples/` (non versionné), mesurés le 02/10 (16:9, 1024x576, 30 img/s,
20 à 50 s de contenu avant l'écran de fin de TikTok). Structure commune aux quatre :

- Une ouverture de 12 à 15 s sans kill : animations d'inspection d'arme et de couteau (souvent sans HUD), les paroles
  de la musique écrites mot par mot en grand au centre de l'image, un plan par mot ou presque. Puis la partie kills,
  HUD visible, jusqu'à la fin de la musique.
- Plans courts : médiane de 0,37 à 0,67 s selon la vidéo (le nôtre : des plans de plusieurs temps).
- Couleurs poussées, accordées au skin de l'arme (violet et rose, rouge, cyan) : 12 à 38 % de pixels très saturés
  contre 7,5 % dans notre montage kills, noirs plus francs (3 à 8 % de l'image contre 1,7 %), contraste plus fort
  dans trois sur quatre. Notre montage kills n'a aucun étalonnage (`GradeSettings` ne sert qu'aux highlights).
- Flou de mouvement partout : 12 à 21 % d'images floues (transitions en flou directionnel, whips, zooms) contre 5 %.
- Effets vus à l'image : texte des paroles en mouvement (glitch, couleur du skin, parfois derrière l'arme), flashs
  colorés, vignettage, ondes et halos, secousses, zooms rapides.
- Calage sur la musique non mesurable sur ces copies : leur piste audio est déclarée à 44,1 kHz mais encodée vers
  24 kHz (lue telle quelle, la musique dure deux fois moins que l'image). Il faudrait des copies propres.

Pistes, de la plus simple à la plus lourde :
- [x] Couleurs boostées en option (`montage.colors`, `--colors boost`, case de la fenêtre de montage) : vibrance,
  saturation, contraste, luminosité, appliqués à chaque plan avant flashs et textes. Valeurs par défaut réglées sur les
  références : 38 % de pixels très saturés et 5,6 % de noirs sur un vrai montage (7,5 % et 1,7 % avant).
  - [ ] Couleurs accordées au skin (teinte dominante de l'arme poussée, le reste un peu désaturé) : à voir.
  - [ ] L'activer par défaut si le rendu plaît.
- [x] Flou de mouvement (`montage.motionBlur`, activé par défaut, `--no-motion-blur`) : images mélangées sur 17 ms
  (traînées sur ce qui bouge vite) et flou radial de 50 ms sur chaque coupe franche. Coupes floutées : 31 sur 36 (11
  avant), comme les références (48 à 93 %).
  - [x] Flou par vecteurs de mouvement (`CameraMotion`, `VectorBlur`) : rotation de la caméra mesurée sur chaque plan,
    flou gaussien orienté de la longueur parcourue (`gblur` piloté par `sendcmd`). 13 % d'images floues (références
    13 à 17 %, 6 % avant), toutes les coupes floutées. `minterpolate` : 180 fois le temps réel, écarté.
    - [ ] Arme et HUD suivent la caméra mais sont floutés avec le décor pendant un flick (les références aussi) :
      les protéger demanderait un masque de l'arme.
- [x] Corrigé en passant : chaque plan du montage kills gardait parfois une image de trop (`trim=duration` sur des
  horodatages hors grille), 11 images (0,18 s) sur 31 plans, la vidéo dépassait le son et les derniers kills tombaient
  après leur temps. Plans coupés en nombre d'images désormais.
  - [ ] Même schéma dans `StoryRenderBuilder` (highlights story, `trim=duration` par plan) : à vérifier.
- [x] Rythme rapide (`montage.fast`, `--fast`, case « Rythme rapide ») : un kill par temps, plans de 0,37 s à
  161 BPM (médiane des références : 0,37 à 0,67 s) au lieu de quatre temps (1,5 s).
  - [ ] L'activer par défaut si le rendu plaît, ou seulement au-dessus d'un tempo.
  - Premier essai jugé illisible (74 kills, R2D2) : 40 % d'images floues juste après le kill (flou de la coupe
    suivante, 0,17 s après le kill, et traînée), échanges montrant l'écran de mort, animations du personnage prises
    pour des mouvements de caméra. Corrigé (zone nette autour du kill, flou de coupe proportionné, échanges écartés,
    mesure sur le décor seul) : 18 % après le kill.
  - Deuxième retour : image moins nette, et des kills coupés (le plan commençait après le kill). Corrigé : mélange
    d'images retiré (-17 % de netteté), vecteurs au-delà de 10 px seulement ; kills calés sur le killfeed lu à 30 img/s
    (`kill-confirm`, `killStyle.confirmEvent`) au lieu de l'annonce d'Outplayed, qui prenait une balle de la rafale
    d'après le kill pour celle qui tue (18 kills sur 57).
  - [ ] Ennemis minuscules en 21:9 (quelques pixels au fond d'un couloir) : 0,2 s ne suffit pas à les trouver.
    Pistes : zoom qui commence avant le kill, vers le réticule ; recadrage plus serré au rythme rapide.
  - [ ] Plusieurs plans par kill dans les sections lentes (les références coupent aussi sans kill : passages entre
    deux kills, inspection d'arme).
- [ ] Ouverture « vitrine » : plans sans kill (inspection d'arme, passages calmes) avant la drop, au lieu d'un kill
  d'accroche. Demande de repérer ces passages dans la capture (arme en main, pas de combat).
- [ ] Paroles mot par mot : transcription de la musique (whisper, déjà utilisé pour les sous-titres) et texte calé
  sur chaque mot pendant l'ouverture.
- [ ] Refaire les mesures de calage sur des copies à l'audio intact.

---

# Améliorations

## Montage kills : pistes ouvertes
- [ ] Whoosh sur les whip pans : brancher les SFX de `StoryRenderBuilder` (`WHOOSH_SOURCE_*`, `SfxBank`) dans `MontageRenderBuilder.whips`.
- [ ] Valider `perClip` (2,5 s) et `min` (12 s) de `montage.length` sur de vraies parties.
- [ ] Vérifier l'icône du chargeur VALORANT sur une capture 16:9 (mesurée en 21:9 seulement).
- [ ] Fin du plan sortant : couper plus tôt après le kill seulement quand la coupe suivante est un raccord sur la pose.
- [ ] Clutch réel : compter les alliés en vie à partir des morts alliées du killfeed.
- [ ] VALORANT : écarter du montage les kills à la capacité (molly, flèche, drone…), peu lisibles à l'image, sauf les
  « beaux » kills aux ultis d'arme (couteaux de Jett, Tour de force de Chamber…). Piste : lire l'icône d'arme/capacité
  de la ligne du killfeed (`KillfeedDetector`) et une liste blanche des capacités gardées dans le profil.

## Interface : retoucher les moments
- [ ] Ajuster début/fin d'un moment (poignées sur `TimelineCard` ou boutons ±1 s).
- [ ] Ajouter un moment à la main en cliquant sur la timeline.
- [ ] Réordonner les clips par glisser-déposer (`ClipOrder` existe côté moteur).
- [ ] Exclure ou forcer un kill précis dans le montage kills.
- [ ] Supprimer / oublier une analyse dans `LibraryView`.

## Flux de travail
- [ ] File d'attente de jobs (aujourd'hui un seul job à la fois via `runTask`).
- [ ] Dossier surveillé : enchaîner l'export automatiquement après l'analyse.
- [ ] Préréglages d'export (« TikTok », « YouTube court », « complet »).

## Qualité de détection
- [ ] Jeu de vérité terrain versionné (timecodes annotés) + test de non-régression précision/rappel, lancé à la main.
- [ ] Profils pour d'autres jeux (CS2, Apex, Fortnite) avec `hud-template` / `killfeed` / `ocr-log`.

## Entretien
- [x] Mettre à jour `CLAUDE.md` et le versionner.

# Nouvelles fonctionnalités

## Nouveaux types de montage
- [ ] Clip unique « meilleur moment » : une vidéo 9:16 de 15 à 30 s par moment (accroche, sous-titres, punch-in).
- [ ] Récap de soirée : plusieurs captures, intercalaire par partie (« Partie 3 – 14 kills »).
- [ ] Montage « fails / morts » : morts du killfeed/Outplayed, musique comique, ralenti sur la mort.
- [ ] Montage « réactions » : segments `laughter`/`shout` de YAMNet, micro en avant, sous-titres whisper.
- [ ] Montage « onetaps » : que des kills en un seul tir à la tête, enchaînés très vite comme les edits TikTok. Toutes
  les armes comptent : un tir, un headshot, un kill.
  - Deux choses à savoir par kill : le headshot et le nombre de tirs. Aucune ne doit dépendre d'Outplayed seul, sinon
    le montage n'existe pas pour une capture OBS ou ShadowPlay (et jamais pour WARDOGS).
  - Commencé : détecteur `game-sounds` (module `analysis`). Profil VALORANT : `game-shots` émet un `shot` par tir du
    joueur (toujours, Outplayed ne donne pas les tirs) ; `headshot-sound` reconnaît le son de l'impact à la tête sur
    gabarit, en secours d'Outplayed. `MontagePlanner.shotCounts` compte les balles de chaque kill jusqu'au tir qui tue
    (`KillTraits.shots`, `oneTap`, réglage `killStyle.oneTapWindow`) ; les stats comptent les kills à la tête entendus.
    Testé sur des signaux synthétiques seulement.
  - Fait : gabarit `config/templates/valorant/headshot.wav` tiré d'une vraie capture, seuil 0,7 : 4 headshots sur 8
    reconnus, aucun faux sur 14 kills au corps (deux parties du 25/09).
    - [ ] Le revoir sur d'autres parties (pris dans l'une des deux mesurées) ; la moitié des headshots manqués.
      Revu sur une partie du 01/10 sans Outplayed (killfeed) : 14 sons reconnus en 3 min, presque tous loin d'un kill
      (impacts à la tête sans kill), aucun à moins de 250 ms des 4 kills : les 2 kills à la tête manqués, aucun faux.
  - Mesuré sur deux parties : les tirs isolés sont entendus, pas les rafales du Vandal (jusqu'à 4 balles sur 5
    manquées, 1706 « tirs » par partie dont des sons qui n'en sont pas). Deux sprays passaient pour des one taps.
  - Fait : détecteur `ammo-counter` (analysis-vision) : le compteur de munitions du HUD relu pendant l'analyse autour
    de chaque kill (`dependsOn`, nouvelle phase après les détecteurs de kills), événements `hud-shot` préférés aux tirs
    entendus (`MontagePlanner.hudShotCounts`). 111 balles sur 111 autour de 14 kills ; 5 vrais one taps sur 138 kills
    à la tête de 16 parties, qu'aucun tir entendu ne désignait (le son du kill et de l'impact comptait comme des tirs),
    et les deux faux écartés. Recherche, stats et montage s'en servent.
    - Corrigé : en Combat à mort, la recharge animée du chargeur à chaque kill comptait trois balles de plus (aucun
      one tap trouvé dans ce mode) ; elle est reconnue à son turquoise et ignorée.
    - [ ] Zone mesurée en 3440x1440 : vérifier en 16:9.
    - [ ] WARDOGS et captures sans compteur lisible : rester sur le son, moins sûr dans les rafales.
  - [ ] Peu de one taps (2 dans une partie) : la durée minimale (`oneTaps.min`, 6 s) étire les plans sur une grille
    grossière ; baisser le minimum ou garder des plans courts quitte à faire un montage de 2 s.
  - [ ] Icône headshot du killfeed : le profil la dit absente des lignes du joueur, à revérifier.
  - Sans source fiable pour le headshot, repli sur « un seul tir » : le montage reste possible, un peu moins strict
    (un one-shot au corps à l'Operator passerait). Le dire dans l'interface plutôt que de ne rien proposer.
  - Fait : filtre « One taps » de la recherche (`MomentQuery.oneTaps`, `search --onetaps`) et montage onetaps
    (`MontageOptions.oneTaps`, `montage --onetaps`, case du dialogue). `OneTaps.select` garde les kills d'une balle à
    la tête ; `MontageSettings.forOneTaps` (réglages `montage.oneTaps`) : un kill par plan, plans de 2 temps, ni
    ralenti ni accroche. Testé sur un plan synthétique : 12 kills en moins de 15 s. Interface non compilée ici.
  - Fait : plans d'un seul temps (`cuts.singleBeat`, `MontageClip.leadIn`) : le kill sur le temps qui ouvre le plan, la
    coupe avancée de `minLead` sur la fin du précédent ; deux temps au-delà de 109 BPM. Premier rendu réel : 0,12 s
    après la balle coupait avant la chute de l'ennemi, passé à 0,3 s (0,25 s avant) ; kill calé sur la balle du
    compteur (`KillTraits.fatal`), l'instant d'Outplayed la plaçant parfois sur la coupe.
    - [ ] À voir sur un vrai rendu : 0,3 s de visée avant l'impact suffit-il à lire le one tap ?
  - Fait : `MusicChoice` favorise les musiques qui gardent des plans courts (`MusicPace`, mesuré sur le plan obtenu).
  - Habillage possible : flash blanc ou punch-in sur l'impact, son du headshot mis en avant, compteur qui défile.

## Habillage
- [ ] Webcam en incrustation : seconde source synchronisée, disposition 9:16 webcam au-dessus du jeu.
- [ ] Compteur de kills à l'écran (« 1… 2… 3… ACE ») et bandeau de score de round.
- [ ] Intro/outro personnalisées (logo, pseudo, fond) définies dans le profil.
- [ ] Miniature automatique : image du meilleur kill + punch-in + texte, PNG prêt pour YouTube.

## Musique
- [x] Bibliothèque musicale : analyser un dossier une fois (BPM, drop, sections), choisir automatiquement la musique selon le nombre de kills et la durée visée.
  `MusicLibrary` (analyses gardées dans `<workDir>/cache/music`) et `MusicChoice.rank` (note du plan × part des groupes
  gardés), `MusicHistory` : une musique des 3 derniers montages perd 0,03 de note, pour que les musiques proches tournent.
- [x] Caler le drop sur le kill principal (musique démarrée en cours si nécessaire).
  Déjà fait en montée en puissance ; ajouté : montée minimale avant la drop (`cuts.dropLead`) et, en ordre
  chronologique, passage de la musique choisi pour que la drop tombe sur le meilleur groupe (ou un qui le vaut).

## Publication et partage
- [x] Publication YouTube : titre, description et tags générés depuis les événements, tout modifiable. Envoi manuel
  assisté par défaut (textes à copier, page d'envoi ouverte) ; envoi direct par l'API en option (`publish.youtube.api`,
  désactivé : projet Google à créer, vidéos verrouillées en privé tant qu'il n'est pas audité).
- [ ] Export TikTok (Content Posting API, brouillon dans la boîte de réception) : demande une application TikTok validée.
- [x] Profils de plateforme : durée max, débit, loudness, zones de sécurité de l'UI TikTok pour le texte.
  `PlatformProfile` (tiktok, shorts, reels, youtube, surchargeables dans app.yaml), `--platform`, choix « Plateforme ».
  Piste : replacer aussi les éléments du HUD (`vertical.hud`) dans la zone sûre — score et capacités tombent sous l'interface de TikTok.

## Analyse et suivi
- [x] Statistiques par soirée : kills par partie, meilleur round, évolution dans le temps.
  `Statistics` (pipeline), `HighlightPipeline.statistics`, commande `stats`, vue « Statistiques ».
- [x] Recherche dans la bibliothèque (« tous mes aces ») et montage à partir du résultat.
  `MomentQuery`, `HighlightPipeline.search`, `MomentPick` (`MontageOptions.onlyKills`), commande `search`, vue « Rechercher ».
  L'arme (« à l'Operator ») n'est pas connue : ni VALORANT ni Outplayed ne la transmettent ; il faudrait lire l'icône d'arme du killfeed.
- [x] Clutchs réels : les événements `clutch` de VALORANT (via Outplayed) remplacent le clutch déduit (`killStyle.clutchEvent`),
  qui en comptait 3 pour 1 réel. Parties analysées avant : clutchs inconnus, « Mettre à jour les analyses » / `stats --refresh`.

## Plus ambitieux
- [ ] Modèle ML personnalisé entraîné sur les moments gardés/décochés des sessions.
- [ ] Lecteur intégré : aperçu vidéo, scrubbing sur la timeline, rendu d'aperçu basse résolution avant l'export.
