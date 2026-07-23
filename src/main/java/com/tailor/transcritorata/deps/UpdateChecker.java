package com.tailor.transcritorata.deps;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

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
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
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
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.debug("Update check got HTTP {}; skipping.", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = MAPPER.readTree(response.body());
            String tagName = root.path("tag_name").asText("");
            String htmlUrl = root.path("html_url").asText("");
            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            if (latestVersion.isBlank() || htmlUrl.isBlank()) {
                LOG.debug("Update check response missing tag_name/html_url; skipping.");
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
