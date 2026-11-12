package com.znxsgl.agent.tools;

import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.service.SemesterService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学期与作息基础查询。
 */
@Component
public class BasicAgentTool extends BaseAgentTool {

    private final SemesterService semesterService;

    public BasicAgentTool(SemesterService semesterService) {
        this.semesterService = semesterService;
    }

    @Override
    public String name() {
        return "semester.current";
    }

    @Override
    public String label() {
        return "查询学期";
    }

    @Override
    public String description() {
        return "查询当前学期和学期列表。";
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
        String current = semesterService.getCurrentSemesterName();
        List<Map<String, Object>> items = new ArrayList<>();
        for (var dto : semesterService.listAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("学期", dto.getName());
            item.put("开学日期", dto.getStartDate());
            item.put("结束日期", dto.getEndDate());
            item.put("当前学期", Boolean.TRUE.equals(dto.getIsCurrent()) ? "是" : "否");
            item.put("状态", dto.getNotice());
            items.add(item);
        }
        return ToolResult.ok("当前学期：" + (current == null || current.isBlank() ? "未设置" : current) + "。",
                items, "/admin/semester");
    }

    @Component
    public static class BellList extends BaseAgentTool {
        private final BasicAgentTool parent;

        public BellList(BasicAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "bell.list";
        }

        @Override
        public String label() {
            return "查询作息";
        }

        @Override
        public String description() {
            return "查询某个年级或默认的作息时间表（每节课起止时间）。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("grade", "可选，年级，如 2026级；不填则查默认作息");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.bellList(ctx, args);
        }
    }

    private ToolResult bellList(AgentContext ctx, Map<String, Object> args) {
        String grade = str(args, "grade");
        List<Map<String, Object>> rows;
        if (!grade.isEmpty()) {
            rows = query("SELECT node, LEFT(start_time,5) AS start_time, LEFT(end_time,5) AS end_time " +
                    "FROM bell_schedule WHERE grade = ? AND class_id IS NULL AND week_parity = 0 " +
                    "ORDER BY node", grade);
        } else {
            rows = query("SELECT node, LEFT(start_time,5) AS start_time, LEFT(end_time,5) AS end_time " +
                    "FROM bell_schedule WHERE grade = '' AND class_id IS NULL AND week_parity = 0 " +
                    "ORDER BY node");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("节次", "第" + row.get("node") + "节");
            item.put("上课", row.get("start_time"));
            item.put("下课", row.get("end_time"));
            items.add(item);
        }
        String label = grade.isEmpty() ? "默认作息" : grade + "作息";
        return ToolResult.ok(items.isEmpty() ? "没有找到" + label + "配置。" : label + "共 " + items.size() + " 节。",
                items, "/admin/bell");
    }
}
