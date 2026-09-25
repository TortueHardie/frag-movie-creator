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

## 3. Transitions sur les animations du jeu

On repère les animations qui reviennent d'un clip à l'autre (rechargement, flick, sprint, sort, grenade), puis on
coupe d'une animation dans un clip à la même animation, au même stade, dans le clip suivant. Le mouvement continue
par-dessus la coupe.

- Placé juste après le point 2 : les deux touchent aux transitions.
- Le flick est déjà mesuré ; les autres animations demandent un nouveau détecteur (sans doute par gabarits d'image,
  comme `hud-template`).

## 4. Scoreur sensible aux temps morts

- Délai avant le premier kill.
- Plus long passage sans kill.
- Part de chaque plan passée hors de l'action.

À ajouter comme critères de `MontageScorer`, à côté de `sync`, `variety`, `coverage`…

## 5. Longueur du montage adaptée au nombre de kills

Au lieu d'étirer les clips pour remplir la musique.

- Aujourd'hui, deux endroits poussent à remplir : le critère `fill` de `MontageScorer` (durée / `maxDuration`) et le
  terme `length / maxDuration` de `CutGrid.select`.
- Viser une durée tirée du nombre de kills retenus, `maxDuration` ne restant qu'un plafond.
