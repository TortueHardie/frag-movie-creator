# À faire

Pistes pour le montage « tous les kills », dans l'ordre où on compte les traiter.

## 1. Mieux classer les kills

Un kill suivi de sa propre mort recule dans le classement ; les aces et les clutchs montent.

- Les morts sont déjà lues par `outplayed-events` (type `death`), mais `MontagePlanner.groups` ne s'en sert pas.
- Le bonus de spectacle d'un groupe se calcule dans `MontagePlanner.style` (tête, flick, enchaînement) : c'est là que
  la pénalité de mort et le bonus ace / clutch trouvent leur place, avec leurs réglages dans `KillStyle`.
- Reste à définir ce qu'on reconnaît comme un clutch sans connaître le nombre d'alliés en vie.

## 2. Transitions dans le sens du mouvement

Un raccord en whip pan qui reprend la direction du flick déjà mesurée par `KillInspector`.

- `FlickMeter.speeds` calcule un décalage signé (`dx`, `dy`) puis n'en garde que la norme : la direction est perdue.
  Il faut la conserver jusqu'à `KillTraits`.
- Le raccord lui-même se construit dans `MontageRenderBuilder`.

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
