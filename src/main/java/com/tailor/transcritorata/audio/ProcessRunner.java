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

    private ProcessRunner() {
    }

    /** Handle to a running external process, allowing the caller to cancel it. */
    public static final class Handle {
        private final AtomicReference<Process> processRef = new AtomicReference<>();

        public void cancel() {
            Process process = processRef.get();
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
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
        LOG.debug("Executando comando externo: {}", command);
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
            throw new ExternalProcessException(
                    "Não foi possível executar o processo externo: " + e.getMessage(), output.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalProcessException("A execução foi cancelada.", output.toString());
        }
    }
}
