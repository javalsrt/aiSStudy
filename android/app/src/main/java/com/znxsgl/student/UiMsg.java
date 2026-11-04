package com.znxsgl.student;

/**
 * 统一用户提示文案：把网络异常、HTTP 错误体转成用户能看懂的中文，
 * 避免把原始 JSON、英文堆栈（Failed to connect to ...）直接弹给用户。
 */
public final class UiMsg {

    private UiMsg() { }

    /** 网络请求失败（onFailure）→ 友好文案 */
    public static String network(Throwable t) {
        if (t == null) return "网络异常，请稍后重试";
        if (t instanceof java.net.SocketTimeoutException) return "网络超时，请稍后重试";
        if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException) {
            return "网络连接失败，请检查网络后重试";
        }
        String m = String.valueOf(t.getMessage());
        String lower = m.toLowerCase();
        if (lower.contains("timeout") || lower.contains("timed out")) return "网络超时，请稍后重试";
        if (lower.contains("failed to connect") || lower.contains("connection refused")
                || lower.contains("unable to resolve host") || lower.contains("econnrefused")) {
            return "网络连接失败，请检查网络后重试";
        }
        return "网络异常，请稍后重试";
    }

    /** 业务失败（onResponse 非 2xx）→ 从错误体里提取 message/error/msg，提取不到给兜底文案 */
    public static String errorBody(String body) {
        return errorBody(body, "请求失败，请稍后重试");
    }

    public static String errorBody(String body, String fallback) {
        if (body == null || body.trim().isEmpty()) return fallback;
        String b = body.trim();
        if (b.startsWith("{")) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(b);
                for (String key : new String[]{"message", "error", "msg", "detail"}) {
                    String v = o.optString(key, "");
                    if (!v.isEmpty() && !"null".equals(v)) return v;
                }
            } catch (Exception ignored) {
                // JSON 解析失败按兜底文案处理，绝不把原始报文弹给用户
            }
            return fallback;
        }
        // 非 JSON：仅在内容像自然语言时展示，避免把 HTML 报错页直接弹出
        if (b.length() <= 60 && !b.startsWith("<")) return b;
        return fallback;
    }
}
