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
        StringBuilder output = new StringBuilder();
        String commandLine = formatCommandLine(command);
        LOG.debug("Executando comando externo: {}", commandLine);
        if (lineConsumer != null) {
            lineConsumer.accept(BANNER_SEPARATOR);
            lineConsumer.accept(commandLine);
            lineConsumer.accept(BANNER_SEPARATOR);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            handle.processRef.set(process);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
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
                        "O processo externo excedeu o tempo limite de " + timeoutSeconds + " segundos.",
                        output.toString());
            }
            if (process.exitValue() != 0) {
                throw new ExternalProcessException(
                        "O processo externo terminou com código de erro " + process.exitValue() + ".",
                        output.toString());
            }
        } catch (IOException e) {
            if (handle.isCancelled()) {
                throw new ProcessCancelledException(output.toString());
            }
            throw new ExternalProcessException(
                    "Não foi possível executar o processo externo: " + e.getMessage(), output.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessCancelledException(output.toString());
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
