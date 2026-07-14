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

        LOG.info("Baixando modelo Whisper {} de {}", option.fileName(), option.downloadUrl());

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(option.downloadUrl())).GET().build();

        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Falha ao baixar o modelo (HTTP " + response.statusCode() + ").");
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);

            try (InputStream in = response.body();
                    OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                long downloaded = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled.get()) {
                        throw new IOException("Download cancelado pelo usuário.");
                    }
                    out.write(buffer, 0, read);
                    downloaded += read;
                    if (listener != null) {
                        listener.onProgress(downloaded, total);
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(partial);
            throw new IOException("Download interrompido: " + e.getMessage(), e);
        }
    }
}
