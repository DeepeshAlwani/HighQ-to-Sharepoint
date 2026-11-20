package com.hq2sp.highq;

import java.io.Serializable;
import java.time.Instant;

/**
 * One user's HighQ connection, kept in their HTTP session.
 * shortcut: held in memory only, so a backend restart means reconnecting; store encrypted (Key Vault key) before hosting.
 */
public class HighQTokens implements Serializable {
    public static final String SESSION_KEY = "highqTokens";

    String accessToken;
    String refreshToken;
    Instant expiresAt;
    String email;
    transient long nextCallAtNanos;   // request spacing, see HighQClient

    /** The signed-in user's HighQ connection, or 401 if they haven't connected. */
    public static HighQTokens from(jakarta.servlet.http.HttpSession session) {
        HighQTokens t = (HighQTokens) session.getAttribute(SESSION_KEY);
        if (t == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Connect HighQ first");
        }
        return t;
    }

    public String email() { return email; }
    public Instant expiresAt() { return expiresAt; }
}
