package com.znxsgl.agent.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具执行结果。
 */
@Data
public class ToolResult {

    private boolean success;
    private String summary;
    private List<Map<String, Object>> items;
    private Map<String, Object> data;
    private String link;
    private String errorCode;

    public static ToolResult ok(String summary) {
        return ok(summary, (String) null);
    }

    public static ToolResult ok(String summary, String link) {
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setSummary(summary);
        r.setLink(link);
        return r;
    }

    public static ToolResult ok(String summary, List<Map<String, Object>> items, String link) {
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setSummary(summary);
        r.setItems(items);
        r.setLink(link);
        return r;
    }

    public static ToolResult ok(String summary, Map<String, Object> data, String link) {
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setSummary(summary);
        r.setData(data);
        r.setLink(link);
        return r;
    }

    public static ToolResult fail(String summary) {
        return fail(summary, "EXECUTE_FAILED");
    }

    public static ToolResult fail(String summary, String errorCode) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setSummary(summary);
        r.setErrorCode(errorCode);
        return r;
    }

    /** 把 data 中的标量字段转成前端可展示的键值对 */
    public List<Map<String, String>> toFields() {
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> fields = new java.util.ArrayList<>();
        data.forEach((k, v) -> {
            if (v == null || v instanceof Map || v instanceof List) {
                return;
            }
            Map<String, String> pair = new LinkedHashMap<>();
            pair.put("label", k);
            pair.put("value", String.valueOf(v));
            fields.add(pair);
        });
        return fields;
    }
}
