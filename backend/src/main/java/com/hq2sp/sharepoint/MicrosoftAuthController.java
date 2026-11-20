package com.hq2sp.sharepoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/** "Sign in with Microsoft": the user logs in on Microsoft's own page; we only ever see the code and tokens. */
@RestController
public class MicrosoftAuthController {

    private static final String STATE_KEY = "microsoftState";

    private final GraphClient graph;
    private final String frontendUrl;

    public MicrosoftAuthController(GraphClient graph, @Value("${app.frontend-url}") String frontendUrl) {
        this.graph = graph;
        this.frontendUrl = frontendUrl;
    }

    @GetMapping("/auth/microsoft/login")
    public ResponseEntity<Void> login(HttpSession session) {
        String state = UUID.randomUUID().toString();   // ties the callback to this browser (CSRF protection)
        session.setAttribute(STATE_KEY, state);
        return redirect(graph.authorizeUrl(state));
    }

    /** Path must match the redirect URI registered on the Entra ID app. */
    @GetMapping("/auth/microsoft/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false) String errorDescription,
                                         HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object expected = session.getAttribute(STATE_KEY);
        session.removeAttribute(STATE_KEY);
        if (code == null || error != null) {
            return redirect(frontendUrl + "/?microsoftError=" + enc(errorDescription != null ? errorDescription : error != null ? error : "no code returned"));
        }
        if (expected == null || !expected.equals(state)) {
            return redirect(frontendUrl + "/?microsoftError=" + enc("Sign-in did not match this browser session. Please try again."));
        }
        MicrosoftTokens tokens;
        try {
            tokens = graph.exchangeCode(code);
        } catch (GraphException e) {
            return redirect(frontendUrl + "/?microsoftError=" + enc(e.getMessage()));
        }
        request.changeSessionId();   // new session id after sign-in
        request.getSession().setAttribute(MicrosoftTokens.SESSION_KEY, tokens);
        return redirect(frontendUrl + "/");
    }

    @GetMapping("/auth/microsoft/status")
    public Map<String, Object> status(HttpSession session) {
        MicrosoftTokens t = (MicrosoftTokens) session.getAttribute(MicrosoftTokens.SESSION_KEY);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", t != null);
        if (t != null) out.put("user", t.user());
        return out;
    }

    /** Signs out of Microsoft only; a HighQ connection in the same session stays. */
    @PostMapping("/auth/microsoft/logout")
    public void logout(HttpSession session) {
        session.removeAttribute(MicrosoftTokens.SESSION_KEY);
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, UTF_8);
    }
}
