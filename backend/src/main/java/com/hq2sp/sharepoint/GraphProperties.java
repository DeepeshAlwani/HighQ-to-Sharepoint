package com.hq2sp.sharepoint;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Microsoft Graph (SharePoint) settings. Each user signs in with their own Microsoft account (delegated
 * permissions), so copies run with that user's SharePoint access. authorizeUrl is opened by the browser;
 * tokenUrl and baseUrl are called by the server. Point them at the mock for local testing.
 */
@ConfigurationProperties("graph")
public record GraphProperties(String baseUrl, String authorizeUrl, String tokenUrl, String clientId,
                              String clientSecret, String redirectUri, String scope) {
}
