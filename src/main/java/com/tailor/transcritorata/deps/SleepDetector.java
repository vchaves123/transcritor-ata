package com.tailor.transcritorata.deps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects whether the machine was suspended (sleep/hibernate) at any point during a given time
 * window, by querying Windows' own System event log -- the {@code Microsoft-Windows-
 * Power-Troubleshooter} provider logs an event (ID 1) on every resume, with exact sleep/wake
 * timestamps as structured (locale-independent) event data, so this doesn't need to guess via a
 * heartbeat-gap heuristic.
 */
public final class SleepDetector {

    private static final Logger LOG = LoggerFactory.getLogger(SleepDetector.class);
    private static final long QUERY_TIMEOUT_SECONDS = 10;

    // Reads each qualifying event's structured EventData (not the rendered, locale-dependent
    // message text) and prints "SleepTime|WakeTime" as ISO-8601 instants, one pair per line.
    // MaxEvents 20 is far more than one run could plausibly produce; keeps the query cheap
    // without needing a -StartTime filter (whose culture-dependent parsing is easy to get wrong).
    //
    // Deliberately uses single-quoted PowerShell strings and '+' concatenation instead of
    // double-quoted interpolation ("$sleep|$wake"): ProcessBuilder's translation of the argument
    // list into a single Windows command line does not reliably preserve embedded double quotes
    // inside the -Command argument, which silently truncated the script and broke this query.
    private static final String POWERSHELL_SCRIPT = """
            Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-Power-Troubleshooter'; Id=1} -MaxEvents 20 -ErrorAction SilentlyContinue | ForEach-Object {
                $data = ([xml]$_.ToXml()).Event.EventData.Data
                $sleep = ($data | Where-Object Name -eq 'SleepTime').'#text'
                $wake = ($data | Where-Object Name -eq 'WakeTime').'#text'
                if ($sleep -and $wake) { Write-Output ($sleep + '|' + $wake) }
            }
            """;

    /** One suspend/resume cycle. */
    public record SleepInterval(Instant sleepTime, Instant wakeTime) {
        public Duration duration() {
            return Duration.between(sleepTime, wakeTime);
        }
    }

    private SleepDetector() {
    }

    /**
     * @return every suspend/resume cycle whose sleep time is at or after {@code since}, oldest
     *         first; empty (never throws) if the query fails for any reason -- this is a
     *         best-effort diagnostic, not something that should ever interrupt the caller.
     */
    public static List<SleepInterval> findSleepIntervalsSince(Instant since) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", POWERSHELL_SCRIPT);
            builder.redirectErrorStream(false);
            process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = process.waitFor(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return List.of();
            }
            return parse(output, since);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Could not query the system event log for sleep/resume events: {}", e.getMessage());
            return List.of();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static List<SleepInterval> parse(String output, Instant since) {
        List<SleepInterval> intervals = new ArrayList<>();
        for (String line : output.lines().toList()) {
            int separator = line.indexOf('|');
            if (separator < 0) {
                continue;
            }
            try {
                Instant sleepTime = Instant.parse(line.substring(0, separator).trim());
                Instant wakeTime = Instant.parse(line.substring(separator + 1).trim());
                if (!sleepTime.isBefore(since)) {
                    intervals.add(new SleepInterval(sleepTime, wakeTime));
                }
            } catch (RuntimeException e) {
                LOG.debug("Could not parse sleep/resume event line '{}': {}", line, e.getMessage());
            }
        }
        intervals.sort(Comparator.comparing(SleepInterval::sleepTime));
        return intervals;
    }
}
