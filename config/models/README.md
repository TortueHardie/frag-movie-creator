# Modèles

`yamnet.onnx` : YAMNet (Google, AudioSet, licence Apache 2.0), converti depuis les poids officiels
`https://storage.googleapis.com/audioset/yamnet.h5` et le code de `tensorflow/models/research/audioset/yamnet`.
Entrée : patchs log-mel `[N, 96, 64]` (0,96 s, 16 kHz) ; sortie : 521 scores de classes (sigmoïde).
Le spectrogramme est calculé en Kotlin (`dev.highlights.ml.LogMelStream`), validé contre la référence TensorFlow.

`yamnet_class_map.csv` : noms des 521 classes (index, identifiant AudioSet, nom).

Sous-titres (whisper.cpp, téléchargés à part, ignorés par git) : `ggml-large-v3-turbo-q5_0.bin` (~574 Mo) et
`ggml-large-v3-q5_0.bin` (~1,1 Go, plus juste sur les noms, pris en premier par le profil VALORANT) depuis
`https://huggingface.co/ggerganov/whisper.cpp` ; `ggml-silero-v5.1.2.bin` (~1 Mo, détection de la voix pour
`whisper-cli`) depuis `https://huggingface.co/ggml-org/whisper-vad`.
