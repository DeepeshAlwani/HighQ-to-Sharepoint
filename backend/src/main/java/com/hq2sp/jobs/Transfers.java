package com.hq2sp.jobs;

import com.hq2sp.highq.HighQClient;
import com.hq2sp.highq.HighQTokens;
import com.hq2sp.migration.Manifest;
import com.hq2sp.migration.ManifestService;
import com.hq2sp.migration.Naming;
import com.hq2sp.sharepoint.GraphClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The two SharePoint transfers. Both skip files already in SharePoint with the same modified date, so re-running resumes. */
@Component
public class Transfers {

    private static final int PARALLEL = 3;
    /** SharePoint's limit on a file's full decoded path. */
    private static final int MAX_SHAREPOINT_PATH = 400;

    private final HighQClient highq;
    private final ManifestService manifests;
    private final GraphClient graph;

    public Transfers(HighQClient highq, ManifestService manifests, GraphClient graph) {
        this.highq = highq;
        this.manifests = manifests;
        this.graph = graph;
    }

    // ---------- HighQ -> SharePoint ----------

    public void highQToSharePoint(Job job, HighQTokens t, long siteId, Long folderId, GraphClient.Target target, boolean originals)
            throws InterruptedException {
        job.phase("Scanning HighQ folders");
        Manifest m = manifests.build(t, siteId, folderId);
        m.errors().forEach(e -> job.log("", "error", e));

        job.phase("Creating folders in SharePoint");
        Set<String> made = ConcurrentHashMap.newKeySet();
        graph.ensureFolders(target, target.basePath(), made);
        for (String f : m.folders()) {
            if (job.cancelled()) return;
            graph.ensureFolders(target, join(target.basePath(), f), made);
        }

        job.total(m.files().size());
        job.phase("Copying files");
        JobService.parallel(job, m.files(), PARALLEL, f -> {
            try {
                copyFromHighQ(job, t, target, f, originals);
            } catch (Exception e) {
                job.failed(f.relativePath(), e.getMessage());
            }
        });
    }

    private void copyFromHighQ(Job job, HighQTokens t, GraphClient.Target target, Manifest.FileEntry f, boolean originals) throws IOException {
        if (f.placeholder()) {
            job.skipped(f.relativePath(), "placeholder, no content in HighQ", true);
            return;
        }
        String path = fitPath(target, f.relativePath(), f.shortName());
        Instant modified = f.modified() == null ? null : Instant.parse(f.modified());
        if (alreadyThere(target, path, modified, null)) {
            job.skipped(path, "already in SharePoint", false);
            return;
        }
        boolean useOriginal = originals && f.redacted();
        HttpResponse<InputStream> r = highq.download(t, f.id(), useOriginal);
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) throw new IllegalStateException("HighQ returned " + r.statusCode());
            long size = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            GraphClient.Item up = size >= 0
                    ? graph.upload(target, path, in, size, modified)
                    : uploadViaTempFile(target, path, in, modified);
            if (size >= 0 && up.size() != size) throw new IllegalStateException("SharePoint has " + up.size() + " of " + size + " bytes");
            String status = useOriginal ? "copied (ORIGINAL of redacted file)" : f.redacted() ? "copied (redacted copy)" : "copied";
            job.transferred(path, status, up.size(), path.endsWith(f.shortName()) ? "renamed: path too long for SharePoint" : null);
        }
    }

    // ---------- zip -> SharePoint ----------

    /** shortcut: paths come from the zip's own folders; switch to HighQ's metadata file once we have a sample export. */
    public void zipToSharePoint(Job job, Path zipPath, GraphClient.Target target, boolean deleteAfter) throws Exception {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            job.phase("Reading zip");
            List<ZipEntry> files = new ArrayList<>();
            Set<String> folders = new TreeSet<>();   // sorted, so parents come before children
            long unpacked = 0;
            int unsafe = 0;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String rel = Naming.safeZipPath(entry.getName());
                if (rel == null) {
                    unsafe++;
                    job.failed(entry.getName(), "unsafe path in zip (points outside the destination), not copied");
                    continue;
                }
                if (entry.isDirectory()) {
                    folders.add(rel);
                } else {
                    files.add(entry);
                    unpacked += Math.max(0, entry.getSize());
                    int slash = rel.lastIndexOf('/');
                    if (slash > 0) folders.add(rel.substring(0, slash));
                }
            }
            long zipSize = Files.size(zipPath);
            if (unpacked > 1_000_000_000L && unpacked > 100 * zipSize) {
                throw new IllegalStateException("This zip would unpack to over 100 times its own size, which is a sign of a malicious zip. Stopped.");
            }

            job.phase("Creating folders in SharePoint");
            Set<String> made = ConcurrentHashMap.newKeySet();
            graph.ensureFolders(target, target.basePath(), made);
            for (String f : folders) {
                if (job.cancelled()) return;
                graph.ensureFolders(target, join(target.basePath(), f), made);
            }

            job.total(files.size() + unsafe);
            job.phase("Copying files");
            JobService.parallel(job, files, PARALLEL, entry -> {
                String rel = Objects.requireNonNull(Naming.safeZipPath(entry.getName()));
                try {
                    String name = rel.substring(rel.lastIndexOf('/') + 1);
                    String shortName = "~" + Integer.toHexString(rel.hashCode()) + extension(name);
                    String path = fitPath(target, rel, shortName);
                    Instant modified = entry.getLastModifiedTime() == null ? null
                            : entry.getLastModifiedTime().toInstant().truncatedTo(ChronoUnit.SECONDS);
                    if (alreadyThere(target, path, modified, entry.getSize())) {
                        job.skipped(path, "already in SharePoint", false);
                        return;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        GraphClient.Item up = graph.upload(target, path, in, entry.getSize(), modified);
                        if (up.size() != entry.getSize()) throw new IllegalStateException("SharePoint has " + up.size() + " of " + entry.getSize() + " bytes");
                        String renamed = !path.equals(join(target.basePath(), rel)) ? "renamed: path too long for SharePoint"
                                : !rel.equals(entry.getName().replace('\\', '/').replaceFirst("^\\./", "")) ? "renamed from \"" + entry.getName() + "\"" : null;
                        job.transferred(path, "copied", up.size(), renamed);
                    }
                } catch (Exception e) {
                    job.failed(rel, e.getMessage());
                }
            });
        } finally {
            if (deleteAfter) Files.deleteIfExists(zipPath);
        }
    }

    // ---------- shared ----------

    private boolean alreadyThere(GraphClient.Target target, String path, Instant modified, Long size) {
        if (modified == null) return false;
        GraphClient.Item existing = graph.getItem(target, path);
        return existing != null && !existing.folder()
                && modified.equals(existing.fileModified() == null ? null : existing.fileModified().truncatedTo(ChronoUnit.SECONDS))
                && (size == null || size == existing.size());
    }

    /** Full path under the library; falls back to the short name when SharePoint's 400-character limit would be exceeded. */
    private static String fitPath(GraphClient.Target target, String rel, String shortName) {
        String path = join(target.basePath(), rel);
        // shortcut: approximates the decoded URL path as site path + library + file path
        int sitePath = target.siteWebUrl().isEmpty() ? 0 : URI.create(target.siteWebUrl()).getPath().length();
        if (sitePath + 1 + target.library().length() + 1 + path.length() <= MAX_SHAREPOINT_PATH) return path;
        int slash = rel.lastIndexOf('/');
        return join(target.basePath(), slash < 0 ? shortName : rel.substring(0, slash) + "/" + shortName);
    }

    private GraphClient.Item uploadViaTempFile(GraphClient.Target target, String path, InputStream in, Instant modified) throws IOException {
        Path tmp = Files.createTempFile("hq2sp-", ".part");
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try (InputStream again = Files.newInputStream(tmp)) {
                return graph.upload(target, path, again, Files.size(tmp), modified);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    static String join(String base, String rel) {
        return base == null || base.isEmpty() ? rel : rel.isEmpty() ? base : base + "/" + rel;
    }
}
