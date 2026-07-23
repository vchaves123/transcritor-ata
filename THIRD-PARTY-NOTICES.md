# Third-party notices

transcritor-ata bundles the following third-party components. This same list is also shown
in-app under **Help → About transcritor-ata...**.

## Java libraries (bundled in the application jar)

| Component | Version | License |
|---|---|---|
| [Eclipse SWT](https://www.eclipse.org/swt/) (`org.eclipse.platform`) | 3.126.0 | [EPL-2.0](https://www.eclipse.org/legal/epl-2.0/) |
| [Apache POI](https://poi.apache.org/) (`org.apache.poi:poi-ooxml`) | 5.3.0 | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [Jackson Databind](https://github.com/FasterXML/jackson-databind) (`com.fasterxml.jackson.core:jackson-databind`) | 2.17.2 | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [ONNX Runtime](https://onnxruntime.ai/) (`com.microsoft.onnxruntime:onnxruntime`) | 1.27.0 | [MIT License](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| [JTransforms](https://github.com/wendykierp/JTransforms) (`com.github.wendykierp:JTransforms`) | 3.2 | [BSD 2-Clause License](https://github.com/wendykierp/JTransforms) |
| [SLF4J](https://www.slf4j.org/) (`org.slf4j:slf4j-api`) | 2.0.16 | [MIT License](https://www.slf4j.org/license.html) |
| [Logback Classic](https://logback.qos.ch/) (`ch.qos.logback:logback-classic`) | 1.5.6 | [EPL-1.0 / LGPL-2.1](https://logback.qos.ch/license.html) (dual-licensed) |

## External tools (bundled alongside the app, not inside the jar)

| Component | License |
|---|---|
| [ffmpeg](https://ffmpeg.org/) (static build, `tools/ffmpeg`) | [LGPL 2.1+](https://ffmpeg.org/legal.html) (LGPL build) |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) / whisper-cli (`tools/whisper-cpu`, `tools/whisper-cuda`) | [MIT License](https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE) |

## AI models

| Model | Notes | License |
|---|---|---|
| [pyannote/speaker-diarization-3.1](https://huggingface.co/pyannote/speaker-diarization-3.1) | Segmentation + embedding, ONNX export, embedded as a resource for speaker identification | See the [model card](https://huggingface.co/pyannote/speaker-diarization-3.1) for license/usage terms |
| [Whisper transcription model](https://huggingface.co/ggerganov/whisper.cpp) | Downloaded separately by the user, not bundled | MIT License (ggerganov/whisper.cpp model conversions) |
| [Silero VAD](https://huggingface.co/ggml-org/whisper-vad) | Voice activity detection, ggml export, embedded as a resource so whisper.cpp can skip long silences and avoid hallucinated text | MIT License |

## Build-time tooling (not distributed with the app)

| Tool | License |
|---|---|
| [WiX Toolset](https://wixtoolset.org/) 3.14 (used to build the `.msi` installer) | [MS-RL](https://github.com/wixtoolset/wix3/blob/develop/LICENSE.TXT) |
| [Eclipse Temurin JDK/jpackage](https://adoptium.net/) 21 | [GPLv2 with Classpath Exception](https://openjdk.org/legal/gplv2+ce.html) |
