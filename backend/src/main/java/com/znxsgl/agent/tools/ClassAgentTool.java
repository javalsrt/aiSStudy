package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 班级查询与维护。
 */
@Component
public class ClassAgentTool extends BaseAgentTool {

    @Override
    public String name() {
        return "class.list";
    }

    @Override
    public String label() {
        return "查询班级";
    }

    @Override
    public String description() {
        return "查询班级列表，包含班主任与学生人数。教师只返回自己任教的班级。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec("keyword", "可选，班级名称关键词", "limit", "可选，默认 20，最多 50");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        String keyword = str(args, "keyword");
        StringBuilder sql = new StringBuilder(
                "SELECT ci.id, ci.class_name, ci.major, ci.grade, ci.department, " +
                        "t.real_name AS head_teacher, " +
                        "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.role = 1) AS student_count " +
                        "FROM class_info ci LEFT JOIN teacher t ON t.id = ci.head_teacher_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (!keyword.isEmpty()) {
            sql.append("AND ci.class_name LIKE ? ");
            params.add(like(keyword));
        }
        if (!ctx.isAdmin()) {
            if (ctx.getTaughtClassIds().isEmpty()) {
                return ToolResult.ok("你暂无任教班级。", List.of(), link(ctx));
            }
            sql.append("AND ci.id IN (").append(placeholders(ctx.getTaughtClassIds().size())).append(") ");
            params.addAll(ctx.getTaughtClassIds());
        }
        sql.append("ORDER BY ci.id LIMIT ?");
        params.add(limit(args, 20, 50));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : query(sql.toString(), params.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("班级", row.get("class_name"));
            item.put("专业", row.get("major"));
            item.put("年级", row.get("grade"));
            item.put("院系", row.get("department"));
            item.put("班主任", row.get("head_teacher"));
            item.put("学生数", row.get("student_count"));
            item.put("id", row.get("id"));
            items.add(item);
        }
        String summary = items.isEmpty() ? "没有找到班级。" : "共 " + items.size() + " 个班级。";
        return ToolResult.ok(summary, items, link(ctx));
    }

    @Component
    public static class Detail extends BaseAgentTool {
        private final ClassAgentTool parent;

        public Detail(ClassAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "class.detail";
        }

        @Override
        public String label() {
            return "班级详情";
        }

        @Override
        public String description() {
            return "查询某个班级的详细信息与学生名单。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("classId", "可选，班级 ID", "className", "可选，班级名称");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.detail(ctx, args);
        }
    }

    @Component
    @Transactional
    public static class Create extends BaseAgentTool {
        private final ClassAgentTool parent;

        public Create(ClassAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "class.create";
        }

        @Override
        public String label() {
            return "新增班级";
        }

        @Override
        public String description() {
            return "新增班级，填写班级名称、专业、年级、院系。仅管理员。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "className", "必填，班级名称",
                    "major", "可选，专业",
                    "grade", "可选，年级，如 2026级",
                    "department", "可选，院系");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.create(ctx, args);
        }
    }

    @Component
    @Transactional
    public static class Update extends BaseAgentTool {
        private final ClassAgentTool parent;

        public Update(ClassAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "class.update";
        }

        @Override
        public String label() {
            return "修改班级";
        }

        @Override
        public String description() {
            return "修改班级名称、专业、年级、院系或班主任。仅管理员。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "classId", "必填，班级 ID",
                    "className", "可选，新班级名称",
                    "major", "可选，新专业",
                    "grade", "可选，新年级",
                    "department", "可选，新院系",
                    "headTeacherName", "可选，新班主任姓名");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.update(ctx, args);
        }
    }

    private ToolResult detail(AgentContext ctx, Map<String, Object> args) {
        Map<String, Object> classRow = findClass(ctx, args);
        Long classId = ((Number) classRow.get("id")).longValue();
        Map<String, Object> data = data(
                "班级", classRow.get("class_name"),
                "专业", classRow.get("major"),
                "年级", classRow.get("grade"),
                "院系", classRow.get("department"),
                "班主任", classRow.get("head_teacher"),
                "学生数", classRow.get("student_count"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : query(
                "SELECT student_no, real_name, phone, status FROM user " +
                        "WHERE class_id = ? AND role = 1 ORDER BY student_no LIMIT 20", classId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("学号", row.get("student_no"));
            item.put("姓名", row.get("real_name"));
            item.put("手机号", row.get("phone"));
            item.put("状态", toInt(row.get("status")) == 1 ? "正常" : "禁用");
            items.add(item);
        }
        String summary = "班级 " + classRow.get("class_name") + " 共 " + classRow.get("student_count") + " 名学生。";
        if (items.isEmpty()) {
            return ToolResult.ok(summary, data, link(ctx));
        }
        return ToolResult.ok(summary, items, link(ctx));
    }

    private ToolResult create(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        String className = reqStr(args, "className", "班级名称");
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_info WHERE class_name = ?", Integer.class, className);
        if (exists != null && exists > 0) {
            throw new AgentException("DUPLICATE", "班级已存在：" + className);
        }
        jdbc.update("INSERT INTO class_info (class_name, major, department, grade) VALUES (?,?,?,?)",
                className,
                emptyToNull(str(args, "major")),
                emptyToNull(str(args, "department")),
                emptyToNull(str(args, "grade")));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return ToolResult.ok("已新增班级 " + className + "。",
                data("班级", className, "专业", str(args, "major"), "年级", str(args, "grade"), "id", id),
                link(ctx));
    }

    private ToolResult update(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        Long classId = reqLng(args, "classId", "班级 ID");
        Map<String, Object> classRow = row("SELECT * FROM class_info WHERE id = ?", classId);
        if (classRow == null) {
            throw new AgentException("CLASS_NOT_FOUND", "班级不存在");
        }
        String className = str(args, "className");
        String major = str(args, "major");
        String grade = str(args, "grade");
        String department = str(args, "department");
        String headTeacherName = str(args, "headTeacherName");
        if (!className.isEmpty()) {
            jdbc.update("UPDATE class_info SET class_name = ? WHERE id = ?", className, classId);
        }
        if (!major.isEmpty()) {
            jdbc.update("UPDATE class_info SET major = ? WHERE id = ?", major, classId);
        }
        if (!grade.isEmpty()) {
            jdbc.update("UPDATE class_info SET grade = ? WHERE id = ?", grade, classId);
        }
        if (!department.isEmpty()) {
            jdbc.update("UPDATE class_info SET department = ? WHERE id = ?", department, classId);
        }
        if (!headTeacherName.isEmpty()) {
            Long teacherId = firstLong("SELECT id FROM teacher WHERE real_name = ? LIMIT 1", headTeacherName);
            if (teacherId == null) {
                throw new AgentException("TEACHER_NOT_FOUND", "没有找到教师：" + headTeacherName);
            }
            jdbc.update("UPDATE class_info SET head_teacher_id = ? WHERE id = ?", teacherId, classId);
        }
        String name = className.isEmpty() ? String.valueOf(classRow.get("class_name")) : className;
        return ToolResult.ok("已更新班级 " + name + " 的信息。",
                data("班级", name, "专业", major.isEmpty() ? classRow.get("major") : major,
                        "年级", grade.isEmpty() ? classRow.get("grade") : grade), link(ctx));
    }

    private Map<String, Object> findClass(AgentContext ctx, Map<String, Object> args) {
        Long classId = lng(args, "classId");
        String className = str(args, "className");
        Map<String, Object> row;
        if (classId != null) {
            row = row("SELECT ci.*, t.real_name AS head_teacher, " +
                    "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.role = 1) AS student_count " +
                    "FROM class_info ci LEFT JOIN teacher t ON t.id = ci.head_teacher_id WHERE ci.id = ?", classId);
        } else if (!className.isEmpty()) {
            row = row("SELECT ci.*, t.real_name AS head_teacher, " +
                            "(SELECT COUNT(*) FROM user u WHERE u.class_id = ci.id AND u.role = 1) AS student_count " +
                            "FROM class_info ci LEFT JOIN teacher t ON t.id = ci.head_teacher_id " +
                            "WHERE ci.class_name = ? LIMIT 1", className);
        } else {
            throw new AgentException("INVALID_ARGS", "请提供班级 ID 或班级名称");
        }
        if (row == null) {
            throw new AgentException("CLASS_NOT_FOUND", "没有找到该班级");
        }
        if (!ctx.isAdmin()) {
            assertClass(ctx, ((Number) row.get("id")).longValue());
        }
        return row;
    }

    private String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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
