package com.hq2sp.highq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Talks to HighQ. READ-ONLY by construction: the only API calls it can make are GETs.
 * Every call carries Auth-Type: OAUTH2 + Bearer token, is spaced out to stay under
 * HighQ's 2000 calls / 10 min per user, and refreshes the token shortly before it expires.
 */
@Component
public class HighQClient {

    /** ~3 calls a second per user. */
    private static final long SPACING_NANOS = Duration.ofMillis(350).toNanos();
    private static final String GATE_PAGE = "checkSiteSecurityTrustEntries";

    private final HighQProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public HighQClient(HighQProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public record Result(int status, JsonNode body) {
        public boolean ok() { return status == 200; }

        /** A site security step (passcode, terms) answers 403 with a link that trusts the current token once completed. */
        public boolean isGate() {
            return status == 403 && body.path("link").asText("").contains(GATE_PAGE);
        }
    }

    public String authorizeUrl() {
        return props.instanceUrl() + "/authorize.action?response_type=code"
                + "&client_id=" + enc(props.clientId())
                + "&redirect_uri=" + enc(props.redirectUri());
    }

    public HighQTokens exchangeCode(String code) {
        HighQTokens t = new HighQTokens();
        tokenRequest(t, Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", props.redirectUri()));
        return t;
    }

    public Result get(HighQTokens t, String path) {
        HttpResponse<String> r = send(t, path, "application/json", HttpResponse.BodyHandlers.ofString());
        JsonNode body;
        try {
            body = json.readTree(r.body());
        } catch (IOException e) {
            String raw = r.body() == null ? "" : r.body();
            body = json.createObjectNode().put("raw", raw.substring(0, Math.min(raw.length(), 500)));
        }
        return new Result(r.statusCode(), body == null ? json.createObjectNode() : body);
    }

    /** Never sends a Range header: HighQ answers byte-range requests with HTTP 500. */
    public HttpResponse<InputStream> download(HighQTokens t, long fileId, boolean original) {
        return send(t, "files/" + fileId + "/content" + (original ? "?original=true" : ""), "*/*",
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private <T> HttpResponse<T> send(HighQTokens t, String path, String accept, HttpResponse.BodyHandler<T> handler) {
        refreshIfNeeded(t);
        throttle(t);
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.instanceUrl() + "/api/" + props.apiVersion() + "/" + path))
                .GET()
                .header("Auth-Type", "OAUTH2")
                .header("Authorization", "Bearer " + t.accessToken)
                .header("Accept", accept)
                .timeout(Duration.ofMinutes(30))
                .build();
        try {
            return http.send(req, handler);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void refreshIfNeeded(HighQTokens t) {
        synchronized (t) {
            if (t.refreshToken != null && t.expiresAt != null && Instant.now().isAfter(t.expiresAt.minusSeconds(60))) {
                tokenRequest(t, Map.of("grant_type", "refresh_token", "refresh_token", t.refreshToken));
            }
        }
    }

    private void throttle(HighQTokens t) {
        long wait;
        synchronized (t) {
            long now = System.nanoTime();
            long slot = Math.max(now, t.nextCallAtNanos);
            t.nextCallAtNanos = slot + SPACING_NANOS;
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void tokenRequest(HighQTokens t, Map<String, String> grant) {
        String form = java.util.stream.Stream.concat(grant.entrySet().stream(),
                        Map.of("client_id", props.clientId(), "client_secret", props.clientSecret()).entrySet().stream())
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.instanceUrl() + "/api/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(r.body());
            if (r.statusCode() != 200 || !body.hasNonNull("access_token")) {
                throw new IllegalStateException("HighQ token request failed: HTTP " + r.statusCode());
            }
            t.accessToken = body.get("access_token").asText();
            if (body.hasNonNull("refresh_token")) t.refreshToken = body.get("refresh_token").asText();
            t.expiresAt = Instant.now().plusSeconds(body.path("expires_in").asLong(3600));
            if (body.hasNonNull("useremail")) t.email = body.get("useremail").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, UTF_8);
    }
}
