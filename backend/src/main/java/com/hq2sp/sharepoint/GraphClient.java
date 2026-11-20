package com.hq2sp.sharepoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The slice of Microsoft Graph the migration needs, acting as the signed-in user: find a site's document
 * library, create folders, check what is already there, and upload files through resumable upload sessions.
 * Throttling (429/503/504) is retried after Retry-After; the user's token is refreshed before it expires.
 */
@Component
public class GraphClient {

    /** Graph upload chunks must be multiples of 320 KiB; 10 MiB keeps memory per transfer small. */
    static final int CHUNK = 320 * 1024 * 32;
    private static final int MAX_TRIES = 6;
    /** offline_access gives a refresh token so long copies outlive the ~1 hour access token. */
    private static final String DEFAULT_SCOPE = "offline_access openid profile User.Read Sites.ReadWrite.All";

    private final GraphProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public GraphClient(GraphProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    /** A resolved destination, carrying the user whose permissions every call uses. */
    public record Target(MicrosoftTokens auth, String driveId, String siteName, String siteWebUrl, String library, String basePath) {
    }

    /** fileModified is fileSystemInfo.lastModifiedDateTime, which the migration sets from HighQ / the zip. */
    public record Item(String name, long size, boolean folder, Instant fileModified) {
    }

    public boolean configured() {
        return StringUtils.hasText(props.authorizeUrl()) && StringUtils.hasText(props.tokenUrl()) && StringUtils.hasText(props.clientId());
    }

    // ---------- user sign-in ----------

    public String authorizeUrl(String state) {
        if (!configured()) throw new GraphException(500, "Microsoft sign-in is not configured (.env GRAPH_*)");
        return props.authorizeUrl() + "?client_id=" + enc(props.clientId())
                + "&response_type=code&response_mode=query"
                + "&redirect_uri=" + enc(props.redirectUri())
                + "&scope=" + enc(scope())
                + "&state=" + enc(state);
    }

    public MicrosoftTokens exchangeCode(String code) {
        MicrosoftTokens t = new MicrosoftTokens();
        tokenRequest(t, Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", props.redirectUri()));
        JsonNode me = call(t, "GET", "me", null);
        t.user = me.path("userPrincipalName").asText(me.path("mail").asText(me.path("displayName").asText()));
        return t;
    }

    // ---------- SharePoint ----------

    public Target resolve(MicrosoftTokens auth, SharePointTarget t) {
        URI site;
        try {
            site = URI.create(t.siteUrl() == null ? "" : t.siteUrl().strip());
        } catch (IllegalArgumentException e) {
            throw new GraphException(400, "Not a valid site URL");
        }
        if (site.getHost() == null) throw new GraphException(400, "Site URL must look like https://<tenant>.sharepoint.com/sites/<site>");
        String sitePath = site.getPath() == null ? "" : site.getPath().replaceAll("/+$", "");
        JsonNode s = call(auth, "GET", "sites/" + site.getHost() + (sitePath.isEmpty() ? "" : ":/" + encodePath(sitePath)), null);
        JsonNode drives = call(auth, "GET", "sites/" + s.path("id").asText() + "/drives", null);

        String library = StringUtils.hasText(t.library()) ? t.library().strip() : "Documents";
        List<String> names = new ArrayList<>();
        for (JsonNode d : drives.path("value")) {
            names.add(d.path("name").asText());
            if (library.equalsIgnoreCase(d.path("name").asText())) {
                return new Target(auth, d.path("id").asText(), s.path("displayName").asText(s.path("name").asText()),
                        s.path("webUrl").asText(), d.path("name").asText(), cleanPath(t.folderPath()));
            }
        }
        throw new GraphException(404, "No document library called \"" + library + "\" on that site. Libraries found: " + String.join(", ", names));
    }

    /** null if nothing exists at that path. */
    public Item getItem(Target target, String path) {
        try {
            return toItem(call(target.auth(), "GET", itemRef(target.driveId(), path), null));
        } catch (GraphException e) {
            if (e.status() == 404) return null;
            throw e;
        }
    }

    /** Creates every missing folder along the path. `known` remembers folders already made in this job. */
    public void ensureFolders(Target target, String path, Set<String> known) {
        String current = "";
        for (String seg : segments(path)) {
            String next = current.isEmpty() ? seg : current + "/" + seg;
            if (known.add(next.toLowerCase())) {
                ObjectNode body = json.createObjectNode().put("name", seg).put("@microsoft.graph.conflictBehavior", "fail");
                body.putObject("folder");
                try {
                    call(target.auth(), "POST", itemRef(target.driveId(), current) + "/children", body);
                } catch (GraphException e) {
                    if (e.status() != 409) throw e;   // already there
                }
            }
            current = next;
        }
    }

    /**
     * Uploads `size` bytes from `in` to path, replacing any existing file, and stamps the file's modified date.
     * shortcut: an interrupted file restarts from byte 0; persist the upload URL to resume mid-file later.
     */
    public Item upload(Target target, String path, InputStream in, long size, Instant modified) {
        ObjectNode fsInfo = json.createObjectNode();
        if (modified != null) fsInfo.put("lastModifiedDateTime", modified.toString());
        String ref = itemRef(target.driveId(), path);

        if (size == 0) {   // upload sessions need at least one byte
            call(target.auth(), "PUT", ref + "/content?@microsoft.graph.conflictBehavior=replace", new byte[0]);
            ObjectNode patch = json.createObjectNode();
            patch.set("fileSystemInfo", fsInfo);
            return toItem(call(target.auth(), "PATCH", ref, patch));
        }

        ObjectNode body = json.createObjectNode();
        ObjectNode item = body.putObject("item").put("@microsoft.graph.conflictBehavior", "replace");
        item.set("fileSystemInfo", fsInfo);
        String uploadUrl = call(target.auth(), "POST", ref + "/createUploadSession", body).path("uploadUrl").asText();

        long sent = 0;
        try {
            while (true) {
                byte[] chunk = in.readNBytes((int) Math.min(CHUNK, size - sent));
                if (chunk.length == 0) throw new GraphException(500, "Source ended early: sent " + sent + " of " + size + " bytes");
                long end = sent + chunk.length - 1;
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Range", "bytes " + sent + "-" + end + "/" + size)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk));
                HttpResponse<String> r = send(req, null);   // upload URLs are pre-authorised: no token
                sent = end + 1;
                if (r.statusCode() == 200 || r.statusCode() == 201) return toItem(json.readTree(r.body()));
                if (r.statusCode() != 202) throw error(r);
                if (sent >= size) throw new GraphException(500, "SharePoint did not complete the upload");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- plumbing ----------

    private JsonNode call(MicrosoftTokens auth, String method, String path, Object body) {
        HttpRequest.BodyPublisher pub;
        String contentType = "application/json";
        try {
            if (body == null) pub = HttpRequest.BodyPublishers.noBody();
            else if (body instanceof byte[] bytes) {
                pub = HttpRequest.BodyPublishers.ofByteArray(bytes);
                contentType = "application/octet-stream";
            } else pub = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/" + path))
                    .header("Content-Type", contentType)
                    .method(method, pub);
            HttpResponse<String> r = send(req, auth);
            if (r.statusCode() >= 300) throw error(r);
            return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** auth null = no Authorization header (upload URLs). */
    private HttpResponse<String> send(HttpRequest.Builder req, MicrosoftTokens auth) {
        req.timeout(Duration.ofMinutes(10));
        for (int attempt = 1; ; attempt++) {
            if (auth != null) req.setHeader("Authorization", "Bearer " + accessToken(auth));
            try {
                HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                int s = r.statusCode();
                if ((s == 429 || s == 503 || s == 504) && attempt < MAX_TRIES) {
                    long wait = r.headers().firstValueAsLong("Retry-After").orElse(1L << attempt);
                    Thread.sleep(Duration.ofSeconds(Math.min(wait, 120)));
                    continue;
                }
                return r;
            } catch (IOException e) {
                if (attempt >= MAX_TRIES) throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private String accessToken(MicrosoftTokens t) {
        synchronized (t) {
            if (t.refreshToken != null && t.expiresAt != null && Instant.now().isAfter(t.expiresAt.minusSeconds(120))) {
                tokenRequest(t, Map.of("grant_type", "refresh_token", "refresh_token", t.refreshToken));
            }
            return t.accessToken;
        }
    }

    private void tokenRequest(MicrosoftTokens t, Map<String, String> grant) {
        String form = grant.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"))
                + "&client_id=" + enc(props.clientId())
                + "&client_secret=" + enc(props.clientSecret())
                + "&scope=" + enc(scope());
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.tokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode b = json.readTree(r.body());
            if (r.statusCode() != 200 || !b.hasNonNull("access_token")) {
                String why = b.path("error_description").asText(b.path("error").asText("HTTP " + r.statusCode()));
                throw new GraphException(401, "Microsoft sign-in failed: " + why);
            }
            t.accessToken = b.get("access_token").asText();
            if (b.hasNonNull("refresh_token")) t.refreshToken = b.get("refresh_token").asText();
            t.expiresAt = Instant.now().plusSeconds(b.path("expires_in").asLong(3600));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String scope() {
        return StringUtils.hasText(props.scope()) ? props.scope() : DEFAULT_SCOPE;
    }

    private GraphException error(HttpResponse<String> r) {
        String message = r.body();
        try {
            JsonNode b = json.readTree(r.body());
            if (b.path("error").hasNonNull("message")) message = b.path("error").path("message").asText();
        } catch (Exception ignored) {
            // keep the raw body
        }
        String hint = r.statusCode() == 403 ? " (your Microsoft account has no edit access there)" : "";
        return new GraphException(r.statusCode(), "SharePoint returned " + r.statusCode() + ": " + message + hint);
    }

    private Item toItem(JsonNode n) {
        String modified = n.path("fileSystemInfo").path("lastModifiedDateTime").asText(null);
        return new Item(n.path("name").asText(), n.path("size").asLong(), n.has("folder"),
                modified == null ? null : Instant.parse(modified));
    }

    static String itemRef(String driveId, String path) {
        String p = encodePath(path);
        return "drives/" + driveId + "/root" + (p.isEmpty() ? "" : ":/" + p + ":");
    }

    /** Encodes each segment for Graph's path addressing (spaces as %20, '#' and '%' escaped). */
    static String encodePath(String path) {
        return segments(path).stream().map(GraphClient::enc).map(s -> s.replace("+", "%20")).collect(Collectors.joining("/"));
    }

    static List<String> segments(String path) {
        return path == null ? List.of() : Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).toList();
    }

    static String cleanPath(String path) {
        return String.join("/", segments(path == null ? "" : path.replace('\\', '/')));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, UTF_8);
    }
}
