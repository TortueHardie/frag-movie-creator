# Third-party notices

Highlights is free software, licensed under the **GNU General Public License, version 3 or (at your option) any
later version** (GPL-3.0-or-later). The full text is in [`LICENSE`](LICENSE). Its source code is available at
<https://github.com/TortueHardie/frag-movie-creator>.

The installer and the portable build also ship the components below, each under its own licence. The licence texts
referenced here are in the [`licenses/`](licenses/) folder of the source code and of the installed application
(`app/resources/licenses`).

## Bundled programs

### FFmpeg (`ffmpeg.exe`, `ffprobe.exe`)

- Licence: **GNU GPL version 3 or later**. FFmpeg's own licence file is shipped next to the executables
  (`app/resources/ffmpeg/LICENSE`), together with `VERSION.txt`, which records the exact build and its configuration.
- FFmpeg runs as a separate program; Highlights only starts it and reads its output.
- Corresponding source: the FFmpeg source code at the revision named in `VERSION.txt`, from
  <https://ffmpeg.org/download.html> (Git: <https://git.ffmpeg.org/ffmpeg.git>), and the build scripts of the
  Windows build used: <https://github.com/BtbN/FFmpeg-Builds> (release installers) or
  <https://www.gyan.dev/ffmpeg/builds/> (local builds).
- Written offer: for at least three years after we distribute a given release, anyone can obtain the complete
  corresponding source code of the FFmpeg build shipped with it, at no charge beyond the cost of providing it, by
  opening an issue at <https://github.com/TortueHardie/frag-movie-creator/issues>.

### Java runtime (Eclipse Temurin, OpenJDK 21)

- Licence: GNU GPL version 2 with the Classpath Exception. The runtime's own notices are shipped in
  `runtime/legal`. Source: <https://adoptium.net/> and <https://github.com/openjdk/jdk21u>.

## Model

### YAMNet (`config/models/yamnet.onnx`, `yamnet_class_map.csv`)

- © Google LLC. Licence: Apache License 2.0 ([`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)).
- Modified: the official TensorFlow weights (`yamnet.h5`, from the `tensorflow/models` repository, AudioSet YAMNet)
  were converted to ONNX for Highlights. Class names come from the same project.

## Libraries

| Component | Licence |
|---|---|
| Kotlin standard library and kotlin-reflect (JetBrains) | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime, atomicfu (JetBrains) | Apache-2.0 |
| Compose Multiplatform for desktop, Material 3, Skiko, JetBrains Runtime API (JetBrains) | Apache-2.0 |
| AndroidX Compose runtime, collection, annotation, lifecycle, savedstate, navigationevent (Google) | Apache-2.0 |
| org.jetbrains:annotations, JSpecify | Apache-2.0 |
| kaml (Charles Korn) | Apache-2.0 |
| snakeyaml-engine-kmp (Piotr Krzemiński) | Apache-2.0 |
| Okio (Square) | Apache-2.0 |
| urlencoder (Erik C. Thauvin) | Apache-2.0 |
| kotlin-logging (Ohad Shai) | Apache-2.0 |
| Clikt (AJ Alt), command-line version only | Apache-2.0 |
| ONNX Runtime (Microsoft) | MIT ([`licenses/MIT-onnxruntime.txt`](licenses/MIT-onnxruntime.txt)) |
| SLF4J API (QOS.ch) | MIT ([`licenses/MIT-slf4j.txt`](licenses/MIT-slf4j.txt)) |
| Logback (QOS.ch) | EPL-1.0 or LGPL-2.1, at the user's choice; source: <https://github.com/qos-ch/logback> |

Apache-2.0 text: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt). EPL-1.0:
<https://www.eclipse.org/legal/epl-v10.html>. LGPL-2.1: <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

## Fonts

Highlights ships no font. Text burned into videos uses fonts already installed with Windows (Impact, Yu Gothic,
Malgun Gothic, Microsoft YaHei, Arial Black, Segoe UI Black), under Microsoft's terms for those fonts.

## Games and trademarks

Highlights isn't endorsed by Riot Games and doesn't reflect the views or opinions of Riot Games or anyone
officially involved in producing or managing Riot Games properties. Riot Games, VALORANT, League of Legends and all
associated properties are trademarks or registered trademarks of Riot Games, Inc.

The small reference images and sounds in `config/templates/` (HUD icons, a headshot sound) are excerpts from the
games VALORANT and Wardogs, used only to recognise game events in the player's own recordings. They remain the
property of their publishers. Outplayed and Overwolf are trademarks of Overwolf Ltd.; Highlights is not affiliated
with Overwolf, Riot Games or any game publisher.
