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

## 3. Raccords visée sur visée (fait, 4 coupes sur 18 sur le dernier montage)

Sur les parties WARDOGS, les rechargements et changements d'arme n'apparaissent presque jamais autour des kills : le
geste qui revient, c'est la visée (le joueur épaule avant le kill et baisse son arme 0 à 0,5 s après). Le plan sortant
s'arrête donc avant qu'il ne baisse son arme, le plan entrant commence quand il a déjà épaulé, et le viseur reste au
centre par-dessus la coupe. Voir `MatchCutter` et `ScopeCuts` ; réglages dans `montage.matchCut`.

- Un kill tiré à la hanche est écarté (symétrie du bas de la zone, `minSymmetry`) : 5 sur 5 écartés, 1 kill visé sur
  14 écarté à tort (décor très dissymétrique derrière l'arme).
- La visée est mesurée avant la planification (`MatchCutter.inspect`) : quand deux plans voisins visent, le kill peut
  changer de temps dans son plan pour qu'il reste moins à ralentir (`MontagePlanner.aimFit`), plan par plan, si la note
  du montage n'y perd pas plus que les raccords gagnés ne valent (`MATCH_VALUE`, 0,005 par raccord). Sur le dernier
  montage : 4 raccords au lieu de 3, note inchangée ; 6 sans arbitrage, mais premier kill à 2,7 s au lieu de 1,6 s et
  plus long passage sans kill de 7,6 s au lieu de 6 s.
- Limite restante : la fin du plan sortant. Le joueur baisse son arme 0 à 0,5 s après le kill ; il faudrait des plans
  plus courts après le kill, ce qui allonge l'attente avant le kill suivant.
- Piste : l'ordre des clips est fixé avant les raccords ; le choisir aussi pour rapprocher les clips qui se raccordent.

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
