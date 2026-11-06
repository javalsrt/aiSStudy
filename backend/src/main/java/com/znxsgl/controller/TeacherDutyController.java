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
        sql.append(" ORDER BY t.is_leader DESC, t.duty = '班主任' DESC, t.id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            Long tid = ((Number) r.get("id")).longValue();
            List<String> classes = jdbc.queryForList(
                    "SELECT class_name FROM class_info WHERE head_teacher_id = ? ORDER BY class_name", String.class, tid);
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
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT ci.id, ci.class_name AS className, ci.grade, ci.major, ci.department, " +
                        "ci.head_teacher_id AS headTeacherId, t.real_name AS headTeacherName, t.duty AS headTeacherDuty, " +
                        "t.is_leader AS headTeacherIsLeader, " +
                        "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.status = 1) AS studentCount " +
                        "FROM class_info ci LEFT JOIN teacher t ON t.id = ci.head_teacher_id " +
                        "ORDER BY ci.grade, ci.class_name"));
    }

    /** 指定班主任（任意教师均可，含领导） */
    @PostMapping("/classes/{classId}/head-teacher")
    public ResponseEntity<?> setHeadTeacher(@PathVariable Long classId, @RequestBody Map<String, Object> body) {
        Long teacherId = body.get("teacherId") == null ? null : Long.valueOf(String.valueOf(body.get("teacherId")));
        if (teacherId == null) return ResponseEntity.badRequest().body(Map.of("error", "请选择教师"));
        List<Map<String, Object>> t = jdbc.queryForList(
                "SELECT id, real_name, duty, is_leader FROM teacher WHERE id = ?", teacherId);
        if (t.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "教师不存在"));

        Long oldId = null;
        List<Map<String, Object>> ci = jdbc.queryForList(
                "SELECT head_teacher_id FROM class_info WHERE id = ?", classId);
        if (ci.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "班级不存在"));
        if (ci.get(0).get("head_teacher_id") != null) {
            oldId = ((Number) ci.get(0).get("head_teacher_id")).longValue();
        }

        jdbc.update("UPDATE class_info SET head_teacher_id = ? WHERE id = ?", teacherId, classId);
        // 新班主任：非领导则职务改为「班主任」
        int isLeader = ((Number) t.get(0).get("is_leader")).intValue();
        if (isLeader == 0) {
            jdbc.update("UPDATE teacher SET duty = '班主任' WHERE id = ?", teacherId);
        }
        // 旧班主任：如果不再带任何班，职务回落为「教师」
        if (oldId != null && !oldId.equals(teacherId)) {
            Integer remain = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM class_info WHERE head_teacher_id = ?", Integer.class, oldId);
            Integer oldLeader = jdbc.queryForObject(
                    "SELECT is_leader FROM teacher WHERE id = ?", Integer.class, oldId);
            if (remain != null && remain == 0 && (oldLeader == null || oldLeader == 0)) {
                jdbc.update("UPDATE teacher SET duty = '教师' WHERE id = ?", oldId);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msg", "已将「" + t.get(0).get("real_name") + "」设为该班班主任");
        resp.put("headTeacherId", teacherId);
        resp.put("headTeacherName", t.get(0).get("real_name"));
        return ResponseEntity.ok(resp);
    }
}
