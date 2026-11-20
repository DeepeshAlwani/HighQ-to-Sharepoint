package com.hq2sp.highq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HighQ only returns users to the redirect URI registered on its API app. A second server can share that address:
 * set signinForwarded=true on it, and forwardTo=its callback on the server that owns the address.
 */
@ConfigurationProperties("highq")
public record HighQProperties(String instanceUrl, String clientId, String clientSecret,
                              String redirectUri, String apiVersion, String forwardTo, boolean signinForwarded) {
}
