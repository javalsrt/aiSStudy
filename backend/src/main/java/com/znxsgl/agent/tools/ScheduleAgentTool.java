package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.service.ScheduleConflictChecker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 班级课表查询。
 */
@Component
public class ScheduleAgentTool extends BaseAgentTool {

    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    @Override
    public String name() {
        return "schedule.query";
    }

    @Override
    public String label() {
        return "查询课表";
    }

    @Override
    public String description() {
        return "查询某个班级的课表，可按星期筛选。教师只能查自己任教的班级。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec(
                "classId", "可选，班级 ID",
                "className", "可选，班级名称",
                "dayOfWeek", "可选，1=周一 … 7=周日",
                "week", "可选，教学周次，用于过滤单双周课程");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        Long classId = lng(args, "classId");
        String className = str(args, "className");
        if (classId == null && !className.isEmpty()) {
            classId = firstLong("SELECT id FROM class_info WHERE class_name = ? LIMIT 1", className);
        }
        if (classId == null) {
            if (!ctx.isAdmin() && ctx.getTaughtClassIds().size() == 1) {
                classId = ctx.getTaughtClassIds().iterator().next();
            } else {
                throw new AgentException("INVALID_ARGS", "请提供要查询的班级");
            }
        }
        assertClass(ctx, classId);
        String resolvedClassName = firstString("SELECT class_name FROM class_info WHERE id = ?", classId);
        if (resolvedClassName == null) {
            throw new AgentException("CLASS_NOT_FOUND", "班级不存在");
        }

        Integer dayOfWeek = intVal(args, "dayOfWeek");
        if (dayOfWeek != null && (dayOfWeek < 1 || dayOfWeek > 7)) {
            throw new AgentException("INVALID_ARGS", "dayOfWeek 必须是 1-7");
        }
        Integer week = intVal(args, "week");

        // schedule 每个学生一行，这里按课程/星期/节次聚合去重
        StringBuilder sql = new StringBuilder(
                "SELECT s.course_name, s.day_of_week, s.start_node, s.step, " +
                        "MIN(LEFT(s.start_time, 5)) AS start_time, MIN(LEFT(s.end_time, 5)) AS end_time, " +
                        "MAX(s.classroom) AS classroom, GROUP_CONCAT(DISTINCT s.weeks) AS weeks " +
                        "FROM schedule s JOIN user u ON u.id = s.user_id " +
                        "WHERE u.class_id = ? AND s.status = 1 ");
        List<Object> params = new ArrayList<>();
        params.add(classId);
        if (dayOfWeek != null) {
            sql.append("AND s.day_of_week = ? ");
            params.add(dayOfWeek);
        }
        sql.append("GROUP BY s.course_name, s.day_of_week, s.start_node, s.step, s.classroom ");
        sql.append("ORDER BY s.day_of_week, s.start_node");

        List<Map<String, Object>> rows = query(sql.toString(), params.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (week != null && !matchesWeek(str(row, "weeks"), week)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            int day = toInt(row.get("day_of_week"));
            item.put("星期", day >= 1 && day <= 7 ? DAY_NAMES[day] : day);
            item.put("节次", "第" + row.get("start_node") + "节起，共" + row.get("step") + "节");
            item.put("时间", str(row, "start_time") + "-" + str(row, "end_time"));
            item.put("课程", row.get("course_name"));
            item.put("教室", row.get("classroom"));
            items.add(item);
        }
        String summary = items.isEmpty()
                ? resolvedClassName + " 没有查到排课记录。"
                : resolvedClassName + " 共 " + items.size() + " 个上课时段。";
        return ToolResult.ok(summary, items, ctx.isAdmin() ? "/admin/schedule" : "/teacher/class");
    }

    private boolean matchesWeek(String weeks, int week) {
        if (weeks == null || weeks.isBlank()) return true;
        for (int w : ScheduleConflictChecker.parseWeeks(weeks)) {
            if (w == week) return true;
        }
        return weeks.contains(String.valueOf(week));
    }

    private int toInt(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }
}
