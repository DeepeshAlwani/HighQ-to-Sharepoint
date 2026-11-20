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
import java.util.UUID;

/** HighQ sign-in: the user logs in on HighQ's own page; we only ever see the code and the tokens. */
@RestController
public class HighQAuthController {

    private static final String STATE_KEY = "highq.state";
    static final String FORWARD_PREFIX = "fwd.";

    private final HighQClient client;
    private final HighQProperties props;
    private final String frontendUrl;

    public HighQAuthController(HighQClient client, HighQProperties props, @Value("${app.frontend-url}") String frontendUrl) {
        this.client = client;
        this.props = props;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/auth/highq/login")
    public ResponseEntity<Void> login(HttpSession session) {
        if (!StringUtils.hasText(props.instanceUrl()) || !StringUtils.hasText(props.clientId())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HighQ instance URL or client id is not configured (.env)");
        }
        // ties the callback to this browser; the prefix asks the server that owns the redirect URI to send it on to us
        String state = (props.signinForwarded() ? FORWARD_PREFIX : "") + UUID.randomUUID();
        session.setAttribute(STATE_KEY, state);
        return redirect(client.authorizeUrl(state));
    }

    /** Path must match the redirect URI registered on the HighQ API app. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(required = false) String state,
                                         HttpServletRequest request) {
        if (state != null && state.startsWith(FORWARD_PREFIX) && StringUtils.hasText(props.forwardTo())) {
            // A sign-in started on the other server: hand the code over unused; that server checks the state.
            // The target is fixed in configuration, never taken from the request, so codes can't be sent elsewhere.
            String to = props.forwardTo() + "?state=" + HighQClient.enc(state)
                    + (code != null ? "&code=" + HighQClient.enc(code) : "")
                    + (error != null ? "&error=" + HighQClient.enc(error) : "");
            return redirect(to);
        }
        HttpSession existing = request.getSession(false);
        Object expected = existing == null ? null : existing.getAttribute(STATE_KEY);
        if (existing != null) existing.removeAttribute(STATE_KEY);
        // shortcut: a callback without state is accepted, in case HighQ doesn't return it; make it required once confirmed
        if (state != null && !state.equals(expected)) {
            return redirect(frontendUrl + "/?highqError=" + HighQClient.enc("Sign-in did not match this browser. Please sign in again."));
        }
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
