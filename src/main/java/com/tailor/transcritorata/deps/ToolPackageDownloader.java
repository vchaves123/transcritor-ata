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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads a {@link ToolPackageOption}'s zip archive (checksummed, resumable-free but
 * stall-guarded, cooperatively cancellable -- same shape as {@link WhisperModelDownloader}) and
 * extracts it into that option's own subfolder under {@code tools/}, so ffmpeg/whisper-cli can be
 * fetched on demand instead of always being bundled into the installer. Also updates
 * {@link ToolChecksumManifest}, so {@link BundledToolIntegrityChecker} still has something real to
 * verify on later startups.
 */
public final class ToolPackageDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(ToolPackageDownloader.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RESPONSE_HEADERS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_STALL_TIMEOUT = Duration.ofSeconds(30);

    @FunctionalInterface
    public interface ProgressListener {
        /** @param totalBytes -1 when the server didn't report a Content-Length. */
        void onProgress(long downloadedBytes, long totalBytes);
    }

    @FunctionalInterface
    public interface PhaseListener {
        /**
         * Called when work moves on to a new phase after the download itself completes --
         * extracting the archive and re-hashing the installed files both take real, unreported
         * time for a large package, and would otherwise look like a hang once the progress bar
         * already reads 100%.
         */
        void onPhase(String phase);
    }

    /**
     * Downloads and extracts {@code option} into {@code toolsDir}/{@code option.subDir()}.
     *
     * @return the path to the extracted executable (same as {@code option.expectedExecutable(toolsDir)})
     * @throws IOException if the download/checksum/extraction fails or is cancelled
     */
    public Path downloadAndInstall(ToolPackageOption option, Path toolsDir, ProgressListener listener,
            PhaseListener phaseListener, AtomicBoolean cancelled) throws IOException {
        Path targetDir = toolsDir.resolve(option.subDir());
        Files.createDirectories(toolsDir);
        Path zipFile = Files.createTempFile(toolsDir, "download-" + option.subDir() + "-", ".zip.part");
        try {
            downloadZip(option, zipFile, listener, cancelled);
            phaseListener.onPhase("Extracting " + option.label() + "...");
            extractZip(zipFile, targetDir, option.flattenTopLevelFolder());
            phaseListener.onPhase("Verifying " + option.label() + "...");
            ToolChecksumManifest.updateFor(toolsDir, targetDir);
            return option.expectedExecutable(toolsDir);
        } finally {
            Files.deleteIfExists(zipFile);
        }
    }

    private void downloadZip(ToolPackageOption option, Path zipFile, ProgressListener listener,
            AtomicBoolean cancelled) throws IOException {
        LOG.info("Downloading {} from {}", option.label(), option.downloadUrl());

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(option.downloadUrl())).GET().build();

        try (ExecutorService readExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpResponse<InputStream> response = sendWithTimeout(client, request, readExecutor);
            if (response.statusCode() != 200) {
                throw new IOException("Failed to download " + option.label() + " (HTTP " + response.statusCode() + ").");
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);

            MessageDigest digest = sha256Digest();
            try (InputStream in = new DigestInputStream(response.body(), digest);
                    OutputStream out = Files.newOutputStream(zipFile)) {
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
            if (!actualSha256.equalsIgnoreCase(option.sha256())) {
                throw new IOException("Downloaded " + option.label() + " failed checksum verification (expected "
                        + option.sha256() + ", got " + actualSha256
                        + "). The file may have been corrupted or tampered with in transit; it was not installed.");
            }
        }
    }

    /**
     * Extracts every entry of {@code zipFile} into {@code targetDir}, optionally stripping a
     * single common top-level folder (see {@link ToolPackageOption#flattenTopLevelFolder()}).
     * Rejects any entry whose resolved path would land outside {@code targetDir} ("zip-slip") --
     * this archive comes from a pinned, checksum-verified URL, but defense in depth costs nothing
     * here and this is the first place this codebase ever extracts a zip archive.
     */
    private void extractZip(Path zipFile, Path targetDir, boolean flattenTopLevelFolder) throws IOException {
        Files.createDirectories(targetDir);
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (flattenTopLevelFolder) {
                    int firstSlash = entryName.indexOf('/');
                    if (firstSlash < 0) {
                        continue; // the top-level folder entry itself; nothing to extract
                    }
                    entryName = entryName.substring(firstSlash + 1);
                    if (entryName.isEmpty()) {
                        continue;
                    }
                }

                Path destination = normalizedTarget.resolve(entryName).normalize();
                if (!destination.startsWith(normalizedTarget)) {
                    throw new IOException("Refusing to extract entry outside the target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Sends {@code request}, bounded by {@link #RESPONSE_HEADERS_TIMEOUT}. This is done via a
     * background task rather than {@code HttpRequest.Builder.timeout()} because that timeout was
     * found to also bound the time spent reading a large {@code ofInputStream()} body afterwards,
     * not just the wait for the response to start arriving -- which broke downloads of any file
     * that legitimately takes longer than the timeout to fully transfer. {@code send()} itself
     * returns as soon as headers are available (the body streams lazily from the returned
     * {@link InputStream}), so bounding only this call achieves the originally intended "time to
     * first byte" limit; {@link #readWithTimeout} bounds the rest of the transfer instead, without
     * capping how long it may legitimately take in total.
     */
    private static HttpResponse<InputStream> sendWithTimeout(HttpClient client, HttpRequest request,
            ExecutorService executor) throws IOException {
        Future<HttpResponse<InputStream>> future =
                executor.submit(() -> client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
        try {
            return future.get(RESPONSE_HEADERS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out waiting for a response (" + RESPONSE_HEADERS_TIMEOUT.getSeconds() + "s).");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Request failed: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted.", e);
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
            throw new IOException("Download stalled: no data received for " + READ_STALL_TIMEOUT.getSeconds() + "s.");
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
