package com.znxsgl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地作息表 OCR 客户端：调用 ocr-service（RapidOCR + 版式解析，离线零成本）。
 * 传输格式为 JSON + base64（比手工拼 multipart 稳定）。
 * 服务不可用时抛异常，由调用方回退到视觉大模型。
 */
@Service
public class BellOcrClient {

    private static final Logger log = LoggerFactory.getLogger(BellOcrClient.class);

    @Value("${ocr.url:http://localhost:8100}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            // 必须 HTTP/1.1：uvicorn 不支持 h2c 升级请求，否则会 422/请求体丢失
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** 识别作息表图片，返回 [{node,startTime,endTime}]；服务异常时抛异常 */
    public List<Map<String, Object>> recognize(byte[] bytes, String filename, String mimeType) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("image", Base64.getEncoder().encodeToString(bytes));
        payload.put("filename", filename == null ? "" : filename);

        String url = baseUrl.endsWith("/") ? baseUrl + "ocr/base64" : baseUrl + "/ocr/base64";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            String body = resp.body() == null ? "" : resp.body().replaceAll("\\s+", " ");
            throw new IllegalStateException("OCR 服务返回 HTTP " + resp.statusCode()
                    + (body.isBlank() ? "" : "：" + body.substring(0, Math.min(200, body.length()))));
        }
        JsonNode root = mapper.readTree(resp.body());
        if (root.hasNonNull("error") && !root.path("error").asText().isBlank()) {
            throw new IllegalStateException(root.path("error").asText());
        }
        JsonNode arr = root.path("periods");
        List<Map<String, Object>> periods = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode p : arr) {
                int node = p.path("node").asInt(-1);
                String st = p.path("startTime").asText("");
                String et = p.path("endTime").asText("");
                if (node < 1 || node > 12 || st.isBlank() || et.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("node", node);
                m.put("startTime", st);
                m.put("endTime", et);
                periods.add(m);
            }
        }
        log.info("[BellOCR] 识别到 {} 个节次", periods.size());
        return periods;
    }

    public boolean enabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
