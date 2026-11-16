package com.znxsgl.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 章节阅读进度服务：记录学生已完成的课时，用于自适应出题前置条件判断
 */
@Service
public class ChapterProgressService {

    private final JdbcTemplate jdbc;

    public ChapterProgressService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 标记课时完成：先查 lesson 拿 chapterId/courseId，再 INSERT IGNORE
     */
    public boolean markComplete(Long userId, Long lessonId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT l.chapter_id AS chapterId, c.course_id AS courseId " +
                "FROM course_lesson l JOIN course_chapter c ON l.chapter_id = c.id " +
                "WHERE l.id = ?", lessonId);
        if (row == null || row.isEmpty()) {
            throw new RuntimeException("课时不存在");
        }
        Long chapterId = ((Number) row.get("chapterId")).longValue();
        Long courseId = ((Number) row.get("courseId")).longValue();
        jdbc.update("INSERT IGNORE INTO chapter_read_progress(user_id, course_id, chapter_id, lesson_id, completed_at) " +
                "VALUES(?,?,?,?,NOW())", userId, courseId, chapterId, lessonId);
        return true;
    }

    /**
     * 每门课程最近学习的章节：按完成时间取该课程最新的一条阅读记录
     */
    public List<Map<String, Object>> getRecentChapterPerCourse(Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.course_id AS courseId, p.chapter_id AS chapterId, " +
                "c.chapter_no AS chapterNo, c.chapter_name AS chapterName, " +
                "MAX(p.completed_at) AS lastStudiedAt " +
                "FROM chapter_read_progress p JOIN course_chapter c ON c.id = p.chapter_id " +
                "WHERE p.user_id = ? " +
                "GROUP BY p.course_id, p.chapter_id, c.chapter_no, c.chapter_name " +
                "ORDER BY lastStudiedAt DESC", userId);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Map<String, Object> row : rows) {
            Object courseId = row.get("courseId");
            if (!(courseId instanceof Number)) continue;
            if (seen.add(((Number) courseId).longValue())) {
                // 时间戳只用于排序，不返回给客户端
                row.remove("lastStudiedAt");
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 返回已完成的 lessonId 列表
     */
    public List<Long> getCompletedLessons(Long userId, Long courseId) {
        return jdbc.queryForList(
                "SELECT lesson_id FROM chapter_read_progress WHERE user_id = ? AND course_id = ?",
                Long.class, userId, courseId);
    }

    /**
     * 返回已完成的 chapterId 去重列表
     */
    public List<Long> getCompletedChapterIds(Long userId, Long courseId) {
        return jdbc.queryForList(
                "SELECT DISTINCT chapter_id FROM chapter_read_progress WHERE user_id = ? AND course_id = ?",
                Long.class, userId, courseId);
    }

    /**
     * 是否有任何已完成课时（满足出题前置条件）
     */
    public boolean canQuiz(Long userId, Long courseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chapter_read_progress WHERE user_id = ? AND course_id = ? LIMIT 1",
                Integer.class, userId, courseId);
        return count != null && count > 0;
    }
}
