package com.hq2sp.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One running or finished transfer. Progress is polled by the browser; the full result list becomes the CSV report. */
public class Job {

    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    public record Row(String path, String status, String detail) {
    }

    final String id = UUID.randomUUID().toString();
    final String kind;
    final String owner;   // HTTP session id: only the user who started a job can see it
    final Instant startedAt = Instant.now();
    volatile State state = State.RUNNING;
    volatile String phase = "Starting";
    volatile String message;
    volatile Instant finishedAt;
    volatile boolean cancelRequested;

    final AtomicInteger total = new AtomicInteger();
    final AtomicInteger finished = new AtomicInteger();
    final AtomicInteger transferred = new AtomicInteger();
    final AtomicInteger skipped = new AtomicInteger();
    final AtomicInteger failed = new AtomicInteger();
    final AtomicLong bytes = new AtomicLong();
    private final List<Row> rows = new ArrayList<>();

    Job(String kind, String owner) {
        this.kind = kind;
        this.owner = owner;
    }

    public String id() { return id; }

    public boolean cancelled() { return cancelRequested; }

    public void phase(String p) { phase = p; }

    public void total(int n) { total.set(n); }

    public synchronized void log(String path, String status, String detail) {
        rows.add(new Row(path, status, detail));
    }

    public void transferred(String path, String status, long size, String detail) {
        transferred.incrementAndGet();
        bytes.addAndGet(size);
        finished.incrementAndGet();
        log(path, status, detail);
    }

    public void skipped(String path, String detail, boolean logIt) {
        skipped.incrementAndGet();
        finished.incrementAndGet();
        if (logIt) log(path, "skipped", detail);
    }

    /** A zip replaced by its files: they are added to the total, the zip itself counts as finished. */
    public void unzipped(String path, String detail, int files) {
        total.addAndGet(files);
        finished.incrementAndGet();
        log(path, "unzipped", detail);
    }

    public void failed(String path, String detail) {
        failed.incrementAndGet();
        finished.incrementAndGet();
        log(path, "failed", detail);
    }

    public synchronized Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("state", state);
        m.put("phase", phase);
        m.put("message", message);
        m.put("total", total.get());
        m.put("finished", finished.get());
        m.put("transferred", transferred.get());
        m.put("skipped", skipped.get());
        m.put("failed", failed.get());
        m.put("bytes", bytes.get());
        m.put("startedAt", startedAt.toString());
        m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        m.put("rows", new ArrayList<>(rows.subList(Math.max(0, rows.size() - 200), rows.size())));
        return m;
    }

    public synchronized String csv() {
        StringBuilder sb = new StringBuilder("path,status,detail\r\n");
        for (Row r : rows) {
            sb.append(cell(r.path())).append(',').append(cell(r.status())).append(',').append(cell(r.detail())).append("\r\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
