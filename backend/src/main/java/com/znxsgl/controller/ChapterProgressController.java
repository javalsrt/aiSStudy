package com.znxsgl.controller;

import com.znxsgl.service.ChapterProgressService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 章节阅读进度接口
 */
@RestController
@RequestMapping("/api/chapter-progress")
public class ChapterProgressController {

    private final ChapterProgressService progressService;

    public ChapterProgressController(ChapterProgressService progressService) {
        this.progressService = progressService;
    }

    /**
     * 标记课时完成
     */
    @PostMapping("/complete")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> complete(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Object lessonIdObj = body.get("lessonId");
        if (lessonIdObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "lessonId不能为空"));
        }
        Long lessonId;
        try {
            lessonId = Long.valueOf(lessonIdObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "lessonId格式错误"));
        }
        progressService.markComplete(userId, lessonId);
        return ResponseEntity.ok(Map.of("msg", "ok"));
    }

    /**
     * 查询已完成进度
     */
    @GetMapping("/completed")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> completed(@RequestParam Long courseId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<Long> lessonIds = progressService.getCompletedLessons(userId, courseId);
        List<Long> chapterIds = progressService.getCompletedChapterIds(userId, courseId);
        boolean canQuiz = progressService.canQuiz(userId, courseId);
        return ResponseEntity.ok(Map.of(
                "lessonIds", lessonIds,
                "chapterIds", chapterIds,
                "canQuiz", canQuiz));
    }

    /**
     * 每门课程最近学习的章节（学习页课程卡片展示用）
     */
    @GetMapping("/recent")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> recent(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(progressService.getRecentChapterPerCourse(userId));
    }
}
