package com.tailor.transcritorata.gui;

import java.nio.file.Path;
import java.time.Duration;

/**
 * A file selected for transcription, with metadata for display in the file list: size is known
 * immediately, duration is filled in asynchronously via ffprobe (null until then, or if ffprobe
 * couldn't read it).
 */
record VideoFileInfo(Path path, long sizeBytes, Duration duration) {

    VideoFileInfo withDuration(Duration newDuration) {
        return new VideoFileInfo(path, sizeBytes, newDuration);
    }
}
