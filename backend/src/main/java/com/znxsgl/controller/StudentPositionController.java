package com.znxsgl.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学生班级职务（班干部）设置。
 * <p>
 * 权限：
 * - 管理员：可为任意用户设置任意职务；
 * - 教师：仅可为「自己担任班主任」或「自己授课班级」的学生设置**班干部**职务。
 */
@RestController
@RequestMapping("/api/student-position")
public class StudentPositionController {

    /** 班干部职务（教师可选范围） */
    private static final List<String> CADRES = List.of(
            "班长", "副班长", "团支书", "学习委员", "纪律委员",
            "生活委员", "文娱委员", "体育委员", "心理委员", "宣传委员", "劳动委员", "课代表");

    /** 管理员额外可选（教师不能设） */
    private static final List<String> ADMIN_ONLY = List.of(
            "班主任助理", "学生会干部", "社团负责人", "其他");

    private final JdbcTemplate jdbc;

    public StudentPositionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 可选职务列表 */
    @GetMapping("/options")
    public ResponseEntity<Map<String, Object>> options(Authentication auth) {
        boolean admin = hasRole(auth, "ROLE_ADMIN");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cadres", CADRES);
        data.put("adminOnly", admin ? ADMIN_ONLY : List.of());
        return ResponseEntity.ok(data);
    }

    /** 某班学生及职务（班主任/任课教师/管理员可看） */
    @GetMapping("/class/{classId}")
    public ResponseEntity<?> byClass(@PathVariable Long classId, Authentication auth) {
        if (!canAccessClass(auth, classId)) {
            return ResponseEntity.status(403).body(Map.of("error", "只能查看自己班级的学生"));
        }
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT id, real_name AS name, student_no AS studentNo, position, status " +
                        "FROM user WHERE class_id = ? AND role = 1 AND status = 1 ORDER BY position IS NULL, position, id",
                classId));
    }

    /** 设置职务（position 为空串=清除） */
    @PostMapping("/{userId}")
    public ResponseEntity<?> setPosition(@PathVariable Long userId, @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        String position = body.get("position") == null ? "" : String.valueOf(body.get("position")).trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, real_name, role, class_id FROM user WHERE id = ?", userId);
        if (rows.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "用户不存在"));
        Map<String, Object> target = rows.get(0);
        int role = ((Number) target.get("role")).intValue();
        Long classId = target.get("class_id") instanceof Number n ? n.longValue() : null;

        boolean admin = hasRole(auth, "ROLE_ADMIN");
        if (!admin) {
            if (role != 1) {
                return ResponseEntity.status(403).body(Map.of("error", "教师只能为学生设置班干部职务"));
            }
            if (!canAccessClass(auth, classId)) {
                return ResponseEntity.status(403).body(Map.of("error", "只能设置自己班级的学生"));
            }
            if (!position.isEmpty() && !CADRES.contains(position)) {
                return ResponseEntity.status(403).body(Map.of("error", "教师只能设置班干部职务（" + String.join("、", CADRES) + "）"));
            }
        } else if (!position.isEmpty() && !CADRES.contains(position) && !ADMIN_ONLY.contains(position)) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知职务"));
        }

        String oldPosition = null;
        List<Map<String, Object>> olds = jdbc.queryForList(
                "SELECT position FROM user WHERE id = ?", userId);
        if (!olds.isEmpty() && olds.get(0).get("position") != null) {
            oldPosition = String.valueOf(olds.get(0).get("position"));
        }

        jdbc.update("UPDATE user SET position = ? WHERE id = ?", position.isEmpty() ? null : position, userId);

        // 记录调整
        try {
            String className = null;
            if (classId != null) {
                List<String> cn = jdbc.queryForList("SELECT class_name FROM class_info WHERE id = ?", String.class, classId);
                if (!cn.isEmpty()) className = cn.get(0);
            }
            String operator = operatorName(auth);
            jdbc.update("INSERT INTO student_position_log (operator_id, operator_name, student_id, student_name, " +
                            "class_id, class_name, old_position, new_position) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    uid(auth), operator, userId, String.valueOf(target.get("real_name")), classId, className,
                    oldPosition, position.isEmpty() ? null : position);
        } catch (Exception ignored) {
        }

        return ResponseEntity.ok(Map.of("msg", position.isEmpty() ? "已清除职务" : "已设为「" + position + "」",
                "position", position));
    }

    /** 学生职务调整记录：管理员看全校，教师看自己班级 */
    @GetMapping("/logs")
    public ResponseEntity<List<Map<String, Object>>> logs(
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            Authentication auth) {
        int n = Math.min(Math.max(limit, 1), 100);
        String sql = "SELECT l.id, l.operator_name AS operatorName, l.student_name AS studentName, " +
                "l.class_name AS className, l.old_position AS oldPosition, l.new_position AS newPosition, " +
                "DATE_FORMAT(l.created_at, '%m-%d %H:%i') AS createdAt " +
                "FROM student_position_log l WHERE 1=1 ";
        if (hasRole(auth, "ROLE_ADMIN")) {
            return ResponseEntity.ok(jdbc.queryForList(sql + "ORDER BY l.id DESC LIMIT ?", n));
        }
        Long uid = uid(auth);
        return ResponseEntity.ok(jdbc.queryForList(
                sql + "AND l.class_id IN (" +
                        "SELECT ci.id FROM class_info ci JOIN teacher t ON t.id = ci.head_teacher_id " +
                        "JOIN user u ON u.real_name = t.real_name WHERE u.id = ?" +
                        " UNION SELECT cc.class_id FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                        "JOIN teacher t2 ON t2.id = c.teacher_id JOIN user u2 ON u2.real_name = t2.real_name WHERE u2.id = ?" +
                        ") ORDER BY l.id DESC LIMIT ?", uid, uid, n));
    }

    private Long uid(Authentication auth) {
        return (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
    }

    private String operatorName(Authentication auth) {
        Long id = uid(auth);
        if (id == null) return null;
        List<String> names = jdbc.queryForList("SELECT real_name FROM user WHERE id = ?", String.class, id);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 是否可管理该班：管理员 / 该班班主任 / 该班任课教师 */
    private boolean canAccessClass(Authentication auth, Long classId) {
        if (classId == null) return false;
        if (hasRole(auth, "ROLE_ADMIN")) return true;
        Long uid = (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
        if (uid == null) return false;
        Integer head = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_info ci JOIN teacher t ON t.id = ci.head_teacher_id " +
                        "JOIN user u ON u.real_name = t.real_name WHERE ci.id = ? AND u.id = ?",
                Integer.class, classId, uid);
        if (head != null && head > 0) return true;
        Integer teach = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_class cc JOIN course c ON c.id = cc.course_id " +
                        "JOIN teacher t ON t.id = c.teacher_id JOIN user u ON u.real_name = t.real_name " +
                        "WHERE cc.class_id = ? AND u.id = ?", Integer.class, classId, uid);
        return teach != null && teach > 0;
    }

    private boolean hasRole(Authentication auth, String role) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }
}
