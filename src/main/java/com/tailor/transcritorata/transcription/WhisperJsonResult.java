package com.tailor.transcritorata.transcription;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mirrors the JSON output produced by whisper.cpp's {@code whisper-cli -oj} flag.
 * Only the fields needed to build {@link com.tailor.transcritorata.model.Segment}s are mapped;
 * everything else is ignored since whisper.cpp's JSON shape has changed across versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhisperJsonResult {

    public List<WhisperTranscriptionEntry> transcription;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WhisperTranscriptionEntry {
        public WhisperOffsets offsets;
        public String text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WhisperOffsets {
        /** Milliseconds from the start of the audio. */
        public long from;
        public long to;
    }
}
