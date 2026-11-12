package com.znxsgl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端 AI 助手操作审计。审计失败不影响主流程。
 */
@Service
public class AgentAuditService {

    private static final Logger log = LoggerFactory.getLogger(AgentAuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(Long userId, String role, String sessionId, String mode, String message,
                    String actionName, Map<String, Object> args, String status, String summary,
                    long durationMs, String requestId, String errorCode) {
        try {
            String argsJson = null;
            if (args != null && !args.isEmpty()) {
                Map<String, Object> safe = new LinkedHashMap<>();
                args.forEach((k, v) -> safe.put(k,
                        k.toLowerCase().contains("password") ? "***" : v));
                argsJson = mapper.writeValueAsString(safe);
            }
            jdbc.update("INSERT INTO ai_agent_log (request_id, user_id, user_role, session_id, mode, " +
                            "user_message, action_name, action_args, result_status, result_summary, " +
                            "duration_ms, error_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    requestId, userId, role, sessionId, mode,
                    truncate(message, 2000), actionName, argsJson, status,
                    truncate(summary, 500), (int) Math.min(durationMs, Integer.MAX_VALUE), errorCode);
        } catch (Exception e) {
            log.warn("写入 AI 审计日志失败: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
