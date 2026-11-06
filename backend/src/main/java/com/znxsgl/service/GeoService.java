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
import java.time.Duration;

/**
 * 逆地理编码：经纬度 → 中文地址。
 * <p>
 * 使用高德 Web 服务 API（需在环境变量/配置中提供 AMAP_KEY，免费申请）。
 * 未配置 Key 或调用失败时返回 null，调用方自行回落到经纬度展示。
 */
@Service
public class GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${amap.key:}")
    private String amapKey;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public boolean enabled() {
        return amapKey != null && !amapKey.isBlank();
    }

    /** 返回「省市区+道路门牌」，失败返回 null */
    public String reverse(double lat, double lng) {
        if (!enabled()) return null;
        String url = "https://restapi.amap.com/v3/geocode/regeo?key=" + amapKey
                + "&location=" + String.format(java.util.Locale.US, "%.6f,%.6f", lng, lat)
                + "&extensions=base&radius=200";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode root = MAPPER.readTree(resp.body());
            if (!"1".equals(root.path("status").asText())) {
                log.debug("高德逆地理编码失败: {}", root.path("info").asText());
                return null;
            }
            JsonNode addr = root.path("regeocode").path("formatted_address");
            String text = addr.isMissingNode() ? "" : addr.asText("");
            return text == null || text.isBlank() ? null : (text.length() > 190 ? text.substring(0, 190) : text);
        } catch (Exception e) {
            log.debug("逆地理编码异常: {}", e.getMessage());
            return null;
        }
    }
}
