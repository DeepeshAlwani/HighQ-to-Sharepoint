package com.hq2sp.migration;

import com.hq2sp.highq.HighQClient;
import com.hq2sp.highq.HighQTokens;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only HighQ endpoints used by the browser for "HighQ to local folder". */
@RestController
@RequestMapping("/api/highq")
public class HighQController {

    private static final Logger log = LoggerFactory.getLogger(HighQController.class);

    private final HighQClient client;
    private final ManifestService manifests;

    public HighQController(HighQClient client, ManifestService manifests) {
        this.client = client;
        this.manifests = manifests;
    }

    /** Is the site readable with this token? If not, returns HighQ's link for the security step. */
    @GetMapping("/sites/{siteId}/gate")
    public Map<String, Object> gate(@PathVariable long siteId, HttpSession session) {
        HighQClient.Result r = client.get(tokens(session), "sites/" + siteId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.ok()) {
            out.put("open", true);
            out.put("siteName", r.body().path("sitename").asText());
            out.put("rootFolderId", r.body().path("sitefolderID").asText());
            return out;
        }
        if (r.isGate()) {
            out.put("open", false);
            out.put("summary", r.body().path("summary").asText());
            out.put("ref", r.body().path("ref").asText());
            out.put("link", r.body().path("link").asText());
            return out;
        }
        throw new ResponseStatusException(HttpStatusCode.valueOf(r.status()), "HighQ refused site " + siteId);
    }

    @GetMapping("/sites/{siteId}/manifest")
    public Manifest manifest(@PathVariable long siteId, @RequestParam(required = false) Long folderId, HttpSession session) {
        return manifests.build(tokens(session), siteId, folderId);
    }

    /** Streams one file from HighQ to the browser without storing it. */
    @GetMapping("/files/{fileId}/content")
    public void content(@PathVariable long fileId, @RequestParam(defaultValue = "false") boolean original,
                        HttpSession session, HttpServletResponse response) throws IOException {
        HighQTokens t = tokens(session);
        if (original) {
            log.info("Original (unredacted) content requested: file {} by {}", fileId, t.email());
        }
        HttpResponse<InputStream> r = client.download(t, fileId, original);
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) {
                response.sendError(r.statusCode() == 403 ? 403 : 502, "HighQ returned " + r.statusCode());
                return;
            }
            response.setContentType("application/octet-stream");
            r.headers().firstValueAsLong("Content-Length").ifPresent(response::setContentLengthLong);
            in.transferTo(response.getOutputStream());
        }
    }

    private static HighQTokens tokens(HttpSession session) {
        return HighQTokens.from(session);
    }
}
