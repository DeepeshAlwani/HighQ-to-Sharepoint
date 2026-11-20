package com.hq2sp.sharepoint;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SharePointController {

    private final GraphClient graph;

    public SharePointController(GraphClient graph) {
        this.graph = graph;
    }

    /** Confirms the site and library exist and the app can reach them, before a job starts. */
    @PostMapping("/api/sharepoint/check")
    public Map<String, Object> check(@RequestBody SharePointTarget target, jakarta.servlet.http.HttpSession session) {
        GraphClient.Target t = graph.resolve(MicrosoftTokens.from(session), target);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("siteName", t.siteName());
        out.put("siteWebUrl", t.siteWebUrl());
        out.put("library", t.library());
        out.put("folderPath", t.basePath());
        return out;
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(GraphException.class)
        ResponseEntity<Map<String, Object>> graph(GraphException e) {
            int status = e.status() >= 400 && e.status() < 600 ? e.status() : 502;
            return ResponseEntity.status(HttpStatusCode.valueOf(status)).body(Map.of("message", e.getMessage()));
        }
    }
}
