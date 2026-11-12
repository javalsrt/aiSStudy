package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.controller.AdminUserController;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 教师账号管理（仅管理员）。
 */
@Component
public class TeacherAgentTool extends BaseAgentTool {

    private final AdminUserController adminUserController;

    public TeacherAgentTool(AdminUserController adminUserController) {
        this.adminUserController = adminUserController;
    }

    @Override
    public String name() {
        return "teacher.search";
    }

    @Override
    public String label() {
        return "查询教师";
    }

    @Override
    public String description() {
        return "按姓名、工号或账号查询教师列表。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec("keyword", "可选，姓名/工号/账号关键词", "limit", "可选，默认 10，最多 30");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        String keyword = str(args, "keyword");
        StringBuilder sql = new StringBuilder(
                "SELECT t.id AS teacherId, t.teacher_no, t.real_name, t.phone, t.email, t.status, " +
                        "u.id AS userId, u.username, d.dept_name " +
                        "FROM teacher t " +
                        "LEFT JOIN user u ON u.username = t.teacher_no " +
                        "LEFT JOIN department d ON d.id = t.dept_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (!keyword.isEmpty()) {
            sql.append("AND (t.real_name LIKE ? OR t.teacher_no LIKE ? OR u.username LIKE ?) ");
            params.add(like(keyword));
            params.add(like(keyword));
            params.add(like(keyword));
        }
        sql.append("ORDER BY t.id DESC LIMIT ?");
        params.add(limit(args, 10, 30));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : query(sql.toString(), params.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("工号", row.get("teacher_no"));
            item.put("姓名", row.get("real_name"));
            item.put("账号", row.get("username"));
            item.put("院系", row.get("dept_name"));
            item.put("手机号", row.get("phone"));
            item.put("状态", toInt(row.get("status")) == 1 ? "正常" : "禁用");
            item.put("id", row.get("teacherId"));
            items.add(item);
        }
        String summary = items.isEmpty() ? "没有找到符合条件的教师。" : "找到 " + items.size() + " 名教师。";
        return ToolResult.ok(summary, items, "/admin/staff?tab=teachers");
    }

    @Component
    @Transactional
    public static class Create extends BaseAgentTool {
        private final TeacherAgentTool parent;

        public Create(TeacherAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "teacher.create";
        }

        @Override
        public String label() {
            return "新增教师";
        }

        @Override
        public String description() {
            return "新增教师账号，默认密码 123456，同步创建教师档案。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "username", "必填，登录账号（同时作为教师工号）",
                    "realName", "必填，姓名",
                    "password", "可选，默认 123456",
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
        private final TeacherAgentTool parent;

        public Update(TeacherAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "teacher.update";
        }

        @Override
        public String label() {
            return "修改教师";
        }

        @Override
        public String description() {
            return "修改教师姓名、手机号、邮箱或状态。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "teacherUserId", "可选，教师 user.id",
                    "username", "可选，登录账号",
                    "realName", "可选，姓名",
                    "newRealName", "可选，新姓名",
                    "phone", "可选，新手机号",
                    "email", "可选，新邮箱",
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
        private final TeacherAgentTool parent;

        public Delete(TeacherAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "teacher.delete";
        }

        @Override
        public String label() {
            return "删除教师";
        }

        @Override
        public String description() {
            return "停用（软删除）教师账号，需要二次确认。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public boolean requiresConfirm() {
            return true;
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("teacherUserId", "可选，教师 user.id", "username", "可选，登录账号", "realName", "可选，姓名");
        }

        @Override
        public ToolResult preview(AgentContext ctx, Map<String, Object> args) {
            return parent.previewTeacher(ctx, args);
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.delete(ctx, args);
        }
    }

    private ToolResult create(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        String username = reqStr(args, "username", "登录账号");
        String realName = reqStr(args, "realName", "姓名");
        String password = str(args, "password");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password.isEmpty() ? "123456" : password);
        body.put("realName", realName);
        body.put("role", 2);
        if (!str(args, "phone").isEmpty()) body.put("phone", str(args, "phone"));
        if (!str(args, "email").isEmpty()) body.put("email", str(args, "email"));
        ResponseEntity<Map<String, Object>> resp = adminUserController.createUser(body);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            String error = resp.getBody() != null && resp.getBody().get("error") != null
                    ? String.valueOf(resp.getBody().get("error")) : "新增失败";
            throw new AgentException("CREATE_FAILED", error);
        }
        return ToolResult.ok("已新增教师 " + realName + "（账号 " + username + "），初始密码 123456。",
                data("姓名", realName, "账号", username, "初始密码", "123456"), "/admin/staff?tab=teachers");
    }

    private ToolResult update(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        Map<String, Object> user = findTeacherUser(args);
        Long userId = ((Number) user.get("id")).longValue();
        String newRealName = str(args, "newRealName");
        String phone = str(args, "phone");
        String email = str(args, "email");
        Integer status = intVal(args, "status");
        if (newRealName.isEmpty() && phone.isEmpty() && email.isEmpty() && status == null) {
            throw new AgentException("INVALID_ARGS", "请提供要修改的字段");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (!newRealName.isEmpty()) body.put("realName", newRealName);
        if (!phone.isEmpty()) body.put("phone", phone);
        if (!email.isEmpty()) body.put("email", email);
        if (status != null) body.put("status", status);
        ResponseEntity<Map<String, String>> resp = adminUserController.updateUser(userId, body);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            String error = resp.getBody() != null && resp.getBody().get("error") != null
                    ? resp.getBody().get("error") : "修改失败";
            throw new AgentException("UPDATE_FAILED", error);
        }
        if (status != null && status == 0) {
            jdbc.update("UPDATE user SET current_token = NULL WHERE id = ?", userId);
        }
        String name = newRealName.isEmpty() ? String.valueOf(user.get("real_name")) : newRealName;
        return ToolResult.ok("已更新教师 " + name + " 的信息。",
                data("姓名", name, "账号", user.get("username")), "/admin/staff?tab=teachers");
    }

    private ToolResult previewTeacher(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        Map<String, Object> user = findTeacherUser(args);
        return ToolResult.ok("即将停用教师 " + user.get("real_name") + "（账号 " + user.get("username") + "），确认后该账号将无法登录。",
                data("姓名", user.get("real_name"), "账号", user.get("username")),
                "/admin/staff?tab=teachers");
    }

    private ToolResult delete(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        Map<String, Object> user = findTeacherUser(args);
        Long userId = ((Number) user.get("id")).longValue();
        if (toInt(user.get("status")) == 0) {
            return ToolResult.ok("教师 " + user.get("real_name") + " 已是禁用状态。", "/admin/staff?tab=teachers");
        }
        ResponseEntity<Map<String, String>> resp = adminUserController.deleteUser(userId);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new AgentException("DELETE_FAILED", "删除失败");
        }
        jdbc.update("UPDATE user SET current_token = NULL WHERE id = ?", userId);
        return ToolResult.ok("已停用教师 " + user.get("real_name") + "（账号 " + user.get("username") + "）。",
                data("姓名", user.get("real_name"), "账号", user.get("username"), "状态", "已停用"),
                "/admin/staff?tab=teachers");
    }

    private Map<String, Object> findTeacherUser(Map<String, Object> args) {
        Long userId = lng(args, "teacherUserId");
        String username = str(args, "username");
        String realName = str(args, "realName");
        Map<String, Object> user = null;
        if (userId != null) {
            user = row("SELECT * FROM user WHERE id = ? AND role = 2", userId);
        } else if (!username.isEmpty()) {
            user = row("SELECT * FROM user WHERE username = ? AND role = 2", username);
        } else if (!realName.isEmpty()) {
            List<Map<String, Object>> rows = query(
                    "SELECT * FROM user WHERE real_name = ? AND role = 2 LIMIT 2", realName);
            if (rows.size() > 1) {
                throw new AgentException("AMBIGUOUS", "存在多个同名教师，请提供登录账号");
            }
            user = rows.isEmpty() ? null : rows.get(0);
        }
        if (user == null) {
            throw new AgentException("TEACHER_NOT_FOUND", "没有找到该教师账号");
        }
        return user;
    }

    private int toInt(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }
}
