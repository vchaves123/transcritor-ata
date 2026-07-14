package com.tailor.transcritorata.deps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperModelOptionTest {

    @Test
    void buildsDownloadUrlFromHuggingFaceBaseAndFileName() {
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
                WhisperModelOption.MEDIUM.downloadUrl());
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
                WhisperModelOption.SMALL.downloadUrl());
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin",
                WhisperModelOption.LARGE_V3.downloadUrl());
    }

    @Test
    void everyOptionHasAFileNameLabelAndDescription() {
        for (WhisperModelOption option : WhisperModelOption.values()) {
            assertTrue(option.fileName().endsWith(".bin"));
            assertTrue(!option.label().isBlank());
            assertTrue(!option.description().isBlank());
        }
    }
}
