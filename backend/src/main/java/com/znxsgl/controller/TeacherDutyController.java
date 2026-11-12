package com.znxsgl.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
                        "t.phone, t.status, d.dept_name AS dept " +
                        "FROM teacher t LEFT JOIN department d ON d.id = t.dept_id WHERE 1=1");
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
                            "t.is_leader AS isLeader, t.phone, t.status, d.dept_name AS dept " +
                            "FROM teacher t LEFT JOIN department d ON d.id = t.dept_id WHERE 1=1" +
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
                r.put("dept", null);
            }
        }
        List<Long> teacherIds = new ArrayList<>();
        for (Map<String, Object> r : rows) teacherIds.add(((Number) r.get("id")).longValue());
        Map<Long, List<Map<String, Object>>> roleMap = loadRoles(teacherIds);
        for (Map<String, Object> r : rows) {
            Long tid = ((Number) r.get("id")).longValue();
            List<Map<String, Object>> roles = roleMap.get(tid);
            if (roles == null || roles.isEmpty()) {
                roles = deriveLegacyRoles(r);
            }
            r.put("roles", roles);
            r.put("roleNames", roleNames(roles));
            r.put("canTeach", canTeach(roles));
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
            removeHeadTeacherRole(oldId);
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
        addHeadTeacherRole(teacherId);
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

    /** 教师不再担任任何班级班主任时，按剩余身份回填 legacy duty 字段 */
    private void revertDuty(Long teacherId) {
        Integer remain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_info WHERE head_teacher_id = ?", Integer.class, teacherId);
        if (remain == null || remain != 0) return;

        List<Map<String, Object>> roles = loadRoles(List.of(teacherId))
                .getOrDefault(teacherId, List.of());
        if (!roles.isEmpty()) {
            String duty = "教师";
            String[] priority = {"leader", "middle", "position", "support"};
            outer:
            for (String category : priority) {
                for (Map<String, Object> role : roles) {
                    if (category.equals(String.valueOf(role.get("category")))) {
                        duty = String.valueOf(role.get("name"));
                        break outer;
                    }
                }
            }
            jdbc.update("UPDATE teacher SET duty = ? WHERE id = ?", duty, teacherId);
            return;
        }

        Integer leader = jdbc.queryForObject(
                "SELECT is_leader FROM teacher WHERE id = ?", Integer.class, teacherId);
        if (leader == null || leader == 0) {
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

    // ==================== 教师身份（职称 / 岗位 / 领导 / 教辅） ====================

    /** 身份目录：分类 + 角色列表 */
    @GetMapping("/roles")
    public ResponseEntity<Map<String, Object>> roles() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("categories", TeacherRoleCatalog.categories());
        resp.put("roles", TeacherRoleCatalog.asMaps());
        return ResponseEntity.ok(resp);
    }

    /** 覆盖式保存教师身份（支持一人多身份） */
    @PostMapping("/teachers/{id}/roles")
    public ResponseEntity<?> updateTeacherRoles(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> exists = jdbc.queryForList("SELECT id FROM teacher WHERE id = ?", id);
        if (exists.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "教师不存在"));

        Map<String, TeacherRoleCatalog.Role> catalog = TeacherRoleCatalog.byCode();
        List<String> codes = new ArrayList<>();
        Object raw = body.get("roleCodes");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String code = String.valueOf(o);
                if (catalog.containsKey(code) && !codes.contains(code)) codes.add(code);
            }
        }

        try {
            jdbc.update("DELETE FROM teacher_role WHERE teacher_id = ?", id);
            for (String code : codes) {
                jdbc.update("INSERT INTO teacher_role (teacher_id, role_code) VALUES (?, ?)", id, code);
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "教师身份表未创建，请先执行 init-db/28-teacher-role.sql"));
        }

        List<Map<String, Object>> roles = new ArrayList<>();
        for (String code : codes) roles.add(catalog.get(code).toMap());

        String title = null;
        boolean leader = false;
        for (String code : codes) {
            TeacherRoleCatalog.Role role = catalog.get(code);
            if (role == null) continue;
            if (TeacherRoleCatalog.CAT_TITLE.equals(role.getCategory()) && title == null) {
                title = role.getName();
            }
            if (TeacherRoleCatalog.CAT_LEADER.equals(role.getCategory())) leader = true;
        }
        // 兼容旧字段：领导 > 中层 > 岗位 > 教辅 > 教师
        String duty = "教师";
        outer:
        for (String category : new String[]{
                TeacherRoleCatalog.CAT_LEADER, TeacherRoleCatalog.CAT_MIDDLE,
                TeacherRoleCatalog.CAT_POSITION, TeacherRoleCatalog.CAT_SUPPORT}) {
            for (String code : codes) {
                TeacherRoleCatalog.Role role = catalog.get(code);
                if (role != null && category.equals(role.getCategory())) {
                    duty = role.getName();
                    break outer;
                }
            }
        }
        if (title != null) {
            jdbc.update("UPDATE teacher SET title = ?, duty = ?, is_leader = ? WHERE id = ?",
                    title, duty, leader ? 1 : 0, id);
        } else {
            jdbc.update("UPDATE teacher SET duty = ?, is_leader = ? WHERE id = ?",
                    duty, leader ? 1 : 0, id);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msg", "已保存");
        resp.put("roles", roles);
        resp.put("canTeach", canTeach(roles));
        return ResponseEntity.ok(resp);
    }

    /**
     * 教务关系数据：教师 + 身份 + 班级 + 授课关系。
     * 前端关系图谱用它展示 专业/院系 → 班级/教师 → 学生/授课 的完整链路。
     */
    @GetMapping("/relations")
    public ResponseEntity<Map<String, Object>> relations() {
        List<Map<String, Object>> teachers;
        try {
            teachers = jdbc.queryForList(
                    "SELECT t.id, t.real_name AS name, t.teacher_no AS teacherNo, t.title, t.duty, " +
                            "t.is_leader AS isLeader, t.status, d.dept_name AS dept " +
                            "FROM teacher t LEFT JOIN department d ON d.id = t.dept_id ORDER BY t.id");
        } catch (Exception e) {
            teachers = jdbc.queryForList(
                    "SELECT id, real_name AS name, teacher_no AS teacherNo, title, NULL AS duty, " +
                            "0 AS isLeader, status, NULL AS dept FROM teacher ORDER BY id");
        }

        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> t : teachers) ids.add(((Number) t.get("id")).longValue());
        Map<Long, List<Map<String, Object>>> roleMap = loadRoles(ids);
        for (Map<String, Object> t : teachers) {
            Long tid = ((Number) t.get("id")).longValue();
            List<Map<String, Object>> roles = roleMap.get(tid);
            if (roles == null || roles.isEmpty()) roles = deriveLegacyRoles(t);
            t.put("roles", roles);
            t.put("roleNames", roleNames(roles));
            t.put("canTeach", canTeach(roles));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("categories", TeacherRoleCatalog.categories());
        catalog.put("roles", TeacherRoleCatalog.asMaps());
        resp.put("roleCatalog", catalog);
        resp.put("teachers", teachers);
        resp.put("classes", loadClasses());
        resp.put("teachings", loadTeachings());
        return ResponseEntity.ok(resp);
    }

    private Map<Long, List<Map<String, Object>>> loadRoles(List<Long> teacherIds) {
        Map<Long, List<Map<String, Object>>> result = new HashMap<>();
        if (teacherIds == null || teacherIds.isEmpty()) return result;
        try {
            String placeholders = String.join(",", Collections.nCopies(teacherIds.size(), "?"));
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT teacher_id AS teacherId, role_code AS roleCode FROM teacher_role " +
                            "WHERE teacher_id IN (" + placeholders + ") ORDER BY teacher_id, id",
                    teacherIds.toArray());
            Map<String, TeacherRoleCatalog.Role> catalog = TeacherRoleCatalog.byCode();
            for (Map<String, Object> row : rows) {
                Long tid = ((Number) row.get("teacherId")).longValue();
                TeacherRoleCatalog.Role role = catalog.get(String.valueOf(row.get("roleCode")));
                if (role != null) {
                    result.computeIfAbsent(tid, k -> new ArrayList<>()).add(role.toMap());
                }
            }
        } catch (Exception ignored) {
            // teacher_role 表不存在时走旧字段兜底
        }
        return result;
    }

    /** 旧数据兜底：title / duty / is_leader 映射成身份 */
    private List<Map<String, Object>> deriveLegacyRoles(Map<String, Object> teacher) {
        Map<String, Map<String, Object>> roles = new LinkedHashMap<>();
        String title = teacher.get("title") == null ? "" : String.valueOf(teacher.get("title"));
        String duty = teacher.get("duty") == null ? "" : String.valueOf(teacher.get("duty"));
        Map<String, TeacherRoleCatalog.Role> catalog = TeacherRoleCatalog.byCode();

        TeacherRoleCatalog.Role titleRole = null;
        if (title.contains("正高") || title.contains("教授")) titleRole = catalog.get("title_principal_senior");
        else if (title.contains("高级") || title.contains("副教授")) titleRole = catalog.get("title_senior");
        else if (title.contains("一级") || title.contains("讲师")) titleRole = catalog.get("title_first");
        else if (title.contains("二级") || title.contains("助教")) titleRole = catalog.get("title_second");
        else if (title.contains("三级")) titleRole = catalog.get("title_third");
        if (titleRole != null) roles.put(titleRole.getCode(), titleRole.toMap());

        TeacherRoleCatalog.Role dutyRole = null;
        if ("班主任".equals(duty)) dutyRole = catalog.get("position_head_teacher");
        else if ("教导主任".equals(duty)) dutyRole = catalog.get("middle_academic_director");
        else if ("副校长".equals(duty)) dutyRole = catalog.get("leader_vice_principal");
        else if ("校长".equals(duty)) dutyRole = catalog.get("leader_principal");
        if (dutyRole == null && !duty.isBlank() && !"教师".equals(duty)) {
            for (TeacherRoleCatalog.Role role : TeacherRoleCatalog.all()) {
                if (role.getName().equals(duty)) {
                    dutyRole = role;
                    break;
                }
            }
        }
        if (dutyRole != null) roles.put(dutyRole.getCode(), dutyRole.toMap());
        return new ArrayList<>(roles.values());
    }

    private List<String> roleNames(List<Map<String, Object>> roles) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> role : roles) names.add(String.valueOf(role.get("name")));
        return names;
    }

    private boolean canTeach(List<Map<String, Object>> roles) {
        if (roles == null || roles.isEmpty()) return true; // 未设置身份，默认按任课教师处理
        for (Map<String, Object> role : roles) {
            if (Boolean.TRUE.equals(role.get("teaches"))) return true;
        }
        return false;
    }

    private List<Map<String, Object>> loadClasses() {
        try {
            return jdbc.queryForList(
                    "SELECT ci.id, ci.class_name AS className, ci.grade, ci.major, ci.department, " +
                            "ci.head_teacher_id AS headTeacherId, t.real_name AS headTeacherName, " +
                            "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.status = 1) AS studentCount " +
                            "FROM class_info ci LEFT JOIN teacher t ON t.id = ci.head_teacher_id " +
                            "ORDER BY ci.grade, ci.class_name");
        } catch (Exception e) {
            return jdbc.queryForList(
                    "SELECT ci.id, ci.class_name AS className, ci.grade, ci.major, ci.department, " +
                            "NULL AS headTeacherId, NULL AS headTeacherName, " +
                            "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.status = 1) AS studentCount " +
                            "FROM class_info ci ORDER BY ci.grade, ci.class_name");
        }
    }

    /**
     * 授课关系：每个班级取「该班最近一个学期的教学任务」，
     * 避免全局最新学期没有某班课程时，班级详情看不到任课教师。
     */
    private List<Map<String, Object>> loadTeachings() {
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<Long> coveredClasses = new java.util.HashSet<>();

        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                    "SELECT tt.teacher_id AS teacherId, t.real_name AS teacherName, " +
                            "tt.class_id AS classId, ci.class_name AS className, tt.course_name AS courseName " +
                            "FROM teaching_task tt " +
                            "JOIN (SELECT class_id, MAX(semester) AS sem FROM teaching_task " +
                            "      WHERE teacher_id IS NOT NULL GROUP BY class_id) latest " +
                            "  ON latest.class_id = tt.class_id AND latest.sem = tt.semester " +
                            "JOIN teacher t ON t.id = tt.teacher_id " +
                            "JOIN class_info ci ON ci.id = tt.class_id " +
                            "WHERE tt.teacher_id IS NOT NULL AND (tt.status IS NULL OR tt.status <> 'failed') " +
                            "ORDER BY tt.teacher_id, ci.class_name, tt.course_name");
            result.addAll(list);
            for (Map<String, Object> row : list) {
                if (row.get("classId") != null) {
                    coveredClasses.add(((Number) row.get("classId")).longValue());
                }
            }
        } catch (Exception ignored) {
        }

        // 部分班级没有 teaching_task（只有 course_class 课程关联），用课程表补齐
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                    "SELECT c.teacher_id AS teacherId, t.real_name AS teacherName, " +
                            "cc.class_id AS classId, ci.class_name AS className, c.course_name AS courseName " +
                            "FROM course c JOIN course_class cc ON cc.course_id = c.id " +
                            "JOIN (SELECT cc2.class_id, MAX(c2.semester) AS sem " +
                            "      FROM course c2 JOIN course_class cc2 ON cc2.course_id = c2.id " +
                            "      WHERE c2.teacher_id IS NOT NULL GROUP BY cc2.class_id) latest " +
                            "  ON latest.class_id = cc.class_id AND latest.sem = c.semester " +
                            "JOIN class_info ci ON ci.id = cc.class_id JOIN teacher t ON t.id = c.teacher_id " +
                            "WHERE c.teacher_id IS NOT NULL " +
                            "ORDER BY c.teacher_id, ci.class_name, c.course_name");
            for (Map<String, Object> row : list) {
                if (row.get("classId") == null) continue;
                Long classId = ((Number) row.get("classId")).longValue();
                if (!coveredClasses.contains(classId)) result.add(row);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void addHeadTeacherRole(Long teacherId) {
        try {
            jdbc.update("INSERT IGNORE INTO teacher_role (teacher_id, role_code) " +
                    "VALUES (?, 'position_head_teacher')", teacherId);
        } catch (Exception ignored) {
        }
    }

    private void removeHeadTeacherRole(Long teacherId) {
        try {
            jdbc.update("DELETE FROM teacher_role WHERE teacher_id = ? AND role_code = 'position_head_teacher'",
                    teacherId);
        } catch (Exception ignored) {
        }
    }
}
