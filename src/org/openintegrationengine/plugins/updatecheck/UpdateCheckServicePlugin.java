/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle for the update check.
 *
 * <p>A scheduled job rather than something the page does when it is opened, for the same
 * reason the volume monitor is: a version check that only happens while someone is looking
 * at a console is not a check, and the point of the chip is to be there the first time
 * somebody logs in after a release.
 *
 * <p>It is, though, the quietest scheduled job in this repository -- one request per
 * tracked project per day, per <i>cluster</i> rather than per node, and none at all when
 * the deployment has set {@code OIE_UPDATE_CHECK=false}.
 */
public class UpdateCheckServicePlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "Update Check";

    private static final Logger LOG = LogManager.getLogger(UpdateCheckServicePlugin.class);

    /**
     * The first pass waits, and not only to keep startup clear.
     *
     * <p>A container that is restarting in a loop would otherwise make a request on each
     * boot, and an engine coming up after an upgrade reports its old version for as long
     * as the configuration controller takes to settle -- five minutes costs nothing here
     * and avoids both.
     */
    private static final int STARTUP_DELAY_SECONDS = 300;

    /**
     * How often the scheduler wakes, which is not how often it checks. The interval that
     * matters is stored and shared, and every wake-up is a no-op unless the shared record
     * is older than it -- so an interval changed on the page takes effect within the hour
     * without a restart, and three nodes still make one request between them.
     */
    private static final int WAKE_INTERVAL_SECONDS = 3600;

    private static volatile UpdateCheckService service;

    private ScheduledExecutorService scheduler;

    public static UpdateCheckService service() {
        return service;
    }

    @Override
    public void init(Properties properties) {
        service = new UpdateCheckService();
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "update-check");
            // Daemon so an in-flight request cannot hold up engine shutdown.
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(this::pass, STARTUP_DELAY_SECONDS,
            WAKE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        if (UpdateCheckSettings.envDisabled()) {
            LOG.info("update check started, but OIE_UPDATE_CHECK is false: "
                + "nothing will be requested and the console will say so");
        } else {
            LOG.info("update check started: {} every {}h, first pass in {}s",
                UpdateCheckSettings.apiBase(), UpdateCheckSettings.intervalHours(),
                STARTUP_DELAY_SECONDS);
        }
    }

    /**
     * One wake-up.
     *
     * <p>Never allowed to throw. An exception escaping a scheduled task cancels every
     * future run, which would leave the check silently dead -- and a dead update check
     * looks exactly like an up-to-date one.
     */
    private void pass() {
        UpdateCheckService current = service;
        if (current == null) {
            return;
        }
        try {
            current.check(false);
        } catch (Throwable t) {
            LOG.error("update check pass failed", t);
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        service = null;
    }

    @Override
    public void update(Properties properties) {
        // The settings live in the configuration table and are read on each wake-up and on
        // each request, so there is nothing to reload.
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    /**
     * Two permissions, not one.
     *
     * <p>Reading the status is what draws the chip, so every signed-in user needs it or
     * the header silently differs between accounts. Running a check or changing the
     * settings is an administrative act -- it is the one that makes an outbound request --
     * and deployments that install an authorization plugin will want to grant them
     * separately.
     */
    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            new ExtensionPermission(PLUGIN_POINT_NAME, "View available updates",
                "Allows seeing which releases are available for this engine and its extensions.",
                new String[] {"updateCheckStatus"},
                new String[] {}),
            new ExtensionPermission(PLUGIN_POINT_NAME, "Manage the update check",
                "Allows checking the release feeds on demand and changing the check's settings.",
                new String[] {"updateCheckNow", "updateCheckSetSettings"},
                new String[] {})
        };
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Map.of();
    }
}
