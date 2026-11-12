package com.znxsgl.agent.tools;

import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 数据总览。
 */
@Component
public class StatsAgentTool extends BaseAgentTool {

    @Override
    public String name() {
        return "stats.overview";
    }

    @Override
    public String label() {
        return "数据总览";
    }

    @Override
    public String description() {
        return "统计学生、教师、班级、课程数量与待审批请假。管理员看全校，教师看任教班级。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec();
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        if (ctx.isAdmin()) {
            Map<String, Object> row = row(
                    "SELECT (SELECT COUNT(*) FROM user WHERE role = 1 AND status = 1) AS students, " +
                            "(SELECT COUNT(*) FROM user WHERE role = 2 AND status = 1) AS teachers, " +
                            "(SELECT COUNT(*) FROM class_info) AS classes, " +
                            "(SELECT COUNT(*) FROM course) AS courses, " +
                            "(SELECT COUNT(*) FROM leave_request WHERE status IN ('PENDING_TEACHER','PENDING_LEADER')) AS pending_leaves");
            Map<String, Object> data = data(
                    "学生人数", row == null ? 0 : row.get("students"),
                    "教师人数", row == null ? 0 : row.get("teachers"),
                    "班级数量", row == null ? 0 : row.get("classes"),
                    "课程数量", row == null ? 0 : row.get("courses"),
                    "待审批请假", row == null ? 0 : row.get("pending_leaves"));
            return ToolResult.ok("全校共有 " + data.get("学生人数") + " 名学生、" + data.get("教师人数")
                    + " 名教师、" + data.get("班级数量") + " 个班级。", data, "/dashboard");
        }

        Map<String, Object> row = row(
                "SELECT COUNT(DISTINCT u.id) AS students, " +
                        "COUNT(DISTINCT ci.id) AS classes, " +
                        "COUNT(DISTINCT c.id) AS courses " +
                        "FROM course c " +
                        "JOIN course_class cc ON cc.course_id = c.id " +
                        "JOIN class_info ci ON ci.id = cc.class_id " +
                        "LEFT JOIN user u ON u.class_id = ci.id AND u.role = 1 " +
                        "WHERE c.teacher_id = ?", ctx.getTeacherId());
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_request r LEFT JOIN class_info ci ON ci.id = r.class_id " +
                        "WHERE r.status IN ('PENDING_TEACHER','PENDING_LEADER') AND " +
                        "(r.teacher_id = ? OR ci.head_teacher_id IN (" +
                        "SELECT t.id FROM teacher t JOIN user tu ON tu.real_name = t.real_name WHERE tu.id = ?))",
                Integer.class, ctx.getUserId(), ctx.getUserId());
        Map<String, Object> data = data(
                "任教班级数", row == null ? 0 : row.get("classes"),
                "学生人数", row == null ? 0 : row.get("students"),
                "课程数量", row == null ? 0 : row.get("courses"),
                "待审批请假", pending == null ? 0 : pending);
        return ToolResult.ok("你的任教班级共 " + data.get("学生人数") + " 名学生、" + data.get("任教班级数")
                + " 个班级。", data, "/dashboard");
    }
}
