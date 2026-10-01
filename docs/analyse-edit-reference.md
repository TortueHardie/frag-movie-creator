# Analyse d'un edit de référence : « Unranked → Radiant »

Edit TikTok vertical d'un streamer VALORANT (576x1024, 30 img/s, 58,8 s), pris comme modèle pour le montage kills.
Analyse faite image par image (1 763 images) et son par son (trames de 6 ms), avec les mesures décrites en fin de
document. Les instants sont en secondes depuis le début de la vidéo ; « image » = 1/30 s.

Ce qui intéresse le moteur, par ordre d'importance :

1. **Chaque frappe forte de la musique reçoit un événement à l'image** : un kill, une coupe ou un flash. Les frappes
   faibles ne reçoivent rien. C'est ce qui donne l'impression que « les kills tombent sur les drums ».
2. **La transition lumineuse** : l'image entière (facecam comprise) monte vers le blanc en 2 images, culmine sur la
   coupe, redescend en 3 à 4 images. Plus de 20 fois dans la vidéo, sur les coupes fortes.
3. **Des plans d'un kill**, coupés 0 à 150 ms avant le kill (parfois pile dessus), dans le drop.
4. **Des effets d'impact** qui anticipent la frappe : lignes de vitesse, surexposition, flou.

## 1. Mise en page

- Écran partagé : facecam en haut (0 à 36 % de la hauteur), jeu en bas (36 à 100 %), recadré au centre de la capture
  16:9 sur toute sa hauteur (les barres du HUD restent visibles : score en haut, vie, capacités, munitions en bas).
- Étiquette « RANK: <RANG> » à la jonction des deux zones, couleur du rang. Elle change avec la progression :
  Unranked (11,3 s), Platinum (16,0), Diamond (19,0), Ascendant (25,25), Immortal (29,8), Radiant (41,5).
- Filigrane TikTok sur le jeu, à droite (ajouté par TikTok à l'export, pas par le monteur).

## 2. Structure

| Temps | Acte | Plans | Son |
|---|---|---|---|
| 0 → 11,3 | Accroche parlée : message du chat en carton, sous-titres mot à mot (mot-clé coloré : « LESS SKILLED » en rouge), phase d'achat | 1 plan de 11 s, flou d'ouverture (0,4 → 0,6) | voix seule, pas de musique |
| 11,3 → 16,0 | Montée : un round en Unranked, kills en continu, quelques jump cuts | 0,4 à 3 s | la musique entre (143 BPM), basses légères |
| 16,0 → 24,4 | Corps : Platinum puis Diamond, un ou deux kills par plan | 0,3 à 1,8 s | basses pleines |
| 24,4 → 26,3 | **Break** : les basses tombent (−15 dB), flash « Ascendant » à 25,25 | 2 plans | basses quasi absentes |
| 26,3 → 29,9 | **Drop** : un kill par frappe forte, effets sur chaque coupe | 0,15 à 0,8 s | basses de retour d'un coup à 26,35 |
| 29,9 → 35,4 | Immortal, round gagné (« WON ») | 0,6 à 1,6 s | |
| 35,4 → 46,9 | Dernier round (« LAST ROUND BEFORE SWAP », « ONE MORE GAME », « AFTER THIS ») puis Radiant | 1 à 4 s | musique + voix |
| 46,9 → 58,8 | Dénouement : WON, incrustation d'un autre streamer (« GG »), VICTORY, nom de la carte (SPLIT) en flou, écran de promotion RADIANT #439 | écrans fixes de 1 à 3,4 s | la voix repasse devant |

## 3. Musique et calage

### Tempo et grille

- 143 BPM (croche = 209,8 ms), tempo et phase stables de 11 à 49 s : la musique n'est ni coupée ni recollée, le
  monteur coupe les images, pas la musique. La phase locale, mesurée par fenêtres de 2,5 s, ne bouge que dans le
  break (24 → 27 s), où il n'y a presque plus de charleston pour la mesurer.
- La grosse caisse suit un motif syncopé (écarts de 627, 627, 418 ms : 1,5 + 1,5 + 1 temps).

### Les coupes et les kills ne sont PAS calés sur la grille

Mesuré sur 44 coupes et 32 kills, contre la grille des croches : 43 % des coupes et 34 % des kills tombent à 40 ms ou
moins d'une croche, contre 38 % au hasard. Il n'y a donc aucun calage systématique sur la grille. Hors du drop, le
monteur coupe sur l'action (le kill, la fin d'une animation), pas sur le temps.

### Dans le drop, chaque frappe forte reçoit un événement

Attaque mesurée sur chaque croche du drop (montée d'énergie en dB, basses 30 à 120 Hz) et ce qui se passe à l'image :

| Croche | Instant | Basses (dB) | À l'image |
|---|---|---|---|
| 125 | 26,38 | **22,6** | kill (tir 26,33, crâne 26,37) + surexposition |
| 127 | 26,80 | 11,6 | kill (26,70), bandeau FLAWLESS |
| 132 | 27,85 | **14,2** | kill aux couteaux (27,90) |
| 133 | 28,06 | **25,2** | coupe (28,10) |
| 135 | 28,48 | 9,5 (aigus 27,0) | pic de la transition lumineuse (28,50 → 28,57) |
| 136 | 28,69 | **10,6** | kill (crâne 28,70) |
| 139 | 29,32 | **12,0** | coupe (29,33) |
| 141 | 29,74 | 5,4 (aigus 18,4) | kill (29,80) + transition lumineuse |

Les croches faibles (moins de 5 dB : 27,01, 27,43, 28,90, 29,53) ne reçoivent rien. Les événements tombent à ±60 ms
de la frappe (2 images), quelques-uns à 100 ms.

Limite de la mesure : sans la musique isolée, le tir du jeu se mêle aux basses ; une partie des attaques mesurées
vient peut-être du tir lui-même. L'effet recherché est de toute façon celui-là : le tir qui tue et la frappe ne font
qu'un.

### Son du jeu

Le jeu reste audible sous la musique dans toute la partie kills (tirs, notifications de kill, capacités). Autour des
transitions lumineuses, le son s'éclaircit (centroïde spectral +250 Hz sur 100 ms, aigus +2,4 dB) après un léger creux
juste avant (−4 dB dans les médiums 200 ms avant) : un bruitage d'impact probable, à confirmer à l'oreille.

## 4. Coupes

- 42 vraies coupes (51 détectées, 9 étaient des flashs de 3 images).
- Partie jeu (11 → 48 s) : plans de 0,73 s en médiane (20e centile 0,38 s, 80e centile 1,56 s).
- Pas d'accélération continue : plan moyen 1,10 s dans la première moitié, 1,16 s dans la seconde. Le drop concentre
  les plans courts.
- Délai entre la coupe et le kill dans le drop : 0 à 150 ms (28,10 : le plan s'ouvre crâne déjà affiché ; 27,47 → kill
  27,65). Le moteur garde aujourd'hui au moins 700 ms (`cuts.minLead`).
- La facecam suit la même coupe que le jeu (même source, même instant) ; elle a aussi ses propres jump cuts (31,37).

## 5. Effets, image par image

### Transition lumineuse (l'effet signature)

Profil médian sur 17 occurrences, luminance du jeu par rapport au plan (sur 255), images −4 à +10 autour du pic :

```
-4  -3  -2   -1    0   +1   +2  +3  +4  +5
 2   1  32  111  138  115   72  28  13  20
```

- Montée en 2 images, pic sur la coupe, descente en 3 à 4 images : 5 images au-dessus de 25 % du pic (de 3 à 13).
- Ce n'est pas un fondu au blanc franc : l'image reste lisible dessous (surexposition, contraste écrasé), avec un flou
  sur les 2 ou 3 images du pic (netteté divisée par 3 ou plus).
- Elle touche tout l'écran, facecam comprise.
- Placée : sur chaque montée de rang (16,0, 25,25, 29,8, 41,8), sur les gros kills (18,3, 19,5, 21,3, 23,2, 32,0,
  37,7, 42,8, 44,6), et sur presque chaque coupe du drop (26,4, 27,55, 28,55).
- Version longue (10 images, 39,0 → 39,3 s, et 46,6 → 46,9 s) pour les grands moments : le dernier round, le round
  gagnant.

### Surexposition sans coupe

26,40 → 26,63 : sur un kill en milieu de plan, l'image s'éclaircit d'un coup (+124) puis redescend sur 6 images. Même
forme que la transition, sans coupe.

### Lignes de vitesse

Traits blancs fins qui partent du centre (façon manga), sur 3 à 5 images : 28,40 → 28,53 et 44,80 → 44,87.
Posées environ 250 ms avant la frappe, elles enjambent la coupe et enchaînent sur la transition lumineuse. (47,1 → 48,0
est un faux positif : les lignes d'un décor.)

### Étirement en bandes

26,60 et 28,60 → 28,67 : pendant la descente de la transition lumineuse, l'image est striée de bandes verticales
étirées (effet « pixel stretch »). Peu fréquent ; le bandeau FLAWLESS apparaît aussi avec ce glitch.

### Zoom

Zoom brusque ×1,14 à 29,70 (punch-in avant la transition de 29,8) et 54,53 (écran de promotion). Pas de zoom sur chaque
kill.

### Ce qui n'y est pas

Pas de ralenti (les images figées de 45 à 57 s sont des écrans fixes), pas de whip pan, pas de raccord sur la pose de
l'arme, pas de secousse de caméra mesurable.

## 6. Textes

- Sous-titres de la voix mot à mot ou par 2 à 3 mots, police grasse condensée blanche, ombre portée, au tiers inférieur
  de la facecam ; mots-clés en couleur (rouge, jaune, couleur du rang).
- Cartons de contexte en haut du jeu : « LAST ROUND BEFORE SWAP » (35,4 → 41,7), message du chat (1,0 → 8,0).
- Écrans du jeu gardés comme cartons : BUY PHASE, WON, FLAWLESS, VICTORY, écran de promotion.

## 7. Écart avec le moteur

| Ce que fait le monteur | Moteur aujourd'hui |
|---|---|
| Un événement (kill, coupe ou flash) sur chaque frappe forte du drop | Plans sur une grille de 2/4/8/16 temps, kill calé sur un temps (ou un contretemps fort via `speedRamp.onHits`) |
| Coupe 0 à 150 ms avant le kill dans le drop | `cuts.minLead` = 700 ms (300 en onetaps) |
| Transition lumineuse de 5 images centrée sur la coupe, avec flou | `flash` : fondu depuis le blanc de 60 ms au début du plan, sans montée avant la coupe |
| Effets sur presque chaque coupe du drop, rares ailleurs | Flash aux coupes fortes, même densité partout (`effectDensity`) |
| Lignes de vitesse, surexposition en milieu de plan, étirement en bandes | absents |
| Écran partagé facecam / jeu, étiquette de rang, sous-titres mot à mot | absents du montage kills (sous-titres dans le style `story`) |

## Mesures

Scripts dans la session d'analyse (non versionnés) : FFmpeg pour l'extraction, NumPy pour les mesures.

- Image : luminance et différence d'image par zone (facecam / jeu), netteté (variance du laplacien), translation par
  corrélation de phase, facteur de zoom par recadrage central, colonnes de discontinuité (bandes), orientation des
  gradients clairs par rapport au centre (lignes de vitesse), décalage rouge/bleu.
- Son : spectre par trames de 1 024 échantillons à 22 050 Hz (pas de 128), énergie par bandes (30 à 120 Hz,
  300 à 3 000 Hz, 6 à 11 kHz), attaques, tempo par autocorrélation, grille ajustée sur les attaques d'aigus (phase
  par fenêtres glissantes).
- Kills : apparition et rafraîchissement du crâne du HUD, vérifiés à l'image (planches à 10 puis 30 img/s).
