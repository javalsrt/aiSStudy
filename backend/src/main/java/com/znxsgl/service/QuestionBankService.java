package com.znxsgl.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 题库服务：Redis 共享缓存 + MySQL 持久化。
 *
 * 缓存 key 由课程、难度、章节等参数指纹生成；
 * Redis 不可用时由 RedisCacheService 自动降级为本机缓存。
 */
@Service
public class QuestionBankService {

    private static final String CACHE_PREFIX = "znxsgl:qbank:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final RedisCacheService cacheService;
    private final ObjectMapper json = new ObjectMapper();

    public QuestionBankService(JdbcTemplate jdbc, RedisCacheService cacheService) {
        this.jdbc = jdbc;
        this.cacheService = cacheService;
    }

    /**
     * 读取缓存：先 Redis/本地，再回源 MySQL。
     */
    public List<Map<String, Object>> get(String cacheKey) {
        String cached = cacheService.get(CACHE_PREFIX + cacheKey);
        if (cached != null && !cached.isBlank()) {
            List<Map<String, Object>> parsed = parse(cached);
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        }
        try {
            String dbJson = jdbc.queryForObject(
                    "SELECT question_json FROM question_bank WHERE cache_key = ?",
                    String.class, cacheKey);
            if (dbJson != null && !dbJson.isBlank()) {
                cacheService.put(CACHE_PREFIX + cacheKey, dbJson, CACHE_TTL);
                List<Map<String, Object>> parsed = parse(dbJson);
                return parsed != null ? parsed : Collections.emptyList();
            }
        } catch (EmptyResultDataAccessException ignored) {
            // 没有缓存
        } catch (Exception e) {
            System.out.println("=== 题库回源失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 保存题库：MySQL upsert + 写入 Redis 共享缓存。
     */
    public void save(String cacheKey, Long courseId, String subject, Integer difficulty,
                     List<Map<String, Object>> questions) {
        if (questions == null || questions.isEmpty()) {
            return;
        }
        String questionJson;
        try {
            questionJson = json.writeValueAsString(questions);
        } catch (Exception e) {
            System.out.println("=== 题库序列化失败: " + e.getMessage());
            return;
        }

        cacheService.put(CACHE_PREFIX + cacheKey, questionJson, CACHE_TTL);
        try {
            jdbc.update(
                    "INSERT INTO question_bank(cache_key, course_id, subject, difficulty, question_json, created_at, updated_at) " +
                    "VALUES(?,?,?,?,?,NOW(),NOW()) " +
                    "ON DUPLICATE KEY UPDATE question_json = VALUES(question_json), " +
                    " subject = VALUES(subject), difficulty = VALUES(difficulty), updated_at = NOW()",
                    cacheKey, courseId, subject, difficulty, questionJson);
        } catch (Exception e) {
            // 表不存在或数据库异常时不影响主流程，Redis/本地缓存仍可用
            System.out.println("=== 题库入库失败: " + e.getMessage());
        }
    }

    public void invalidateCourse(Long courseId) {
        try {
            jdbc.update("DELETE FROM question_bank WHERE course_id = ?", courseId);
        } catch (Exception e) {
            System.out.println("=== 清理课程题库失败: " + e.getMessage());
        }
        cacheService.evictByPrefix(CACHE_PREFIX);
    }

    private List<Map<String, Object>> parse(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            System.out.println("=== 题库 JSON 解析失败: " + e.getMessage());
            return null;
        }
    }
}