/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * What the last check found, and when.
 *
 * <pre>
 *   enabled, intervalHours   the settings, editable on the Updates page
 *   checkedAt                when a check last completed, ISO-8601 instant
 *   error                    why the last one did not, empty when it did
 *   latest.&lt;componentId&gt;     the newest published release of that component
 * </pre>
 *
 * <p>Stored through {@code ConfigurationController.saveProperty}, which writes to the
 * {@code configuration} table. That is what makes it survive this image's reset of
 * {@code conf/} and {@code extensions/} on every boot, and it is also what makes the
 * result <b>shared</b>: every node against the same database reads the same answer.
 *
 * <p>That sharing is the whole of this plugin's cluster story. {@code checkedAt} is
 * written before the network call rather than after, so a node starting up finds a fresh
 * timestamp and skips the check its neighbour is already making. No leader election, no
 * per-node settings, and nothing to add to {@code OIE_DISABLE_EXTENSIONS} -- three engines
 * make one request a day between them, and a node whose console someone opens answers from
 * the shared record whether or not it was the one that made it.
 *
 * <p>Only facts about the releases are kept here. Whether any of them is an <i>update</i>
 * is worked out against the running version each time the status is read, so an engine
 * that comes back up on the new version stops claiming one immediately rather than at the
 * next check.
 */
final class UpdateCheckStore {

    private static final String PREFIX_LATEST = "latest.";
    private static final String K_CHECKED_AT = "checkedAt";
    private static final String K_ERROR = "error";

    private static final Logger LOG = LogManager.getLogger(UpdateCheckStore.class);

    private UpdateCheckStore() {
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static Properties stored() {
        try {
            Properties p = config().getPropertiesForGroup(UpdateCheckSettings.GROUP);
            return p == null ? new Properties() : p;
        } catch (Exception e) {
            // Before the database is up, or during shutdown. An empty set reads as
            // "never checked", which is true enough and harmless.
            LOG.debug("update check: could not read stored settings: {}", e.getMessage());
            return new Properties();
        }
    }

    static String get(String key, String fallback) {
        String v = stored().getProperty(key);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    /**
     * {@code saveProperty} is an UPDATE, then an INSERT when the UPDATE matched nothing,
     * so two nodes creating the same property for the first time can both reach the INSERT
     * and one loses on the unique key. The retry finds the row present and takes the
     * UPDATE path, which is the right outcome either way.
     */
    static void put(String key, String value) {
        try {
            config().saveProperty(UpdateCheckSettings.GROUP, key, value == null ? "" : value);
        } catch (RuntimeException first) {
            try {
                config().saveProperty(UpdateCheckSettings.GROUP, key, value == null ? "" : value);
            } catch (RuntimeException second) {
                // Never fatal: failing to cache a version check must not take anything
                // else down with it.
                LOG.warn("update check: could not store {}: {}", key, second.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    static Map<String, Release> releases() {
        Properties p = stored();
        Map<String, Release> out = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(PREFIX_LATEST)) {
                continue;
            }
            Release r = Release.parse(p.getProperty(key));
            if (r != null) {
                out.put(key.substring(PREFIX_LATEST.length()), r);
            }
        }
        return out;
    }

    static void saveRelease(String componentId, Release release) {
        if (release == null) {
            return;
        }
        put(PREFIX_LATEST + componentId, release.toTsv());
    }

    /**
     * Forgets a component's cached release.
     *
     * <p>Used when a component is no longer tracked, so a record for something that has
     * been dropped from {@code OIE_UPDATE_CHECK_EXTENSIONS} does not sit in the table
     * forever being ignored.
     */
    static void forgetRelease(String componentId) {
        try {
            config().removeProperty(UpdateCheckSettings.GROUP, PREFIX_LATEST + componentId);
        } catch (Exception e) {
            LOG.debug("update check: could not remove {}: {}", componentId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // When, and whether it worked
    // ------------------------------------------------------------------

    static Instant checkedAt() {
        String v = get(K_CHECKED_AT, "");
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static void setCheckedAt(Instant when) {
        put(K_CHECKED_AT, when.toString());
    }

    static String error() {
        return get(K_ERROR, "");
    }

    static void setError(String message) {
        put(K_ERROR, message == null ? "" : message);
    }

    /**
     * Whether a check has completed, or been started by another node, within
     * {@code interval}.
     *
     * <p>A timestamp in the future is treated as fresh rather than being corrected. Clocks
     * differ between nodes, and the cost of waiting one more interval is nothing next to
     * two nodes deciding in turn that the other's timestamp is wrong.
     */
    static boolean isFresh(Duration interval) {
        Instant last = checkedAt();
        return last != null && Duration.between(last, Instant.now()).compareTo(interval) < 0;
    }
}
