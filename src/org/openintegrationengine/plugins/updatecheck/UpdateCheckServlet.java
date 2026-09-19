/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link UpdateCheckService} onto HTTP.
 *
 * <p>A failed operation answers {@code 200} with {@code ok:false} and a readable message
 * rather than a 500 carrying a serialised Java exception, the same as the other extensions
 * here: the console renders what it is given, and a stack trace in a web page is an error
 * message wrapped in something that makes people stop reading.
 */
public class UpdateCheckServlet extends MirthServlet implements UpdateCheckServletInterface {

    public UpdateCheckServlet(@Context HttpServletRequest request,
                              @Context SecurityContext securityContext) {
        super(request, securityContext, UpdateCheckServicePlugin.PLUGIN_POINT_NAME);
    }

    private static UpdateCheckService service() {
        UpdateCheckService s = UpdateCheckServicePlugin.service();
        if (s == null) {
            // Installed, but the ServicePlugin never started: a server fault, not a bad
            // request, so it gets a status that says so.
            throw new MirthApiException(Response.Status.SERVICE_UNAVAILABLE);
        }
        return s;
    }

    private static Map<String, Object> failure(Throwable t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        String message = t.getMessage();
        out.put("error", message == null || message.isBlank()
            ? t.getClass().getSimpleName() : message);
        return out;
    }

    @Override
    public Map<String, Object> getStatus() {
        try {
            return service().status();
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> checkNow() {
        try {
            return service().check(true);
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Override
    public Map<String, Object> setSettings(Map<String, String> settings) {
        try {
            return service().saveSettings(settings);
        } catch (Exception e) {
            return failure(e);
        }
    }
}
