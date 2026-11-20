package com.hq2sp.sharepoint;

/** Where files go: a SharePoint site, one of its document libraries, and a folder inside it (optional). */
public record SharePointTarget(String siteUrl, String library, String folderPath) {
}
