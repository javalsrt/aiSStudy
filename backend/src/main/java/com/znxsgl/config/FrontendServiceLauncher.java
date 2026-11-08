package com.znxsgl.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地开发时，让官网静态站点与 React 管理端随 Spring Boot 一起启停。
 * 策略与 {@link EmbeddingServiceLauncher}、{@link OcrServiceLauncher} 保持一致：
 * 端口已在监听则直接复用 → 否则拉起子进程 → 轮询 HTTP 直到就绪 → 应用关闭时终止进程树。
 * <p>
 * 容器部署时通过 FRONTEND_LOCAL_AUTO_START=false 关闭（官网与管理端由 Nginx 托管）。
 */
@Component
public class FrontendServiceLauncher {

    @Value("${frontend.local.auto-start:false}")
    private boolean autoStart;

    // ===== 官网静态站点（website/） =====
    @Value("${frontend.local.website.enabled:false}")
    private boolean websiteEnabled;

    @Value("${frontend.local.website.port:5500}")
    private int websitePort;

    @Value("${frontend.local.website.python-cmd:python}")
    private String websitePythonCmd;

    @Value("${frontend.local.website.work-dir:}")
    private String websiteWorkDir;

    // ===== React 管理端（web-admin-react/） =====
    @Value("${frontend.local.admin.enabled:false}")
    private boolean adminEnabled;

    @Value("${frontend.local.admin.port:5174}")
    private int adminPort;

    @Value("${frontend.local.admin.npm-cmd:npm}")
    private String adminNpmCmd;

    @Value("${frontend.local.admin.work-dir:}")
    private String adminWorkDir;

    private Process websiteProcess;
    private Process adminProcess;

    @PostConstruct
    public void start() {
        if (!autoStart) {
            return;
        }
        if (websiteEnabled) {
            startWebsite();
        }
        if (adminEnabled) {
            startAdmin();
        }
    }

    @PreDestroy
    public void stop() {
        stopProcess(adminProcess, "管理端");
        stopProcess(websiteProcess, "官网");
    }

    /** 启动官网静态站点：python -m http.server */
    private void startWebsite() {
        if (isServiceHealthy(websitePort)) {
            return;
        }
        Path siteDir = resolveDir(websiteWorkDir, "website", "index.html");
        if (siteDir == null) {
            System.err.println("=== 未找到官网目录（website/index.html），跳过官网自动启动");
            return;
        }

        System.out.println("[本地] 启动官网 :" + websitePort);
        try {
            List<String> commands = new ArrayList<>();
            commands.add(websitePythonCmd);
            commands.add("-m");
            commands.add("http.server");
            commands.add(String.valueOf(websitePort));
            commands.add("--bind");
            commands.add("127.0.0.1");

            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(siteDir.toFile());
            pb.inheritIO();

            websiteProcess = pb.start();
            new Thread(() -> waitForHealthy(websiteProcess, "官网", websitePort, 30),
                    "website-health-check").start();
        } catch (Exception e) {
            System.err.println("=== 启动本地官网失败: " + e.getMessage());
        }
    }

    /** 启动 React 管理端：npm run dev（Vite 开发服务器） */
    private void startAdmin() {
        if (isServiceHealthy(adminPort)) {
            return;
        }
        Path adminDir = resolveDir(adminWorkDir, "web-admin-react", "package.json");
        if (adminDir == null) {
            System.err.println("=== 未找到管理端目录（web-admin-react/package.json），跳过管理端自动启动");
            return;
        }
        if (!Files.isDirectory(adminDir.resolve("node_modules"))) {
            System.err.println("=== 管理端尚未安装依赖，请先在 web-admin-react 执行 npm install");
            return;
        }

        System.out.println("[本地] 启动管理端 :" + adminPort);
        try {
            List<String> commands = new ArrayList<>();
            // Windows 下 npm 是 npm.cmd 批处理，必须经 cmd.exe 启动；进程树关闭时用 taskkill /T
            if (isWindows()) {
                commands.add("cmd.exe");
                commands.add("/c");
            }
            commands.add(adminNpmCmd);
            commands.add("run");
            commands.add("dev");

            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(adminDir.toFile());
            pb.inheritIO();

            adminProcess = pb.start();
            new Thread(() -> waitForHealthy(adminProcess, "管理端", adminPort, 90),
                    "admin-health-check").start();
        } catch (Exception e) {
            System.err.println("=== 启动本地管理端失败: " + e.getMessage());
        }
    }

    /** 解析前端目录：支持从项目根目录、backend 目录或更深层子目录启动 */
    private Path resolveDir(String configured, String folder, String requiredFile) {
        if (configured != null && !configured.trim().isEmpty()) {
            Path p = Paths.get(configured).toAbsolutePath().normalize();
            return Files.exists(p.resolve(requiredFile)) ? p : null;
        }
        String userDir = System.getProperty("user.dir");
        Path[] candidates = new Path[]{
                Paths.get(userDir, folder),
                Paths.get(userDir, "..", folder),
                Paths.get(userDir, "..", "..", folder)
        };
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.exists(normalized.resolve(requiredFile))) {
                return normalized;
            }
        }
        return null;
    }

    /** 异步轮询就绪，避免阻塞 Spring Boot 启动 */
    private void waitForHealthy(Process process, String name, int port, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isServiceHealthy(port)) {
                System.out.println("[本地] " + name + " 就绪 :" + port);
                return;
            }
            if (process != null && !process.isAlive()) {
                System.err.println("=== 本地" + name + "进程已退出，退出码: " + process.exitValue()
                        + "，请检查控制台日志（npm install / Python 是否可用）");
                return;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("=== 本地" + name + "在 " + timeoutSeconds + " 秒内未就绪，请检查控制台日志");
    }

    /**
     * 健康检查：分别探测 IPv4 / IPv6 两个回环地址。
     * Windows 下 Node/Vite 常只监听 ::1，而 Java 默认把 localhost 解析成 127.0.0.1，
     * 只探测 localhost 会把已在运行的服务误判为未启动，从而重复拉起并报端口占用。
     */
    private boolean isServiceHealthy(int port) {
        return probe("127.0.0.1", port) || probe("::1", port);
    }

    private boolean probe(String host, int port) {
        HttpURLConnection conn = null;
        try {
            String hostPart = host.contains(":") ? "[" + host + "]" : host;
            URL url = new URL("http://" + hostPart + ":" + port + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 关闭子进程：Windows 需要连同 npm/Vite 子进程一起结束 */
    private void stopProcess(Process process, String name) {
        if (process == null || !process.isAlive()) {
            return;
        }
        System.out.println("=== 正在关闭本地" + name + "...");
        if (isWindows()) {
            try {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                process.destroyForcibly();
            }
            return;
        }
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

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
