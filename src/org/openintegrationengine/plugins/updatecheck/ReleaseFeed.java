/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a project's published releases and works out which one is the newest.
 *
 * <p>Deliberately <b>not</b> {@code /releases/latest}. GitHub's "latest" is the most
 * recently published non-prerelease, which is not the same as the highest version: this
 * engine's own 4.5.2 and 4.6.0 were published on the same day, and any project that
 * back-ports a patch to an older line will at some point have a "latest" that is older
 * than what you are running. So the list is read and ordered here, by
 * {@link Version}, with drafts and pre-releases dropped.
 *
 * <p>Jackson comes from the engine's {@code server-lib/jackson} rather than being bundled,
 * the same route by which the key store extension reads its vaults' JSON.
 */
final class ReleaseFeed {

    private static final Logger LOG = LogManager.getLogger(ReleaseFeed.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Enough history to find the highest version without paging. */
    private static final int PER_PAGE = 30;

    /** A checksums asset is a few lines of text; anything larger is not one. */
    private static final long MAX_CHECKSUM_ASSET_BYTES = 64 * 1024;

    private static final Pattern CHECKSUMS_ASSET =
        Pattern.compile("(?i)^(sha256sums?(\\.txt)?|checksums?(\\.txt)?)$");

    /** "<64 hex>  filename" or "<64 hex> *filename", as sha256sum writes it. */
    private static final Pattern CHECKSUM_LINE =
        Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(.+)$");

    private ReleaseFeed() {
    }

    /**
     * The highest published release of {@code component}'s project.
     *
     * @return the release, or null when the project has published none this can order
     * @throws IOException when the feed could not be read at all -- which is recorded and
     *         shown, because a check that has been failing for a month should not look
     *         the same as one that found nothing new
     */
    static Release latest(Component component) throws IOException {
        String repo = component.repo();
        if (repo == null || repo.isBlank() || !repo.contains("/")) {
            throw new IOException("not a usable owner/repo: '" + repo + "'");
        }

        String url = UpdateCheckSettings.apiBase() + "/repos/" + encodePath(repo)
            + "/releases?per_page=" + PER_PAGE;
        Http.Result res = Http.get(url, headers());
        if (!res.ok()) {
            throw new IOException(Http.describe("reading releases for " + repo, res));
        }

        JsonNode releases;
        try {
            releases = MAPPER.readTree(res.body);
        } catch (Exception e) {
            throw new IOException("releases for " + repo + " were not JSON: " + e.getMessage(), e);
        }
        if (releases == null || !releases.isArray()) {
            throw new IOException("releases for " + repo + " were not a list");
        }

        JsonNode best = null;
        Version bestVersion = null;
        for (JsonNode release : releases) {
            if (release == null || !release.isObject()) {
                continue;
            }
            if (release.path("draft").asBoolean(false) || release.path("prerelease").asBoolean(false)) {
                continue;
            }
            Version v = Version.parse(text(release, "tag_name"));
            if (v == null) {
                v = Version.parse(text(release, "name"));
            }
            if (v == null) {
                // A release tagged something this cannot order is not an error: projects
                // carry odd tags, and the next one along is usually ordinary.
                continue;
            }
            if (v.isPreRelease()) {
                continue;
            }
            if (bestVersion == null || v.compareTo(bestVersion) > 0) {
                bestVersion = v;
                best = release;
            }
        }

        if (best == null) {
            return null;
        }

        JsonNode asset = pickAsset(best, component.assetPattern());
        String downloadUrl = asset == null ? "" : text(asset, "browser_download_url");
        String assetName = asset == null ? "" : text(asset, "name");
        String sha256 = assetName.isEmpty() ? "" : checksumFor(best, assetName);

        return new Release(bestVersion.raw(), text(best, "published_at"),
            text(best, "html_url"), downloadUrl, assetName, sha256);
    }

    /**
     * The artifact among a release's assets, or null.
     *
     * <p>The first match wins, and the pattern is the component's: the engine release
     * carries Windows, macOS and Linux archives plus an installer, and picking the wrong
     * one would put a download link on the page that nobody in this stack can use.
     */
    private static JsonNode pickAsset(JsonNode release, Pattern pattern) {
        JsonNode assets = release.path("assets");
        if (!assets.isArray()) {
            return null;
        }
        for (JsonNode asset : assets) {
            String name = text(asset, "name");
            if (!name.isEmpty() && pattern.matcher(name).matches()) {
                return asset;
            }
        }
        return null;
    }

    /**
     * The published SHA-256 of {@code assetName}, or empty.
     *
     * <p>Looks for a checksums asset beside the artifact, then for {@code <artifact>.sha256}
     * -- between them that covers how the projects this stack installs publish theirs.
     * Every failure here is silent and returns empty: a missing checksum makes the
     * instructions less convenient, not wrong, and it must never be the reason a check
     * reports an error.
     */
    private static String checksumFor(JsonNode release, String assetName) {
        JsonNode assets = release.path("assets");
        if (!assets.isArray()) {
            return "";
        }

        for (JsonNode asset : assets) {
            String name = text(asset, "name");
            boolean candidate = CHECKSUMS_ASSET.matcher(name).matches()
                || name.equalsIgnoreCase(assetName + ".sha256");
            if (!candidate) {
                continue;
            }
            if (asset.path("size").asLong(Long.MAX_VALUE) > MAX_CHECKSUM_ASSET_BYTES) {
                continue;
            }
            String url = text(asset, "browser_download_url");
            if (url.isEmpty()) {
                continue;
            }
            try {
                Http.Result res = Http.get(url, headers());
                if (!res.ok()) {
                    continue;
                }
                String hash = findHash(res.body, assetName);
                if (!hash.isEmpty()) {
                    return hash;
                }
            } catch (IOException e) {
                LOG.debug("update check: could not read {}: {}", name, e.getMessage());
            }
        }
        return "";
    }

    /**
     * Pulls one artifact's hash out of a checksums file.
     *
     * <p>A single-entry file that names nothing (a bare {@code <hash>} in an
     * {@code <artifact>.sha256}) is taken as the artifact's, since that is what such a
     * file is. Otherwise the filename must match, or nothing is returned -- attaching one
     * artifact's hash to another would produce a pin that fails the boot it was meant to
     * secure.
     */
    private static String findHash(String body, String assetName) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        if (trimmed.matches("(?i)^[0-9a-f]{64}$")) {
            return trimmed.toLowerCase();
        }
        for (String line : trimmed.split("\\R")) {
            Matcher m = CHECKSUM_LINE.matcher(line.strip());
            if (m.matches() && m.group(2).strip().equalsIgnoreCase(assetName)) {
                return m.group(1).toLowerCase();
            }
        }
        return "";
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        // Named so an operator reading an egress log, or a maintainer reading their
        // traffic, can tell what this is.
        headers.put("User-Agent", "oie-update-check");
        String token = UpdateCheckSettings.token();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token.strip());
        }
        return headers;
    }

    /** owner/repo, each segment encoded, the slash kept. */
    private static String encodePath(String repo) {
        String[] parts = repo.strip().split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? "" : value.asText().strip();
    }
}
