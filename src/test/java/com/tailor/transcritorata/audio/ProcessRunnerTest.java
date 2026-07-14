package com.tailor.transcritorata.audio;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    @Test
    void formatsCommandLineQuotingArgumentsWithSpaces() {
        String line = ProcessRunner.formatCommandLine(
                List.of("ffmpeg", "-i", "C:\\Videos\\reunião de diretoria.mkv", "-y", "saida.wav"));

        assertEquals("ffmpeg -i \"C:\\Videos\\reunião de diretoria.mkv\" -y saida.wav", line);
    }

    @Test
    void emitsCommandBannerBeforeRunningTheProcess() throws Exception {
        AtomicReference<String> firstLine = new AtomicReference<>();
        ProcessRunner.Handle handle = new ProcessRunner.Handle();

        // "cmd /c echo ok" is a trivial, always-available Windows command used only to exercise
        // the banner + line-streaming behavior, not real ffmpeg/whisper-cli output.
        ProcessRunner.run(List.of("cmd", "/c", "echo ok"), handle, 10, line -> {
            if (firstLine.get() == null) {
                firstLine.set(line);
            }
        });

        assertTrue(firstLine.get().startsWith(">> cmd /c"), "a primeira linha deve ser o banner do comando");
    }

    @Test
    void throwsProcessCancelledExceptionInsteadOfGenericFailureWhenCancelled() throws Exception {
        ProcessRunner.Handle handle = new ProcessRunner.Handle();

        // "ping -n 20 127.0.0.1" is a long-running, always-available Windows command used only
        // to give the cancel() call below time to fire before the process would exit on its own.
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handle.cancel();
        });
        canceller.start();

        assertThrows(ProcessCancelledException.class, () ->
                ProcessRunner.run(List.of("cmd", "/c", "ping -n 20 127.0.0.1 > nul"), handle, 30, line -> { }));

        canceller.join();
    }
}
