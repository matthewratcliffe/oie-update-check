/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the console asks, and the pass that answers it.
 *
 * <p>Two jobs, kept apart on purpose:
 *
 * <ul>
 *   <li>{@link #status()} reads. It never touches the network, so the page and the chip
 *       load at the speed of one query against the {@code configuration} table, and an
 *       engine with no route out is exactly as fast as one with a route out.
 *   <li>{@link #check(boolean)} writes. It reads the release feeds and stores what it
 *       found. A scheduler runs it; the page's <b>Check now</b> button runs it; nothing
 *       else does.
 * </ul>
 *
 * <p>And a third thing it deliberately does not do: apply anything. There is no code here
 * that downloads a release, writes into {@code extensions/} or restarts the engine. Moving
 * an engine that carries clinical traffic to a new version is a decision with a
 * maintenance window, a database backup and a rebuild of every extension behind it -- the
 * console's job is to say a release exists and what applying it involves, and then get out
 * of the way of the person who decides.
 */
public final class UpdateCheckService {

    private static final Logger LOG = LogManager.getLogger(UpdateCheckService.class);

    /** Fields of a component row, in order. See the console plugin's COMPONENT_FIELDS. */
    private static final String STATE_UPDATE = "update";
    private static final String STATE_CURRENT = "current";
    private static final String STATE_UNKNOWN = "unknown";
    private static final String STATE_MISSING = "not-installed";

    /**
     * One check at a time in this JVM. The scheduled pass and a person hitting Check now
     * are the same work, and doing it twice at once would double the requests while
     * halving the chance of a coherent answer.
     */
    private final AtomicBoolean checking = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The whole state of the check, as the console renders it. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Release> cached = UpdateCheckStore.releases();

        List<String> rows = new ArrayList<>();
        int updates = 0;

        for (Component component : Component.tracked()) {
            String running = component.runningVersion();
            Release latest = cached.get(component.id());

            String state;
            if (component.kind() == Component.Kind.EXTENSION && running == null) {
                state = STATE_MISSING;
            } else if (latest == null || running == null) {
                state = STATE_UNKNOWN;
            } else if (Version.isUpgrade(Version.parse(running), Version.parse(latest.version))) {
                state = STATE_UPDATE;
                updates++;
            } else {
                state = STATE_CURRENT;
            }

            rows.add(Tsv.join(
                component.id(),
                component.label(),
                component.kind().name().toLowerCase(),
                running == null ? "" : running,
                latest == null ? "" : latest.version,
                state,
                latest == null ? "" : latest.publishedAt,
                latest == null ? "" : latest.releaseUrl,
                latest == null ? "" : latest.downloadUrl,
                latest == null ? "" : latest.assetName,
                latest == null ? "" : latest.sha256,
                component.repo()));
        }

        Instant checkedAt = UpdateCheckStore.checkedAt();

        out.put("ok", Boolean.TRUE);
        out.put("updates", Integer.toString(updates));
        out.put("enabled", Boolean.toString(UpdateCheckSettings.enabled()));
        // Told apart from the stored setting so the page can explain a switch it must not
        // offer to flip: OIE_UPDATE_CHECK is the deployment's answer, not the console's.
        out.put("envDisabled", Boolean.toString(UpdateCheckSettings.envDisabled()));
        out.put("storedEnabled", Boolean.toString(UpdateCheckSettings.storedEnabled()));
        out.put("intervalHours", Integer.toString(UpdateCheckSettings.intervalHours()));
        out.put("checkedAt", checkedAt == null ? "" : checkedAt.toString());
        out.put("checking", Boolean.toString(checking.get()));
        out.put("error", UpdateCheckStore.error());
        // Named so the page can say which host it contacts without the reader having to
        // trust that it is the default one.
        out.put("apiBase", UpdateCheckSettings.apiBase());
        out.put("components", rows);
        return out;
    }

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    /**
     * Reads the release feeds, if it should.
     *
     * @param force from the Check now button: skips the freshness test, so someone
     *        watching for a release they know is coming is not told to wait a day, but
     *        still cannot run a check the deployment has switched off
     */
    public Map<String, Object> check(boolean force) {
        if (!UpdateCheckSettings.enabled()) {
            return status();
        }
        if (!force && UpdateCheckStore.isFresh(Duration.ofHours(UpdateCheckSettings.intervalHours()))) {
            // Another node has already done it, or this one did before a restart.
            return status();
        }
        if (!checking.compareAndSet(false, true)) {
            return status();
        }

        try {
            // Claimed before the first request, not after the last: a second node starting
            // in the same minute sees a fresh timestamp and leaves this one to it.
            UpdateCheckStore.setCheckedAt(Instant.now());

            List<String> problems = new ArrayList<>();
            Map<String, Release> previous = UpdateCheckStore.releases();
            List<String> trackedIds = new ArrayList<>();

            for (Component component : Component.tracked()) {
                trackedIds.add(component.id());
                if (component.isMissing()) {
                    // Nothing to compare against, so nothing worth asking a release feed
                    // about -- and a reason not to spend a request on it either.
                    continue;
                }
                try {
                    Release release = ReleaseFeed.latest(component);
                    if (release == null) {
                        problems.add(component.label() + ": no published release found");
                        continue;
                    }
                    UpdateCheckStore.saveRelease(component.id(), release);
                    announce(component, previous.get(component.id()), release);
                } catch (Exception e) {
                    // One unreachable project must not cost the answer for the other.
                    problems.add(component.label() + ": " + message(e));
                    LOG.debug("update check: {} failed", component.label(), e);
                }
            }

            // Drop cached records for things no longer tracked.
            for (String id : previous.keySet()) {
                if (!trackedIds.contains(id)) {
                    UpdateCheckStore.forgetRelease(id);
                }
            }

            UpdateCheckStore.setError(String.join("; ", problems));
            if (!problems.isEmpty()) {
                // A person reads this one in the server log and the page; it is the whole
                // difference between "nothing new" and "has not worked since March".
                LOG.info("update check finished with problems: {}", String.join("; ", problems));
            }
        } finally {
            checking.set(false);
        }
        return status();
    }

    /**
     * Says so in the log the first time a release is seen.
     *
     * <p>Once per new version, not once per pass: a line every day saying the same release
     * is still available is how a log stops being read. The engine's root logger is at
     * ERROR, and this repository's entrypoint raises {@code org.openintegrationengine} to
     * INFO for exactly this kind of operational fact.
     */
    private void announce(Component component, Release before, Release after) {
        if (before != null && before.version.equals(after.version)) {
            return;
        }
        String running = component.runningVersion();
        if (Version.isUpgrade(Version.parse(running), Version.parse(after.version))) {
            LOG.info("{} {} is available (running {}): {}", component.label(), after.version,
                running, after.releaseUrl.isEmpty() ? component.repo() : after.releaseUrl);
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m.strip();
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /**
     * Applies the settings from the page.
     *
     * <p>Turning the check on is followed immediately by a forced pass. Someone who has
     * just enabled it is asking the question now, and leaving them with an empty page and
     * "next check in 24 hours" would read as broken.
     */
    public Map<String, Object> saveSettings(Map<String, String> settings) {
        if (settings == null) {
            return status();
        }

        boolean wasEnabled = UpdateCheckSettings.storedEnabled();
        boolean enable = wasEnabled;

        String enabled = settings.get("enabled");
        if (enabled != null && !enabled.isBlank()) {
            enable = Boolean.parseBoolean(enabled.strip());
            UpdateCheckSettings.setEnabled(enable);
        }

        String interval = settings.get("intervalHours");
        if (interval != null && !interval.isBlank()) {
            UpdateCheckSettings.setIntervalHours(
                UpdateCheckSettings.parseInt(interval, UpdateCheckSettings.intervalHours()));
        }

        if (enable && !wasEnabled && UpdateCheckSettings.enabled()) {
            return check(true);
        }
        return status();
    }
}
