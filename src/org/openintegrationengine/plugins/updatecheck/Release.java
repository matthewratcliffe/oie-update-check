/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

/**
 * The published release a component could move to, as the console needs it.
 *
 * <p>Only facts about the release are held here -- not whether it is an update. That
 * comparison is made against the running version every time the status is read, not when
 * the check ran, so the chip disappears the moment an engine comes back up on the new
 * version instead of waiting up to a day for the next check to notice.
 *
 * <p>{@code sha256} is best effort and often empty. Where a project publishes a checksums
 * asset the instructions can print the pinned {@code OIE_EXTENSION_URLS} entry ready to
 * paste; where it does not -- and two of the extensions this stack installs do not -- the
 * page says so rather than inventing one, because a hash this plugin computed itself from
 * a download it made would look like a vendor-published value and would not be one.
 */
final class Release {

    private static final int FIELDS = 6;

    final String version;
    final String publishedAt;
    final String releaseUrl;
    final String downloadUrl;
    final String assetName;
    final String sha256;

    Release(String version, String publishedAt, String releaseUrl, String downloadUrl,
            String assetName, String sha256) {
        this.version = nullToEmpty(version);
        this.publishedAt = nullToEmpty(publishedAt);
        this.releaseUrl = nullToEmpty(releaseUrl);
        this.downloadUrl = nullToEmpty(downloadUrl);
        this.assetName = nullToEmpty(assetName);
        this.sha256 = nullToEmpty(sha256);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.strip();
    }

    String toTsv() {
        return Tsv.join(version, publishedAt, releaseUrl, downloadUrl, assetName, sha256);
    }

    static Release parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] f = Tsv.split(line, FIELDS);
        if (f[0].isEmpty()) {
            return null;
        }
        return new Release(f[0], f[1], f[2], f[3], f[4], f[5]);
    }
}
