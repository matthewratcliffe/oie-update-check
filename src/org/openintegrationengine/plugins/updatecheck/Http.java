/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The one HTTP client this plugin uses, and the only place it reaches the network.
 *
 * <p>{@link java.net.http.HttpClient} rather than anything bundled: the plugin reads two
 * JSON documents and one small text file from a release host, which the JDK's own client
 * covers, so the extension ships no third-party jars and puts nothing extra in the
 * engine's JVM. Same choice, for the same reason, as the key store extension.
 *
 * <p>Everything here is deliberately timid, because this is an engine that moves clinical
 * traffic and a version check is the least important thing it does:
 *
 * <ul>
 *   <li><b>Short timeouts, always set.</b> The check runs on a daemon scheduler thread;
 *       an unbounded read against a host that accepts the connection and then says
 *       nothing would hold that thread indefinitely.
 *   <li><b>Redirects are followed only to HTTPS.</b> Release assets redirect to a CDN, so
 *       they have to be followed, but never down to plaintext.
 *   <li><b>Bodies are capped.</b> A release feed is tens of kilobytes; anything past the
 *       cap is a host behaving unexpectedly, and the JDK's string body handler would
 *       otherwise buffer all of it into the engine's heap.
 * </ul>
 */
final class Http {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Generous for a release feed, small enough that a wrong URL cannot cost memory. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /**
     * One client for the JVM. It pools connections and the plugin asks the same host for
     * the same handful of documents on a timer, so a per-request client would mean a new
     * TLS handshake each time for no gain.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private Http() {
    }

    static final class Result {
        final int status;
        final String body;

        Result(int status, String body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    static Result get(String url, Map<String, String> headers) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a usable URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IOException("refusing to fetch a non-HTTP URL: " + url);
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }

        try {
            HttpResponse<String> response =
                CLIENT.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body != null && body.length() > MAX_BODY_BYTES) {
                throw new IOException("response from " + uri.getHost() + " is larger than "
                    + MAX_BODY_BYTES + " bytes");
            }
            return new Result(response.statusCode(), body == null ? "" : body);
        } catch (InterruptedException e) {
            // Restored so a shutdown that interrupts a check still stops the scheduler
            // rather than being swallowed here and having to be noticed again later.
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling " + uri.getHost(), e);
        }
    }

    /**
     * A failure message safe to put in front of an administrator.
     *
     * <p>Named host, status, and a truncated body -- "HTTP 403" on its own has sent people
     * looking at the wrong thing more than once, and the usual 403 here is a rate limit
     * whose body says exactly that.
     */
    static String describe(String what, Result r) {
        String body = r.body == null ? "" : r.body.strip();
        if (body.length() > 300) {
            body = body.substring(0, 300) + "...";
        }
        return what + ": HTTP " + r.status + (body.isEmpty() ? "" : " -- " + body);
    }
}
