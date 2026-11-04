package com.znxsgl.service;

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
 * 作息表图片识别：调用 OpenAI 兼容的多模态视觉模型（Qwen2.5-VL / GLM-4V 等）。
 * 只提取教学节次时间，非教学时段（起床铃/早读/午休等）忽略。
 * <p>
 * 配置（环境变量）：VISION_API_KEY 必填；VISION_URL / VISION_MODEL 可选，默认硅基流动 + Qwen2.5-VL。
 */
@Service
public class BellVisionService {

    private static final Logger log = LoggerFactory.getLogger(BellVisionService.class);

    @Value("${vision.api-key:}")
    private String apiKey;

    @Value("${vision.url:https://api.siliconflow.cn/v1/chat/completions}")
    private String url;

    @Value("${vision.model:Qwen/Qwen2.5-VL-7B-Instruct}")
    private String model;

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String PROMPT = """
            你是一个学校作息表识别器。图片是一张作息时间表（中文）。
            请只提取【教学节次】的起止时间，并映射为节次编号：
            - 第一节~第八节：node=1~8（若表中只到第七节，则 node=1~7；"晚自习"视为第8节，若同时存在第八节则晚自习忽略）
            - 忽略所有非教学时段：起床铃、早训、早餐、早读、大课间、眼保健操、午餐、午休、课外活动、
              晚餐、晚自习后的晚点名、晚休、手机统一管理等
            要求：
            1. 时间用 24 小时制、两位小时/分钟，格式 HH:mm（如 08:55、9:5 需规范为 09:05）
            2. 同一节次若出现多行（如单双周），取第一行
            3. 只输出严格 JSON，不要 markdown、不要解释，结构：
            {"periods":[{"node":1,"startTime":"08:55","endTime":"09:35"}]}
            """;

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 识别图片，返回模型原始文本（由调用方解析 JSON） */
    public String recognize(byte[] imageBytes, String mimeType) throws Exception {
        if (!enabled()) {
            throw new IllegalStateException("未配置视觉模型：请设置环境变量 VISION_API_KEY（支持 OpenAI 兼容的多模态模型，如 Qwen2.5-VL / GLM-4V）");
        }
        String mime = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", dataUrl));

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", PROMPT);

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(textPart);
        content.add(imagePart);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", content));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", 800);
        body.put("messages", messages);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("视觉模型调用失败（HTTP " + resp.statusCode() + "）："
                    + trim(resp.body(), 200));
        }
        var root = mapper.readTree(resp.body());
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("视觉模型未返回内容");
        }
        String text = choices.get(0).path("message").path("content").asText("");
        if (text.isBlank()) {
            throw new IllegalStateException("视觉模型返回为空");
        }
        log.info("[BellVision] 识别结果预览: {}", trim(text.replace("\n", " "), 160));
        return text;
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
