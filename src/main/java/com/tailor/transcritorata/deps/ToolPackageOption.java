package com.tailor.transcritorata.deps;

import java.nio.file.Path;

/**
 * A downloadable external tool package (ffmpeg, or one whisper-cli build), extracted into its own
 * subfolder under {@code tools/}. Pinned to the exact same tag/asset/SHA-256 triples the release
 * workflow uses to build the (formerly bundled) installer -- see {@code release.yml} -- so bumping
 * a version here and there must be done together, deliberately, not independently.
 */
public enum ToolPackageOption {

    // BtbN/FFmpeg-Builds only keeps a rolling window of recent autobuild tags -- the previous
    // pin (autobuild-2026-07-23-14-16) started 404ing once BtbN pruned it, roughly two weeks
    // after it was pinned. Whoever re-pins this next should expect the same: grab the current
    // latest autobuild tag/asset from https://github.com/BtbN/FFmpeg-Builds/releases, download it,
    // and compute its own SHA-256 -- don't reuse an old tag's URL once it's gone.
    FFMPEG("ffmpeg", 148_274_234L,
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-08-07-13-13/ffmpeg-N-125990-g5c395992f9-win64-lgpl.zip",
            "c7ae39256aacd89a1204d62293f0da6c6623497684117844aaec758c59729673",
            "ffmpeg",
            // The BtbN zip wraps everything in a single version-named folder; this strips that one
            // top-level folder so ffmpeg.exe ends up directly at tools/ffmpeg/bin/ffmpeg.exe.
            true,
            "bin/ffmpeg.exe"),

    WHISPER_CLI_CPU("whisper-cli (CPU)", 7_982_101L,
            "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.1/whisper-bin-x64.zip",
            "7d8be46ecd31828e1eb7a2ecdd0d6b314feafd82163038ab6092594b0a063539",
            "whisper-cpu",
            false,
            "Release/whisper-cli.exe"),

    WHISPER_CLI_CUDA("whisper-cli (NVIDIA GPU)", 677_887_125L,
            "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.1/whisper-cublas-12.4.0-bin-x64.zip",
            "106a2030eff8998e4ef320fe72e263a78449e9040386ee27c41ea80b001b601b",
            "whisper-cuda",
            false,
            "Release/whisper-cli.exe");

    private final String label;
    private final long downloadSizeBytes;
    private final String downloadUrl;
    private final String sha256;
    private final String subDir;
    private final boolean flattenTopLevelFolder;
    private final String relativeExecutablePath;

    ToolPackageOption(String label, long downloadSizeBytes, String downloadUrl, String sha256, String subDir,
            boolean flattenTopLevelFolder, String relativeExecutablePath) {
        this.label = label;
        this.downloadSizeBytes = downloadSizeBytes;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
        this.subDir = subDir;
        this.flattenTopLevelFolder = flattenTopLevelFolder;
        this.relativeExecutablePath = relativeExecutablePath;
    }

    public String label() {
        return label;
    }

    /** @return the approximate size of the zip archive itself (pinned at the time this URL was added). */
    public long downloadSizeBytes() {
        return downloadSizeBytes;
    }

    public String downloadUrl() {
        return downloadUrl;
    }

    public String sha256() {
        return sha256;
    }

    /** @return this package's own subfolder name under {@code tools/} (e.g. "ffmpeg", "whisper-cuda"). */
    public String subDir() {
        return subDir;
    }

    /** @return true if the archive wraps everything in one extra top-level folder that must be stripped. */
    public boolean flattenTopLevelFolder() {
        return flattenTopLevelFolder;
    }

    /** @return where {@code toolsDir}/{@link #subDir()} should end up containing the tool's executable. */
    public Path expectedExecutable(Path toolsDir) {
        return toolsDir.resolve(subDir).resolve(relativeExecutablePath);
    }
}
