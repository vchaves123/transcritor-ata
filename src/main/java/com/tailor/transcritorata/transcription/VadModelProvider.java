package com.tailor.transcritorata.transcription;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tailor.transcritorata.config.AppConfig;

/**
 * Extracts the Silero VAD model bundled as a jar resource to a real file on disk, so it can be
 * passed to whisper-cli's {@code -vm} flag (external processes can't read from inside the jar).
 *
 * <p>Enabling VAD makes whisper.cpp segment the audio by actual speech activity first, instead of
 * blindly processing fixed 30s windows regardless of content — this avoids the hallucinated/
 * repeated text and missed short utterances that whisper.cpp otherwise produces on recordings
 * with long stretches of silence between sparse speech.
 */
final class VadModelProvider {

    private static final Logger LOG = LoggerFactory.getLogger(VadModelProvider.class);
    private static final String RESOURCE_PATH = "/models/ggml-silero-v5.1.2.bin";
    private static final String FILE_NAME = "ggml-silero-v5.1.2.bin";

    private static volatile Path extractedModelPath;
    private static volatile boolean extractionFailed;

    private VadModelProvider() {
    }

    /** @return the path to the extracted VAD model, or empty if it couldn't be extracted. */
    static synchronized java.util.Optional<Path> resolve() {
        if (extractedModelPath != null) {
            return java.util.Optional.of(extractedModelPath);
        }
        if (extractionFailed) {
            return java.util.Optional.empty();
        }
        Path target = AppConfig.defaultConfigDir().resolve(FILE_NAME);
        try {
            // Re-extracted not just when missing, but also when a previously-extracted copy no
            // longer matches the jar's own resource: this file is fed to whisper-cli's native
            // model loader (-vm), so trusting a stale file without re-checking would let disk
            // corruption or in-place tampering by another process running as the same user go
            // undetected. Re-verified on every resolve() (not just once ever) precisely because
            // that kind of tampering can happen at any time after the first extraction.
            if (!Files.isRegularFile(target) || !matchesBundledResource(target)) {
                Files.createDirectories(target.getParent());
                try (InputStream in = VadModelProvider.class.getResourceAsStream(RESOURCE_PATH)) {
                    if (in == null) {
                        throw new IOException("Resource not found on classpath: " + RESOURCE_PATH);
                    }
                    Path tempFile = Files.createTempFile(target.getParent(), FILE_NAME, ".tmp");
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            extractedModelPath = target;
            return java.util.Optional.of(target);
        } catch (IOException e) {
            LOG.warn("Could not extract the VAD model; transcription will proceed without VAD: {}", e.getMessage());
            extractionFailed = true;
            return java.util.Optional.empty();
        }
    }

    /** @return true if {@code target}'s SHA-256 matches the bundled jar resource's. */
    private static boolean matchesBundledResource(Path target) {
        try (InputStream resourceIn = VadModelProvider.class.getResourceAsStream(RESOURCE_PATH);
                InputStream targetIn = Files.newInputStream(target)) {
            if (resourceIn == null) {
                return false;
            }
            return MessageDigest.isEqual(sha256(resourceIn), sha256(targetIn));
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be available on every JDK", e);
        }
    }
}
