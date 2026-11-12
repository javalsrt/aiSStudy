package com.znxsgl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.znxsgl.agent.dto.AgentContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 操作记录查询与软删除数据恢复。
 */
@Service
public class AgentRecordService {

    private final JdbcTemplate jdbc;
    private final AgentScopeService scopeService;
    private final AgentAuditService auditService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentRecordService(JdbcTemplate jdbc, AgentScopeService scopeService,
                              AgentAuditService auditService) {
        this.jdbc = jdbc;
        this.scopeService = scopeService;
        this.auditService = auditService;
    }

    public Map<String, Object> page(Authentication auth, int pageNum, int pageSize,
                                    String keyword, String status, String actionType) {
        AgentContext ctx = scopeService.buildContext(auth);
        int page = Math.max(1, pageNum);
        int size = Math.min(Math.max(1, pageSize), 100);

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (!ctx.isAdmin()) {
            where.append(" AND l.user_id = ? ");
            params.add(ctx.getUserId());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim() + "%";
            where.append(" AND (l.user_message LIKE ? OR l.result_summary LIKE ? " +
                    "OR l.action_name LIKE ? OR u.real_name LIKE ?) ");
            params.add(kw);
            params.add(kw);
            params.add(kw);
            params.add(kw);
        }
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            where.append(" AND l.result_status = ? ");
            params.add(status);
        }
        appendActionFilter(where, actionType);

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_agent_log l LEFT JOIN user u ON u.id = l.user_id" + where,
                Integer.class, params.toArray());

        List<Object> listParams = new ArrayList<>(params);
        listParams.add(size);
        listParams.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT l.id, l.user_id, u.real_name AS operator_name, u.username AS operator_username, " +
                        "l.user_role, l.session_id, l.mode, l.user_message, l.action_name, l.action_args, " +
                        "l.result_status, l.result_summary, l.duration_ms, l.error_code, " +
                        "DATE_FORMAT(l.created_at, '%Y-%m-%d %H:%i:%s') AS created_at " +
                        "FROM ai_agent_log l LEFT JOIN user u ON u.id = l.user_id" + where +
                        " ORDER BY l.id DESC LIMIT ? OFFSET ?",
                listParams.toArray());

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            Map<String, Object> args = parseArgs(row.get("action_args"));
            String action = str(row.get("action_name"));
            item.put("actionLabel", actionLabel(action));
            item.put("args", args);
            RestoreState state = restoreState(ctx, row, args);
            item.put("restorable", state.restorable);
            item.put("restored", state.restored);
            item.put("targetType", state.targetType);
            item.put("targetName", state.targetName);
            item.put("restoreReason", state.reason);
            list.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("pageNum", page);
        result.put("pageSize", size);
        result.put("list", list);
        return result;
    }

    @Transactional
    public Map<String, Object> restore(Authentication auth, Long logId) {
        AgentContext ctx = scopeService.buildContext(auth);
        Map<String, Object> log = row("SELECT * FROM ai_agent_log WHERE id = ?", logId);
        if (log == null) {
            throw new AgentException("RECORD_NOT_FOUND", "操作记录不存在", 404);
        }
        Long ownerId = toLong(log.get("user_id"));
        if (!ctx.isAdmin() && !Objects.equals(ownerId, ctx.getUserId())) {
            throw new AgentException("FORBIDDEN", "只能恢复自己发起的操作", 403);
        }
        String action = str(log.get("action_name"));
        if (!"success".equalsIgnoreCase(str(log.get("result_status")))) {
            throw new AgentException("NOT_RESTORABLE", "只有执行成功的删除记录才能恢复");
        }
        String targetType = targetType(action);
        if (targetType == null) {
            throw new AgentException("NOT_RESTORABLE", "该记录不是删除操作，无法恢复");
        }
        if ("teacher".equals(targetType) && !ctx.isAdmin()) {
            throw new AgentException("FORBIDDEN", "只有管理员可以恢复教师账号", 403);
        }

        Map<String, Object> args = parseArgs(log.get("action_args"));
        TargetLookup lookup = lookupTarget(targetType, args);
        if (lookup.target == null) {
            throw new AgentException("TARGET_NOT_FOUND",
                    lookup.reason == null ? "目标数据不存在，无法恢复" : lookup.reason);
        }
        Map<String, Object> target = lookup.target;
        Long targetUserId = toLong(target.get("id"));
        String targetName = str(target.get("real_name"));
        if (toInt(target.get("status")) == 1) {
            return Map.of("message", targetName + " 当前已是正常状态，无需恢复",
                    "restored", false, "targetType", targetType, "targetName", targetName);
        }
        if (!ctx.isAdmin() && "student".equals(targetType)) {
            Long classId = toLong(target.get("class_id"));
            if (classId == null || !ctx.getTaughtClassIds().contains(classId)) {
                throw new AgentException("FORBIDDEN", "该学生不在当前任教范围，无法恢复", 403);
            }
        }

        long start = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("UPDATE user SET status = 1, current_token = NULL WHERE id = ?", targetUserId);
        if ("teacher".equals(targetType)) {
            String username = str(target.get("username"));
            int updated = jdbc.update("UPDATE teacher SET status = 1 WHERE teacher_no = ?", username);
            if (updated == 0) {
                List<Long> teacherIds = jdbc.queryForList(
                        "SELECT id FROM teacher WHERE real_name = ? LIMIT 2", Long.class, targetName);
                if (teacherIds.size() == 1) {
                    updated = jdbc.update("UPDATE teacher SET status = 1 WHERE id = ?", teacherIds.get(0));
                }
            }
            if (updated == 0) {
                throw new AgentException("TEACHER_PROFILE_NOT_FOUND", "教师档案不存在，无法恢复");
            }
        }

        String summary = "已恢复" + ("teacher".equals(targetType) ? "教师 " : "学生 ") + targetName + " 的账号";
        auditService.log(ctx.getUserId(), ctx.getRole(), str(log.get("session_id")), "assistant",
                "恢复操作记录 #" + logId, targetType + ".restore", args, "success", summary,
                System.currentTimeMillis() - start, requestId, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", summary);
        result.put("restored", true);
        result.put("targetType", targetType);
        result.put("targetName", targetName);
        result.put("targetId", targetUserId);
        return result;
    }

    private RestoreState restoreState(AgentContext ctx, Map<String, Object> log, Map<String, Object> args) {
        String action = str(log.get("action_name"));
        String targetType = targetType(action);
        if (targetType == null) {
            return RestoreState.none();
        }
        if (!"success".equalsIgnoreCase(str(log.get("result_status")))) {
            return RestoreState.none();
        }
        TargetLookup lookup = lookupTarget(targetType, args);
        if (lookup.target == null) {
            return new RestoreState(false, false, targetType, null, lookup.reason);
        }
        String targetName = str(lookup.target.get("real_name"));
        if (toInt(lookup.target.get("status")) == 1) {
            return new RestoreState(false, true, targetType, targetName, "当前已是正常状态");
        }
        if (!ctx.isAdmin() && "student".equals(targetType)) {
            Long classId = toLong(lookup.target.get("class_id"));
            if (classId == null || !ctx.getTaughtClassIds().contains(classId)) {
                return new RestoreState(false, false, targetType, targetName, "该学生不在当前任教范围");
            }
        }
        return new RestoreState(true, false, targetType, targetName, null);
    }

    private TargetLookup lookupTarget(String targetType, Map<String, Object> args) {
        if ("student".equals(targetType)) {
            Long id = toLong(args.get("studentId"));
            if (id != null) {
                return TargetLookup.of(row("SELECT * FROM user WHERE id = ? AND role = 1", id));
            }
            String studentNo = str(args.get("studentNo"));
            if (!studentNo.isEmpty()) {
                return TargetLookup.of(row("SELECT * FROM user WHERE student_no = ? AND role = 1", studentNo));
            }
            String realName = str(args.get("realName"));
            if (!realName.isEmpty()) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT * FROM user WHERE real_name = ? AND role = 1 LIMIT 2", realName);
                if (rows.size() > 1) return new TargetLookup(null, "存在多个同名学生，无法自动恢复");
                return TargetLookup.of(rows.isEmpty() ? null : rows.get(0));
            }
        } else if ("teacher".equals(targetType)) {
            Long id = toLong(args.get("teacherUserId"));
            if (id != null) {
                return TargetLookup.of(row("SELECT * FROM user WHERE id = ? AND role = 2", id));
            }
            String username = str(args.get("username"));
            if (!username.isEmpty()) {
                return TargetLookup.of(row("SELECT * FROM user WHERE username = ? AND role = 2", username));
            }
            String realName = str(args.get("realName"));
            if (!realName.isEmpty()) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT * FROM user WHERE real_name = ? AND role = 2 LIMIT 2", realName);
                if (rows.size() > 1) return new TargetLookup(null, "存在多个同名教师，无法自动恢复");
                return TargetLookup.of(rows.isEmpty() ? null : rows.get(0));
            }
        }
        return new TargetLookup(null, "删除参数中缺少可识别的账号信息，无法自动恢复");
    }

    private void appendActionFilter(StringBuilder where, String actionType) {
        if (actionType == null || actionType.isBlank() || "all".equalsIgnoreCase(actionType)) {
            return;
        }
        switch (actionType) {
            case "query" -> where.append(" AND (l.action_name LIKE '%.search' OR l.action_name LIKE '%.list' " +
                    "OR l.action_name LIKE '%.detail' OR l.action_name LIKE '%.query' " +
                    "OR l.action_name LIKE '%.pending' OR l.action_name LIKE '%.records' " +
                    "OR l.action_name LIKE '%.overview' OR l.action_name LIKE '%.current') ");
            case "create" -> where.append(" AND l.action_name LIKE '%.create' ");
            case "update" -> where.append(" AND (l.action_name LIKE '%.update' " +
                    "OR l.action_name LIKE '%.toggleStatus' OR l.action_name LIKE '%.resetPassword') ");
            case "delete" -> where.append(" AND l.action_name LIKE '%.delete' ");
            case "review" -> where.append(" AND l.action_name LIKE '%.review' ");
            case "restore" -> where.append(" AND l.action_name LIKE '%.restore' ");
            default -> {
            }
        }
    }

    private String targetType(String action) {
        if ("student.delete".equals(action)) return "student";
        if ("teacher.delete".equals(action)) return "teacher";
        return null;
    }

    private String actionLabel(String action) {
        if (action == null || action.isBlank()) return "AI 对话";
        String[] parts = action.split("\\.");
        String domain = switch (parts[0]) {
            case "student" -> "学生";
            case "teacher" -> "教师";
            case "class" -> "班级";
            case "course" -> "课程";
            case "schedule" -> "课表";
            case "exam" -> "考试作业";
            case "leave" -> "请假";
            case "schoolInfo" -> "校园信息";
            case "stats" -> "统计";
            case "semester" -> "学期";
            case "bell" -> "作息";
            default -> parts[0];
        };
        String verb = parts.length < 2 ? "" : switch (parts[1]) {
            case "search", "list", "detail", "query", "pending", "records", "overview", "current" -> "查询";
            case "create" -> "新增";
            case "update" -> "修改";
            case "delete" -> "删除";
            case "resetPassword" -> "重置密码";
            case "toggleStatus" -> "状态变更";
            case "review" -> "审批";
            case "restore" -> "恢复";
            default -> parts[1];
        };
        return domain + verb;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(Object raw) {
        if (raw == null) return Map.of();
        try {
            if (raw instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            String text = raw instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(raw);
            if (text.isBlank()) return Map.of();
            return mapper.readValue(text, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    private int toInt(Object o) {
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private record TargetLookup(Map<String, Object> target, String reason) {
        static TargetLookup of(Map<String, Object> target) {
            return new TargetLookup(target, target == null ? "目标数据不存在，无法恢复" : null);
        }
    }

    private record RestoreState(boolean restorable, boolean restored, String targetType,
                                String targetName, String reason) {
        static RestoreState none() {
            return new RestoreState(false, false, null, null, null);
        }
    }
}
