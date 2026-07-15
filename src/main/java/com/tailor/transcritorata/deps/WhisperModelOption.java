package com.tailor.transcritorata.deps;

/**
 * Whisper ggml models officially published by ggerganov, offered to the user on first run so
 * they don't have to hunt for a download link themselves.
 */
public enum WhisperModelOption {

    SMALL("ggml-small.bin", "Small", "~466 MB — faster, lower accuracy. Good for modest machines."),
    MEDIUM("ggml-medium.bin", "Medium (recommended)", "~1.5 GB — good balance for CPU use."),
    LARGE_V3("ggml-large-v3.bin", "Large", "~2.9 GB — best accuracy. Recommended if you have a GPU.");

    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String fileName;
    private final String label;
    private final String description;

    WhisperModelOption(String fileName, String label, String description) {
        this.fileName = fileName;
        this.label = label;
        this.description = description;
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
