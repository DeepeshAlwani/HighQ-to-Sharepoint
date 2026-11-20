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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
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

    public void highQToSharePoint(Job job, HighQTokens t, long siteId, Long folderId, GraphClient.Target target, boolean originals,
                                  boolean unzip)
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
                copyFromHighQ(job, t, target, f, originals, unzip, made);
            } catch (Exception e) {
                job.failed(f.relativePath(), e.getMessage());
            }
        });
    }

    private void copyFromHighQ(Job job, HighQTokens t, GraphClient.Target target, Manifest.FileEntry f, boolean originals,
                               boolean unzip, Set<String> made) throws Exception {
        boolean useOriginal = originals && f.redacted();
        if (unzip && isArchive(f.relativePath()) && !f.placeholder()) {
            HttpResponse<InputStream> r = highq.download(t, f.id(), useOriginal);
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) throw new IllegalStateException("HighQ returned " + r.statusCode());
                unpackIntoFolder(job, in, target, f.relativePath(), made);
            }
            return;
        }
        String path = fitPath(target, f.relativePath(), f.shortName());
        Instant modified = f.modified() == null ? null : Instant.parse(f.modified());
        if (alreadyThere(target, path, modified, null)) {
            job.skipped(path, "already in SharePoint", false);
            return;
        }
        if (f.placeholder()) {   // HighQ has no content for placeholders: keep the slot as an empty file
            graph.upload(target, path, InputStream.nullInputStream(), 0, modified);
            job.transferred(path, "placeholder", 0, "empty file; HighQ has no content for placeholders");
            return;
        }
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
    public void zipToSharePoint(Job job, Path zipPath, GraphClient.Target target, boolean deleteAfter, boolean unzipNested) throws Exception {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            job.phase("Reading zip");
            Contents c = scan(job, zip, Files.size(zipPath), "");

            job.phase("Creating folders in SharePoint");
            Set<String> made = ConcurrentHashMap.newKeySet();
            graph.ensureFolders(target, target.basePath(), made);
            for (String f : c.folders()) {
                if (job.cancelled()) return;
                graph.ensureFolders(target, join(target.basePath(), f), made);
            }

            job.total(c.files().size() + c.unsafe());
            job.phase("Copying files");
            JobService.parallel(job, c.files(), PARALLEL, entry -> copyEntry(job, zip, entry, target, "", unzipNested, made));
        } finally {
            if (deleteAfter) Files.deleteIfExists(zipPath);
        }
    }

    private record Contents(List<ZipEntry> files, Set<String> folders, int unsafe) {
    }

    /** Lists a zip's files and folders (paths under {@code prefix}); unsafe entries are reported as failed. */
    private static Contents scan(Job job, ZipFile zip, long zipSize, String prefix) {
        List<ZipEntry> files = new ArrayList<>();
        Set<String> folders = new TreeSet<>();   // sorted, so parents come before children
        long unpacked = 0;
        int unsafe = 0;
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            String rel = Naming.safeZipPath(entry.getName());
            if (rel == null) {
                unsafe++;
                job.failed(join(prefix, entry.getName()), "unsafe path in zip (points outside the destination), not copied");
                continue;
            }
            rel = join(prefix, rel);
            if (entry.isDirectory()) {
                folders.add(rel);
            } else {
                files.add(entry);
                unpacked += Math.max(0, entry.getSize());
                int slash = rel.lastIndexOf('/');
                if (slash > 0) folders.add(rel.substring(0, slash));
            }
        }
        if (unpacked > 1_000_000_000L && unpacked > 100 * zipSize) {
            throw new IllegalStateException("This zip would unpack to over 100 times its own size, which is a sign of a malicious zip. Stopped.");
        }
        return new Contents(files, folders, unsafe);
    }

    /** Copies one zip entry to SharePoint under {@code prefix}; with unzipNested an archive entry is unpacked into a folder instead. */
    private void copyEntry(Job job, ZipFile zip, ZipEntry entry, GraphClient.Target target, String prefix, boolean unzipNested,
                           Set<String> made) {
        String rel = join(prefix, Objects.requireNonNull(Naming.safeZipPath(entry.getName())));
        try {
            if (unzipNested && isArchive(rel)) {
                try (InputStream in = zip.getInputStream(entry)) {
                    unpackIntoFolder(job, in, target, rel, made);
                }
                return;
            }
            Instant modified = entry.getLastModifiedTime() == null ? null : entry.getLastModifiedTime().toInstant();
            String original = join(prefix, entry.getName().replace('\\', '/').replaceFirst("^\\./", ""));
            putFile(job, target, rel, rel.equals(original) ? null : entry.getName(), entry.getSize(), modified,
                    () -> zip.getInputStream(entry));
        } catch (Exception e) {
            job.failed(rel, e.getMessage());
        }
    }

    interface Source {
        InputStream open() throws IOException;
    }

    /** Uploads one unpacked file to target/rel unless it is already there with the same date and size. */
    private void putFile(Job job, GraphClient.Target target, String rel, String renamedFrom, long size, Instant modified,
                         Source source) throws IOException {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        String shortName = "~" + Integer.toHexString(rel.hashCode()) + extension(name);
        String path = fitPath(target, rel, shortName);
        Instant mod = modified == null ? null : modified.truncatedTo(ChronoUnit.SECONDS);
        if (alreadyThere(target, path, mod, size)) {
            job.skipped(path, "already in SharePoint", false);
            return;
        }
        try (InputStream in = source.open()) {
            GraphClient.Item up = graph.upload(target, path, in, size, mod);
            if (up.size() != size) throw new IllegalStateException("SharePoint has " + up.size() + " of " + size + " bytes");
            String renamed = !path.equals(join(target.basePath(), rel)) ? "renamed: path too long for SharePoint"
                    : renamedFrom != null ? "renamed from \"" + renamedFrom + "\"" : null;
            job.transferred(path, "copied", up.size(), renamed);
        }
    }

    /**
     * Unpacks an archive (.zip, .7z or .rar, read from {@code in}) into a folder next to it named after it:
     * "A/Contracts.zip" -> "A/Contracts". One level only: archives inside it are copied as files. An archive that
     * can't be read (damaged, password-protected) is copied as it is. Re-running resumes: files already in
     * SharePoint are skipped, though the archive itself is read again.
     */
    private void unpackIntoFolder(Job job, InputStream in, GraphClient.Target target, String archiveRel, Set<String> made) throws Exception {
        String ext = extension(archiveRel).toLowerCase(Locale.ROOT);
        String folder = archiveRel.substring(0, archiveRel.length() - ext.length());
        Path tmp = Files.createTempFile("hq2sp-", ext);   // both readers need random access
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                if (ext.equals(".zip")) unzip(job, tmp, target, archiveRel, folder, made);
                else unpackWith7Zip(job, tmp, target, archiveRel, folder, made);
            } catch (ZipException | ArchiveUnreadable unreadable) {
                try (InputStream again = Files.newInputStream(tmp)) {
                    String path = join(target.basePath(), archiveRel);
                    GraphClient.Item up = graph.upload(target, path, again, Files.size(tmp), null);
                    job.transferred(path, "copied", up.size(), "could not be unpacked (" + unreadable.getMessage() + "), copied as it is");
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static class ArchiveUnreadable extends IOException {
        ArchiveUnreadable(String message) {
            super(message);
        }
    }

    private void unzip(Job job, Path zipFile, GraphClient.Target target, String zipRel, String folder, Set<String> made) throws Exception {
        try (ZipFile inner = new ZipFile(zipFile.toFile())) {
            Contents c = scan(job, inner, Files.size(zipFile), folder);
            graph.ensureFolders(target, join(target.basePath(), folder), made);
            for (String f : c.folders()) graph.ensureFolders(target, join(target.basePath(), f), made);
            job.unzipped(join(target.basePath(), zipRel), "into folder \"" + folder + "\" (" + c.files().size() + " files)", c.files().size() + c.unsafe());
            for (ZipEntry e : c.files()) {
                if (job.cancelled()) return;
                copyEntry(job, inner, e, target, folder, false, made);
            }
        }
    }

    /** .7z and .rar: 7-Zip unpacks to a temporary folder on the server, then each file goes to SharePoint. */
    private void unpackWith7Zip(Job job, Path archive, GraphClient.Target target, String archiveRel, String folder, Set<String> made)
            throws Exception {
        long unpacked;
        try {
            unpacked = SevenZip.unpackedSize(archive);
        } catch (IOException e) {
            throw new ArchiveUnreadable(e.getMessage());
        }
        if (unpacked > 1_000_000_000L && unpacked > 100 * Files.size(archive)) {
            throw new IllegalStateException("This archive would unpack to over 100 times its own size, which is a sign of a malicious archive. Not unpacked.");
        }
        Path out = Files.createTempDirectory("hq2sp-unpack-");
        try {
            try {
                SevenZip.extract(archive, out);
            } catch (IOException e) {
                throw new ArchiveUnreadable(e.getMessage());
            }
            List<Path> files = new ArrayList<>();
            Set<String> folders = new TreeSet<>();
            try (var walk = Files.walk(out)) {   // does not follow links; links inside archives are left out
                walk.filter(p -> !p.equals(out)).forEach(p -> {
                    String rel = Naming.safeZipPath(out.relativize(p).toString().replace('\\', '/'));
                    if (rel == null) return;
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) folders.add(join(folder, rel));
                    else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) files.add(p);
                });
            }
            graph.ensureFolders(target, join(target.basePath(), folder), made);
            for (String f : folders) graph.ensureFolders(target, join(target.basePath(), f), made);
            job.unzipped(join(target.basePath(), archiveRel), "into folder \"" + folder + "\" (" + files.size() + " files)", files.size());
            for (Path p : files) {
                if (job.cancelled()) return;
                String raw = out.relativize(p).toString().replace('\\', '/');
                String rel = join(folder, Naming.safeZipPath(raw));
                try {
                    putFile(job, target, rel, rel.equals(join(folder, raw)) ? null : raw, Files.size(p),
                            Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant(), () -> Files.newInputStream(p));
                } catch (Exception e) {
                    job.failed(rel, e.getMessage());
                }
            }
        } finally {
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
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

    /** "x.zip", "x.7z" or "x.rar", but not a bare ".zip" name. shortcut: multi-part RARs (.part1.rar, .r00) are unpacked per part. */
    static boolean isArchive(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String ext = extension(name);
        return name.length() > ext.length() && (ext.equals(".zip") || ext.equals(".7z") || ext.equals(".rar"));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    static String join(String base, String rel) {
        return base == null || base.isEmpty() ? rel : rel.isEmpty() ? base : base + "/" + rel;
    }
}
