/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the check gets its policy from, and the order of precedence.
 *
 * <p>Two layers, and the environment is the outer one:
 *
 * <ul>
 *   <li>{@code OIE_UPDATE_CHECK=false} stops the plugin calling out at all. It beats the
 *       stored setting and is not editable from the console, so it holds even against
 *       someone with administrator rights in the UI and cannot be undone by a database
 *       restored from another environment. That is what an air-gapped or
 *       egress-restricted deployment needs: a refusal that lives in the deployment, not
 *       in the data.
 *   <li>everything else is stored in the {@code configuration} table and editable on the
 *       Updates page, so a change survives the container rebuild this image does on every
 *       boot and is shared by every node against the same database.
 * </ul>
 *
 * <p>The same shape as the OIDC extension's pinning, and for the same reason: a deployment
 * that configures machines rather than consoles needs a setting the console cannot
 * override, and an operator reading a config file needs to see what it will do.
 */
final class UpdateCheckSettings {

    static final String GROUP = "Update Check";

    private static final String K_ENABLED = "enabled";
    private static final String K_INTERVAL = "intervalHours";

    /** Once a day. A release is not news within an hour of being published. */
    private static final int DEFAULT_INTERVAL_HOURS = 24;
    private static final int MIN_INTERVAL_HOURS = 1;
    /** A month. Past this the setting is indistinguishable from off, so say off instead. */
    private static final int MAX_INTERVAL_HOURS = 720;

    private UpdateCheckSettings() {
    }

    // ------------------------------------------------------------------
    // Environment
    // ------------------------------------------------------------------

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** True when the deployment has switched the check off outright. */
    static boolean envDisabled() {
        String v = env("OIE_UPDATE_CHECK");
        return v != null && ("false".equalsIgnoreCase(v) || "0".equals(v) || "off".equalsIgnoreCase(v));
    }

    /**
     * Where the release metadata is read from.
     *
     * <p>Overridable so a deployment that cannot reach {@code api.github.com} can point
     * this at a mirror that answers the same shape, rather than having to choose between
     * unexpected egress and no answer.
     */
    static String apiBase() {
        String v = env("OIE_UPDATE_CHECK_API_BASE");
        String base = v == null ? "https://api.github.com" : v;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * An optional bearer token, from the environment only.
     *
     * <p>Never stored and never returned by the API: the console has no field for it, so
     * there is no path by which it reaches a browser, an export or the git sync
     * extension's dump of the configuration table. Unauthenticated is the normal case --
     * two repositories once a day sits far inside the anonymous rate limit -- and the
     * token exists for a private mirror or a shared egress address that has burned
     * through it.
     */
    static String token() {
        return env("OIE_UPDATE_CHECK_TOKEN");
    }

    static String engineRepo() {
        String v = env("OIE_UPDATE_CHECK_ENGINE_REPO");
        return v == null ? "OpenIntegrationEngine/engine" : v;
    }

    static String webAdminRepo() {
        String v = env("OIE_UPDATE_CHECK_WEBADMIN_REPO");
        return v == null ? "gibson9583/oie-web-support-plugin" : v;
    }

    /**
     * Extra extensions to watch: {@code "Sentinel=gibson9583/oie-sentinel,Thread
     * Viewer=gibson9583/engine-thread-viewer"}.
     *
     * <p>The key is the extension's name as {@code plugin.xml} declares it (its directory
     * name works too), which is what makes the running version readable. An entry naming
     * an extension that is not installed is kept and shown as not installed, rather than
     * dropped -- a list shared between environments should be able to mention something
     * only production has.
     */
    static Map<String, String> extraExtensions() {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = env("OIE_UPDATE_CHECK_EXTENSIONS");
        if (raw == null) {
            return out;
        }
        for (String entry : raw.split(",")) {
            String e = entry.strip();
            int eq = e.indexOf('=');
            if (eq <= 0 || eq == e.length() - 1) {
                continue;
            }
            String name = e.substring(0, eq).strip();
            String repo = e.substring(eq + 1).strip();
            if (!name.isEmpty() && repo.contains("/")) {
                out.put(name, repo);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Stored
    // ------------------------------------------------------------------

    /** Whether the check may run: the deployment's refusal first, then the stored setting. */
    static boolean enabled() {
        return !envDisabled() && storedEnabled();
    }

    static boolean storedEnabled() {
        return !"false".equalsIgnoreCase(UpdateCheckStore.get(K_ENABLED, "true"));
    }

    static void setEnabled(boolean enabled) {
        UpdateCheckStore.put(K_ENABLED, Boolean.toString(enabled));
    }

    static int intervalHours() {
        int v = parseInt(UpdateCheckStore.get(K_INTERVAL, null), DEFAULT_INTERVAL_HOURS);
        return clampInterval(v);
    }

    static void setIntervalHours(int hours) {
        UpdateCheckStore.put(K_INTERVAL, Integer.toString(clampInterval(hours)));
    }

    private static int clampInterval(int hours) {
        return Math.max(MIN_INTERVAL_HOURS, Math.min(MAX_INTERVAL_HOURS, hours));
    }

    static int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
