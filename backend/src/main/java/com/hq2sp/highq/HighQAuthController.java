package com.hq2sp.highq;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** HighQ sign-in: the user logs in on HighQ's own page; we only ever see the code and the tokens. */
@RestController
public class HighQAuthController {

    private final HighQClient client;
    private final HighQProperties props;
    private final String frontendUrl;

    public HighQAuthController(HighQClient client, HighQProperties props, @Value("${app.frontend-url}") String frontendUrl) {
        this.client = client;
        this.props = props;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/auth/highq/login")
    public ResponseEntity<Void> login() {
        if (!StringUtils.hasText(props.instanceUrl()) || !StringUtils.hasText(props.clientId())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HighQ instance URL or client id is not configured (.env)");
        }
        return redirect(client.authorizeUrl());
    }

    /** Path must match the redirect URI registered on the HighQ API app. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request) {
        if (!StringUtils.hasText(code)) {
            return redirect(frontendUrl + "/?highqError=" + HighQClient.enc(error == null ? "no code returned" : error));
        }
        HighQTokens tokens = client.exchangeCode(code);
        request.getSession(true);
        request.changeSessionId();   // new session id after sign-in
        request.getSession().setAttribute(HighQTokens.SESSION_KEY, tokens);
        return redirect(frontendUrl + "/");
    }

    @GetMapping("/auth/highq/status")
    public Map<String, Object> status(HttpSession session) {
        HighQTokens t = (HighQTokens) session.getAttribute(HighQTokens.SESSION_KEY);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", t != null);
        if (t != null) {
            out.put("email", t.email());
            out.put("expiresAt", t.expiresAt() == null ? null : t.expiresAt().toString());
        }
        return out;
    }

    @PostMapping("/auth/highq/logout")
    public void logout(HttpSession session) {
        session.invalidate();
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
