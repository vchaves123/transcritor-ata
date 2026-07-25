package com.tailor.transcritorata.deps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperModelOptionTest {

    @Test
    void buildsDownloadUrlFromHuggingFaceBaseAndFileName() {
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin",
                WhisperModelOption.MEDIUM_Q5_0.downloadUrl());
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
                WhisperModelOption.LARGE_V3_TURBO_Q5_0.downloadUrl());
    }

    @Test
    void recommendsLargeTurboForGpuAndMediumCompactForCpu() {
        assertEquals(WhisperModelOption.LARGE_V3_TURBO_Q5_0, WhisperModelOption.recommendedFor(true));
        assertEquals(WhisperModelOption.MEDIUM_Q5_0, WhisperModelOption.recommendedFor(false));
    }

    @Test
    void everyOptionHasAFileNameLabelAndDescription() {
        for (WhisperModelOption option : WhisperModelOption.values()) {
            assertTrue(option.fileName().endsWith(".bin"));
            assertTrue(!option.label().isBlank());
            assertTrue(!option.description().isBlank());
        }
    }

    @Test
    void everyOptionHasAPinnedSha256Checksum() {
        for (WhisperModelOption option : WhisperModelOption.values()) {
            assertTrue(option.sha256() != null && option.sha256().matches("[0-9a-f]{64}"),
                    option + " must have a 64-character lowercase hex SHA-256 pinned");
        }
    }
}
