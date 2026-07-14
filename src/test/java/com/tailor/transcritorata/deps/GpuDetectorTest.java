package com.tailor.transcritorata.deps;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GpuDetectorTest {

    @Test
    void reportsGpuPresentWhenNvidiaSmiRunsSuccessfully() {
        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.canRun(eq(List.of("nvidia-smi", "-L")), anyLong())).thenReturn(true);

        assertTrue(new GpuDetector(locator).hasNvidiaGpu());
    }

    @Test
    void reportsGpuAbsentWhenNvidiaSmiIsNotAvailable() {
        ExecutableLocator locator = mock(ExecutableLocator.class);
        when(locator.canRun(eq(List.of("nvidia-smi", "-L")), anyLong())).thenReturn(false);

        assertFalse(new GpuDetector(locator).hasNvidiaGpu());
    }
}
