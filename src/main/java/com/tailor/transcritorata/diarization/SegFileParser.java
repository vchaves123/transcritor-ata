package com.tailor.transcritorata.diarization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code .seg} file produced by LIUM_SpkDiarization.
 *
 * <p>Each non-comment line has the form:
 * <pre>show channel startFrame durationFrames gender band environment speakerLabel</pre>
 * where {@code startFrame}/{@code durationFrames} are counts of 10&nbsp;ms frames. Comment lines
 * start with {@code ;;} and are ignored.
 */
public final class SegFileParser {

    /** LIUM works on 100 frames per second, i.e. one frame every 10 ms. */
    private static final long MILLIS_PER_FRAME = 10;

    private SegFileParser() {
    }

    public static List<SpeakerTurn> parse(Path segFile) throws IOException {
        List<String> lines = Files.readAllLines(segFile, StandardCharsets.UTF_8);
        return parseLines(lines);
    }

    static List<SpeakerTurn> parseLines(List<String> lines) {
        List<SpeakerTurn> turns = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith(";;")) {
                continue;
            }
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 8) {
                continue;
            }
            try {
                long startFrame = Long.parseLong(fields[2]);
                long durationFrames = Long.parseLong(fields[3]);
                String speakerLabel = fields[7];

                Duration start = Duration.ofMillis(startFrame * MILLIS_PER_FRAME);
                Duration end = Duration.ofMillis((startFrame + durationFrames) * MILLIS_PER_FRAME);
                turns.add(new SpeakerTurn(start, end, speakerLabel));
            } catch (NumberFormatException e) {
                // linha malformada: ignora em vez de abortar a diarização inteira
            }
        }
        turns.sort((a, b) -> a.start().compareTo(b.start()));
        return turns;
    }
}
