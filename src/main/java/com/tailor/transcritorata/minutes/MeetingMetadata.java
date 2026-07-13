package com.tailor.transcritorata.minutes;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Metadata about the source recording, shown in the metadata table at the top of the generated
 * minutes document.
 */
public record MeetingMetadata(LocalDate meetingDate, String sourceFileName, Duration duration, String companyName) {

    public MeetingMetadata {
        companyName = companyName == null ? "" : companyName;
    }
}
