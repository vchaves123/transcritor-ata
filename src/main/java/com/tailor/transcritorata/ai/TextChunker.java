package com.tailor.transcritorata.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits long text into chunks no larger than a configured character limit, preferring to break
 * on paragraph or sentence boundaries so each chunk stays coherent for summarization.
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> split(String text, int limit) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= limit) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            int end = Math.min(start + limit, length);
            if (end < length) {
                int breakPoint = lastBreakPoint(text, start, end);
                if (breakPoint > start) {
                    end = breakPoint;
                }
            }
            chunks.add(text.substring(start, end).strip());
            start = end;
        }
        return chunks;
    }

    private static int lastBreakPoint(String text, int start, int end) {
        int paragraphBreak = text.lastIndexOf("\n\n", end - 1);
        if (paragraphBreak > start) {
            return paragraphBreak + 2;
        }
        int sentenceBreak = Math.max(text.lastIndexOf(". ", end - 1), text.lastIndexOf(".\n", end - 1));
        if (sentenceBreak > start) {
            return sentenceBreak + 2;
        }
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline > start) {
            return newline + 1;
        }
        return end;
    }
}
