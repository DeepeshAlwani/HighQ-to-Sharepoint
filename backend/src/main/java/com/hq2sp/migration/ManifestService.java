package com.hq2sp.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.hq2sp.highq.HighQClient;
import com.hq2sp.highq.HighQTokens;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Walks a HighQ site (or one folder) and lists every folder and file with its target relative path.
 * shortcut: runs inside the request (fine for sites of a few thousand items); move to a background job for large sites.
 */
@Service
public class ManifestService {

    private final HighQClient client;

    public ManifestService(HighQClient client) {
        this.client = client;
    }

    public Manifest build(HighQTokens t, long siteId, Long folderId) {
        HighQClient.Result site = client.get(t, "sites/" + siteId);
        if (site.isGate()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Site security step needed: " + site.body().path("summary").asText());
        }
        if (!site.ok()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(site.status()), "HighQ refused site " + siteId);
        }
        long root = folderId != null ? folderId : site.body().path("sitefolderID").asLong();

        List<String> folders = new ArrayList<>();
        List<Manifest.FileEntry> files = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        record Pending(long id, String rel) {
        }
        ArrayDeque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(root, ""));

        while (!queue.isEmpty()) {
            Pending p = queue.poll();
            Set<String> used = new HashSet<>();   // folders and files share one namespace per folder

            // auto-numbering only comes back in list responses, not on a folder's own details
            HighQClient.Result items = client.get(t, "folders/" + p.id() + "/items?limit=-1");
            if (!items.ok()) {
                errors.add("Folder " + p.id() + " (" + display(p.rel()) + "): HighQ returned " + items.status());
                continue;
            }
            for (JsonNode sub : each(items.body().path("folder"))) {
                String number = text(sub, "auto-numbering");
                String name = unique(used, Naming.localName(number, text(sub, "name"), null),
                        () -> Naming.localName(number, text(sub, "name") + " (" + sub.path("id").asText() + ")", null));
                String rel = join(p.rel(), name);
                folders.add(rel);
                queue.add(new Pending(sub.path("id").asLong(), rel));
            }

            String q = URLEncoder.encode("\"folderid=" + p.id() + "\"", UTF_8);
            HighQClient.Result list = client.get(t, "files?q=" + q + "&limit=-1");
            if (!list.ok()) {
                errors.add("Files in folder " + p.id() + " (" + display(p.rel()) + "): HighQ returned " + list.status());
                continue;
            }
            for (JsonNode f : each(list.body().path("file"))) {
                long id = f.path("id").asLong();
                String number = text(f, "auto-numbering");
                String type = text(f, "type");
                String name = unique(used, Naming.localName(number, text(f, "name"), type),
                        () -> Naming.localName(number, text(f, "name") + " (" + id + ")", type));
                files.add(new Manifest.FileEntry(
                        id,
                        text(f, "versionid"),
                        join(p.rel(), name),
                        Naming.shortName(number, id, type),
                        f.hasNonNull("size") ? f.get("size").asLong() : null,
                        Naming.highQDateToIso(text(f, "modifieddate")),
                        f.path("redactionstatus").asBoolean(false),
                        "placeholder".equalsIgnoreCase(type)));
            }
        }
        return new Manifest(site.body().path("sitename").asText(), root, folders, files, errors);
    }

    private static String unique(Set<String> used, String name, java.util.function.Supplier<String> fallback) {
        if (used.add(name.toLowerCase(Locale.ROOT))) return name;
        String alt = fallback.get();
        used.add(alt.toLowerCase(Locale.ROOT));
        return alt;
    }

    /** HighQ returns a list as an array, a single object, or nothing. */
    private static Iterable<JsonNode> each(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        return node.isArray() ? node : List.of(node);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private static String display(String rel) {
        return rel.isEmpty() ? "start folder" : rel;
    }
}
