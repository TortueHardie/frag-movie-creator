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

## 3. Transitions sur les animations du jeu (fait, à régler sur de vraies parties)

On repère les animations qui reviennent d'un clip à l'autre (rechargement, flick, sprint, sort, grenade), puis on
coupe d'une animation dans un clip à la même animation, au même stade, dans le clip suivant. Le mouvement continue
par-dessus la coupe. Voir `MatchCutter` et `MatchCuts` ; réglages dans `montage.matchCut`.

- Pas de détecteur par type d'animation : la zone de l'arme et des mains est comparée d'une coupe à l'autre, et c'est
  la ressemblance du geste qui décide. Le flick, mouvement de toute la vue, reste l'affaire du whip pan (point 2).
- À régler sur de vraies captures : la zone (`region`, mesurée en 16:9) et le seuil (`minSimilarity`, la ressemblance
  de chaque coupe est écrite au journal).
- Piste : l'ordre des clips est fixé avant les raccords ; le choisir aussi pour rapprocher les clips qui se raccordent.

## 4. Scoreur sensible aux temps morts (fait)

Trois critères de `MontageScorer` : `opening` (délai avant le premier kill), `lull` (plus long passage sans kill, fin
comprise) et `action` (part de chaque plan hors de l'action). Ils comptent dans le choix des variantes du plan, et
`highlights score` les affiche (`ouverture`, `trou`, `action`).

- Constat : quatre kills sur une minute de musique laissent des trous de plus de 10 s, le critère `lull` tombe à 0.
  C'est l'étirement que le point 5 doit corriger.

## 5. Longueur du montage adaptée au nombre de kills

Au lieu d'étirer les clips pour remplir la musique.

- Aujourd'hui, deux endroits poussent à remplir : le critère `fill` de `MontageScorer` (durée / `maxDuration`) et le
  terme `length / maxDuration` de `CutGrid.select`.
- Viser une durée tirée du nombre de kills retenus, `maxDuration` ne restant qu'un plafond.
