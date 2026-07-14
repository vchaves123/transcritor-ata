package com.tailor.transcritorata.deps;

/**
 * Whisper ggml models officially published by ggerganov, offered to the user on first run so
 * they don't have to hunt for a download link themselves.
 */
public enum WhisperModelOption {

    SMALL("ggml-small.bin", "Pequeno", "~466 MB — mais rápido, precisão menor. Bom para máquinas modestas."),
    MEDIUM("ggml-medium.bin", "Médio (recomendado)", "~1,5 GB — bom equilíbrio para uso em CPU."),
    LARGE_V3("ggml-large-v3.bin", "Grande", "~2,9 GB — melhor precisão. Recomendado se você tiver GPU.");

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
