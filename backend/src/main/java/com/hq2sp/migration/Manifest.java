package com.hq2sp.migration;

import java.util.List;

/** Everything a transfer needs to know about one HighQ site or folder, with paths already named per the agreed rules. */
public record Manifest(String siteName, long rootFolderId, List<String> folders, List<FileEntry> files, List<String> errors) {

    /** relativePath uses '/' separators and is relative to the chosen start folder. */
    public record FileEntry(long id, String versionId, String relativePath, String shortName, Long size,
                            String modified, boolean redacted, boolean placeholder) {
    }
}
