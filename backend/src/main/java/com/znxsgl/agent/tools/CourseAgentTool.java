package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.service.SemesterService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 课程查询与维护。
 */
@Component
public class CourseAgentTool extends BaseAgentTool {

    private final SemesterService semesterService;

    public CourseAgentTool(SemesterService semesterService) {
        this.semesterService = semesterService;
    }

    @Override
    public String name() {
        return "course.list";
    }

    @Override
    public String label() {
        return "查询课程";
    }

    @Override
    public String description() {
        return "查询课程列表，包含任课教师、学期、学分与关联班级。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec(
                "keyword", "可选，课程名称关键词",
                "semester", "可选，学期名称",
                "limit", "可选，默认 20，最多 50");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        String keyword = str(args, "keyword");
        String semester = str(args, "semester");
        StringBuilder sql = new StringBuilder(
                "SELECT c.id, c.course_name, c.course_no, c.semester, c.course_type, c.credit, " +
                        "t.real_name AS teacher_name, " +
                        "GROUP_CONCAT(DISTINCT ci.class_name ORDER BY ci.class_name SEPARATOR '、') AS class_names " +
                        "FROM course c " +
                        "LEFT JOIN teacher t ON t.id = c.teacher_id " +
                        "LEFT JOIN course_class cc ON cc.course_id = c.id " +
                        "LEFT JOIN class_info ci ON ci.id = cc.class_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (!keyword.isEmpty()) {
            sql.append("AND c.course_name LIKE ? ");
            params.add(like(keyword));
        }
        if (!semester.isEmpty()) {
            sql.append("AND c.semester = ? ");
            params.add(semester);
        }
        if (!ctx.isAdmin()) {
            sql.append("AND c.teacher_id = ? ");
            params.add(ctx.getTeacherId());
        }
        sql.append("GROUP BY c.id, c.course_name, c.course_no, c.semester, c.course_type, c.credit, t.real_name ");
        sql.append("ORDER BY c.id DESC LIMIT ?");
        params.add(limit(args, 20, 50));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : query(sql.toString(), params.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("课程", row.get("course_name"));
            item.put("课程编号", row.get("course_no"));
            item.put("学期", row.get("semester"));
            item.put("类型", row.get("course_type"));
            item.put("学分/课时", row.get("credit"));
            item.put("教师", row.get("teacher_name"));
            item.put("班级", row.get("class_names"));
            item.put("id", row.get("id"));
            items.add(item);
        }
        String summary = items.isEmpty() ? "没有找到课程。" : "共 " + items.size() + " 门课程。";
        return ToolResult.ok(summary, items, "/courses");
    }

    @Component
    @Transactional
    public static class Create extends BaseAgentTool {
        private final CourseAgentTool parent;

        public Create(CourseAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "course.create";
        }

        @Override
        public String label() {
            return "新增课程";
        }

        @Override
        public String description() {
            return "新增课程，可指定任课教师、学期、类型与学分。仅管理员。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "courseName", "必填，课程名称",
                    "teacherName", "可选，任课教师姓名",
                    "teacherId", "可选，任课教师 teacher.id",
                    "semester", "可选，学期名称，默认当前学期",
                    "courseType", "可选，必修/选修，默认必修",
                    "credit", "可选，学分/课时数");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.create(ctx, args);
        }
    }

    @Component
    @Transactional
    public static class Update extends BaseAgentTool {
        private final CourseAgentTool parent;

        public Update(CourseAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "course.update";
        }

        @Override
        public String label() {
            return "修改课程";
        }

        @Override
        public String description() {
            return "修改课程名称、类型、学分或学期。管理员可改任意课程，教师只能改自己的课程。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "courseId", "必填，课程 ID",
                    "courseName", "可选，新课程名称",
                    "courseType", "可选，必修/选修",
                    "credit", "可选，新学分/课时数",
                    "semester", "可选，新学期");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.update(ctx, args);
        }
    }

    private ToolResult create(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        String courseName = reqStr(args, "courseName", "课程名称");
        Long teacherId = lng(args, "teacherId");
        String teacherName = str(args, "teacherName");
        if (teacherId == null && !teacherName.isEmpty()) {
            teacherId = firstLong("SELECT id FROM teacher WHERE real_name = ? LIMIT 1", teacherName);
            if (teacherId == null) {
                throw new AgentException("TEACHER_NOT_FOUND", "没有找到教师：" + teacherName);
            }
        }
        String semester = str(args, "semester");
        if (semester.isEmpty()) {
            semester = semesterService.getCurrentSemesterName();
        }
        if (semester == null || semester.isBlank()) {
            throw new AgentException("INVALID_ARGS", "请提供学期，当前系统未设置当前学期");
        }
        String courseType = str(args, "courseType");
        if (courseType.isEmpty()) courseType = "必修";
        Double credit = null;
        String creditText = str(args, "credit");
        if (!creditText.isEmpty()) {
            try {
                credit = Double.parseDouble(creditText);
            } catch (Exception e) {
                throw new AgentException("INVALID_ARGS", "学分必须是数字");
            }
        }
        jdbc.update("INSERT INTO course (course_name, teacher_id, semester, course_type, credit) VALUES (?,?,?,?,?)",
                courseName, teacherId, semester, courseType, credit);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return ToolResult.ok("已新增课程 " + courseName + "（" + semester + "）。",
                data("课程", courseName, "学期", semester, "类型", courseType,
                        "教师", teacherName, "id", id), "/courses");
    }

    private ToolResult update(AgentContext ctx, Map<String, Object> args) {
        Long courseId = reqLng(args, "courseId", "课程 ID");
        Map<String, Object> course = row("SELECT * FROM course WHERE id = ?", courseId);
        if (course == null) {
            throw new AgentException("COURSE_NOT_FOUND", "课程不存在");
        }
        if (!ctx.isAdmin()) {
            scopeService.assertCourseOwned(ctx, courseId);
        }
        String courseName = str(args, "courseName");
        String courseType = str(args, "courseType");
        String semester = str(args, "semester");
        String creditText = str(args, "credit");
        if (courseName.isEmpty() && courseType.isEmpty() && semester.isEmpty() && creditText.isEmpty()) {
            throw new AgentException("INVALID_ARGS", "请提供要修改的字段");
        }
        if (!courseName.isEmpty()) {
            jdbc.update("UPDATE course SET course_name = ? WHERE id = ?", courseName, courseId);
        }
        if (!courseType.isEmpty()) {
            jdbc.update("UPDATE course SET course_type = ? WHERE id = ?", courseType, courseId);
        }
        if (!semester.isEmpty()) {
            jdbc.update("UPDATE course SET semester = ? WHERE id = ?", semester, courseId);
        }
        if (!creditText.isEmpty()) {
            try {
                jdbc.update("UPDATE course SET credit = ? WHERE id = ?", Double.parseDouble(creditText), courseId);
            } catch (NumberFormatException e) {
                throw new AgentException("INVALID_ARGS", "学分必须是数字");
            }
        }
        String name = courseName.isEmpty() ? String.valueOf(course.get("course_name")) : courseName;
        return ToolResult.ok("已更新课程 " + name + "。",
                data("课程", name, "学期", semester.isEmpty() ? course.get("semester") : semester), "/courses");
    }
}
