package com.znxsgl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.RateLimiter;
import com.znxsgl.exception.RateLimitException;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 大语言模型服务（当前使用 DeepSeek R1）
 */
@Service
public class LlmService {

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.url}")
    private String apiUrl;

    // AI 接口超时配置：大模型推理可能较慢，读写超时放宽到 180 秒
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // 全局限流：每秒最多 20 次 AI 调用（DeepSeek V4 Flash 平台并发额度 2500，20/s 仅是零头，
    // 主要防止极端刷接口，不影响几十人并发刷题）
    private final RateLimiter globalRateLimiter = RateLimiter.create(20.0);

    // 按用户限流：每分钟最多 10 次 AI 调用，防止单个用户滥用
    private final ConcurrentHashMap<Long, RateLimiter> userRateLimiters = new ConcurrentHashMap<>();

    /**
     * 同步调用 AI，返回完整回复文本（全局限流）
     */
    public String chat(String systemPrompt, String userMessage) {
        if (!globalRateLimiter.tryAcquire()) {
            throw new RateLimitException("AI 服务繁忙，请稍后再试");
        }
        return chatInternal(null, systemPrompt, userMessage, 8192);
    }

    /**
     * 同步调用 AI，指定更大的输出 token 预算（用于生成长文本/大 JSON 的场景）
     */
    public String chat(String systemPrompt, String userMessage, int maxTokens) {
        if (!globalRateLimiter.tryAcquire()) {
            throw new RateLimitException("AI 服务繁忙，请稍后再试");
        }
        return chatInternal(null, systemPrompt, userMessage, maxTokens);
    }

    /**
     * 同步调用 AI，按用户限流，适合需要防止单个用户滥用的场景。
     */
    public String chat(Long userId, String systemPrompt, String userMessage) {
        if (userId != null) {
            RateLimiter userLimiter = userRateLimiters.computeIfAbsent(userId,
                    k -> RateLimiter.create(10.0 / 60.0)); // 每分钟 10 次
            if (!userLimiter.tryAcquire()) {
                throw new RateLimitException("请求过于频繁，请稍后再试");
            }
        }
        if (!globalRateLimiter.tryAcquire()) {
            throw new RateLimitException("AI 服务繁忙，请稍后再试");
        }
        return chatInternal(userId, systemPrompt, userMessage, 8192);
    }

    /**
     * 排队调用 AI（后台任务用）：阻塞等待全局令牌，而不是拒绝。
     * 适合异步出题等场景：高并发时请求自动排队，避免直接抛"繁忙"拒绝，
     * 保证 100 人同时出题时所有人最终都能拿到结果（只是先后不同）。
     */
    public String chatQueued(String systemPrompt, String userMessage) {
        globalRateLimiter.acquire(); // 20/s 速率下平均等待 50ms，排队而非拒绝
        return chatInternal(null, systemPrompt, userMessage, 8192);
    }

    /**
     * 文档分析（纯文本调用 LLM）
     */
    public String analyzeDocument(String prompt, String filePath, String mimeType) {
        return chatInternal(null, prompt, "请分析以下文件内容", 8192);
    }

    private String chatInternal(Long userId, String systemPrompt, String userMessage, int maxTokens) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTokens);

            ArrayNode messages = body.putArray("messages");
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                ObjectNode sys = mapper.createObjectNode();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
                messages.add(sys);
            }

            ObjectNode user = mapper.createObjectNode();
            user.put("role", "user");
            user.put("content", userMessage != null ? userMessage : "");
            messages.add(user);

            String reqJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            System.out.println("=== LLM 请求: " + (userMessage != null ? userMessage.substring(0, Math.min(80, userMessage.length())) : ""));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(reqJson, MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                String bodyStr = response.body().string();

                if (response.code() != 200) {
                    System.out.println("=== LLM 错误[" + response.code() + "]: " + bodyStr.substring(0, Math.min(300, bodyStr.length())));
                    return null;
                }

                JsonNode node = mapper.readTree(bodyStr);
                JsonNode choices = node.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode message = choices.get(0).path("message");
                    String result = message.path("content").asText("");
                    if (!result.isEmpty()) {
                        System.out.println("=== LLM 回复: " + result.substring(0, Math.min(100, result.length())));
                        return result;
                    }
                    String reasoning = message.path("reasoning_content").asText("");
                    if (!reasoning.isEmpty()) {
                        System.out.println("=== LLM 仅返回推理内容");
                        return reasoning;
                    }
                }

                System.out.println("=== LLM 未识别的响应格式: " + bodyStr.substring(0, Math.min(200, bodyStr.length())));
                return null;
            }
        } catch (SocketTimeoutException e) {
            System.out.println("=== LLM 调用超时 [userId=" + userId + "]: " + e.getMessage());
            throw new RuntimeException("AI接口调用超时，请稍后重试", e);
        } catch (Exception e) {
            System.out.println("=== LLM 异常 [userId=" + userId + "]: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}
