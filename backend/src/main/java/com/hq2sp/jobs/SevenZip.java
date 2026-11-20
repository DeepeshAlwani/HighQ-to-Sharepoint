package com.hq2sp.jobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unpacks .7z and .rar archives with the 7-Zip command line (Ubuntu packages 7zip and 7zip-rar, installed in the
 * Docker images). 7-Zip itself refuses entries that point outside the output folder.
 */
final class SevenZip {

    private SevenZip() {
    }

    /** Total unpacked size, read from the archive's listing without extracting. */
    static long unpackedSize(Path archive) throws IOException, InterruptedException {
        String out = run(List.of("7z", "l", "-slt", "-ba", "-p", archive.toString()), null, archive);
        long total = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("Size = ")) total += Long.parseLong(line.substring(7).trim());
        }
        return total;
    }

    /** Extracts everything into {@code outDir}; throws with 7-Zip's error text if it fails (damaged, encrypted, not an archive). */
    static void extract(Path archive, Path outDir) throws IOException, InterruptedException {
        // -p with no password: an encrypted archive fails instead of waiting for a password prompt
        run(List.of("7z", "x", "-y", "-bd", "-p", "-o" + outDir, archive.toString()), outDir, archive);
    }

    private static String run(List<String> cmd, Path dir, Path archive) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException notInstalled) {
            throw new IOException("7-Zip is not installed on the server");
        }
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("7-Zip took over 30 minutes");
        }
        if (p.exitValue() != 0) {
            String reason = out.lines().filter(l -> l.startsWith("ERROR") || l.contains("Wrong password") || l.contains("Can not open"))
                    .findFirst().orElse("7-Zip exit code " + p.exitValue()).trim();
            reason = reason.replace(archive + " : ", "").replace(archive.toString(), "archive");
            throw new IOException(reason);
        }
        return out;
    }
}
