# transcritor-ata

Desktop application for transcribing meeting recordings (`.wmv`, `.mp4`, `.mkv`, `.avi`, among
other formats supported by ffmpeg) and automatically generating a **professional-looking minutes
document in `.docx`**, ready to send to clients.

Built for **non-technical users**: the interface is fully in English, with an installation
checker that explains step by step what still needs to be configured.

## Target audience

Teams that record meetings on video and need to turn those recordings into written minutes,
without needing to edit video, use the command line, or know AI tools.

## How it works (workflow overview)

1. Choose one or more meeting video files (in the order they should be concatenated).
2. Click "Transcribe". The program extracts the audio, transcribes it, and generates the minutes.
3. When finished, open the `.docx` minutes directly from the application.

## Prerequisites (Windows 11)

The application checks all of this automatically on startup (menu **Help → Verify installation**),
showing links and instructions for anything that's missing. The requirements are:

### 1. ffmpeg

Required to extract audio from videos. Install via Terminal/PowerShell:

```
winget install Gyan.FFmpeg
```

Alternative: download from https://www.gyan.dev/ffmpeg/builds/ and add the `bin` folder to PATH.

> After installing via winget, close and reopen transcritor-ata so the updated PATH is
> recognized.

**"Bundled" alternative**: if you extract a static Windows ffmpeg build (e.g.:
https://github.com/BtbN/FFmpeg-Builds/releases, asset `*-win64-lgpl.zip`) into `tools/ffmpeg/` at
the project root, so that the executable ends up at `tools/ffmpeg/bin/ffmpeg.exe`, the application
detects and uses that path automatically on every startup — no need to touch the system PATH.
This folder is excluded from version control (`.gitignore`).

### 2. whisper.cpp (recommended transcription engine)

Download the pre-built Windows binary from the official releases:

https://github.com/ggml-org/whisper.cpp/releases

Look for the `.zip` file with the `-bin-x64` suffix, extract it to a folder of your choice, and,
in transcritor-ata's preferences, point the executable field to the extracted `whisper-cli.exe`.
**No compilation required.**

**Automatic GPU detection**: if you extract the builds into the `tools/whisper-cpu/` and
`tools/whisper-cuda/` folders at the project root (same layout as the `whisper-bin-x64` and
`whisper-cublas-*-bin-x64` `.zip` files, respectively), the application detects at runtime whether
an NVIDIA GPU is available (via `nvidia-smi`) and automatically adjusts the configured executable
on every startup — cuBLAS when a GPU is present, CPU when it isn't. These folders are excluded
from version control (`.gitignore`) because they are large third-party binaries; download them
yourself if you want this automatic behavior, or manually configure a single fixed path in the
preferences.

**Low-VRAM GPU (2 GB or less)**: if transcription fails due to insufficient GPU memory with larger
models (`medium`, `large-v3`), check the "Prioritize speed and GPU memory usage" option in
Preferences. It swaps beam search (whisper.cpp's default) for greedy decoding, which uses
significantly less VRAM and is faster, at the cost of some accuracy. In addition, the app already
automatically retries transcription on the CPU if the GPU runs out of memory mid-transcription.

**Recordings with long silences**: whisper.cpp is enabled to use Voice Activity Detection (VAD)
automatically for every transcription. Instead of blindly processing fixed 30-second windows
regardless of content, it first detects where actual speech happens and only transcribes those
stretches — this avoids the hallucinated/repeated text and missed short utterances that whisper.cpp
otherwise produces on recordings with sparse speech surrounded by long stretches of silence. The
VAD model (~900 KB) is bundled in the jar and extracted automatically on first use; no extra setup
is needed.

### 3. A Whisper model (`.bin`)

**No need to download it manually**: the first time the application opens without a valid model
configured, a dialog appears offering several options, with the approximate size and description
of each. After choosing one and clicking "Download", the model is downloaded automatically to
`tools/models/` and the preferences are adjusted automatically. You can also "Skip for now" and
configure it manually later.

If you'd rather download it yourself: https://huggingface.co/ggerganov/whisper.cpp/tree/main

- `ggml-medium-q5_0.bin` — **recommended**. Quantized version of Medium: ~514 MB (2.9x smaller)
  and ~18% faster on CPU in local testing, with the same transcription output as full-precision
  Medium on every recording tested so far — the best balance for most machines.
- `ggml-small-q5_1.bin` — quantized version of Small: ~181 MB (2.6x smaller), ~20% faster, same
  output as full-precision Small in testing. Pick this on more modest hardware.
- `ggml-small.bin` / `ggml-medium.bin` / `ggml-large-v3.bin` — the original, full-precision
  models, in case you ever see a difference in output quality vs. their quantized counterparts.
- `ggml-large-v3-turbo-q5_0.bin` — a pruned+quantized variant of Large (~547 MB, ~5x smaller than
  Large). **Not necessarily faster on CPU**: its encoder is still Large-sized, which dominates
  runtime on short/medium recordings, so in local testing it was slower than Medium (compact).
  Its speed advantage mainly applies on GPU or very long recordings.

Save the file and select it in the application's preferences.

### 4. (Optional) Speaker identification

No extra installation or download is required. Simply check the "Identify participants in the
transcription" checkbox in Preferences before transcribing, so the minutes indicate who spoke
each segment (`Speaker 1`, `Speaker 2`, ...).

This feature runs **entirely within the program itself**, with no external process: it's a Java
reimplementation of the neural pipeline [pyannote/speaker-diarization-3.1](https://huggingface.co/pyannote/speaker-diarization-3.1)
(segmentation + speaker embeddings + hierarchical clustering), running via
[ONNX Runtime](https://onnxruntime.ai/). The models (~32 MB) are already embedded in the
application's jar.

> It's significantly more accurate than the previous solution based on LIUM_SpkDiarization
> (classic, non-neural), but it's still experimental — accuracy can vary depending on the number
> of participants, audio quality, and overlapping speech. If it fails, the minutes are generated
> normally, just without speaker labels.

## Build and run

Requires **JDK 21** and **Maven**.

```
mvn package
java -jar target/transcritor-ata.jar
```

The generated jar (`target/transcritor-ata.jar`) already includes all dependencies (fat-jar).

> On Windows, SWT runs normally on the main thread. The `-XstartOnFirstThread` flag is only
> needed on macOS — it doesn't apply to normal use of this application on Windows.

## Installing (end-user release)

For non-technical users, the recommended way to use transcritor-ata is the **Windows installer**
(`.msi`): download it, double-click, and follow the wizard — no administrator privileges required
(it installs for the current user only). It adds a Start Menu entry, an optional Desktop shortcut,
and a normal "Uninstall" entry under Windows Settings → Apps. Installing a newer version
automatically upgrades/replaces the previous one. It already includes:

- A dedicated Java runtime (no need to have Java installed).
- ffmpeg.
- whisper-cli (CPU and GPU/CUDA builds — the application automatically picks the right one based
  on your video card, on every startup).
- Speaker identification (ONNX models embedded in the jar itself, optional).

**Not included**: the Whisper transcription model (`.bin`) — on first run, a dialog lets you
choose the size and downloads it automatically.

Download the latest version from the [Releases](../../releases) tab of this repository.

A **portable version** (`.zip`, extract-and-run, no install/uninstall entry at all) is also
published on the same Releases page, for cases where installing isn't an option (e.g. a locked-
down machine, or running straight off a USB drive).

### Building the release packages yourself

Requires JDK 21 (with `jpackage` on the PATH), Maven, and the `tools/` folder already populated
with ffmpeg and whisper-cli (see the prerequisites above — the easiest way is to run the
application itself once, which already downloads/organizes all of this into the expected
structure).

**Installer (`.msi`)** — additionally requires the
[WiX Toolset](https://github.com/wixtoolset/wix3/releases) 3.x (`candle.exe`/`light.exe` on PATH;
`winget install WiXToolset.WiXToolset` also works if winget is functioning correctly):

```powershell
.\package-installer.ps1
```

This compiles the project, generates the app image with `jpackage`, bundles the external tools
into it, and produces `release-installer\transcritor-ata-<version>.msi`.

**Portable (`.zip`)**:

```powershell
.\package-portable.ps1
```

This compiles the project, generates an app-image with `jpackage`, bundles the external tools
alongside it (without the Whisper model), and produces
`transcritor-ata-portable-win64-<version>.zip` at the project root.

Neither the `.msi` nor the `.zip` are tracked in git (both are too large) — publish them as assets
of a [GitHub Release](../../releases) instead of committing them.

## Importing into Eclipse

`File → Import → Maven → Existing Maven Projects`, select the project folder. m2e resolves the
dependencies automatically, including the correct SWT profile for your operating system (Windows
by default).

## Project structure

- `gui` — main window and SWT dialogs.
- `audio` — audio extraction via ffmpeg and external process execution.
- `transcription` — transcription engine (Whisper.cpp) and the orchestrating pipeline.
- `minutes` — generation of `.docx` minutes (Apache POI).
- `diarization` — optional speaker identification (neural pipeline via ONNX Runtime).
- `deps` — dependency checker.
- `config` — user preferences.

## Where user data is stored

- Configuration: `%APPDATA%\transcritor-ata\config.properties`
- Logs: `%APPDATA%\transcritor-ata\logs\`

## Known limitations

- Speaker identification (diarization) is optional and experimental — accuracy can vary
  considerably depending on the recording. See item 4 of the prerequisites.
- There is no installer (`.msi`/`.exe`); distribution is via an executable jar.
- The minutes' styles are defined in code (`DocxMinutesGenerator`), without using a corporate
  `.dotx` template — the class has already been structured for this future evolution.

## Tests

```
mvn test
```

Tests don't require ffmpeg, whisper.cpp, or models — external processes are isolated behind
interfaces and tested with mocks/fixtures.
