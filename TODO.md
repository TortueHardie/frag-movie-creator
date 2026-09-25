# À faire

Pistes pour le montage « tous les kills », dans l'ordre où on compte les traiter.

## 1. Mieux classer les kills (fait)

Un kill suivi de sa propre mort recule dans le classement ; les aces et les clutchs montent. Voir `RoundOutcome` et
`MontagePlanner.rounds` ; réglages dans `KillStyle` (`deathPenalty`, `aceBonus`, `clutchBonus`, `roundGap`).

- Reste ouvert : le clutch est déduit (round survécu, fini sur un multi-kill) faute de connaître le nombre d'alliés en
  vie. À affiner si une source donne les rounds ou l'état de l'équipe.

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
