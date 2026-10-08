package com.znxsgl.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 签到接口
 *
 * 权限说明：
 * - /create、/close 仅教师或管理员可调用
 * - /verify 仅学生或管理员可调用
 * - /status 需登录（由 SecurityConfig 统一认证）
 */
@RestController
@RequestMapping("/api/checkin")
public class CheckInController {

    private final JdbcTemplate jdbc;

    public CheckInController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 教师创建签到 */
    @PostMapping("/create")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, Authentication auth) {
        String courseName = (String) body.get("courseName");
        String password = (String) body.get("password");
        Long userId = (Long) auth.getPrincipal();

        // 获取教师姓名
        String userName;
        try {
            Map<String, Object> user = jdbc.queryForMap("SELECT real_name FROM user WHERE id = ?", userId);
            userName = (String) user.get("real_name");
        } catch (Exception e) {
            userName = "教师";
        }

        // 校验课程归属当前教师
        Long teacherId = resolveTeacherId(userId);
        if (teacherId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        Integer courseCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM course WHERE course_name = ? AND teacher_id = ?",
            Integer.class, courseName, teacherId);
        if (courseCount == null || courseCount == 0) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }

        // 关闭该课程之前的签到
        jdbc.update("UPDATE check_in SET active = 0 WHERE course_name = ? AND active = 1", courseName);

        // 创建新签到（15分钟有效）
        jdbc.update(
            "INSERT INTO check_in (course_name, created_by, creator_name, password, created_at, expires_at, active) " +
            "VALUES (?, ?, ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 15 MINUTE), 1)",
            courseName, userId, userName, password);

        Map<String, Object> result = new HashMap<>();
        result.put("msg", "签到已创建，有效期15分钟");
        result.put("password", password);
        return ResponseEntity.ok(result);
    }

    /** 学生验证签到密码 */
    @PostMapping("/verify")
    @PreAuthorize("hasRole('STUDENT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body, Authentication auth) {
        String courseName = (String) body.get("courseName");
        String password = (String) body.get("password");
        // 学生只能自己签到：以登录身份为准，忽略请求体中的 studentId
        Long studentId = (Long) auth.getPrincipal();
        String studentName;
        try {
            Map<String, Object> user = jdbc.queryForMap("SELECT real_name FROM user WHERE id = ?", studentId);
            studentName = (String) user.get("real_name");
        } catch (Exception e) {
            studentName = "学生";
        }

        // 查找有效签到
        List<Map<String, Object>> list = jdbc.queryForList(
            "SELECT id FROM check_in WHERE course_name = ? AND password = ? AND active = 1 AND expires_at > NOW()",
            courseName, password);

        if (list.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "签到码无效或已过期"));
        }

        Long checkInId = ((Number) list.get(0).get("id")).longValue();

        // 依赖唯一索引 (check_in_id, student_id) 做原子幂等写入，避免先查后插并发重复
        int inserted = jdbc.update(
            "INSERT IGNORE INTO check_in_record (check_in_id, student_id, student_name, checked_at) " +
            "VALUES (?, ?, ?, NOW())",
            checkInId, studentId, studentName);

        if (inserted == 0) {
            return ResponseEntity.ok(Map.of("error", "您已签到过了"));
        }

        return ResponseEntity.ok(Map.of("msg", studentName + " 签到成功！", "success", true));
    }

    /** 获取签到状态 */
    @GetMapping("/status/{courseName}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String courseName) {
        List<Map<String, Object>> active = jdbc.queryForList(
            "SELECT id, password, creator_name, created_at, expires_at FROM check_in " +
            "WHERE course_name = ? AND active = 1 AND expires_at > NOW() LIMIT 1",
            courseName);

        Map<String, Object> result = new HashMap<>();
        if (!active.isEmpty()) {
            Map<String, Object> ci = active.get(0);
            Long id = ((Number) ci.get("id")).longValue();
            int checkedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM check_in_record WHERE check_in_id = ?", Integer.class, id);
            result.put("active", true);
            result.put("password", ci.get("password"));
            result.put("creatorName", ci.get("creator_name"));
            result.put("checkedCount", checkedCount);
        } else {
            result.put("active", false);
            result.put("checkedCount", 0);
        }
        return ResponseEntity.ok(result);
    }

    /** 教师关闭签到 */
    @PostMapping("/close")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> close(@RequestBody Map<String, Object> body, Authentication auth) {
        String courseName = (String) body.get("courseName");
        Long userId = (Long) auth.getPrincipal();
        // 校验课程归属当前教师
        Long teacherId = resolveTeacherId(userId);
        if (teacherId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        Integer courseCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM course WHERE course_name = ? AND teacher_id = ?",
            Integer.class, courseName, teacherId);
        if (courseCount == null || courseCount == 0) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        jdbc.update("UPDATE check_in SET active = 0 WHERE course_name = ? AND active = 1", courseName);
        return ResponseEntity.ok(Map.of("msg", "签到已关闭"));
    }

    /** 通过 userId 反查 teacher 表的 id（依据 real_name 关联） */
    private Long resolveTeacherId(Long userId) {
        try {
            List<Long> ids = jdbc.queryForList(
                "SELECT t.id FROM teacher t " +
                "JOIN user u ON u.real_name = t.real_name " +
                "WHERE u.id = ? LIMIT 1", Long.class, userId);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            return null;
        }
    }
}
