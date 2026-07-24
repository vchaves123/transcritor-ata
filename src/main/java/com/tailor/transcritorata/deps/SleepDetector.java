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
 * window, by querying Windows' own System event log -- no heartbeat-gap heuristic needed, since
 * Windows already logs exact sleep/wake timestamps. Different machines use different suspend
 * mechanisms depending on hardware/firmware support and Windows version, so every mechanism
 * currently in real-world use is queried, not just whichever one happens to be on the dev
 * machine:
 *
 * <ul>
 *   <li>Classic S3 sleep/hibernate (older/desktop hardware): the {@code Microsoft-Windows-
 *       Power-Troubleshooter} provider logs an event (ID 1) on every resume, with exact
 *       sleep/wake timestamps as structured (locale-independent) event data.</li>
 *   <li>Classic S3 sleep, lower-level (present on effectively every Windows version/edition,
 *       used as a second source in case Power-Troubleshooter isn't logging for some reason):
 *       {@code Microsoft-Windows-Kernel-Power} logs ID 42 ("entering sleep") and ID 107
 *       ("resumed from sleep") as a pair.</li>
 *   <li>Modern Standby (S0ix -- the default on most laptops sold since ~2017, entered by simply
 *       closing the lid, where Power-Troubleshooter ID 1 is <b>not</b> logged at all):
 *       {@code Microsoft-Windows-Kernel-Power} logs ID 506 ("entering Modern Standby") and
 *       ID 507 ("exiting Modern Standby") as a pair. Missing this mechanism entirely was caught
 *       during real-world testing on a laptop that suspends via lid-close, not full hibernate --
 *       a Power-Troubleshooter-only query silently reported "no sleep detected" for a run that
 *       had very much been interrupted by closing the lid.</li>
 * </ul>
 *
 * <p>The same physical suspend/resume cycle is sometimes logged by more than one of these
 * mechanisms at once (observed directly: a 506/507 pair fired milliseconds apart, exactly
 * overlapping a Power-Troubleshooter ID 1 event, as part of Windows' own bookkeeping around a
 * hibernate resume) -- overlapping/adjacent intervals are merged so the same real event is never
 * reported, or summed into a "total", more than once.
 */
public final class SleepDetector {

    private static final Logger LOG = LoggerFactory.getLogger(SleepDetector.class);
    private static final long QUERY_TIMEOUT_SECONDS = 10;

    // Windows logs some of these pairs a few milliseconds apart as part of its own internal
    // state-machine bookkeeping around a hibernate resume -- not a real, user-observable
    // suspension, so intervals shorter than this are dropped as noise.
    private static final Duration MIN_MEANINGFUL_DURATION = Duration.ofSeconds(1);

    // Two intervals within this gap of each other are treated as the same physical suspend/resume
    // cycle reported by more than one mechanism, and merged into one instead of double-counted.
    private static final Duration MERGE_GAP_TOLERANCE = Duration.ofSeconds(5);

    // Reads each qualifying event's structured EventData (not the rendered, locale-dependent
    // message text) and prints "SleepTime|WakeTime" as ISO-8601 instants, one pair per line, for
    // each of the three mechanisms described in the class Javadoc. MaxEvents is far more than one
    // run could plausibly produce; keeps the query cheap without needing a -StartTime filter
    // (whose culture-dependent parsing is easy to get wrong).
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

            function Get-PairedEvents($enterId, $exitId) {
                $events = Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-Kernel-Power'; Id=$enterId,$exitId} -MaxEvents 40 -ErrorAction SilentlyContinue | Sort-Object TimeCreated
                $pending = $null
                foreach ($ev in $events) {
                    if ($ev.Id -eq $enterId) {
                        $pending = $ev.TimeCreated.ToUniversalTime()
                    } elseif ($ev.Id -eq $exitId -and $pending -ne $null) {
                        Write-Output ($pending.ToString('o') + '|' + $ev.TimeCreated.ToUniversalTime().ToString('o'))
                        $pending = $null
                    }
                }
            }

            Get-PairedEvents 42 107
            Get-PairedEvents 506 507
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
     *         first, with duplicate/overlapping reports from different mechanisms merged; empty
     *         (never throws) if the query fails for any reason -- this is a best-effort
     *         diagnostic, not something that should ever interrupt the caller.
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
            return mergeOverlapping(parse(output, since));
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
                if (!sleepTime.isBefore(since) && Duration.between(sleepTime, wakeTime).compareTo(MIN_MEANINGFUL_DURATION) >= 0) {
                    intervals.add(new SleepInterval(sleepTime, wakeTime));
                }
            } catch (RuntimeException e) {
                LOG.debug("Could not parse sleep/resume event line '{}': {}", line, e.getMessage());
            }
        }
        intervals.sort(Comparator.comparing(SleepInterval::sleepTime));
        return intervals;
    }

    /** Merges intervals that overlap or start within {@link #MERGE_GAP_TOLERANCE} of the previous one's end. */
    private static List<SleepInterval> mergeOverlapping(List<SleepInterval> sortedIntervals) {
        if (sortedIntervals.size() <= 1) {
            return sortedIntervals;
        }
        List<SleepInterval> merged = new ArrayList<>();
        SleepInterval current = sortedIntervals.get(0);
        for (int i = 1; i < sortedIntervals.size(); i++) {
            SleepInterval next = sortedIntervals.get(i);
            if (!next.sleepTime().isAfter(current.wakeTime().plus(MERGE_GAP_TOLERANCE))) {
                Instant laterWake = next.wakeTime().isAfter(current.wakeTime()) ? next.wakeTime() : current.wakeTime();
                current = new SleepInterval(current.sleepTime(), laterWake);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
