package com.hq2sp.highq;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("highq")
public record HighQProperties(String instanceUrl, String clientId, String clientSecret,
                              String redirectUri, String apiVersion) {
}
