package com.znxsgl.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.znxsgl.agent.dto.AgentContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具基类：统一参数解析、权限校验与常用查询。
 */
public abstract class BaseAgentTool implements AgentTool {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AgentScopeService scopeService;

    @Autowired
    protected ObjectMapper mapper;

    protected void requireAdmin(AgentContext ctx) {
        if (ctx == null || !ctx.isAdmin()) {
            throw new AgentException("FORBIDDEN", "该操作仅管理员可执行", 403);
        }
    }

    protected void assertClass(AgentContext ctx, Long classId) {
        scopeService.assertClassInScope(ctx, classId);
    }

    protected String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    protected String reqStr(Map<String, Object> args, String key, String label) {
        String v = str(args, key);
        if (v.isEmpty()) {
            throw new AgentException("INVALID_ARGS", "请提供" + label);
        }
        if (v.length() > 200) {
            throw new AgentException("INVALID_ARGS", label + "过长");
        }
        return v;
    }

    protected Long lng(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            throw new AgentException("INVALID_ARGS", key + " 必须是数字");
        }
    }

    protected Long reqLng(Map<String, Object> args, String key, String label) {
        Long v = lng(args, key);
        if (v == null) {
            throw new AgentException("INVALID_ARGS", "请提供" + label);
        }
        return v;
    }

    protected Integer intVal(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            if (v instanceof Number n) return n.intValue();
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            throw new AgentException("INVALID_ARGS", key + " 必须是数字");
        }
    }

    protected int intVal(Map<String, Object> args, String key, int defaultValue) {
        Integer v = intVal(args, key);
        return v == null ? defaultValue : v;
    }

    protected int limit(Map<String, Object> args, int defaultValue, int max) {
        int v = intVal(args, "limit", defaultValue);
        return Math.max(1, Math.min(v, max));
    }

    @SuppressWarnings("unchecked")
    protected List<Long> longList(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (!(v instanceof List<?> raw)) return List.of();
        List<Long> ids = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Number n) {
                ids.add(n.longValue());
            } else {
                try {
                    ids.add(Long.parseLong(String.valueOf(item).trim()));
                } catch (Exception ignored) {
                }
            }
        }
        return ids;
    }

    protected String like(String keyword) {
        return "%" + keyword + "%";
    }

    protected List<Map<String, Object>> query(String sql, Object... params) {
        return jdbc.queryForList(sql, params);
    }

    protected Map<String, Object> row(String sql, Object... params) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected Long firstLong(String sql, Object... params) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected String firstString(String sql, Object... params) {
        List<String> rows = jdbc.queryForList(sql, String.class, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    protected Map<String, Object> argSpec(String... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
