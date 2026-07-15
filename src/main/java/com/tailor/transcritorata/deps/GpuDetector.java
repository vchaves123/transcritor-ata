package com.tailor.transcritorata.deps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects whether an NVIDIA GPU (with a working driver) is available, by probing for
 * {@code nvidia-smi}. Isolated behind {@link ExecutableLocator} so it can be unit-tested without
 * touching real hardware.
 */
public final class GpuDetector {

    private static final Logger LOG = LoggerFactory.getLogger(GpuDetector.class);
    private static final long PROBE_TIMEOUT_SECONDS = 5;

    private final ExecutableLocator locator;

    public GpuDetector(ExecutableLocator locator) {
        this.locator = locator;
    }

    /** @return true if {@code nvidia-smi -L} runs successfully, indicating an NVIDIA GPU + driver are present. */
    public boolean hasNvidiaGpu() {
        return locator.canRun(List.of("nvidia-smi", "-L"), PROBE_TIMEOUT_SECONDS);
    }

    /** @return the total VRAM of the (first) NVIDIA GPU in MB, or empty if it couldn't be read. */
    public Optional<Long> vramMb() {
        List<String> command = List.of("nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits");
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String firstLine;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                firstLine = reader.readLine();
            }
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(firstLine.trim()));
        } catch (IOException | InterruptedException | NumberFormatException | NullPointerException e) {
            LOG.debug("Could not read total VRAM via nvidia-smi: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
