package com.tailor.transcritorata.deps;

import java.util.List;

/**
 * Detects whether an NVIDIA GPU (with a working driver) is available, by probing for
 * {@code nvidia-smi}. Isolated behind {@link ExecutableLocator} so it can be unit-tested without
 * touching real hardware.
 */
public final class GpuDetector {

    private static final long PROBE_TIMEOUT_SECONDS = 5;

    private final ExecutableLocator locator;

    public GpuDetector(ExecutableLocator locator) {
        this.locator = locator;
    }

    /** @return true if {@code nvidia-smi -L} runs successfully, indicating an NVIDIA GPU + driver are present. */
    public boolean hasNvidiaGpu() {
        return locator.canRun(List.of("nvidia-smi", "-L"), PROBE_TIMEOUT_SECONDS);
    }
}
