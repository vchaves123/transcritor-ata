package com.tailor.transcritorata.transcription;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.tailor.transcritorata.model.Segment;

/**
 * Combines the per-chunk transcription results produced when a long recording is split into
 * fixed-length WAV chunks, shifting each chunk's segments by its nominal start offset so
 * timestamps in the final minutes reflect the original recording.
 */
public final class ChunkMerger {

    private ChunkMerger() {
    }

    /**
     * @param chunkSegments segments transcribed from each chunk, in chunk order (index 0 first)
     * @param chunkDuration nominal duration of each chunk (the last chunk may be shorter; that's fine,
     *                       since only its start offset matters)
     */
    public static List<Segment> merge(List<List<Segment>> chunkSegments, Duration chunkDuration) {
        List<Segment> merged = new ArrayList<>();
        for (int i = 0; i < chunkSegments.size(); i++) {
            Duration offset = chunkDuration.multipliedBy(i);
            for (Segment segment : chunkSegments.get(i)) {
                merged.add(segment.withOffset(offset));
            }
        }
        return merged;
    }
}
