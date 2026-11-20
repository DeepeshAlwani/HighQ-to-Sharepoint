package com.hq2sp.migration;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Naming rules agreed for the migration: keep HighQ's numbering ("1 Structure", "19 Policy.docx"),
 * make each name safe for Windows and SharePoint. Mirrored in frontend/src/naming.ts for zip entries.
 */
public final class Naming {

    private static final Pattern INVALID = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
    private static final Pattern TRAILING = Pattern.compile("[. ]+$");
    private static final Pattern RESERVED = Pattern.compile("(?i)CON|PRN|AUX|NUL|COM\\d|LPT\\d");
    private static final DateTimeFormatter HIGHQ_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    private Naming() {
    }

    /** "auto-numbering name.ext", with characters Windows rejects replaced by '_'. */
    public static String localName(String number, String name, String ext) {
        String n = (number == null || number.isBlank()) ? nz(name) : number.strip() + " " + nz(name);
        n = INVALID.matcher(n).replaceAll("_").strip();
        n = TRAILING.matcher(n).replaceAll("");
        if (RESERVED.matcher(n).matches()) n = "_" + n;
        if (n.isEmpty()) n = "_";
        if (ext != null && !ext.isBlank()) n = n + "." + ext.strip().replaceFirst("^\\.", "");
        return n;
    }

    /** Fallback when the full path is too long for Windows: keeps the number and extension, ~id keeps it unique. */
    public static String shortName(String number, long id, String ext) {
        return localName(number, "~" + id, ext);
    }

    /** HighQ dates look like "06 Nov 2026 09:32" and are UTC. Returns ISO-8601, or null if unparseable. */
    public static String highQDateToIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.strip(), HIGHQ_DATE).toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * A zip entry's path made safe ('/'-separated, each segment cleaned), or null if it tries to
     * leave the destination ("zip slip"). Mirrors safeZipPath in frontend/src/naming.ts.
     */
    public static String safeZipPath(String entryName) {
        if (entryName == null) return null;
        String p = entryName.replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) return null;
        java.util.List<String> segs = new java.util.ArrayList<>();
        for (String s : p.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) return null;
            segs.add(localName(null, s, null));
        }
        return segs.isEmpty() ? null : String.join("/", segs);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
