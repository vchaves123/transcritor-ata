package com.tailor.transcritorata.diarization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.audio.ExternalProcessException;
import com.tailor.transcritorata.audio.ProcessRunner;

/**
 * {@link SpeakerDiarizer} backed by the LIUM_SpkDiarization tool, invoked as an external
 * {@code java -jar} process (reusing {@link ProcessRunner}, so it inherits the command banner,
 * live log streaming and cooperative cancellation used by the rest of the pipeline).
 *
 * <p>LIUM is a classic GMM/HMM diarizer: it is markedly less accurate than modern neural
 * pipelines, so this feature is surfaced to the user as "experimental".
 */
public final class LiumSpeakerDiarizer implements SpeakerDiarizer {

    private static final Logger LOG = LoggerFactory.getLogger(LiumSpeakerDiarizer.class);

    private final String javaExecutable;
    private final Path liumJar;
    private final long timeoutSeconds;

    public LiumSpeakerDiarizer(String javaExecutable, Path liumJar, long timeoutSeconds) {
        this.javaExecutable = javaExecutable;
        this.liumJar = liumJar;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public List<SpeakerTurn> diarize(Path wav, ProcessRunner.Handle handle, Consumer<String> logLineListener)
            throws DiarizationException {
        Path segFile;
        try {
            segFile = Files.createTempFile("transcritor-ata-diar-", ".seg").toAbsolutePath();
            Files.deleteIfExists(segFile);
        } catch (IOException e) {
            throw new DiarizationException("Não foi possível preparar o arquivo de diarização: " + e.getMessage(), e);
        }

        // O "showName" é apenas um rótulo interno exigido pelo LIUM; usamos um valor fixo.
        String showName = "reuniao";
        List<String> command = List.of(
                javaExecutable,
                "-Xmx2048m",
                "-jar", liumJar.toString(),
                "--fInputMask=" + wav.toString(),
                "--sOutputMask=" + segFile.toString(),
                "--doCEClustering",
                showName);

        LOG.info("Diarizando {} com LIUM ({})", wav, liumJar);
        try {
            ProcessRunner.run(command, handle, timeoutSeconds,
                    logLineListener != null ? logLineListener : line -> { });

            if (!Files.exists(segFile)) {
                throw new DiarizationException("O LIUM não gerou o arquivo de diarização esperado.");
            }
            return SegFileParser.parse(segFile);
        } catch (ExternalProcessException e) {
            throw new DiarizationException("Falha ao executar a diarização: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new DiarizationException("Falha ao ler o resultado da diarização: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(segFile);
            } catch (IOException e) {
                LOG.debug("Não foi possível remover o arquivo temporário {}: {}", segFile, e.getMessage());
            }
        }
    }
}
