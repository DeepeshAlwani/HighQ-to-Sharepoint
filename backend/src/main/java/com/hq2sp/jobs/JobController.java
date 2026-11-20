package com.hq2sp.jobs;

import com.hq2sp.highq.HighQTokens;
import com.hq2sp.sharepoint.GraphClient;
import com.hq2sp.sharepoint.MicrosoftTokens;
import com.hq2sp.sharepoint.SharePointTarget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;
    private final Transfers transfers;
    private final GraphClient graph;
    private final Path uploadDir;

    public JobController(JobService jobs, Transfers transfers, GraphClient graph, @Value("${app.upload-dir}") String uploadDir) {
        this.jobs = jobs;
        this.transfers = transfers;
        this.graph = graph;
        this.uploadDir = Path.of(uploadDir);
    }

    public record HighQToSharePointRequest(long siteId, Long folderId, SharePointTarget target, boolean originals) {
    }

    @PostMapping("/highq-to-sharepoint")
    public Map<String, Object> highQToSharePoint(@RequestBody HighQToSharePointRequest req, HttpServletRequest http) {
        HighQTokens t = HighQTokens.from(http.getSession());
        // fail fast on a wrong site or library; the copy runs as the signed-in Microsoft user
        GraphClient.Target target = graph.resolve(MicrosoftTokens.from(http.getSession()), req.target());
        Job job = jobs.start("highq-to-sharepoint", http.getSession().getId(),
                j -> transfers.highQToSharePoint(j, t, req.siteId(), req.folderId(), target, req.originals()));
        return Map.of("id", job.id());
    }

    /**
     * The zip is saved on the backend, then extracted straight into SharePoint.
     * shortcut: saved to local disk; use Azure Blob Storage (with a retention rule) when hosted.
     */
    @PostMapping(path = "/zip-to-sharepoint", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> zipToSharePoint(@RequestParam("file") MultipartFile file,
                                               @RequestParam String siteUrl,
                                               @RequestParam(defaultValue = "Documents") String library,
                                               @RequestParam(defaultValue = "") String folderPath,
                                               @RequestParam(defaultValue = "true") boolean deleteAfter,
                                               HttpServletRequest http) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The zip is empty");
        GraphClient.Target target = graph.resolve(MicrosoftTokens.from(http.getSession()), new SharePointTarget(siteUrl, library, folderPath));
        Files.createDirectories(uploadDir);
        Path saved = uploadDir.resolve(UUID.randomUUID() + ".zip");
        file.transferTo(saved);
        Job job = jobs.start("zip-to-sharepoint", http.getSession().getId(),
                j -> transfers.zipToSharePoint(j, saved, target, deleteAfter));
        return Map.of("id", job.id(), "keptZipAt", deleteAfter ? "" : saved.toString());
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id, HttpServletRequest http) {
        return find(id, http).view();
    }

    @GetMapping("/{id}/report.csv")
    public ResponseEntity<String> report(@PathVariable String id, HttpServletRequest http) {
        Job job = find(id, http);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + job.kind + "-report.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(job.csv());
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, HttpServletRequest http) {
        Job job = find(id, http);
        job.cancelRequested = true;
        return job.view();
    }

    private Job find(String id, HttpServletRequest http) {
        Job job = jobs.get(id, http.getSession().getId());
        if (job == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such job");
        return job;
    }
}
