package com.tailor.transcritorata.transcription;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Mirrors the JSON produced by Vosk's {@code Recognizer.Result()}/{@code FinalResult()} when word timing is enabled. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VoskJsonResult {

    public String text;
    public List<VoskWord> result;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VoskWord {
        public double start;
        public double end;
        public String word;
    }
}
