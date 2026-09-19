/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.server.controllers.ControllerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One thing whose version this plugin watches.
 *
 * <p>Two by default, which are the two halves of what an administrator is looking at when
 * they read the version in the console header:
 *
 * <ul>
 *   <li><b>the engine</b> -- its running version comes from the configuration controller,
 *       the same string {@code GET /api/server/version} returns and the same one
 *       {@code ExtensionLoader} matches extensions against.
 *   <li><b>the web administrator</b> -- which is not a thing you upgrade on its own: what
 *       you install is the Web Support extension, and its {@code pluginVersion} is what
 *       the release you would fetch is tagged with. So that is what is compared, rather
 *       than the console bundle's own build version, which nothing publishes releases of.
 * </ul>
 *
 * <p>More can be added with {@code OIE_UPDATE_CHECK_EXTENSIONS}, because this stack
 * installs four other community extensions the same way and they release on the same
 * pattern -- see the plugin README. Nothing built from {@code plugins/} is tracked: those
 * are built from this repository, and their version is whatever the last build stamped.
 */
final class Component {

    enum Kind {
        /** The engine itself: a new release means rebuilding the image. */
        ENGINE,
        /** An installed extension: a new release means a new zip and a restart. */
        EXTENSION
    }

    private final String id;
    private final String label;
    private final Kind kind;
    private final String repo;
    /** For EXTENSION: the name in plugin.xml, which is the key into the metadata map. */
    private final String extensionName;
    /** Recognises the artifact among a release's assets. */
    private final Pattern asset;

    private Component(String id, String label, Kind kind, String repo, String extensionName,
                      Pattern asset) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.repo = repo;
        this.extensionName = extensionName;
        this.asset = asset;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    Kind kind() {
        return kind;
    }

    String repo() {
        return repo;
    }

    Pattern assetPattern() {
        return asset;
    }

    /**
     * What is running now, or null when it cannot be read.
     *
     * <p>Null is a real answer here and is shown as "unknown" rather than being treated as
     * old: an extension that is not installed, or one whose metadata the engine could not
     * parse, must not produce a chip telling someone to upgrade it.
     */
    String runningVersion() {
        try {
            if (kind == Kind.ENGINE) {
                String v = ControllerFactory.getFactory()
                    .createConfigurationController().getServerVersion();
                return v == null || v.isBlank() ? null : v.strip();
            }
            Map<String, PluginMetaData> plugins = ControllerFactory.getFactory()
                .createExtensionController().getPluginMetaData();
            if (plugins == null) {
                return null;
            }
            for (PluginMetaData meta : plugins.values()) {
                if (meta == null) {
                    continue;
                }
                // Matched on the extension's declared name, and on its directory name as
                // a fallback: the map is keyed by name, but which of the two an operator
                // would type into OIE_UPDATE_CHECK_EXTENSIONS is not obvious, and both
                // identify the extension unambiguously.
                if (extensionName.equalsIgnoreCase(meta.getName())
                        || extensionName.equalsIgnoreCase(meta.getPath())) {
                    String v = meta.getPluginVersion();
                    return v == null || v.isBlank() ? null : v.strip();
                }
            }
            return null;
        } catch (Exception e) {
            // A controller that is not ready yet, most likely during startup. Unknown.
            return null;
        }
    }

    /** True when this names an extension that is not installed on this engine. */
    boolean isMissing() {
        return kind == Kind.EXTENSION && runningVersion() == null;
    }

    // ------------------------------------------------------------------
    // The tracked set
    // ------------------------------------------------------------------

    private static final Pattern ENGINE_ASSET =
        Pattern.compile("(?i)^oie_unix_.*\\.tar\\.gz$");
    private static final Pattern ZIP_ASSET =
        Pattern.compile("(?i)^.*\\.zip$");

    static final String ENGINE_ID = "engine";

    /**
     * The components to check, in display order.
     *
     * <p>Built fresh on each pass rather than cached, so an extension installed since the
     * last engine start is picked up without one, and so an operator changing
     * {@code OIE_UPDATE_CHECK_EXTENSIONS} only has to restart the container -- which they
     * have to do to change an environment variable anyway.
     */
    static List<Component> tracked() {
        Map<String, Component> out = new LinkedHashMap<>();

        out.put(ENGINE_ID, new Component(ENGINE_ID, "Open Integration Engine", Kind.ENGINE,
            UpdateCheckSettings.engineRepo(), null, ENGINE_ASSET));

        out.put("websupport", new Component("websupport", "Web administrator",
            Kind.EXTENSION, UpdateCheckSettings.webAdminRepo(), "Web Support", ZIP_ASSET));

        // Extras: "Sentinel=gibson9583/oie-sentinel,Thread Viewer=owner/repo"
        for (Map.Entry<String, String> extra : UpdateCheckSettings.extraExtensions().entrySet()) {
            String name = extra.getKey();
            String id = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            if (id.isEmpty() || out.containsKey(id)) {
                continue;
            }
            out.put(id, new Component(id, name, Kind.EXTENSION, extra.getValue(), name, ZIP_ASSET));
        }

        return new ArrayList<>(out.values());
    }
}
