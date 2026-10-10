package com.znxsgl.student.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocket 管理器：接收服务端推送的排课变动通知
 */
public class WebSocketManager {

    private static final String TAG = "WSManager";
    private static WebSocketManager instance;
    private WebSocket webSocket;
    private final OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private boolean connected = false;

    public interface OnScheduleUpdateListener {
        void onUpdate(String courseName, String content, String scheduleInfo);
    }

    public interface OnChatUpdateListener {
        /** @param courseId 课程ID（聊天室唯一键，可能为 null）；courseName 仅用于展示 */
        void onChatUpdate(Long courseId, String courseName, String senderName, String content);
    }

    private final List<OnScheduleUpdateListener> listeners = new ArrayList<>();
    private final List<OnChatUpdateListener> chatListeners = new ArrayList<>();

    private WebSocketManager() {
        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // 无限超时
                .build();
    }

    public static synchronized WebSocketManager getInstance() {
        if (instance == null) instance = new WebSocketManager();
        return instance;
    }

    /** 添加监听器（支持多个页面同时监听） */
    public void addListener(OnScheduleUpdateListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
    }

    /** 移除监听器 */
    public void removeListener(OnScheduleUpdateListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /** 添加聊天更新监听 */
    public void addChatListener(OnChatUpdateListener listener) {
        synchronized (chatListeners) {
            if (!chatListeners.contains(listener)) chatListeners.add(listener);
        }
    }

    public void removeChatListener(OnChatUpdateListener listener) {
        synchronized (chatListeners) {
            chatListeners.remove(listener);
        }
    }

    /** 兼容旧接口：替换为添加 */
    public void setListener(OnScheduleUpdateListener listener) {
        synchronized (listeners) {
            listeners.clear();
            listeners.add(listener);
        }
    }

    public void connect(String baseUrl, long userId) {
        if (connected) return;
        String wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://");
        wsUrl += "/ws/schedule?userId=" + userId + "&role=student";

        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                Log.d(TAG, "WebSocket 已连接");
                // 启动心跳
                startHeartbeat(ws);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                Log.d(TAG, "收到消息: " + text);
                try {
                    Type type = new TypeToken<Map<String, Object>>(){}.getType();
                    Map<String, Object> msg = gson.fromJson(text, type);
                    String msgType = (String) msg.get("type");
                    if ("schedule_update".equals(msgType)) {
                        Map<String, Object> data = (Map<String, Object>) msg.get("data");
                        String courseName = (String) data.get("courseName");
                        String content = (String) data.get("content");
                        String scheduleInfo = (String) data.get("scheduleInfo");
                        synchronized (listeners) {
                            for (OnScheduleUpdateListener l : listeners) {
                                mainHandler.post(() -> l.onUpdate(courseName, content, scheduleInfo));
                            }
                        }
                    } else if ("chat_update".equals(msgType)) {
                        Map<String, Object> data = (Map<String, Object>) msg.get("data");
                        Object cidObj = data.get("courseId");
                        Long courseId = cidObj instanceof Number ? ((Number) cidObj).longValue() : null;
                        String courseName = (String) data.get("courseName");
                        String senderName = (String) data.get("senderName");
                        String content = (String) data.get("content");
                        synchronized (chatListeners) {
                            for (OnChatUpdateListener l : chatListeners) {
                                mainHandler.post(() -> l.onChatUpdate(courseId, courseName, senderName, content));
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "消息解析失败", e);
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                connected = false;
                ws.close(1000, null);
                Log.d(TAG, "WebSocket 关闭中");
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                connected = false;
                Log.d(TAG, "WebSocket 已断开");
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                connected = false;
                Log.e(TAG, "WebSocket 连接失败", t);
                // 5秒后重连
                mainHandler.postDelayed(() -> connect(baseUrl, userId), 5000);
            }
        });
    }

    private void startHeartbeat(WebSocket ws) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (connected && ws != null) {
                    ws.send("ping");
                    mainHandler.postDelayed(this, 30000); // 30秒心跳
                }
            }
        }, 30000);
    }

    public void disconnect() {
        connected = false;
        if (webSocket != null) {
            webSocket.close(1000, "app close");
            webSocket = null;
        }
    }

    public boolean isConnected() {
        return connected;
    }
}
