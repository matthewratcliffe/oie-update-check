/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import java.util.Map;

/**
 * REST surface for the update check, under {@code /api/updatecheck}.
 *
 * <p>Everything returns a map whose {@code components} entry is a list of tab-separated
 * rows -- see {@link Tsv} for why that is not laziness.
 */
@Path("/updatecheck")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface UpdateCheckServletInterface extends BaseServletInterface {

    /**
     * What the last check found, compared against what is running now.
     *
     * <p>Reads the {@code configuration} table and nothing else. The console asks for this
     * on every page load to decide whether to draw the chip, so it has to stay cheap, and
     * it must never be the reason a console feels slow on an engine that cannot reach the
     * network.
     */
    @GET
    @Path("/status")
    @io.swagger.v3.oas.annotations.Operation(summary =
        "Available updates for the engine and the installed extensions being watched.")
    @MirthOperation(name = "updateCheckStatus", display = "Get update check status",
        auditable = false)
    Map<String, Object> getStatus() throws ClientException;

    /**
     * Reads the release feeds now instead of waiting for the schedule.
     *
     * <p>Refused when the deployment has set {@code OIE_UPDATE_CHECK=false}: that is an
     * instruction about network egress, and a button in a browser does not get to overrule
     * it. The response then simply reports the check as disabled.
     */
    @POST
    @Path("/check")
    @io.swagger.v3.oas.annotations.Operation(summary = "Check the release feeds now.")
    @MirthOperation(name = "updateCheckNow", display = "Check for updates now",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> checkNow() throws ClientException;

    /**
     * Turns the check on or off, and sets how often it runs.
     *
     * <p>The body must be XStream-shaped ({@code <map><entry><string>k</string>
     * <string>v</string></entry></map>}); a plain JSON object is refused by the engine's
     * deserialiser with a 500.
     */
    @POST
    @Path("/settings")
    @io.swagger.v3.oas.annotations.Operation(summary = "Update the check's settings.")
    @MirthOperation(name = "updateCheckSetSettings", display = "Set update check settings",
        type = Operation.ExecuteType.ASYNC)
    Map<String, Object> setSettings(
        @Param("settings")
        @io.swagger.v3.oas.annotations.Parameter(description =
            "enabled and intervalHours. Unsupplied keys are left as they were.",
            required = true)
        Map<String, String> settings) throws ClientException;
}
