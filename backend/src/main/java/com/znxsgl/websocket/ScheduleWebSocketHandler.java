package com.znxsgl.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket 处理器（学生 + 教师共用）
 * 学生连接：ws://host/ws/schedule?userId=123&role=student
 * 教师连接：ws://host/ws/schedule?userId=456&role=teacher
 */
@Component
public class ScheduleWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 学生 userId → WebSocketSession
    private final Map<Long, WebSocketSession> studentSessions = new ConcurrentHashMap<>();
    // 教师 Session 集合（广播用）
    private final Set<WebSocketSession> teacherSessions = new CopyOnWriteArraySet<>();
    // 教师 userId → WebSocketSession（点对点用）
    private final Map<Long, WebSocketSession> teacherMap = new ConcurrentHashMap<>();
    // 用户级推送去重：key=userId:type:contentHash，value=上次推送时间戳，60 秒内相同内容不再推送
    private final Map<String, Long> userPushDedup = new ConcurrentHashMap<>();
    private static final long USER_PUSH_DEDUP_MS = 60_000;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            String query = session.getUri() != null ? session.getUri().getQuery() : "";
            Long userId = parseUserId(query);
            String role = parseParam(query, "role");

            if (userId == null) {
                System.out.println("=== WebSocket 连接拒绝: userId 为空, query=" + query);
                session.close(CloseStatus.BAD_DATA);
                return;
            }

            if ("teacher".equals(role)) {
                teacherSessions.add(session);
                teacherMap.put(userId, session);
                System.out.println("WebSocket 教师连接: userId=" + userId + ", sessionId=" + session.getId());
            } else {
                // 学生连接：如果同一用户已存在旧连接，先关闭旧连接，避免多端/重连导致重复 session
                WebSocketSession oldSession = studentSessions.get(userId);
                if (oldSession != null && oldSession.isOpen() && !oldSession.getId().equals(session.getId())) {
                    System.out.println("=== WebSocket 学生重复连接，关闭旧session: userId=" + userId
                            + ", oldSessionId=" + oldSession.getId() + ", newSessionId=" + session.getId());
                    try { oldSession.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
                }
                studentSessions.put(userId, session);
                System.out.println("WebSocket 学生连接: userId=" + userId + ", sessionId=" + session.getId());
                // 通知所有在线教师：该学生上线
                broadcastToTeachers("student_online", buildStudentStatusMsg(userId, true));
            }
        } catch (Exception e) {
            System.out.println("=== WebSocket afterConnectionEstablished 异常: sessionId="
                    + (session != null ? session.getId() : "null") + ", error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if ("ping".equals(payload)) {
            try { session.sendMessage(new TextMessage("pong")); } catch (IOException ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 清理学生连接
        boolean wasStudent = false;
        Long removedUserId = null;
        for (Map.Entry<Long, WebSocketSession> e : studentSessions.entrySet()) {
            if (e.getValue().getId().equals(session.getId())) {
                removedUserId = e.getKey();
                wasStudent = true;
                break;
            }
        }
        if (wasStudent && removedUserId != null) {
            studentSessions.remove(removedUserId);
            System.out.println("WebSocket 学生断开: userId=" + removedUserId);
            // 通知所有在线教师：该学生下线
            broadcastToTeachers("student_offline", buildStudentStatusMsg(removedUserId, false));
        }

        // 清理教师连接
        teacherSessions.remove(session);
        teacherMap.values().removeIf(s -> s.getId().equals(session.getId()));
        if (!wasStudent) {
            System.out.println("WebSocket 教师断开: " + session.getId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        // EOFException 是客户端正常断开或网络中断，不需要打印完整堆栈
        if (ex instanceof java.io.EOFException || "EOFException".equals(ex.getClass().getSimpleName())) {
            System.out.println("=== WebSocket 客户端断开: sessionId="
                    + (session != null ? session.getId() : "null") + ", reason=" + ex.getClass().getSimpleName());
        } else {
            System.out.println("=== WebSocket handleTransportError: sessionId="
                    + (session != null ? session.getId() : "null") + ", error=" + ex.getMessage());
            ex.printStackTrace();
        }
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    /** 判断学生是否在线（通过 WebSocket 连接状态） */
    public boolean isOnline(Long userId) {
        WebSocketSession session = studentSessions.get(userId);
        return session != null && session.isOpen();
    }

    /** 获取所有在线学生 ID 集合 */
    public Set<Long> getOnlineStudentIds() {
        return new HashSet<>(studentSessions.keySet());
    }

    // ========== 推送方法 ==========

    /** 给指定学生推送消息，返回是否成功发送 */
    public boolean sendToUser(Long userId, String type, Map<String, Object> data) {
        // 用户级内容去重：60 秒内同一用户同一类型相同内容只推送一次
        String contentHash = hashData(data);
        String dedupKey = userId + ":" + type + ":" + contentHash;
        long now = System.currentTimeMillis();
        userPushDedup.entrySet().removeIf(e -> now - e.getValue() > USER_PUSH_DEDUP_MS);
        Long lastPush = userPushDedup.get(dedupKey);
        if (lastPush != null && now - lastPush < USER_PUSH_DEDUP_MS) {
            System.out.println("=== WebSocket sendToUser 被用户级去重拦截: userId=" + userId
                    + ", type=" + type + ", dedupKey=" + dedupKey);
            return false;
        }

        WebSocketSession session = studentSessions.get(userId);
        if (session == null) {
            // 用户不在线是常态（班级群发会给全员推送），静默跳过，避免刷屏
            return false;
        }
        if (!session.isOpen()) {
            System.out.println("=== WebSocket sendToUser session已关闭: userId=" + userId + ", sessionId=" + session.getId() + ", type=" + type);
            return false;
        }
        boolean success = sendJson(session, type, data);
        if (success) {
            userPushDedup.put(dedupKey, now);
        }
        System.out.println("=== WebSocket sendToUser: userId=" + userId + ", sessionId=" + session.getId()
                + ", type=" + type + ", success=" + success);
        return success;
    }

    /** 给某个班级的所有在线学生推送消息 */
    public void sendToClass(Long classId, String type, Map<String, Object> data,
                             org.springframework.jdbc.core.JdbcTemplate jdbc) {
        try {
            List<Long> userIds = jdbc.queryForList(
                "SELECT id FROM user WHERE class_id = ? AND role = 1", Long.class, classId);
            System.out.println("=== WebSocket sendToClass: classId=" + classId + ", type=" + type
                    + ", studentCount=" + userIds.size());
            for (Long uid : userIds) {
                sendToUser(uid, type, data);
            }
        } catch (Exception e) {
            System.out.println("=== WebSocket sendToClass 异常: classId=" + classId + ", type=" + type
                    + ", error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 广播消息给所有在线教师 */
    private void broadcastToTeachers(String type, Map<String, Object> data) {
        String json;
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", type);
            msg.put("data", data);
            json = MAPPER.writeValueAsString(msg);
        } catch (IOException e) { return; }

        for (WebSocketSession session : teacherSessions) {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(json));
                    }
                } catch (IOException ignored) {}
            }
        }
    }

    // ========== 辅助方法 ==========

    private Map<String, Object> buildStudentStatusMsg(Long userId, boolean online) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("online", online);
        return data;
    }

    private boolean sendJson(WebSocketSession session, String type, Map<String, Object> data) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", type);
            msg.put("data", data);
            String json = MAPPER.writeValueAsString(msg);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException e) {
            // 发送失败，说明连接已断开，移除
            System.out.println("=== WebSocket sendJson 发送失败: sessionId=" + session.getId() + ", type=" + type + ", error=" + e.getMessage());
            e.printStackTrace();
            studentSessions.values().removeIf(s -> s.getId().equals(session.getId()));
            return false;
        }
    }

    private Long parseUserId(String query) {
        String val = parseParam(query, "userId");
        if (val == null) return null;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return null; }
    }

    private String parseParam(String query, String key) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && key.equals(kv[0])) return kv[1];
        }
        return null;
    }

    /** 为 Map 数据生成简单 hash，用于用户级推送去重 */
    private String hashData(Map<String, Object> data) {
        try {
            return String.valueOf(MAPPER.writeValueAsString(data).hashCode());
        } catch (Exception e) {
            return String.valueOf(data.hashCode());
        }
    }
}
