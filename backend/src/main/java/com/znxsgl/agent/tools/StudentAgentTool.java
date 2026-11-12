package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.controller.AdminUserController;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学生查询与增删改查。教师只能操作自己任教的班级。
 */
@Component
public class StudentAgentTool extends BaseAgentTool {

    private final AdminUserController adminUserController;
    private final PasswordEncoder passwordEncoder;

    public StudentAgentTool(AdminUserController adminUserController,
                            PasswordEncoder passwordEncoder) {
        this.adminUserController = adminUserController;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String name() {
        return "student.search";
    }

    @Override
    public String label() {
        return "查询学生";
    }

    @Override
    public String description() {
        return "按关键词、班级或状态查询学生列表，返回学号、姓名、班级、手机号等。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec(
                "keyword", "可选，姓名/学号/账号关键词",
                "classId", "可选，班级 ID",
                "className", "可选，班级名称（与 classId 二选一）",
                "status", "可选，1=正常 0=禁用",
                "limit", "可选，默认 10，最多 30");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        String keyword = str(args, "keyword");
        String className = str(args, "className");
        Long classId = lng(args, "classId");
        if (classId == null && !className.isEmpty()) {
            classId = firstLong("SELECT id FROM class_info WHERE class_name = ? LIMIT 1", className);
            if (classId == null) {
                throw new AgentException("CLASS_NOT_FOUND", "没有找到班级：" + className);
            }
        }
        if (classId != null) {
            assertClass(ctx, classId);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT u.id, u.student_no, u.real_name, u.username, u.phone, u.email, " +
                        "u.class_id, ci.class_name, u.status " +
                        "FROM user u LEFT JOIN class_info ci ON ci.id = u.class_id " +
                        "WHERE u.role = 1");
        List<Object> params = new ArrayList<>();
        if (!keyword.isEmpty()) {
            sql.append(" AND (u.real_name LIKE ? OR u.student_no LIKE ? OR u.username LIKE ?)");
            params.add(like(keyword));
            params.add(like(keyword));
            params.add(like(keyword));
        }
        if (classId != null) {
            sql.append(" AND u.class_id = ?");
            params.add(classId);
        } else if (!ctx.isAdmin()) {
            if (ctx.getTaughtClassIds().isEmpty()) {
                return ToolResult.ok("你暂无任教班级，无法查询学生。", List.of(), link(ctx));
            }
            sql.append(" AND u.class_id IN (").append(placeholders(ctx.getTaughtClassIds().size())).append(")");
            params.addAll(ctx.getTaughtClassIds());
        }
        Integer status = intVal(args, "status");
        if (status != null) {
            sql.append(" AND u.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY u.id DESC LIMIT ?");
        params.add(limit(args, 10, 30));

        List<Map<String, Object>> rows = query(sql.toString(), params.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("学号", row.get("student_no"));
            item.put("姓名", row.get("real_name"));
            item.put("班级", row.get("class_name"));
            item.put("手机号", row.get("phone"));
            item.put("状态", toInt(row.get("status")) == 1 ? "正常" : "禁用");
            item.put("id", row.get("id"));
            items.add(item);
        }
        String summary = items.isEmpty() ? "没有找到符合条件的学生。"
                : "找到 " + items.size() + " 名学生" + (rows.size() >= limit(args, 10, 30) ? "（仅显示前 " + items.size() + " 条）" : "") + "。";
        return ToolResult.ok(summary, items, link(ctx));
    }

    @Component
    public static class Detail extends BaseAgentTool {
        private final StudentAgentTool parent;

        public Detail(StudentAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "student.detail";
        }

        @Override
        public String label() {
            return "学生详情";
        }

        @Override
        public String description() {
            return "按 studentId、studentNo 或 realName 查询单个学生详情。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("studentId", "可选，学生 user.id", "studentNo", "可选，学号", "realName", "可选，姓名");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.detail(ctx, args);
        }
    }

    @Component
    @Transactional
    public static class Create extends BaseAgentTool {
        private final StudentAgentTool parent;

        public Create(StudentAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "student.create";
        }

        @Override
        public String label() {
            return "新增学生";
        }

        @Override
        public String description() {
            return "新增学生账号，默认密码 123456。教师只能把自己任教班级的学生加入。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "studentNo", "必填，学号（同时作为登录账号）",
                    "realName", "必填，姓名",
                    "classId", "必填，班级 ID",
                    "className", "可选，班级名称（与 classId 二选一）",
                    "phone", "可选，手机号",
                    "email", "可选，邮箱");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.create(ctx, args);
        }
    }

    @Component
    @Transactional
    public static class Update extends BaseAgentTool {
        private final StudentAgentTool parent;

        public Update(StudentAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "student.update";
        }

        @Override
        public String label() {
            return "修改学生";
        }

        @Override
        public String description() {
            return "修改学生的姓名、手机号、邮箱、班级或状态。教师不能把学生移到非任教班级。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "studentId", "可选，学生 user.id（推荐）",
                    "studentNo", "可选，学号",
                    "realName", "可选，姓名",
                    "newRealName", "可选，新姓名",
                    "phone", "可选，新手机号",
                    "email", "可选，新邮箱",
                    "classId", "可选，新班级 ID",
                    "status", "可选，1=正常 0=禁用");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.update(ctx, args);
        }
    }

    @Component
    @Transactional
    public static class Delete extends BaseAgentTool {
        private final StudentAgentTool parent;

        public Delete(StudentAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "student.delete";
        }

        @Override
        public String label() {
            return "删除学生";
        }

        @Override
        public String description() {
            return "停用（软删除）一个学生账号，需要用户二次确认。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public boolean requiresConfirm() {
            return true;
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("studentId", "可选，学生 user.id（推荐）", "studentNo", "可选，学号", "realName", "可选，姓名");
        }

        @Override
        public ToolResult preview(AgentContext ctx, Map<String, Object> args) {
            return parent.previewStudent(ctx, args);
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.delete(ctx, args);
        }
    }

    @Component
    @Transactional
    public static class ResetPassword extends BaseAgentTool {
        private final StudentAgentTool parent;

        public ResetPassword(StudentAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "student.resetPassword";
        }

        @Override
        public String label() {
            return "重置学生密码";
        }

        @Override
        public String description() {
            return "把学生密码重置为 123456，并让旧登录失效。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("studentId", "可选，学生 user.id", "studentNo", "可选，学号", "realName", "可选，姓名");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.resetPassword(ctx, args);
        }
    }

    // ==================== 共用实现 ====================

    private ToolResult detail(AgentContext ctx, Map<String, Object> args) {
        Map<String, Object> student = findStudent(ctx, args);
        String className = firstString("SELECT class_name FROM class_info WHERE id = ?", student.get("class_id"));
        Map<String, Object> data = data(
                "学号", student.get("student_no"),
                "姓名", student.get("real_name"),
                "班级", className,
                "账号", student.get("username"),
                "手机号", student.get("phone"),
                "邮箱", student.get("email"),
                "状态", toInt(student.get("status")) == 1 ? "正常" : "禁用");
        return ToolResult.ok("已找到学生 " + student.get("real_name") + "。", data, link(ctx));
    }

    private ToolResult create(AgentContext ctx, Map<String, Object> args) {
        String studentNo = reqStr(args, "studentNo", "学号");
        String realName = reqStr(args, "realName", "姓名");
        Long classId = lng(args, "classId");
        String className = str(args, "className");
        if (classId == null && !className.isEmpty()) {
            classId = firstLong("SELECT id FROM class_info WHERE class_name = ? LIMIT 1", className);
        }
        if (classId == null) {
            throw new AgentException("INVALID_ARGS", "请提供班级");
        }
        assertClass(ctx, classId);
        String phone = str(args, "phone");
        String email = str(args, "email");

        if (ctx.isAdmin()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", studentNo);
            body.put("password", "123456");
            body.put("realName", realName);
            body.put("role", 1);
            body.put("studentNo", studentNo);
            body.put("classId", classId);
            if (!phone.isEmpty()) body.put("phone", phone);
            if (!email.isEmpty()) body.put("email", email);
            ResponseEntity<Map<String, Object>> resp = adminUserController.createUser(body);
            Map<String, Object> respBody = resp.getBody();
            if (!resp.getStatusCode().is2xxSuccessful()) {
                String error = respBody != null && respBody.get("error") != null
                        ? String.valueOf(respBody.get("error")) : "新增失败";
                throw new AgentException("CREATE_FAILED", error);
            }
            Long id = respBody != null && respBody.get("id") instanceof Number n ? n.longValue() : null;
            return ToolResult.ok("已新增学生 " + realName + "（学号 " + studentNo + "），初始密码 123456。",
                    data("姓名", realName, "学号", studentNo, "班级", classNameOrId(classId), "初始密码", "123456", "id", id),
                    link(ctx));
        }

        // 教师分支：受控写入
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ? OR student_no = ?", Integer.class, studentNo, studentNo);
        if (exists != null && exists > 0) {
            throw new AgentException("DUPLICATE", "学号或账号已存在：" + studentNo);
        }
        Map<String, Object> classRow = row("SELECT class_name, major, grade FROM class_info WHERE id = ?", classId);
        if (classRow == null) {
            throw new AgentException("CLASS_NOT_FOUND", "班级不存在");
        }
        jdbc.update("INSERT INTO user (student_no, username, password_hash, real_name, phone, email, role, " +
                        "class_id, major, grade, status) VALUES (?,?,?,?,?,?,1,?,?,?,1)",
                studentNo, studentNo, passwordEncoder.encode("123456"), realName,
                phone.isEmpty() ? null : phone, email.isEmpty() ? null : email,
                classId, classRow.get("major"), classRow.get("grade"));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (?, 3)", id);
        return ToolResult.ok("已新增学生 " + realName + "（学号 " + studentNo + "），初始密码 123456。",
                data("姓名", realName, "学号", studentNo, "班级", classRow.get("class_name"), "初始密码", "123456", "id", id),
                link(ctx));
    }

    private ToolResult update(AgentContext ctx, Map<String, Object> args) {
        Map<String, Object> student = findStudent(ctx, args);
        Long studentId = ((Number) student.get("id")).longValue();
        Long currentClassId = student.get("class_id") instanceof Number n ? n.longValue() : null;
        Long targetClassId = lng(args, "classId");
        if (targetClassId != null && !targetClassId.equals(currentClassId)) {
            assertClass(ctx, targetClassId);
        }
        String newRealName = str(args, "newRealName");
        String phone = str(args, "phone");
        String email = str(args, "email");
        Integer status = intVal(args, "status");
        if (newRealName.isEmpty() && phone.isEmpty() && email.isEmpty() && targetClassId == null && status == null) {
            throw new AgentException("INVALID_ARGS", "请提供要修改的字段");
        }

        if (ctx.isAdmin()) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (!newRealName.isEmpty()) body.put("realName", newRealName);
            if (!phone.isEmpty()) body.put("phone", phone);
            if (!email.isEmpty()) body.put("email", email);
            if (targetClassId != null) body.put("classId", targetClassId);
            if (status != null) body.put("status", status);
            ResponseEntity<Map<String, String>> resp = adminUserController.updateUser(studentId, body);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                String error = resp.getBody() != null && resp.getBody().get("error") != null
                        ? resp.getBody().get("error") : "修改失败";
                throw new AgentException("UPDATE_FAILED", error);
            }
            if (status != null && status == 0) {
                jdbc.update("UPDATE user SET current_token = NULL WHERE id = ?", studentId);
            }
        } else {
            if (targetClassId != null) {
                Map<String, Object> classRow = row("SELECT class_name, major, grade FROM class_info WHERE id = ?", targetClassId);
                if (classRow == null) throw new AgentException("CLASS_NOT_FOUND", "目标班级不存在");
                jdbc.update("UPDATE user SET class_id = ?, major = ?, grade = ? WHERE id = ?",
                        targetClassId, classRow.get("major"), classRow.get("grade"), studentId);
            }
            if (!newRealName.isEmpty()) {
                jdbc.update("UPDATE user SET real_name = ? WHERE id = ?", newRealName, studentId);
            }
            if (!phone.isEmpty()) {
                jdbc.update("UPDATE user SET phone = ? WHERE id = ?", phone, studentId);
            }
            if (!email.isEmpty()) {
                jdbc.update("UPDATE user SET email = ? WHERE id = ?", email, studentId);
            }
            if (status != null) {
                jdbc.update("UPDATE user SET status = ? WHERE id = ?", status, studentId);
                if (status == 0) {
                    jdbc.update("UPDATE user SET current_token = NULL WHERE id = ?", studentId);
                }
            }
        }
        String name = newRealName.isEmpty() ? String.valueOf(student.get("real_name")) : newRealName;
        return ToolResult.ok("已更新学生 " + name + " 的信息。",
                data("姓名", name, "学号", student.get("student_no"),
                        "班级", targetClassId == null ? classNameOrId(currentClassId) : classNameOrId(targetClassId)),
                link(ctx));
    }

    private ToolResult previewStudent(AgentContext ctx, Map<String, Object> args) {
        Map<String, Object> student = findStudent(ctx, args);
        Long classId = student.get("class_id") instanceof Number n ? n.longValue() : null;
        Map<String, Object> data = data(
                "姓名", student.get("real_name"),
                "学号", student.get("student_no"),
                "班级", classNameOrId(classId),
                "状态", toInt(student.get("status")) == 1 ? "正常" : "禁用");
        return ToolResult.ok("即将停用学生 " + student.get("real_name") + "（" + student.get("student_no") + "），确认后该账号将无法登录。",
                data, link(ctx));
    }

    private ToolResult delete(AgentContext ctx, Map<String, Object> args) {
        Map<String, Object> student = findStudent(ctx, args);
        Long studentId = ((Number) student.get("id")).longValue();
        if (toInt(student.get("status")) == 0) {
            return ToolResult.ok("学生 " + student.get("real_name") + " 已是禁用状态。", link(ctx));
        }
        if (ctx.isAdmin()) {
            ResponseEntity<Map<String, String>> resp = adminUserController.deleteUser(studentId);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new AgentException("DELETE_FAILED", "删除失败");
            }
            jdbc.update("UPDATE user SET current_token = NULL WHERE id = ?", studentId);
        } else {
            jdbc.update("UPDATE user SET status = 0, current_token = NULL WHERE id = ?", studentId);
        }
        return ToolResult.ok("已停用学生 " + student.get("real_name") + "（" + student.get("student_no") + "）。",
                data("姓名", student.get("real_name"), "学号", student.get("student_no"), "状态", "已停用"),
                link(ctx));
    }

    private ToolResult resetPassword(AgentContext ctx, Map<String, Object> args) {
        Map<String, Object> student = findStudent(ctx, args);
        Long studentId = ((Number) student.get("id")).longValue();
        if (ctx.isAdmin()) {
            ResponseEntity<Map<String, String>> resp =
                    adminUserController.resetPassword(studentId, Map.of("password", "123456"));
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new AgentException("RESET_FAILED", "重置密码失败");
            }
        } else {
            jdbc.update("UPDATE user SET password_hash = ?, current_token = NULL WHERE id = ?",
                    passwordEncoder.encode("123456"), studentId);
        }
        return ToolResult.ok("已把 " + student.get("real_name") + " 的密码重置为 123456，旧登录已失效。",
                data("姓名", student.get("real_name"), "学号", student.get("student_no"), "新密码", "123456"),
                link(ctx));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findStudent(AgentContext ctx, Map<String, Object> args) {
        Long studentId = lng(args, "studentId");
        String studentNo = str(args, "studentNo");
        String realName = str(args, "realName");
        Map<String, Object> student;
        if (studentId != null) {
            student = row("SELECT * FROM user WHERE id = ? AND role = 1", studentId);
        } else if (!studentNo.isEmpty()) {
            student = row("SELECT * FROM user WHERE student_no = ? AND role = 1", studentNo);
        } else if (!realName.isEmpty()) {
            List<Map<String, Object>> rows = query(
                    "SELECT * FROM user WHERE real_name = ? AND role = 1 LIMIT 2", realName);
            if (rows.size() > 1) {
                throw new AgentException("AMBIGUOUS", "存在多个同名学生，请提供学号或学生 ID");
            }
            student = rows.isEmpty() ? null : rows.get(0);
        } else {
            throw new AgentException("INVALID_ARGS", "请提供学生 ID、学号或姓名");
        }
        if (student == null) {
            throw new AgentException("STUDENT_NOT_FOUND", "没有找到该学生");
        }
        if (!ctx.isAdmin()) {
            Long classId = student.get("class_id") instanceof Number n ? n.longValue() : null;
            scopeService.assertStudentInScope(ctx, classId);
        }
        return student;
    }

    private String classNameOrId(Long classId) {
        if (classId == null) return "未分班";
        String name = firstString("SELECT class_name FROM class_info WHERE id = ?", classId);
        return name == null ? String.valueOf(classId) : name;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private String link(AgentContext ctx) {
        return ctx.isAdmin() ? "/admin/staff?tab=students" : "/teacher/class";
    }

    private int toInt(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }
}
