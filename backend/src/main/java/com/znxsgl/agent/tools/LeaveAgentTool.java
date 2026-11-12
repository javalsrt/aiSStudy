package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.service.LeaveReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请假待办、审批与记录查询。
 */
@Component
public class LeaveAgentTool extends BaseAgentTool {

    private final LeaveReviewService leaveReviewService;

    public LeaveAgentTool(LeaveReviewService leaveReviewService) {
        this.leaveReviewService = leaveReviewService;
    }

    @Override
    public String name() {
        return "leave.pending";
    }

    @Override
    public String label() {
        return "待审批请假";
    }

    @Override
    public String description() {
        return "查询待审批的请假/外出申请。教师只看到自己有审批权的班级。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec("classId", "可选，班级 ID", "limit", "可选，默认 10，最多 30");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        Long classId = lng(args, "classId");
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.type, r.reason, r.start_time, r.end_time, r.days, r.status, " +
                        "u.real_name AS student_name, u.student_no, ci.class_name " +
                        "FROM leave_request r " +
                        "LEFT JOIN user u ON u.id = r.user_id " +
                        "LEFT JOIN class_info ci ON ci.id = r.class_id " +
                        "WHERE r.status IN ('PENDING_TEACHER','PENDING_LEADER') ");
        List<Object> params = new ArrayList<>();
        if (!ctx.isAdmin() && !ctx.isLeader()) {
            sql.append("AND (r.teacher_id = ? OR ci.head_teacher_id IN (" +
                    "SELECT t.id FROM teacher t JOIN user tu ON tu.real_name = t.real_name WHERE tu.id = ?)) ");
            params.add(ctx.getUserId());
            params.add(ctx.getUserId());
        }
        if (classId != null) {
            sql.append("AND r.class_id = ? ");
            params.add(classId);
        }
        sql.append("ORDER BY r.id DESC LIMIT ?");
        params.add(limit(args, 10, 30));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : query(sql.toString(), params.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("学生", row.get("student_name"));
            item.put("学号", row.get("student_no"));
            item.put("班级", row.get("class_name"));
            item.put("类型", "OUTING".equals(str(row, "type")) ? "临时外出" : "请假");
            item.put("事由", row.get("reason"));
            item.put("时间", str(row, "start_time") + " 至 " + str(row, "end_time"));
            item.put("天数", row.get("days"));
            item.put("状态", "PENDING_LEADER".equals(str(row, "status")) ? "待领导终审" : "待班主任审批");
            item.put("id", row.get("id"));
            items.add(item);
        }
        String summary = items.isEmpty() ? "当前没有待审批的请假。" : "共有 " + items.size() + " 条待审批请假。";
        return ToolResult.ok(summary, items, ctx.isAdmin() ? "/admin/leave" : "/teacher/leave");
    }

    @Component
    public static class Review extends BaseAgentTool {
        private final LeaveAgentTool parent;

        public Review(LeaveAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "leave.review";
        }

        @Override
        public String label() {
            return "审批请假";
        }

        @Override
        public String description() {
            return "通过或驳回一条请假申请。action=approve/reject，可附审批意见。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "leaveId", "必填，请假申请 ID",
                    "action", "必填，approve=通过 reject=驳回",
                    "comment", "可选，审批意见");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.review(ctx, args);
        }
    }

    @Component
    public static class Records extends BaseAgentTool {
        private final LeaveAgentTool parent;

        public Records(LeaveAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "leave.records";
        }

        @Override
        public String label() {
            return "请假记录";
        }

        @Override
        public String description() {
            return "查询已处理的请假/外出记录。教师只看自己班级。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "classId", "可选，班级 ID",
                    "studentName", "可选，学生姓名",
                    "limit", "可选，默认 10，最多 30");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.records(ctx, args);
        }
    }

    private ToolResult review(AgentContext ctx, Map<String, Object> args) {
        Long leaveId = reqLng(args, "leaveId", "请假申请 ID");
        String action = str(args, "action");
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw new AgentException("INVALID_ARGS", "action 必须是 approve 或 reject");
        }
        String comment = str(args, "comment");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("comment", comment);
        ResponseEntity<?> resp = leaveReviewService.review(leaveId, body, ctx.getAuthentication());
        Map<?, ?> respBody = resp.getBody() instanceof Map<?, ?> m ? m : Map.of();
        if (!resp.getStatusCode().is2xxSuccessful()) {
            Object error = respBody.get("error");
            throw new AgentException("REVIEW_FAILED", error == null ? "审批失败" : String.valueOf(error));
        }
        String msg = respBody.get("msg") == null ? "审批完成" : String.valueOf(respBody.get("msg"));
        return ToolResult.ok(msg,
                data("申请编号", leaveId, "操作", "approve".equals(action) ? "通过" : "驳回",
                        "意见", comment.isEmpty() ? "无" : comment,
                        "结果", respBody.get("status")),
                ctx.isAdmin() ? "/admin/leave" : "/teacher/leave");
    }

    private ToolResult records(AgentContext ctx, Map<String, Object> args) {
        Long classId = lng(args, "classId");
        String studentName = str(args, "studentName");
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.type, r.reason, r.start_time, r.end_time, r.days, r.status, " +
                        "u.real_name AS student_name, ci.class_name " +
                        "FROM leave_request r " +
                        "LEFT JOIN user u ON u.id = r.user_id " +
                        "LEFT JOIN class_info ci ON ci.id = r.class_id " +
                        "WHERE r.status IN ('APPROVED','REJECTED','RETURNED','CANCELED') ");
        List<Object> params = new ArrayList<>();
        if (!ctx.isAdmin() && !ctx.isLeader()) {
            sql.append("AND (r.teacher_id = ? OR ci.head_teacher_id IN (" +
                    "SELECT t.id FROM teacher t JOIN user tu ON tu.real_name = t.real_name WHERE tu.id = ?)) ");
            params.add(ctx.getUserId());
            params.add(ctx.getUserId());
        }
        if (classId != null) {
            sql.append("AND r.class_id = ? ");
            params.add(classId);
        }
        if (!studentName.isEmpty()) {
            sql.append("AND u.real_name LIKE ? ");
            params.add(like(studentName));
        }
        sql.append("ORDER BY r.id DESC LIMIT ?");
        params.add(limit(args, 10, 30));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : query(sql.toString(), params.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("学生", row.get("student_name"));
            item.put("班级", row.get("class_name"));
            item.put("类型", "OUTING".equals(str(row, "type")) ? "临时外出" : "请假");
            item.put("事由", row.get("reason"));
            item.put("时间", str(row, "start_time") + " 至 " + str(row, "end_time"));
            item.put("状态", statusText(str(row, "status")));
            item.put("id", row.get("id"));
            items.add(item);
        }
        return ToolResult.ok(items.isEmpty() ? "暂无请假记录。" : "共 " + items.size() + " 条请假记录。",
                items, ctx.isAdmin() ? "/admin/leave" : "/teacher/leave");
    }

    private String statusText(String status) {
        return switch (status) {
            case "APPROVED" -> "已通过";
            case "REJECTED" -> "已驳回";
            case "RETURNED" -> "已返校";
            case "CANCELED" -> "已取消";
            default -> status;
        };
    }

}
