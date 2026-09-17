# Modèles

`yamnet.onnx` : YAMNet (Google, AudioSet, licence Apache 2.0), converti depuis les poids officiels
`https://storage.googleapis.com/audioset/yamnet.h5` et le code de `tensorflow/models/research/audioset/yamnet`.
Entrée : patchs log-mel `[N, 96, 64]` (0,96 s, 16 kHz) ; sortie : 521 scores de classes (sigmoïde).
Le spectrogramme est calculé en Kotlin (`dev.highlights.ml.LogMelStream`), validé contre la référence TensorFlow.

`yamnet_class_map.csv` : noms des 521 classes (index, identifiant AudioSet, nom).
