package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.controller.ExamHomeworkController;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考试/作业查询、上下架与删除。
 */
@Component
public class ExamAgentTool extends BaseAgentTool {

    private final ExamHomeworkController examController;

    public ExamAgentTool(ExamHomeworkController examController) {
        this.examController = examController;
    }

    @Override
    public String name() {
        return "exam.list";
    }

    @Override
    public String label() {
        return "查询考试作业";
    }

    @Override
    public String description() {
        return "查询考试/作业列表，可看到班级、状态、题目数与提交数。教师只查自己创建的。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec(
                "classId", "可选，班级 ID",
                "type", "可选，exam=考试 homework=作业",
                "status", "可选，0=草稿 1=进行中 2=已结束 3=已归档",
                "keyword", "可选，标题关键词",
                "limit", "可选，默认 10，最多 30");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        StringBuilder sql = new StringBuilder(
                "SELECT e.id, e.title, e.type, e.status, e.class_id, ci.class_name, " +
                        "e.start_time, e.end_time, e.total_score, " +
                        "(SELECT COUNT(*) FROM exam_question q WHERE q.exam_homework_id = e.id) AS question_count, " +
                        "(SELECT COUNT(*) FROM exam_submission s WHERE s.exam_homework_id = e.id) AS submission_count " +
                        "FROM exam_homework e LEFT JOIN class_info ci ON ci.id = e.class_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (!ctx.isAdmin()) {
            sql.append("AND e.teacher_id = ? ");
            params.add(ctx.getTeacherId());
        }
        Long classId = lng(args, "classId");
        if (classId != null) {
            assertClass(ctx, classId);
            sql.append("AND e.class_id = ? ");
            params.add(classId);
        }
        String type = str(args, "type");
        if (!type.isEmpty()) {
            sql.append("AND e.type = ? ");
            params.add(type);
        }
        Integer status = intVal(args, "status");
        if (status != null) {
            sql.append("AND e.status = ? ");
            params.add(status);
        }
        String keyword = str(args, "keyword");
        if (!keyword.isEmpty()) {
            sql.append("AND e.title LIKE ? ");
            params.add(like(keyword));
        }
        sql.append("ORDER BY e.id DESC LIMIT ?");
        params.add(limit(args, 10, 30));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : query(sql.toString(), params.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("标题", row.get("title"));
            item.put("类型", "exam".equals(str(row, "type")) ? "考试" : "作业");
            item.put("班级", row.get("class_name"));
            item.put("状态", statusText(toInt(row.get("status"))));
            item.put("题目数", row.get("question_count"));
            item.put("提交数", row.get("submission_count"));
            item.put("总分", row.get("total_score"));
            item.put("id", row.get("id"));
            items.add(item);
        }
        String summary = items.isEmpty() ? "没有找到考试或作业。" : "共 " + items.size() + " 条考试/作业。";
        return ToolResult.ok(summary, items, "/exam-homework");
    }

    @Component
    public static class Detail extends BaseAgentTool {
        private final ExamAgentTool parent;

        public Detail(ExamAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "exam.detail";
        }

        @Override
        public String label() {
            return "考试作业详情";
        }

        @Override
        public String description() {
            return "查询某场考试/作业的题目数、提交数与平均分。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("examId", "必填，考试/作业 ID");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.detail(ctx, args);
        }
    }

    @Component
    public static class ToggleStatus extends BaseAgentTool {
        private final ExamAgentTool parent;

        public ToggleStatus(ExamAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "exam.toggleStatus";
        }

        @Override
        public String label() {
            return "上下架考试作业";
        }

        @Override
        public String description() {
            return "修改考试/作业状态：0=草稿 1=进行中 2=已结束 3=已归档。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN", "TEACHER");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("examId", "必填，考试/作业 ID", "status", "必填，0-3");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.toggle(ctx, args);
        }
    }

    @Component
    public static class Delete extends BaseAgentTool {
        private final ExamAgentTool parent;

        public Delete(ExamAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "exam.delete";
        }

        @Override
        public String label() {
            return "删除考试作业";
        }

        @Override
        public String description() {
            return "删除考试/作业，需要二次确认。";
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
            return argSpec("examId", "必填，考试/作业 ID");
        }

        @Override
        public ToolResult preview(AgentContext ctx, Map<String, Object> args) {
            return parent.previewDelete(ctx, args);
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.delete(ctx, args);
        }
    }

    private ToolResult detail(AgentContext ctx, Map<String, Object> args) {
        Long examId = reqLng(args, "examId", "考试/作业 ID");
        Map<String, Object> exam = requireExam(ctx, examId);
        Integer submissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_submission WHERE exam_homework_id = ?", Integer.class, examId);
        Double avg = jdbc.queryForObject(
                "SELECT ROUND(AVG(total_score), 1) FROM exam_submission WHERE exam_homework_id = ? AND submitted_at IS NOT NULL",
                Double.class, examId);
        Integer questions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exam_question WHERE exam_homework_id = ?", Integer.class, examId);
        return ToolResult.ok("已找到《" + exam.get("title") + "》。",
                data("标题", exam.get("title"),
                        "班级", exam.get("class_name"),
                        "状态", statusText(toInt(exam.get("status"))),
                        "题目数", questions,
                        "提交数", submissions,
                        "平均分", avg == null ? "-" : avg), "/exam-homework");
    }

    private ToolResult toggle(AgentContext ctx, Map<String, Object> args) {
        Long examId = reqLng(args, "examId", "考试/作业 ID");
        Integer status = intVal(args, "status");
        if (status == null || status < 0 || status > 3) {
            throw new AgentException("INVALID_ARGS", "status 必须是 0-3");
        }
        Map<String, Object> exam = requireExam(ctx, examId);
        ResponseEntity<Map<String, Object>> resp =
                examController.toggleStatus(examId, Map.<String, Object>of("status", status), ctx.getAuthentication());
        if (!resp.getStatusCode().is2xxSuccessful()) {
            String error = resp.getBody() != null && resp.getBody().get("error") != null
                    ? String.valueOf(resp.getBody().get("error")) : "状态修改失败";
            throw new AgentException("UPDATE_FAILED", error);
        }
        return ToolResult.ok("已把《" + exam.get("title") + "》状态改为" + statusText(status) + "。",
                data("标题", exam.get("title"), "状态", statusText(status)), "/exam-homework");
    }

    private ToolResult previewDelete(AgentContext ctx, Map<String, Object> args) {
        Long examId = reqLng(args, "examId", "考试/作业 ID");
        Map<String, Object> exam = requireExam(ctx, examId);
        return ToolResult.ok("即将删除《" + exam.get("title") + "》，删除后不可恢复。",
                data("标题", exam.get("title"), "班级", exam.get("class_name"),
                        "状态", statusText(toInt(exam.get("status")))), "/exam-homework");
    }

    private ToolResult delete(AgentContext ctx, Map<String, Object> args) {
        Long examId = reqLng(args, "examId", "考试/作业 ID");
        Map<String, Object> exam = requireExam(ctx, examId);
        ResponseEntity<Map<String, Object>> resp = examController.delete(examId, ctx.getAuthentication());
        if (!resp.getStatusCode().is2xxSuccessful()) {
            String error = resp.getBody() != null && resp.getBody().get("error") != null
                    ? String.valueOf(resp.getBody().get("error")) : "删除失败";
            throw new AgentException("DELETE_FAILED", error);
        }
        return ToolResult.ok("已删除《" + exam.get("title") + "》。",
                data("标题", exam.get("title"), "班级", exam.get("class_name")), "/exam-homework");
    }

    private Map<String, Object> requireExam(AgentContext ctx, Long examId) {
        Map<String, Object> exam = row(
                "SELECT e.*, ci.class_name FROM exam_homework e " +
                        "LEFT JOIN class_info ci ON ci.id = e.class_id WHERE e.id = ?", examId);
        if (exam == null) {
            throw new AgentException("EXAM_NOT_FOUND", "考试/作业不存在");
        }
        if (!ctx.isAdmin()) {
            Number teacherId = exam.get("teacher_id") instanceof Number n ? n : null;
            if (teacherId == null || teacherId.longValue() != ctx.getTeacherId()) {
                throw new AgentException("FORBIDDEN", "无权操作该考试/作业", 403);
            }
        }
        return exam;
    }

    private String statusText(int status) {
        return switch (status) {
            case 0 -> "草稿";
            case 1 -> "进行中";
            case 2 -> "已结束";
            case 3 -> "已归档";
            default -> String.valueOf(status);
        };
    }

    private int toInt(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }
}
