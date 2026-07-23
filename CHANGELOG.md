# Changelog

All notable changes to transcritor-ata are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions match the tags on the
[Releases](../../releases) page.

## [Unreleased]

## [1.0.10] - 2026-07-23

### Security
- The extracted VAD model file is now re-verified (SHA-256 against the bundled jar resource)
  every time it's reused, instead of only checking it exists. Found during a security audit: a
  stale copy corrupted on disk or altered by another process running as the same user would
  otherwise be silently fed to whisper.cpp's native model loader without detection.
- The startup update check now caps how much of the GitHub API response it reads (1 MiB) instead
  of loading it into memory unbounded, and refuses to open a "Download" link whose URL isn't
  actually a `github.com/.../transcritor-ata/releases/...` link, instead of trusting whatever the
  response contained.
- The release workflow now downloads ffmpeg and whisper.cpp from pinned upstream releases
  verified against known-good SHA-256 hashes, instead of always fetching "latest" with no
  integrity check. Previously, a compromised upstream release of either project would have been
  silently bundled into the very next transcritor-ata release and shipped to every user.
- Bumped `logback-classic` from 1.5.6 to 1.5.18 (routine dependency hygiene found during a
  security audit; the app was never actually exploitable via the CVEs logback has had in this
  range, since none of them apply without the optional Janino library, which this project doesn't
  depend on).
- The bundled-tools checksum manifest now covers `.dll` files, not just `.exe`. whisper-cli loads
  ~49 DLLs (ggml.dll, whisper.dll, CUDA runtime DLLs, ...) into its own process with full
  code-execution capability; a tampered/corrupted DLL was previously invisible to the integrity
  check entirely.

### Changed
- Speaker clustering now uses average-linkage instead of single-linkage agglomerative
  clustering, to avoid the "chaining effect": previously, a single similar-sounding segment pair
  (background noise, cross-talk, a whisper) between two different speakers was enough to merge
  their entire clusters into one. Average linkage requires the clusters to be close as a whole,
  not just at one point.

## [1.0.9] - 2026-07-23

### Fixed
- The installer now shows a confirmation screen ("Completed the transcritor-ata Setup Wizard") when
  it finishes, instead of silently closing with no feedback. jpackage's default MSI UI has no
  Welcome/License/Finish pages unless a license file is supplied; the installer now embeds
  `LICENSE` (GPLv3) for exactly this purpose.

## [1.0.8] - 2026-07-23

### Added
- On startup, the app checks GitHub for a newer release and, if one is found, offers to open its
  download page or dismiss the notice.

### Removed
- The portable `.zip` package. The Windows installer (`.msi`) is now the only distributed
  release artifact.

### Fixed
- Whisper model download failures are now logged (previously only shown in the dialog and then lost).
- `package-installer.ps1`/CI no longer fails when the WiX Toolset is already present on the build machine.

## [1.0.7] - 2026-07-23

### Added
- GitHub Actions workflow (`.github/workflows/release.yml`): pushing a `vX.Y.Z` tag now builds and
  publishes the `.msi` and portable `.zip` automatically, instead of running the packaging
  scripts by hand.

### Changed
- Transcription and (optional) speaker identification now run one after another instead of in
  parallel. Both are CPU-heavy on their own (whisper-cli requests every logical CPU; the ONNX
  Runtime diarization models default to every physical core too), so running them concurrently
  meant each got a smaller, unpredictable slice of the CPU -- net slower, not faster.

## [1.0.6] - 2026-07-22

### Added
- Quantized/pruned Whisper model options: Small (compact), Medium (compact), and Large Turbo
  (compact) -- 2.6x-5x smaller than the originals, for machines where the full-size models are
  too heavy.
- Device-aware model selection: the app now automatically prefers Large Turbo (compact) on GPU
  and Medium (compact) on CPU, based on real benchmarking on meeting audio (file size alone isn't
  a reliable speed proxy once quantized/pruned model families are mixed).
- Windows installer (`.msi`), built via `jpackage` + WiX Toolset, as an alternative to the
  portable `.zip`: Start Menu/Desktop shortcuts, a normal Windows "Uninstall" entry, no
  administrator privileges required (per-user install), and automatic upgrade across versions.

### Changed
- The model download dialog is narrower and more vertical; descriptions were shortened.

## [1.0.5] - 2026-07-19

### Security
- Resolved bundled ffmpeg/whisper-cli/model paths against the app's actual install directory
  (`AppHome`, via `ProcessHandle`) instead of the JVM's working directory, and resolved
  `nvidia-smi`/`ffmpeg`/`whisper-cli` bare-name fallbacks to absolute paths -- closes a local
  binary-planting risk in the portable, unsigned app-image.
- The downloaded Whisper model's SHA-256 is now verified before it's trusted.
- Added a best-effort SHA-256 manifest for the bundled tools (ffmpeg, whisper-cli), checked (and
  warned about, not blocked on) at startup.
- Bounded memory/CPU usage against pathological input: capped `ProcessRunner`'s retained output
  buffer, the whisper.cpp JSON transcription file size before parsing, `Waveform`'s loaded audio
  duration, and the speaker-clustering input size.
- Closed a Cancel-button race in `ProcessRunner`; external processes are now always terminated on
  any unexpected exception.
- `config.properties` and generated `.docx` minutes are now written atomically (temp file +
  rename), so a crash mid-write can no longer corrupt/destroy the previous good file.
- Full file paths are no longer shown in the GUI log panel or persisted to the on-disk log file.

## [1.0.4] - 2026-07-15

### Fixed
- Enabled whisper.cpp's Voice Activity Detection (VAD) by default. whisper.cpp processes audio in
  fixed 30-second windows regardless of content, which caused hallucinated/repeated text and
  missed short utterances on recordings with sparse speech surrounded by long silences. The VAD
  model (Silero, ~900 KB, MIT license) is bundled in the jar and extracted automatically on first
  use.

## [1.0.3] - 2026-07-15

### Added
- "About transcritor-ata..." dialog (Help menu) with version info and full third-party license
  notices.
- "Elapsed time" indicator next to Transcribe/Cancel while a transcription runs.
- Adaptive GPU model-selection cascade with live status feedback (tries the largest model that
  fits VRAM, falls back to smaller models or CPU on out-of-memory).

### Changed
- Moved the speaker-identification and fast-mode checkboxes from the main window into
  Preferences; dropped the "(experimental)" wording from the diarization label.
- Moved "Preferences" from a standalone button into a "Settings" menu.
- Preferences' "Browse..." buttons now open in the currently configured file's folder.
- Redesigned the main window: reorderable file list, configurable output folder, automatic
  GPU/fast-mode detection.
- Replaced the LIUM_SpkDiarization speaker-identification engine with a from-scratch neural
  pipeline (pyannote segmentation + embedding, via ONNX Runtime), embedded directly in the jar --
  no external process or JRE dependency required.
- Fully translated the application (UI, logs, generated `.docx`, README) to English.

### Removed
- The Claude/Anthropic AI-structured-minutes feature (the app now only generates the plain,
  timestamped transcript minutes).
- The Vosk transcription engine and audio-chunking feature.
- Various dead code found during codebase sweeps.

### Fixed
- Malformed `PAGE` field in the generated minutes' `.docx` footer.

## [1.0.2] - 2026-07-14

### Fixed
- Speaker identification (LIUM) not running in the portable build: the embedded jpackage runtime
  had no usable `java.exe` for it to shell out to. The package now bundles a dedicated `jlink`
  runtime just for that purpose.

## [1.0.1] - 2026-07-14

### Added
- A dialog to download and switch the Whisper transcription model at any time from Preferences.
- Automatic fallback to CPU when the GPU runs out of VRAM mid-transcription.

### Changed
- Transcription and diarization output are shown in separate log panels, so they don't interleave.

## [1.0.0] - 2026-07-14

Initial release: video-to-minutes transcription pipeline (ffmpeg + whisper.cpp), optional speaker
identification, `.docx` minutes generation, SWT desktop GUI, and a portable Windows package.

[Unreleased]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.10...HEAD
[1.0.10]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.9...v1.0.10
[1.0.9]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/vchaves123/transcritor-ata/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/vchaves123/transcritor-ata/releases/tag/v1.0.0
