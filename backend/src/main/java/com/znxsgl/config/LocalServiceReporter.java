package com.znxsgl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动完成后统一打印本地各服务状态：后端、MySQL、官网、管理端、Embedding、OCR。
 * 框架自身的启动日志已由 logging.level.root=WARN 收敛，这里每个服务只输出一行。
 */
@Component
public class LocalServiceReporter {

    private static final Pattern MYSQL_URL = Pattern.compile("jdbc:mysql://([^/?]+)/([^?]+)");

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${frontend.local.auto-start:false}")
    private boolean frontendAutoStart;
    @Value("${frontend.local.website.enabled:false}")
    private boolean websiteEnabled;
    @Value("${frontend.local.website.port:5500}")
    private int websitePort;
    @Value("${frontend.local.admin.enabled:false}")
    private boolean adminEnabled;
    @Value("${frontend.local.admin.port:5174}")
    private int adminPort;

    @Value("${embedding.local.auto-start:false}")
    private boolean embeddingAutoStart;
    @Value("${embedding.local.port:8000}")
    private int embeddingPort;

    @Value("${ocr.auto-start:false}")
    private boolean ocrAutoStart;
    @Value("${ocr.port:8100}")
    private int ocrPort;

    private final DataSource dataSource;

    public LocalServiceReporter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        System.out.println("[启动] 后端 已就绪 · http://localhost:" + serverPort);
        reportDatabase();
        if (websiteEnabled) {
            reportHttp("官网", websitePort, "/", frontendAutoStart);
        }
        if (adminEnabled) {
            reportHttp("管理端", adminPort, "/", frontendAutoStart);
        }
        reportHttp("Embedding", embeddingPort, "/health", embeddingAutoStart);
        reportHttp("OCR", ocrPort, "/health", ocrAutoStart);
    }

    /** 输出 MySQL 连接状态，直接从 JDBC URL 中提取主机/库名 */
    private void reportDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            String jdbcUrl = conn.getMetaData().getURL();
            Matcher m = MYSQL_URL.matcher(jdbcUrl);
            String target = m.find() ? m.group(1) + "/" + m.group(2) : jdbcUrl;
            System.out.println("[本地] MySQL 已连接 · " + target);
        } catch (Exception e) {
            System.err.println("[本地] MySQL 连接失败 · " + e.getMessage());
        }
    }

    /** 输出本地 HTTP 服务状态：已运行 / 启动中 / 未运行 */
    private void reportHttp(String name, int port, String path, boolean autoStart) {
        String url = "http://localhost:" + port;
        String state = isHealthy(port, path) ? " 已运行" : (autoStart ? " 启动中" : " 未运行");
        System.out.println("[本地] " + name + state + " · " + url);
    }

    /** 同时探测 IPv4 / IPv6 回环地址，避免 Vite 只监听 ::1 时被误判 */
    private boolean isHealthy(int port, String path) {
        return probe("127.0.0.1", port, path) || probe("::1", port, path);
    }

    private boolean probe(String host, int port, String path) {
        HttpURLConnection conn = null;
        try {
            String hostPart = host.contains(":") ? "[" + host + "]" : host;
            URL url = new URL("http://" + hostPart + ":" + port + path);
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
}
