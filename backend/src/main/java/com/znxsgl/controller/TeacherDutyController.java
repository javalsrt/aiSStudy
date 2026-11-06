package com.znxsgl.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师职务与班主任设置。
 * <p>
 * - teacher.title 是「职称」（讲师/副教授），teacher.duty 是「职务」（教师/班主任/教导主任）。
 * - 每个班级在 class_info.head_teacher_id 指定一名班主任（teacher.id）。
 * - is_leader=1 的教师视为校级领导，可终审超过 3 天的请假。
 */
@RestController
@RequestMapping("/api/admin/teacher-duty")
@PreAuthorize("hasRole('ADMIN')")
public class TeacherDutyController {

    private final JdbcTemplate jdbc;

    public TeacherDutyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 教师列表（含职务、是否领导、所带班级） */
    @GetMapping("/teachers")
    public ResponseEntity<List<Map<String, Object>>> teachers(
            @RequestParam(value = "keyword", required = false) String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.real_name AS name, t.teacher_no AS teacherNo, t.title, t.duty, t.is_leader AS isLeader, " +
                        "t.phone, t.status FROM teacher t WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (t.real_name LIKE ? OR t.teacher_no LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY 1");
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT t.id, t.real_name AS name, t.teacher_no AS teacherNo, t.title, t.duty, " +
                            "t.is_leader AS isLeader, t.phone, t.status FROM teacher t WHERE 1=1" +
                            (keyword != null && !keyword.isBlank()
                                    ? " AND (t.real_name LIKE '%" + keyword.trim().replace("'", "") + "%' " +
                                    "OR t.teacher_no LIKE '%" + keyword.trim().replace("'", "") + "%')"
                                    : "") +
                            " ORDER BY t.is_leader DESC, t.duty = '班主任' DESC, t.id");
        } catch (Exception e) {
            // 数据库未执行迁移 22（缺 duty / is_leader 列）时降级
            rows = jdbc.queryForList(
                    "SELECT id, real_name AS name, teacher_no AS teacherNo, title, phone, status FROM teacher ORDER BY id");
            for (Map<String, Object> r : rows) {
                r.put("duty", "教师");
                r.put("isLeader", 0);
            }
        }
        for (Map<String, Object> r : rows) {
            Long tid = ((Number) r.get("id")).longValue();
            List<String> classes;
            try {
                classes = jdbc.queryForList(
                        "SELECT class_name FROM class_info WHERE head_teacher_id = ? ORDER BY class_name",
                        String.class, tid);
            } catch (Exception e) {
                classes = java.util.Collections.emptyList();
            }
            r.put("headClasses", classes);
            r.put("headClassCount", classes.size());
        }
        return ResponseEntity.ok(rows);
    }

    /** 修改职务 / 领导标记 */
    @PostMapping("/teachers/{id}")
    public ResponseEntity<?> updateTeacher(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String duty = body.get("duty") == null ? null : String.valueOf(body.get("duty")).trim();
        Object leaderObj = body.get("isLeader");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, duty, is_leader FROM teacher WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "教师不存在"));
        String currentDuty = String.valueOf(rows.get(0).get("duty"));
        int leader = leaderObj == null ? ((Number) rows.get(0).get("is_leader")).intValue()
                : ("true".equalsIgnoreCase(String.valueOf(leaderObj)) || "1".equals(String.valueOf(leaderObj)) ? 1 : 0);
        if (duty == null || duty.isBlank()) duty = currentDuty;
        if (leader == 1 && "教师".equals(duty)) duty = "教导主任";
        jdbc.update("UPDATE teacher SET duty = ?, is_leader = ? WHERE id = ?", duty, leader, id);
        return ResponseEntity.ok(Map.of("msg", "已保存", "duty", duty, "isLeader", leader));
    }

    /** 班级列表（含当前班主任） */
    @GetMapping("/classes")
    public ResponseEntity<List<Map<String, Object>>> classes() {
        try {
            return ResponseEntity.ok(jdbc.queryForList(
                    "SELECT ci.id, ci.class_name AS className, ci.grade, ci.major, ci.department, " +
                            "ci.head_teacher_id AS headTeacherId, t.real_name AS headTeacherName, t.duty AS headTeacherDuty, " +
                            "t.is_leader AS headTeacherIsLeader, " +
                            "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.status = 1) AS studentCount " +
                            "FROM class_info ci LEFT JOIN teacher t ON t.id = ci.head_teacher_id " +
                            "ORDER BY ci.grade, ci.class_name"));
        } catch (Exception e) {
            // 未迁移 head_teacher_id / duty 时降级：仍返回班级与人数，班主任显示为空
            return ResponseEntity.ok(jdbc.queryForList(
                    "SELECT ci.id, ci.class_name AS className, ci.grade, ci.major, ci.department, " +
                            "NULL AS headTeacherId, NULL AS headTeacherName, NULL AS headTeacherDuty, " +
                            "0 AS headTeacherIsLeader, " +
                            "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.status = 1) AS studentCount " +
                            "FROM class_info ci ORDER BY ci.grade, ci.class_name"));
        }
    }

    /**
     * 指定 / 取消班主任（teacherId 为空或 0 表示取消）。
     * 任意教师均可担任，含校级领导。
     */
    @PostMapping("/classes/{classId}/head-teacher")
    public ResponseEntity<?> setHeadTeacher(@PathVariable Long classId, @RequestBody Map<String, Object> body,
                                            org.springframework.security.core.Authentication auth) {
        List<Map<String, Object>> ci = jdbc.queryForList(
                "SELECT class_name, head_teacher_id FROM class_info WHERE id = ?", classId);
        if (ci.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "班级不存在"));
        String className = String.valueOf(ci.get(0).get("class_name"));
        Long oldId = ci.get(0).get("head_teacher_id") == null ? null
                : ((Number) ci.get(0).get("head_teacher_id")).longValue();
        String oldName = oldId == null ? null : teacherName(oldId);

        Object rawId = body.get("teacherId");
        Long teacherId = (rawId == null || String.valueOf(rawId).isBlank() || "0".equals(String.valueOf(rawId)))
                ? null : Long.valueOf(String.valueOf(rawId));

        // ===== 取消班主任 =====
        if (teacherId == null) {
            if (oldId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "该班当前没有班主任"));
            }
            jdbc.update("UPDATE class_info SET head_teacher_id = NULL WHERE id = ?", classId);
            revertDuty(oldId);
            writeLog(auth, classId, className, "CLEAR", oldName, null);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("msg", "已取消「" + className + "」的班主任");
            resp.put("headTeacherId", null);
            resp.put("headTeacherName", null);
            return ResponseEntity.ok(resp);
        }

        // ===== 设置班主任 =====
        List<Map<String, Object>> t = jdbc.queryForList(
                "SELECT id, real_name, duty, is_leader FROM teacher WHERE id = ?", teacherId);
        if (t.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "教师不存在"));
        String newName = String.valueOf(t.get(0).get("real_name"));

        jdbc.update("UPDATE class_info SET head_teacher_id = ? WHERE id = ?", teacherId, classId);
        int isLeader = ((Number) t.get(0).get("is_leader")).intValue();
        if (isLeader == 0) {
            jdbc.update("UPDATE teacher SET duty = '班主任' WHERE id = ?", teacherId);
        }
        if (oldId != null && !oldId.equals(teacherId)) {
            revertDuty(oldId);
        }
        writeLog(auth, classId, className, "SET", oldName, newName);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msg", "已将「" + newName + "」设为「" + className + "」班主任");
        resp.put("headTeacherId", teacherId);
        resp.put("headTeacherName", newName);
        return ResponseEntity.ok(resp);
    }

    /** 班主任调整记录（最近 N 条） */
    @GetMapping("/logs")
    public ResponseEntity<List<Map<String, Object>>> logs(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        int n = Math.min(Math.max(limit, 1), 100);
        try {
            return ResponseEntity.ok(jdbc.queryForList(
                    "SELECT id, operator_name AS operatorName, class_id AS classId, class_name AS className, " +
                            "action, old_teacher AS oldTeacher, new_teacher AS newTeacher, " +
                            "DATE_FORMAT(created_at, '%m-%d %H:%i') AS createdAt " +
                            "FROM class_head_log ORDER BY id DESC LIMIT ?", n));
        } catch (Exception e) {
            // 未执行迁移 24（缺 class_head_log）时返回空列表，不影响页面
            return ResponseEntity.ok(List.of());
        }
    }

    private String teacherName(Long id) {
        List<String> names = jdbc.queryForList("SELECT real_name FROM teacher WHERE id = ?", String.class, id);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 教师不再担任任何班级班主任时，职务回落到「教师」（领导保持不变） */
    private void revertDuty(Long teacherId) {
        Integer remain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_info WHERE head_teacher_id = ?", Integer.class, teacherId);
        Integer leader = jdbc.queryForObject(
                "SELECT is_leader FROM teacher WHERE id = ?", Integer.class, teacherId);
        if (remain != null && remain == 0 && (leader == null || leader == 0)) {
            jdbc.update("UPDATE teacher SET duty = '教师' WHERE id = ?", teacherId);
        }
    }

    private void writeLog(org.springframework.security.core.Authentication auth, Long classId, String className,
                          String action, String oldTeacher, String newTeacher) {
        try {
            Long uid = (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
            String name = null;
            if (uid != null) {
                List<String> names = jdbc.queryForList("SELECT real_name FROM user WHERE id = ?", String.class, uid);
                if (!names.isEmpty()) name = names.get(0);
            }
            jdbc.update("INSERT INTO class_head_log (operator_id, operator_name, class_id, class_name, action, " +
                            "old_teacher, new_teacher) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    uid, name, classId, className, action, oldTeacher, newTeacher);
        } catch (Exception ignored) {
        }
    }
}
