package com.znxsgl.service;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一键排课的异步任务管理。
 *
 * 全量排课会写入数万行 schedule，同步 HTTP 请求会让页面长时间等待（自动化测试中甚至拖垮浏览器），
 * 因此改为后台单线程执行：接口立即返回 jobId，前端轮询进度，页面不再被阻塞。
 */
@Service
public class AutoScheduleJobService {

    public static class Job {
        public String jobId;
        public String semester;
        public volatile String status = "running"; // running / done / failed
        public volatile int total;
        public volatile int processed;
        public final long startedAt = System.currentTimeMillis();
        public volatile Long finishedAt;
        public volatile AutoScheduleService.ScheduleResult result;
        public volatile String error;
    }

    private final AutoScheduleService autoScheduleService;

    /** 单线程执行：排课会清空并重写课表，绝不允许并发执行 */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger idx = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "auto-schedule-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public AutoScheduleJobService(AutoScheduleService autoScheduleService) {
        this.autoScheduleService = autoScheduleService;
    }

    /**
     * 启动排课任务。
     *
     * @return 已有任务在跑时返回 null，避免并发清空/写入同一学期的课表
     */
    public synchronized Job start(String semester, boolean clearExisting) {
        if (running() != null) return null;

        Job job = new Job();
        job.jobId = UUID.randomUUID().toString().replace("-", "");
        job.semester = semester;
        jobs.put(job.jobId, job);
        prune();

        pool.submit(() -> {
            try {
                job.result = autoScheduleService.autoSchedule(semester, clearExisting,
                        new AutoScheduleService.ProgressListener() {
                            @Override
                            public void onStart(int total) {
                                job.total = total;
                            }

                            @Override
                            public void onProgress(int processed) {
                                job.processed = processed;
                            }
                        });
                job.status = "done";
            } catch (Throwable e) {
                job.status = "failed";
                job.error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } finally {
                job.finishedAt = System.currentTimeMillis();
            }
        });
        return job;
    }

    public Job get(String jobId) {
        return jobs.get(jobId);
    }

    /** 当前正在执行的排课任务；页面刷新后可用它重新接上进度 */
    public Job running() {
        for (Job j : jobs.values()) {
            if ("running".equals(j.status)) return j;
        }
        return null;
    }

    /** 只保留最近 20 个任务，避免内存无限增长 */
    private void prune() {
        if (jobs.size() <= 20) return;
        jobs.values().stream()
                .filter(j -> !"running".equals(j.status))
                .sorted(Comparator.comparingLong(j -> j.startedAt))
                .limit(Math.max(0, jobs.size() - 20))
                .forEach(j -> jobs.remove(j.jobId));
    }
}
