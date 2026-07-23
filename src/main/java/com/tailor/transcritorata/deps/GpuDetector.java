package com.tailor.transcritorata.deps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    // Well-known locations for nvidia-smi.exe beyond PATH, in case the driver installer didn't
    // add it there (it usually does, but System32 always has a copy on machines with the driver).
    private static final List<Path> NVIDIA_SMI_CANDIDATE_DIRS = List.of(
            Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32"),
            Path.of("C:\\Program Files", "NVIDIA Corporation", "NVSMI"));

    private final ExecutableLocator locator;
    private final String nvidiaSmiExecutable;

    public GpuDetector(ExecutableLocator locator) {
        this.locator = locator;
        // Resolved once to an absolute path via an explicit PATH/candidate-dir scan, instead of
        // ever handing the bare "nvidia-smi" name to ProcessBuilder: on Windows a bare name is
        // looked up in the current working directory before PATH, so a same-named file planted
        // there would otherwise run in place of the real driver tool. Falls back to the bare name
        // only if it truly can't be found anywhere, which fails the same way a bare invocation
        // would have anyway (no driver installed).
        this.nvidiaSmiExecutable = locator.findOnPathOrCandidates("nvidia-smi.exe", NVIDIA_SMI_CANDIDATE_DIRS)
                .map(Path::toString)
                .orElse("nvidia-smi");
    }

    /** @return true if {@code nvidia-smi -L} runs successfully, indicating an NVIDIA GPU + driver are present. */
    public boolean hasNvidiaGpu() {
        return locator.canRun(List.of(nvidiaSmiExecutable, "-L"), PROBE_TIMEOUT_SECONDS);
    }

    /** @return the total VRAM of the (first) NVIDIA GPU in MB, or empty if it couldn't be read. */
    public Optional<Long> vramMb() {
        List<String> command = List.of(nvidiaSmiExecutable, "--query-gpu=memory.total", "--format=csv,noheader,nounits");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            process = builder.start();
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
        } finally {
            // Defensive backstop: an exception thrown between start() and waitFor() (e.g. while
            // reading the output stream) would otherwise leave nvidia-smi running unmanaged until
            // it exits on its own.
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
