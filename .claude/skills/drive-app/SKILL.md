---
name: drive-app
description: Lancer l'application bureau Highlights (app-ui, Compose) avec son pont de pilotage et la piloter par curl, sans prendre la souris ni le clavier de l'utilisateur ni l'écran entier. À utiliser pour vérifier un écran, un parcours ou une traduction dans la vraie application, prendre des captures de ses fenêtres (même cachées derrière d'autres), lire son état ou son journal, cliquer ou taper dedans. Pas de computer-use pour cette application.
---

# Piloter l'application Highlights

Le pont de pilotage (`app-ui/src/main/kotlin/dev/highlights/ui/bridge/`) est un petit serveur HTTP **de développement**
dans l'application : état de l'interface, actions du contrôleur, captures des fenêtres, arbre d'accessibilité, clics et
frappes simulés, journal. Il ne touche que les fenêtres de l'application : le curseur ne bouge pas, rien ne passe au
premier plan, les captures viennent du rendu Skia de la fenêtre (elles marchent fenêtre cachée).

Sûreté : éteint par défaut, allumé seulement par `-Pbridge` sur `:app-ui:run` ; refusé dans l'application installée
(MSI, portable) ; 127.0.0.1 seulement, port et jeton au hasard.

## 1. Lancer

En arrière-plan (la tâche Gradle dure tant que l'application est ouverte), depuis la racine du dépôt :

```bash
rm -f ~/.highlights/control-bridge.json
./gradlew.bat :app-ui:run -Pbridge > "$SCRATCH/app-run.log" 2>&1   # run_in_background
```

Attendre le fichier du pont (premier lancement : compilation, ~1 min) :

```bash
until [ -f ~/.highlights/control-bridge.json ] || grep -qE "FAILED|BUILD SUCCESSFUL" "$SCRATCH/app-run.log"; do sleep 1; done
```

`~/.highlights/control-bridge.json` : `url`, `port`, `token`, `pid`, `log`. Une petite fonction pour la suite :

```bash
F=~/.highlights/control-bridge.json
URL=$(python -c "import json,sys;print(json.load(open(sys.argv[1]))['url'])" $F)
TOK=$(python -c "import json,sys;print(json.load(open(sys.argv[1]))['token'])" $F)
b() { if [ -n "$2" ]; then curl -s -H "Authorization: Bearer $TOK" -H "Content-Type: application/json" --data-binary "$2" "$URL$1"; else curl -s -H "Authorization: Bearer $TOK" "$URL$1"; fi; }
shot() { curl -s -H "Authorization: Bearer $TOK" "$URL/screenshot$2" -o "$1"; }   # shot fichier.png [?window=N]
```

(Les fonctions shell ne survivent pas d'un appel Bash à l'autre : les mettre dans un fichier du scratchpad et le sourcer.)

Fermer : `taskkill //PID <pid> //F` (pid du fichier du pont) ; la tâche Gradle se termine alors en échec, c'est normal.

L'application de dev lit `config/` du projet, mais les préférences de l'utilisateur (`~/.highlights/` : dossier
surveillé, musiques) et sa bibliothèque d'analyses (dossier de sortie) sont les vraies : ne rien y effacer.

## 2. Routes

Toutes exigent le jeton (`Authorization: Bearer <jeton>` ou `X-Bridge-Token`). `GET /` liste les routes.

| Route | Rôle |
|---|---|
| `GET /state[?path=a.b.0.c][&depth=N][&list=N]` | `UiState` en JSON (propriétés calculées comprises : `canAnalyze`, `canExport`, `montage.canCreate`…). Grands tableaux résumés, listes tronquées ; `path` descend dans le JSON, `depth` (10 par défaut) va plus loin. |
| `GET /actions` | Actions de `UiActions` et leurs paramètres. |
| `POST /action {"name", "args", "paths"}` | Appelle une action sur le thread de l'interface. `args` : liste dans l'ordre ou objet par nom. `paths` répond aux boîtes de dialogue (`chooseSource`, `addSources`, `chooseSession`, `chooseMusic`, `chooseMusicLibrary`, `chooseOutputDir`, `chooseWatchFolder`, `saveReport` pour « Enregistrer sous » : `.zip` ajouté si absent) : jamais de dialogue natif. Ouvrir/montrer/éditer un fichier, le navigateur, le presse-papiers sont seulement notés (`platform` dans la réponse). Les `update*` prennent les champs à changer : `{"name":"updateMontage","args":[{"fast":true}]}`. La réponse donne aussi `error` (boîte d'erreur ouverte) et `job`. |
| `GET /wait[?timeout=600]` | Attend la fin du traitement en cours (`job` nul, config chargée, recherche/publication chargées) ; rend l'erreur affichée s'il y en a une. |
| `GET /windows` | Fenêtres affichées : index (0 = principale), titre, position du contenu à l'écran, `scale`. |
| `GET /screenshot[?window=N]` | PNG du contenu (sans barre de titre). Sans `window` : toutes les fenêtres superposées comme à l'écran (aperçu 9:16 à côté de la principale). Pixels physiques : diviser par `scale` pour retrouver les coordonnées de clic. |
| `GET /tree[?all][&window=N][&text=…]` | Arbre d'accessibilité de Compose : `id`, `role` (`push button`, `check box`, `radio button`, `text`, `label`, `slider`…), `name`, `value`, `enabled`, `visible`, `bounds` [x, y, l, h] relatifs au contenu de la fenêtre. Sans `all` : seulement ce qui porte un texte ou est interactif. |
| `POST /click` | `{"text":"Analyser"}` (+ `"role":"push button"`), `{"id":"0.0.0.0.0.0.38"}` ou `{"x":190,"y":624,"window":0}` ; `"button":"right"`, `"count":2`. Refuse un élément hors de la zone visible : faire défiler. |
| `POST /scroll` | `{"x":190,"y":600,"amount":10}` (crans, positif = vers le bas) ou `{"text":…,"amount":…}`. |
| `POST /type` | `{"text":"45s","target":{"text":"60s"}}` : clique la cible puis tape. Sans `target` : dans le champ qui a le focus. |
| `POST /key` | `{"key":"ENTER"}`, `"TAB"`, `"BACK_SPACE"`, `"ctrl+A"`, `"ESCAPE"`… |
| `GET /logs?lines=N` | Fin du journal (`~/.highlights/logs/highlights.log`). |

Préférer les actions (`/action`) pour avancer dans un parcours (fiable, sans dépendre des textes) et les clics pour
vérifier qu'un bouton fait bien ce qu'il dit. Un clic sur un bouton qui ouvre un dialogue de fichiers ouvrirait le vrai
dialogue : passer par l'action avec `paths`. Vérifier une traduction : `GET /tree` liste tous les textes affichés
(défiler pour voir le reste d'un panneau, `visible: false` = hors de la zone affichée), et la capture montre la mise en page.

## 3. Parcours type : capture → analyse → montage kills → captures

```bash
# Capture : exemples/ (non versionné, à la racine du dépôt principal D:\dev\editor\exemples) ou une vidéo TestMedia.
b /action '{"name":"chooseSource","paths":["D:/dev/editor/exemples/tiktok_01.mp4"]}'
b /wait
b "/state?path=canAnalyze"                     # true
b /action '{"name":"analyze"}'
b "/wait?timeout=600"                          # {"ok":true} ou {"ok":true,"error":"Aucun moment retenu : …"}
shot analyse.png
b /click '{"text":"OK","role":"push button"}'  # ferme la boîte d'erreur s'il y en a une (ou action dismissError)
b /action '{"name":"setThreshold","args":[0.2]}' && b /wait
b "/state?path=session.enabledCount"

b /action '{"name":"openMontage"}'
b /action '{"name":"chooseMusic","paths":["D:/dev/editor/music sample/BACK2BACK.mp3"]}'
b /action '{"name":"updateMontage","args":[{"fast":true,"maxDurationText":"30s","density":"HEAVY"}]}'
b "/state?path=montage.canCreate"
shot montage.png
b /click '{"text":"Annuler"}'

K=$(b "/state?path=session.segments.0.key" | tr -d '"')
b /action "{\"name\":\"previewVertical\",\"args\":[\"$K\"]}" && b /wait
b /windows                                     # fenêtre 1 : « Aperçu 9:16 — … »
shot apercu.png ; shot apercu-seul.png "?window=1"
b /action '{"name":"dismissImagePreview"}'
b "/logs?lines=40"
```

Sans `exemples/` : générer une vidéo de test avec `TestMedia.generate` (testFixtures de `:ffmpeg`), ou prendre une
capture que l'utilisateur indique. `createMontage` lance un vrai rendu FFmpeg (plusieurs minutes, fichiers dans le
dossier de sortie) : seulement si le parcours à vérifier l'exige.

Lire une capture : outil Read sur le PNG.

## 4. Limites connues

- Les menus déroulants (« ? », langue, profil) ne sont pas dans `/tree` : cliquer par coordonnées après une capture.
  Un élément de menu qui ouvre l'Explorateur ou le navigateur passe alors par la vraie plateforme : préférer l'action.
- Signaler un problème : `openReport` (collecte en fond, attendre `report.loading == false`), puis `saveReport` avec
  `paths` ; l'URL du ticket prérempli est dans `report.issueUrl` et dans `platform` (le navigateur n'est pas ouvert).
- Mémoire insuffisante : lancer l'application avec `-Dhighlights.availableMemory=<Mo>` (ex. 300) pour voir le message
  au lancement d'une analyse ; `-Dhighlights.logFile=<fichier>` sépare les journaux (et les plantages notés) du vrai
  `~/.highlights/logs`.
- Le dossier surveillé de l'utilisateur (`~/.highlights/watch.json`) reprend au lancement : ses captures sont analysées
  vers le dossier de sortie de la config utilisée. Ne pas appeler `stopWatching` (réécrirait ses préférences).

- `focused` de `/windows` est l'avis d'AWT, pas forcément la fenêtre au premier plan de Windows.
- Les menus déroulants de Compose s'ouvrent dans la fenêtre (même arbre, même capture) ; un dialogue Swing (sélecteur
  de dossier ouvert à la main) est dessiné par Swing, sans garantie de rendu.
- Une action qui appelle la plateforme plus tard, depuis un traitement de fond, passe par la vraie plateforme (le
  court-circuit ne vaut que pendant l'appel de l'action).
- Fenêtre réduite dans la barre des tâches : non vérifié (Skia ne redessine sans doute plus ; la capture montrerait la dernière image).
