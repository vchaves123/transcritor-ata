package com.tailor.transcritorata.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an external process with its combined stdout/stderr continuously drained on a background
 * thread (to avoid the classic pipe-buffer deadlock), a configurable timeout, and cooperative
 * cancellation via {@link Handle#cancel()}.
 */
public final class ProcessRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessRunner.class);
    private static final String BANNER_SEPARATOR = "=".repeat(60);
    // Caps how much combined stdout/stderr is retained for the failure-message StringBuilder.
    // A long-running whisper-cli/ffmpeg process that hallucinates/misbehaves and prints
    // unbounded output must not be allowed to grow this buffer without limit and OOM the JVM;
    // only the most recent output is useful for diagnosing a failure anyway.
    private static final int MAX_RETAINED_OUTPUT_CHARS = 1 << 18; // 256 KiB
    // Compaction only kicks in once this much further over the cap has accumulated, so a
    // fast-printing process doesn't pay an O(n) buffer shift on every single line.
    private static final int COMPACTION_SLACK_CHARS = 1 << 16; // 64 KiB
    private static final String TRUNCATION_MARKER = "[...output truncated, showing only the most recent lines...]\n";

    private ProcessRunner() {
    }

    /** Handle to a running external process, allowing the caller to cancel it. */
    public static final class Handle {
        private final AtomicReference<Process> processRef = new AtomicReference<>();
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            Process process = processRef.get();
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * Runs {@code command}, streaming each output line to {@code lineConsumer} as it is produced.
     *
     * @throws ExternalProcessException if the process exits with a non-zero code or times out
     */
    public static void run(List<String> command, Handle handle, long timeoutSeconds,
            Consumer<String> lineConsumer) throws ExternalProcessException {
        // Closes the race where cancel() lands between two sequential run() calls sharing the
        // same Handle (e.g. between per-video ffmpeg extractions, or between GPU/CPU whisper
        // attempts): without this check, cancel() would find no live process to kill yet -- since
        // this next one hasn't started -- and this call would run to completion regardless.
        if (handle.isCancelled()) {
            throw new ProcessCancelledException("");
        }

        StringBuilder output = new StringBuilder();
        String commandLine = formatCommandLine(command);
        LOG.debug("Running external command: {}", commandLine);
        if (lineConsumer != null) {
            // The full command line (with absolute paths to the source video, temp WAV, model,
            // etc.) is only ever logged at DEBUG to the log file, never surfaced to this
            // GUI-visible listener: full paths can reveal sensitive folder/file names (e.g. an
            // HR-related meeting folder) if the on-screen log panel is screenshotted or shared.
            String redactedCommandLine = formatCommandLine(redactPaths(command));
            lineConsumer.accept(BANNER_SEPARATOR);
            lineConsumer.accept(redactedCommandLine);
            lineConsumer.accept(BANNER_SEPARATOR);
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();
            handle.processRef.set(process);
            // Covers the remaining, much narrower window between processRef.set() above and
            // cancel()'s own isAlive() check: if cancel() ran just before this line, the process
            // it wanted to kill is this one, but it may have raced ahead of processRef.set().
            if (handle.isCancelled()) {
                process.destroyForcibly();
                throw new ProcessCancelledException("");
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendBounded(output, line);
                    LOG.debug("[proc] {}", line);
                    if (lineConsumer != null) {
                        lineConsumer.accept(line);
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            // Checked before inspecting the exit code: a forcibly-killed process almost always
            // exits with a non-zero code, which would otherwise be reported as a generic failure
            // instead of the deliberate cancellation it actually was.
            if (handle.isCancelled()) {
                throw new ProcessCancelledException(output.toString());
            }
            if (!finished) {
                process.destroyForcibly();
                throw new ExternalProcessException(
                        "The external process exceeded the timeout of " + timeoutSeconds + " seconds.",
                        output.toString());
            }
            if (process.exitValue() != 0) {
                throw new ExternalProcessException(
                        "The external process terminated with error code " + process.exitValue() + ".",
                        output.toString());
            }
        } catch (IOException e) {
            if (handle.isCancelled()) {
                throw new ProcessCancelledException(output.toString());
            }
            throw new ExternalProcessException(
                    "Could not run the external process: " + e.getMessage(), output.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessCancelledException(output.toString());
        } finally {
            // Defensive backstop: if anything unexpected (e.g. a RuntimeException thrown by
            // lineConsumer) escapes the loop above before the normal waitFor/exit-code handling
            // runs, this ensures the child process is never left running unmanaged.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Appends {@code line} to {@code output}, keeping only the most recent
     * {@link #MAX_RETAINED_OUTPUT_CHARS} characters so a runaway/hallucinating process can never
     * grow this buffer without bound.
     */
    private static void appendBounded(StringBuilder output, String line) {
        output.append(line).append('\n');
        if (output.length() > MAX_RETAINED_OUTPUT_CHARS + COMPACTION_SLACK_CHARS) {
            output.delete(0, output.length() - MAX_RETAINED_OUTPUT_CHARS);
            output.insert(0, TRUNCATION_MARKER);
        }
    }

    /**
     * Replaces any argument that looks like a filesystem path (contains a path separator and
     * isn't a flag) with just its file name, so the GUI-visible command banner never reveals full
     * folder paths -- only the DEBUG log file (not casually screenshotted or shared) keeps them.
     */
    private static List<String> redactPaths(List<String> command) {
        List<String> redacted = new java.util.ArrayList<>(command.size());
        for (String arg : command) {
            redacted.add(looksLikeMultiSegmentPath(arg)
                    ? java.nio.file.Path.of(arg).getFileName().toString()
                    : arg);
        }
        return redacted;
    }

    /**
     * True for genuine multi-directory paths (e.g. {@code C:\Videos\x.mkv},
     * {@code tools/ffmpeg/bin/ffmpeg.exe}); false for short single-segment flags some tools use
     * (e.g. cmd.exe's {@code /c}), which {@link java.nio.file.Path#getNameCount()} reports as 1.
     */
    private static boolean looksLikeMultiSegmentPath(String arg) {
        if (arg.isEmpty() || arg.startsWith("-") || (arg.indexOf('/') < 0 && arg.indexOf('\\') < 0)) {
            return false;
        }
        try {
            return java.nio.file.Path.of(arg).getNameCount() > 1;
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    /** Renders a command list as a single readable line, quoting arguments that contain spaces. */
    static String formatCommandLine(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String arg : command) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            if (arg.isEmpty() || arg.indexOf(' ') >= 0) {
                line.append('"').append(arg).append('"');
            } else {
                line.append(arg);
            }
        }
        return line.toString();
    }
}
