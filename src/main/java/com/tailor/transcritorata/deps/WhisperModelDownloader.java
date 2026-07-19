package com.tailor.transcritorata.deps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a {@link WhisperModelOption} into a target directory, reporting progress and
 * supporting cooperative cancellation. Writes to a {@code .part} file first so a failed or
 * cancelled download never leaves a corrupt/truncated model file at the final path.
 */
public final class WhisperModelDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(WhisperModelDownloader.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RESPONSE_HEADERS_TIMEOUT = Duration.ofSeconds(30);
    // A MITM'd/misbehaving server could accept the connection and then simply never send (or
    // trickle) body bytes; HttpRequest's own timeout only bounds waiting for response headers,
    // not reading an ofInputStream() body afterwards, so each read is bounded individually.
    private static final Duration READ_STALL_TIMEOUT = Duration.ofSeconds(30);

    @FunctionalInterface
    public interface ProgressListener {
        /** @param totalBytes -1 when the server didn't report a Content-Length. */
        void onProgress(long downloadedBytes, long totalBytes);
    }

    /**
     * @return the final path of the downloaded model file
     * @throws IOException if the download fails, is cancelled, or the server responds with an error
     */
    public Path download(WhisperModelOption option, Path targetDir, ProgressListener listener,
            AtomicBoolean cancelled) throws IOException {
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(option.fileName());
        Path partial = targetDir.resolve(option.fileName() + ".part");

        LOG.info("Downloading Whisper model {} from {}", option.fileName(), option.downloadUrl());

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(option.downloadUrl()))
                .timeout(RESPONSE_HEADERS_TIMEOUT)
                .GET().build();

        try (ExecutorService readExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to download the model (HTTP " + response.statusCode() + ").");
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);

            MessageDigest digest = sha256Digest();
            try (InputStream in = new DigestInputStream(response.body(), digest);
                    OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                long downloaded = 0;
                int read;
                while ((read = readWithTimeout(in, buffer, readExecutor)) != -1) {
                    if (cancelled.get()) {
                        throw new IOException("Download cancelled by the user.");
                    }
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (listener != null) {
                        listener.onProgress(downloaded, total);
                    }
                }
            }

            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            String expectedSha256 = option.sha256();
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("Downloaded model failed checksum verification (expected " + expectedSha256
                        + ", got " + actualSha256 + "). The file may have been corrupted or tampered with in "
                        + "transit; it was not saved.");
            }

            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(partial);
            throw new IOException("Download interrupted: " + e.getMessage(), e);
        }
    }

    /**
     * Reads one chunk from {@code in}, bounded by {@link #READ_STALL_TIMEOUT}: a connection that
     * stays open but stops sending data would otherwise block this call (and the Cancel button
     * with it) indefinitely.
     */
    private static int readWithTimeout(InputStream in, byte[] buffer, ExecutorService readExecutor)
            throws IOException {
        Future<Integer> future = readExecutor.submit(() -> in.read(buffer));
        try {
            return future.get(READ_STALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException(
                    "Download stalled: no data received for " + READ_STALL_TIMEOUT.getSeconds() + "s.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Download failed: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted.", e);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be available on every JDK", e);
        }
    }
}
