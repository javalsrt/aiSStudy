package com.znxsgl.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot 启动时自动拉起本地作息表 OCR 服务（ocr-service/app.py）。
 * 与 {@link EmbeddingServiceLauncher} 同样的策略：
 * 已在运行则复用 → 否则拉起 Python 进程 → 轮询 /health 直到就绪。
 * <p>
 * 容器部署时通过 OCR_AUTO_START=false 关闭（使用独立 ocr 容器）。
 */
@Component
public class OcrServiceLauncher {

    @Value("${ocr.auto-start:false}")
    private boolean autoStart;

    @Value("${ocr.port:8100}")
    private int port;

    @Value("${ocr.work-dir:}")
    private String workDir;

    @Value("${ocr.python-cmd:python}")
    private String pythonCmd;

    private Process process;

    @PostConstruct
    public void start() {
        if (!autoStart) {
            return;
        }
        if (isServiceHealthy()) {
            return;
        }

        Path serviceDir = resolveWorkDir();
        if (serviceDir == null) {
            System.err.println("=== 本地 OCR 服务目录不存在（ocr-service/app.py），跳过自动启动");
            return;
        }

        System.out.println("[本地] 启动 OCR :" + port);
        try {
            List<String> commands = new ArrayList<>();
            commands.add(pythonCmd);
            commands.add("app.py");

            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(serviceDir.toFile());
            pb.inheritIO();
            pb.environment().put("OCR_PORT", String.valueOf(port));

            process = pb.start();
            // 异步等待健康检查，避免阻塞应用启动
            new Thread(this::waitForHealthy, "ocr-health-check").start();
        } catch (Exception e) {
            System.err.println("=== 启动本地 OCR 服务失败: " + e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (process != null && process.isAlive()) {
            System.out.println("=== 正在关闭本地 OCR 服务...");
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

    /** 解析 ocr-service 目录：支持从项目根目录/backend 目录/子目录启动 */
    private Path resolveWorkDir() {
        if (workDir != null && !workDir.trim().isEmpty()) {
            Path p = Paths.get(workDir).toAbsolutePath().normalize();
            return Files.exists(p.resolve("app.py")) ? p : null;
        }
        String userDir = System.getProperty("user.dir");
        Path[] candidates = new Path[]{
                Paths.get(userDir, "ocr-service"),
                Paths.get(userDir, "..", "ocr-service"),
                Paths.get(userDir, "..", "..", "ocr-service")
        };
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalized.resolve("app.py"))) {
                return normalized;
            }
        }
        return null;
    }

    /** 轮询健康检查，就绪后打印与 Embedding 一致的日志 */
    private void waitForHealthy() {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (isServiceHealthy()) {
                System.out.println("[本地] OCR 就绪 :" + port);
                return;
            }
            if (process != null && !process.isAlive()) {
                System.err.println("=== 本地 OCR 服务进程已异常退出，退出码: " + process.exitValue());
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("=== 本地 OCR 服务在 90 秒内未就绪，请检查 Python 依赖是否安装");
    }

    private boolean isServiceHealthy() {
        try {
            URL url = new URL("http://localhost:" + port + "/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
