# Privacy

transcritor-ata is a local desktop application. This document explains what happens to your data
when you use it.

## Your meeting recordings and generated minutes

Video files you select, the audio extracted from them, the transcription, speaker
identification, and the generated `.docx` minutes are **processed entirely on your own
computer**. Nothing about the content of your recordings — audio, transcript text, speaker
labels, or the resulting minutes — is ever sent anywhere over the network, to us or to any
third party.

- **Transcription** runs locally via [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
  (CPU or your own GPU).
- **Speaker identification** runs locally via [ONNX Runtime](https://onnxruntime.ai/), using
  models embedded in the application itself.
- **Minutes generation** (`.docx`) happens locally via Apache POI.

No cloud AI service is used for any of this. (An earlier version of this app experimented with an
optional Claude/Anthropic integration for AI-structured minutes; it was fully removed — see
[CHANGELOG.md](CHANGELOG.md) — and no code path in the current version sends recording content to
any external AI service.)

## What does go over the network

The only outbound network activity this app performs is **downloading software/models it needs
to run**, not sending any of your data:

- The Whisper transcription model (`.bin`), downloaded from
  [huggingface.co/ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp) when you
  choose one in the setup dialog or Preferences.
- ffmpeg and whisper-cli, from pinned GitHub release URLs, if a first-run dialog finds either
  missing on your machine and you choose to download it (or manually, if you follow the README's
  "bundled" setup instructions instead). Each download is verified against a known-good SHA-256
  checksum before it's used; a mismatch on a later startup (e.g. local corruption) shows a
  blocking warning instead of silently running the file.
- On every startup, a request to GitHub's public releases API
  (`api.github.com/repos/vchaves123/transcritor-ata/releases/latest`) to check whether a newer
  version is available. This request doesn't include any of your data — it's the same as visiting
  the [Releases](../../releases) page in a browser — and only the version number and release page
  URL are read from the response. If it fails (offline, GitHub unreachable, etc.), it's silently
  ignored and startup proceeds normally.

These are plain HTTPS downloads *to* your machine (or a read-only version check); the app does not
upload, phone home, or report usage/analytics of any kind. There is no telemetry, no crash
reporting service, and no account or sign-in.

## What's stored locally, and where

- **Preferences** (chosen engine paths, output folder): plaintext properties file at
  `%APPDATA%\transcritor-ata\config.properties`. No secrets or credentials are stored there.
- **Logs**: `%APPDATA%\transcritor-ata\logs\`, rotated daily, kept for 14 days. These may contain
  file *names* (e.g. the video file you transcribed) for troubleshooting, but full folder paths
  are deliberately kept out of both the on-screen log panel and the log files.
- **Downloaded models and tools**: saved under the app's own `tools/` folder (`tools/models/`,
  `tools/ffmpeg/`, `tools/whisper-cpu/` or `tools/whisper-cuda/`) in your install directory, not
  in any OS-wide cache. A `tools/CHECKSUMS.sha256` file alongside them records the SHA-256 of each
  downloaded executable, used to detect local corruption/tampering on later startups.

Uninstalling the app does not automatically delete `%APPDATA%\transcritor-ata\` or the downloaded
models folder; remove them yourself if you want a completely clean uninstall.

## Third-party components

See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for the bundled libraries, tools, and AI
models this app uses, and their respective licenses — none of them are configured to send data
off your machine as part of this app's usage.

## Questions

This app is developed and distributed by Tailor. If you have questions about this policy, open an
issue on the [GitHub repository](https://github.com/vchaves123/transcritor-ata).
