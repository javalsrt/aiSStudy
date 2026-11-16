package com.znxsgl.service;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 演示题库：demo_math 账号固定返回本地 12 道单选题，
 * 不调用大模型，保证答辩演示时题目和答案可控、可重复。
 */
@Service
public class DemoQuizService {

    private static final long DEMO_MATH_COURSE_ID = 359L;
    private final Map<Long, String> cache = new ConcurrentHashMap<>();

    public boolean isDemo(String username) {
        return "demo_math".equals(username);
    }

    /** 返回固定题库 JSON；非演示科目返回 null */
    public String rawQuestions(Long courseId) {
        if (courseId == null || courseId != DEMO_MATH_COURSE_ID) return null;
        return cache.computeIfAbsent(courseId, id -> {
            try (InputStream in = getClass().getResourceAsStream("/demo/demo_math_quiz.json")) {
                if (in == null) return "";
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        });
    }
}
