package com.znxsgl.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot 启动时自动拉起本地 BGE-M3 Embedding 服务
 */
@Component
public class EmbeddingServiceLauncher {

    @Value("${embedding.local.auto-start:false}")
    private boolean autoStart;

    @Value("${embedding.local.port:8000}")
    private int port;

    @Value("${embedding.local.work-dir:}")
    private String workDir;

    @Value("${embedding.local.python-cmd:python}")
    private String pythonCmd;

    private Process process;

    @PostConstruct
    public void start() {
        if (!autoStart) {
            return;
        }

        // 如果服务已经在运行，直接复用
        if (isServiceHealthy()) {
            return;
        }

        Path serviceDir = resolveWorkDir();
        if (serviceDir == null || !Files.exists(serviceDir)) {
            System.err.println("=== 本地 Embedding 服务目录不存在: " + workDir + "，跳过自动启动");
            return;
        }

        System.out.println("[本地] 启动 Embedding :" + port);
        try {
            List<String> commands = new ArrayList<>();
            commands.add(pythonCmd);
            commands.add("-m");
            commands.add("uvicorn");
            commands.add("main:app");
            commands.add("--host");
            commands.add("0.0.0.0");
            commands.add("--port");
            commands.add(String.valueOf(port));

            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(serviceDir.toFile());
            pb.inheritIO();
            pb.environment().put("EMBEDDING_PORT", String.valueOf(port));

            process = pb.start();

            // 异步等待健康检查通过，避免阻塞 Spring Boot 启动过长时间
            new Thread(this::waitForHealthy, "embedding-health-check").start();
        } catch (Exception e) {
            System.err.println("=== 启动本地 Embedding 服务失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void stop() {
        if (process != null && process.isAlive()) {
            System.out.println("=== 正在关闭本地 Embedding 服务...");
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 解析本地 Embedding 服务工作目录
     */
    private Path resolveWorkDir() {
        if (workDir != null && !workDir.trim().isEmpty()) {
            return Paths.get(workDir).toAbsolutePath().normalize();
        }

        String userDir = System.getProperty("user.dir");
        Path[] candidates = new Path[]{
                Paths.get(userDir, "embedding-service"),          // 从项目根目录启动
                Paths.get(userDir, "..", "embedding-service"),    // 从 backend 目录启动
                Paths.get(userDir, "..", "..", "embedding-service") // 其他子目录
        };
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalized.resolve("main.py"))) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * 等待服务健康检查通过
     */
    private void waitForHealthy() {
        long deadline = System.currentTimeMillis() + 120_000; // 最多等待 120 秒（含首次模型下载）
        while (System.currentTimeMillis() < deadline) {
            if (isServiceHealthy()) {
                System.out.println("[本地] Embedding 就绪 :" + port);
                return;
            }
            if (process != null && !process.isAlive()) {
                System.err.println("=== 本地 Embedding 服务进程已异常退出，退出码: " + process.exitValue());
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("=== 本地 Embedding 服务在 120 秒内未就绪，请检查日志");
    }

    /**
     * 检查 Embedding 服务健康状态
     */
    private boolean isServiceHealthy() {
        try {
            URL url = new URL("http://localhost:" + port + "/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
