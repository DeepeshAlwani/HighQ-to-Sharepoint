package com.hq2sp.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs transfers in the background and keeps their progress.
 * shortcut: jobs live in memory, so a backend restart loses progress; resume still works because
 * files already in SharePoint with the same modified date are skipped. Move to Spring Batch + a database for hosting.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService runners = Executors.newCachedThreadPool();

    public interface Work {
        void run(Job job) throws Exception;
    }

    public Job start(String kind, String owner, Work work) {
        Job job = new Job(kind, owner);
        jobs.put(job.id, job);
        runners.submit(() -> {
            try {
                work.run(job);
                job.state = job.cancelRequested ? Job.State.CANCELLED : Job.State.DONE;
            } catch (Exception e) {
                log.warn("Job {} ({}) failed", job.id, kind, e);
                job.state = Job.State.FAILED;
                job.message = e.getMessage();
            } finally {
                job.phase = "Finished";
                job.finishedAt = Instant.now();
            }
        });
        return job;
    }

    /** The job, only for the session that started it. */
    public Job get(String id, String owner) {
        Job j = jobs.get(id);
        return j != null && j.owner.equals(owner) ? j : null;
    }

    /** Runs `each` over items on `threads` workers; stops taking new items once the job is cancelled. */
    public static <T> void parallel(Job job, List<T> items, int threads, Consumer<T> each) throws InterruptedException {
        ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>(items);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    for (T item = queue.poll(); item != null && !job.cancelled(); item = queue.poll()) {
                        each.accept(item);
                    }
                });
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.DAYS);
        }
    }
}
