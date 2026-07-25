package com.tailor.transcritorata.deps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks the GitHub Releases API for a newer published version than {@link AppVersion#CURRENT}.
 *
 * <p>Best-effort only: any network/parsing failure is logged and treated as "no update found" --
 * this check must never block or interrupt normal startup.
 */
public final class UpdateChecker {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateChecker.class);
    private static final String LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/vchaves123/transcritor-ata/releases/latest";
    // The only thing this URL is ever used for is Program.launch() (opening it in the user's
    // browser); this prefix is enforced on the API response before that happens so a compromised
    // or unexpectedly-shaped response can never make the app hand an arbitrary URL/string to the
    // OS's shell-open call.
    private static final String EXPECTED_RELEASE_URL_PREFIX =
            "https://github.com/vchaves123/transcritor-ata/releases/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    // GitHub's own "latest release" response is a few KB; this is a generous cap so a
    // misbehaving/compromised response can't be read fully into memory without bound.
    private static final long MAX_RESPONSE_BYTES = 1L << 20; // 1 MiB
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A newer release than what's currently running. */
    public record UpdateInfo(String version, String downloadUrl) {
    }

    private UpdateChecker() {
    }

    /** @return the latest release, if it's newer than {@link AppVersion#CURRENT}; empty otherwise or on failure. */
    public static Optional<UpdateInfo> checkForUpdate() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API_URL))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(TIMEOUT)
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                LOG.debug("Update check got HTTP {}; skipping.", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = MAPPER.readTree(readBounded(response.body(), TIMEOUT));
            String tagName = root.path("tag_name").asText("");
            String htmlUrl = root.path("html_url").asText("");
            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            if (latestVersion.isBlank() || htmlUrl.isBlank()) {
                LOG.debug("Update check response missing tag_name/html_url; skipping.");
                return Optional.empty();
            }
            if (!htmlUrl.startsWith(EXPECTED_RELEASE_URL_PREFIX)) {
                LOG.debug("Update check response's html_url ({}) isn't a github.com release link for this repo; skipping.",
                        htmlUrl);
                return Optional.empty();
            }

            if (isNewer(latestVersion, AppVersion.CURRENT)) {
                return Optional.of(new UpdateInfo(latestVersion, htmlUrl));
            }
            return Optional.empty();
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Could not check for updates: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads {@code in} fully as UTF-8, refusing to read more than {@link #MAX_RESPONSE_BYTES} --
     * without this, a misbehaving/compromised response with no sane size could be read entirely
     * into memory before parsing even starts.
     *
     * <p>The read itself also has a hard deadline of {@code timeout}: {@link HttpRequest.Builder#timeout}
     * only bounds how long {@code send()} takes to receive the response headers for a
     * {@code BodyHandlers.ofInputStream()} request -- once that returns, reading the body here
     * happens with no further deadline of its own, so a connection that stalls mid-body could
     * otherwise block indefinitely despite the 5-second timeout configured on the request. Reading
     * on a background thread and bounding it with {@link Future#get(long, TimeUnit)} enforces that
     * deadline for real; on timeout the stream is closed to unblock the in-progress read.
     */
    private static String readBounded(InputStream in, Duration timeout) throws IOException {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "update-check-reader");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<byte[]> task = executor.submit(() -> {
                byte[] buffer = new byte[8192];
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_RESPONSE_BYTES) {
                        throw new IOException(
                                "Update check response exceeded " + MAX_RESPONSE_BYTES + " bytes; aborting.");
                    }
                }
                return out.toByteArray();
            });
            byte[] bytes = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (TimeoutException e) {
            closeQuietly(in);
            throw new IOException("Update check response stalled past " + timeout + "; aborting.");
        } catch (ExecutionException e) {
            closeQuietly(in);
            throw (e.getCause() instanceof IOException io) ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(in);
            throw new IOException("Interrupted while reading update check response.", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Best-effort: only used to unblock a stalled read on the worker thread.
        }
    }

    /** @return true if {@code remoteVersion} is numerically newer than {@code currentVersion} (e.g. "1.0.10" > "1.0.9"). */
    static boolean isNewer(String remoteVersion, String currentVersion) {
        int[] remote = parseVersion(remoteVersion);
        int[] current = parseVersion(currentVersion);
        int length = Math.max(remote.length, current.length);
        for (int i = 0; i < length; i++) {
            int r = i < remote.length ? remote[i] : 0;
            int c = i < current.length ? current[i] : 0;
            if (r != c) {
                return r > c;
            }
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parseLeadingInt(parts[i]);
        }
        return result;
    }

    /** Tolerates a trailing non-numeric suffix (e.g. "7-beta"), parsing only the leading digits. */
    private static int parseLeadingInt(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
