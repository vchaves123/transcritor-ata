package com.tailor.transcritorata.deps;

/**
 * Whisper ggml models officially published by ggerganov, offered to the user on first run so
 * they don't have to hunt for a download link themselves.
 */
public enum WhisperModelOption {

    SMALL("ggml-small.bin", "Small", "~466 MB — faster, lower accuracy. Good for modest machines.",
            "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
    MEDIUM("ggml-medium.bin", "Medium (recommended)", "~1.5 GB — good balance for CPU use.",
            "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
    LARGE_V3("ggml-large-v3.bin", "Large", "~2.9 GB — best accuracy. Recommended if you have a GPU.",
            "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2");

    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String fileName;
    private final String label;
    private final String description;
    private final String sha256;

    WhisperModelOption(String fileName, String label, String description, String sha256) {
        this.fileName = fileName;
        this.label = label;
        this.description = description;
        this.sha256 = sha256;
    }

    /** @return the officially published SHA-256 digest, verified after download before trusting the file. */
    public String sha256() {
        return sha256;
    }

    public String fileName() {
        return fileName;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String downloadUrl() {
        return BASE_URL + fileName;
    }
}
