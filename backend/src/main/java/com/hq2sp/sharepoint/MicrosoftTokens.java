package com.hq2sp.sharepoint;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.Serializable;
import java.time.Instant;

/**
 * One user's Microsoft (SharePoint) sign-in, kept in their HTTP session.
 * shortcut: held in memory only, so a backend restart means signing in again; store encrypted before hosting.
 */
public class MicrosoftTokens implements Serializable {
    public static final String SESSION_KEY = "microsoftTokens";

    String accessToken;
    String refreshToken;
    Instant expiresAt;
    String user;

    public static MicrosoftTokens from(HttpSession session) {
        MicrosoftTokens t = (MicrosoftTokens) session.getAttribute(SESSION_KEY);
        if (t == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with Microsoft first");
        return t;
    }

    public String user() { return user; }
}
