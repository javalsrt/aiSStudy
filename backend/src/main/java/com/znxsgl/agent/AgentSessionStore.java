package com.znxsgl.agent;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端 AI 助手会话存储（单机内存版）。
 * <p>
 * 30 分钟无活动自动过期；单用户最多保留 5 个会话；同一会话串行处理，避免并发重复执行。
 */
@Component
public class AgentSessionStore {

    public static final long SESSION_TTL_MS = 30 * 60 * 1000L;
    public static final long PENDING_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_MESSAGES = 20;
    private static final int MAX_SESSIONS_PER_USER = 5;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public Session getOrCreate(Long userId, String sessionId) {
        cleanup();
        String sid = normalizeSessionId(sessionId);
        String key = userId + ":" + sid;
        Session session = sessions.computeIfAbsent(key, k -> new Session(userId, sid));
        session.setLastActive(System.currentTimeMillis());
        enforceUserLimit(userId, sid);
        return session;
    }

    public void addMessage(Session session, String role, String content) {
        String text = content == null ? "" : content;
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
        }
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", text);
        session.getMessages().add(message);
        while (session.getMessages().size() > MAX_MESSAGES) {
            session.getMessages().remove(0);
        }
        session.setLastActive(System.currentTimeMillis());
    }

    public PendingAction consumePending(Session session, String token, boolean approved) {
        PendingAction pending = session.getPending();
        if (pending == null) {
            throw new AgentException("NO_PENDING", "当前没有待确认的操作");
        }
        if (token == null || !token.equals(pending.getToken())) {
            throw new AgentException("INVALID_TOKEN", "确认令牌无效，请重新发起操作");
        }
        if (System.currentTimeMillis() - pending.getCreatedAt() > PENDING_TTL_MS) {
            session.setPending(null);
            throw new AgentException("EXPIRED", "确认已过期，请重新发起操作");
        }
        if (!approved) {
            session.setPending(null);
            return null;
        }
        // 一次性消费：执行前先移除，防止双击重复删除
        session.setPending(null);
        return pending;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().getLastActive() > SESSION_TTL_MS);
    }

    private void enforceUserLimit(Long userId, String currentSessionId) {
        List<Session> userSessions = new ArrayList<>();
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getValue().getUserId().equals(userId)) {
                userSessions.add(entry.getValue());
            }
        }
        if (userSessions.size() <= MAX_SESSIONS_PER_USER) return;
        userSessions.sort(Comparator.comparingLong(Session::getLastActive));
        int remove = userSessions.size() - MAX_SESSIONS_PER_USER;
        for (Session session : userSessions) {
            if (remove <= 0) break;
            if (!session.getSessionId().equals(currentSessionId)) {
                sessions.remove(userId + ":" + session.getSessionId());
                remove--;
            }
        }
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId != null && sessionId.matches("[A-Za-z0-9_-]{6,64}")) {
            return sessionId;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Data
    public static class Session {
        private final Long userId;
        private final String sessionId;
        private final List<Map<String, String>> messages = new ArrayList<>();
        private final Object lock = new Object();
        private PendingAction pending;
        private Map<String, Object> lastEntity = new LinkedHashMap<>();
        private volatile long lastActive = System.currentTimeMillis();

        public Session(Long userId, String sessionId) {
            this.userId = userId;
            this.sessionId = sessionId;
        }
    }

    @Data
    public static class PendingAction {
        private String name;
        private String label;
        private Map<String, Object> args;
        private String token;
        private String summary;
        private List<Map<String, String>> fields;
        private String link;
        private long createdAt;
    }
}
