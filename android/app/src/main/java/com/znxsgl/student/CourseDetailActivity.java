package com.znxsgl.student;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.znxsgl.student.model.ChatMsgDto;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;
import com.znxsgl.student.network.WebSocketManager;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class CourseDetailActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final int REQ_FILE = 1002;
    private static final String TAG = "CourseDetail";

    private EditText etInput;
    private RecyclerView rvChat;
    private ChatAdapter adapter;
    private String courseName;
    /** 聊天室唯一键：同名课程（如多门「英语」）必须按课程ID隔离 */
    private long courseId;
    private String token;
    private final List<Object> items = new ArrayList<>();
    private File cameraFile;

    // 流式输出相关
    private final Handler streamHandler = new Handler(Looper.getMainLooper());
    private int streamMsgPos = -1;
    private String streamFullText = "";
    private int streamCharIdx = 0;
    private static final int STREAM_INTERVAL_MS = 15;  // 每次输出间隔（ms）
    private static final int STREAM_CHARS_PER_TICK = 2; // 每次输出字符数（2000 字约 15 秒完成）
    private boolean isStreaming = false;

    // 缓存学生各考试/作业的提交状态（examId -> {submitStatus, score, statusText}）
    private final Map<Long, Map<String, Object>> examStatusMap = new HashMap<>();

    // @ 提及提示弹窗
    private android.widget.PopupWindow mentionPopup;
    private MentionAdapter mentionAdapter;
    private final List<MentionItem> mentionItems = new ArrayList<>();

    // WebSocket 监听器
    private final WebSocketManager.OnScheduleUpdateListener wsListener = (cn, content, info) -> {
        if (courseName != null && courseName.equals(cn)) {
            runOnUiThread(() -> appendScheduleNotice(content));
        }
    };

    // WebSocket 聊天更新监听：教师/其他学生发消息实时刷新聊天记录
    private final WebSocketManager.OnChatUpdateListener chatListener = (cid, cn, senderName, content) -> {
        boolean mine = courseId > 0
                ? (cid != null && cid == courseId)
                : (courseName != null && courseName.equals(cn));
        if (mine) runOnUiThread(() -> loadMessages());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_course_detail);

        courseName = getIntent().getStringExtra("course_name");
        if (courseName == null) courseName = "课程详情";
        courseId = getIntent().getLongExtra("course_id", 0L);
        if (courseId <= 0) { // 兼容以字符串形式传入的 course_id
            try {
                String raw = getIntent().getStringExtra("course_id");
                if (raw != null && !raw.isEmpty()) courseId = Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        token = "Bearer " + prefs.getString("token", "");

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        TextView tvTitle = findViewById(R.id.tv_title);
        tvTitle.setText(courseName);

        rvChat = findViewById(R.id.rv_chat);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatAdapter(items, this, examStatusMap);
        rvChat.setAdapter(adapter);

        etInput = findViewById(R.id.et_input);
        setupMentionPopup();

        // 发送按钮：空内容自动置灰（bg_btn_primary 的 state_enabled=false 变浅蓝灰）
        TextView btnSend = findViewById(R.id.btn_send);
        btnSend.setEnabled(false);
        etInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                btnSend.setEnabled(s.toString().trim().length() > 0);
            }
        });

        findViewById(R.id.btn_send).setOnClickListener(v -> handleSend());
        findViewById(R.id.btn_add).setOnClickListener(v -> showAddSheet());

        // 注册 WebSocket 监听，教师修改排课时实时同步到对话
        WebSocketManager.getInstance().addListener(wsListener);
        // 注册聊天更新监听，教师/学生发消息实时刷新聊天记录
        WebSocketManager.getInstance().addChatListener(chatListener);

        loadMessages();
        loadExamStatuses();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 进入/返回该聊天窗口：声明"正在查看"（抑制红点与提示），并立即标记已读消除红点
        MainActivity.setActiveChatCourse(courseId > 0 ? courseId : null);
        markReadForCurrentCourse();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 离开聊天页：恢复红点统计
        MainActivity.setActiveChatCourse(null);
        // 取消当前页面的 WebSocket 监听
        WebSocketManager.getInstance().removeListener(wsListener);
        WebSocketManager.getInstance().removeChatListener(chatListener);
        streamHandler.removeCallbacksAndMessages(null);
    }

    private Long parseLongSafe(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    /** 解析消息类型前缀协议：[image] / [file] / [exam] */
    private void parseMsgType(ChatMsgDto msg) {
        String raw = msg.getContent();
        if (raw != null && raw.startsWith("[image]")) {
            msg.msgType = "image";
            msg.imageUrl = raw.substring(7);
            msg.setContent("[图片]");
        } else if (raw != null && raw.startsWith("[file]")) {
            msg.msgType = "file";
            String rest = raw.substring(6);
            int sep = rest.indexOf('|');
            if (sep > 0) {
                msg.fileName = rest.substring(0, sep);
                msg.fileUrl = rest.substring(sep + 1);
            } else {
                msg.fileName = rest;
                msg.fileUrl = "#";
            }
            msg.setContent(msg.fileName);
        } else if (raw != null && raw.startsWith("[exam]")) {
            msg.msgType = "exam_card";
            // [exam]标题|考试ID|类型|状态文本|课程名
            String[] parts = raw.substring(6).split("\\|", -1);
            msg.examTitle = parts.length > 0 ? parts[0] : "";
            msg.examId = parts.length > 1 ? parseLongSafe(parts[1]) : null;
            msg.examType = parts.length > 2 ? parts[2] : "exam";
            msg.examStatus = parts.length > 3 ? parts[3] : "点击开始作答";
            if (msg.getBizId() == null && msg.examId != null) msg.setBizId(msg.examId);
            if (msg.getBizType() == null) msg.setBizType("exam_publish");
        } else {
            msg.msgType = "text";
        }
    }

    /** 加载当前学生所有考试/作业的提交状态，用于卡片显示分数与状态 */
    private void loadExamStatuses() {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getExamHomeworkTodos(token).enqueue(new Callback<List<Map<String, Object>>>() {
            @Override
            public void onResponse(Call<List<Map<String, Object>>> call, Response<List<Map<String, Object>>> resp) {
                if (!resp.isSuccessful() || resp.body() == null) return;
                examStatusMap.clear();
                for (Map<String, Object> row : resp.body()) {
                    Object idObj = row.get("id");
                    if (idObj instanceof Number) {
                        examStatusMap.put(((Number) idObj).longValue(), row);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onFailure(Call<List<Map<String, Object>>> call, Throwable t) {
                // 静默失败，不影响聊天展示
            }
        });
    }

    /** 房间键：有课程ID就用 ID（同名课程隔离），否则退回课程名让服务端按身份收敛 */
    private Map<String, String> courseBody() {
        Map<String, String> body = new HashMap<>();
        if (courseId > 0) body.put("courseId", String.valueOf(courseId));
        body.put("courseName", courseName);
        return body;
    }

    private void loadMessages() {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        Call<List<ChatMsgDto>> call = courseId > 0
                ? api.getChatMessagesById(token, courseId)
                : api.getChatMessages(token, courseName);
        call.enqueue(new Callback<List<ChatMsgDto>>() {
            @Override
            public void onResponse(Call<List<ChatMsgDto>> call, retrofit2.Response<List<ChatMsgDto>> resp) {
                if (resp.isSuccessful() && resp.body() != null) buildItems(resp.body());
            }
            @Override
            public void onFailure(Call<List<ChatMsgDto>> call, Throwable t) {}
        });
        // 标记已读
        api.markAsRead(token, courseBody()).enqueue(new Callback<Map<String, String>>() {
            @Override public void onResponse(Call<Map<String, String>> c, retrofit2.Response<Map<String, String>> r) {}
            @Override public void onFailure(Call<Map<String, String>> c, Throwable t) {}
        });
    }

    private void buildItems(List<ChatMsgDto> msgs) {
        items.clear();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        // 1. 先按时间升序排序，确保最新消息在底部
        List<ChatMsgDto> sorted = new ArrayList<>(msgs);
        Collections.sort(sorted, (a, b) -> {
            String ta = a.getCreatedAt();
            String tb = b.getCreatedAt();
            if (ta == null) return -1;
            if (tb == null) return 1;
            return ta.compareTo(tb);
        });

        // 2. 考试/作业卡片按 bizId 去重：同一 examId 只保留最新一条
        Map<Long, ChatMsgDto> examCardMap = new LinkedHashMap<>();
        List<ChatMsgDto> filtered = new ArrayList<>();
        for (ChatMsgDto msg : sorted) {
            parseMsgType(msg);
            if ("exam_card".equals(msg.msgType) || "exam_publish".equals(msg.getBizType())) {
                Long bizId = msg.getBizId() != null ? msg.getBizId() : msg.examId;
                if (bizId == null) {
                    filtered.add(msg);
                    continue;
                }
                ChatMsgDto existing = examCardMap.get(bizId);
                if (existing == null || msg.getCreatedAt() == null ||
                        (existing.getCreatedAt() != null && msg.getCreatedAt().compareTo(existing.getCreatedAt()) > 0)) {
                    examCardMap.put(bizId, msg);
                }
            } else {
                filtered.add(msg);
            }
        }
        filtered.addAll(examCardMap.values());

        // 3. 再次整体按时间升序排序，避免考试卡片被固定追加到最后导致顺序错乱
        Collections.sort(filtered, (a, b) -> {
            String ta = a.getCreatedAt();
            String tb = b.getCreatedAt();
            if (ta == null) return -1;
            if (tb == null) return 1;
            return ta.compareTo(tb);
        });

        // 4. 添加时间分隔符并构建 items
        Date prevTime = null;
        for (ChatMsgDto msg : filtered) {
            Date cur = null;
            try { cur = sdf.parse(msg.getCreatedAt()); } catch (ParseException ignored) {}
            if (prevTime == null && cur != null) {
                items.add(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(cur));
            } else if (prevTime != null && cur != null
                    && (cur.getTime() - prevTime.getTime()) > 10000) {
                items.add(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(cur));
            }
            // 注意：不要再次调用 parseMsgType，前面排序前已解析过；
            // 重复解析会因 content 已被改写（如 [图片]）而错误地回退成 text 类型
            items.add(msg);
            prevTime = cur;
        }
        adapter.notifyDataSetChanged();
        // 延迟滚动到底部，确保 RecyclerView 已完成布局
        rvChat.post(() -> {
            if (!items.isEmpty()) rvChat.scrollToPosition(items.size() - 1);
        });
    }

    // ===== @ 提及提示 =====
    private void setupMentionPopup() {
        // 默认先展示 AI，随后从通讯录接口加载教师/同学
        mentionItems.add(new MentionItem("AI", "AI 助教", "智能问答"));

        View popupView = getLayoutInflater().inflate(R.layout.popup_mention, null);
        RecyclerView rv = popupView.findViewById(R.id.rv_mentions);
        rv.setLayoutManager(new LinearLayoutManager(this));
        mentionAdapter = new MentionAdapter(mentionItems, item -> {
            insertMention(item);
            hideMentionPopup();
        });
        rv.setAdapter(mentionAdapter);

        mentionPopup = new android.widget.PopupWindow(
                popupView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        mentionPopup.setOutsideTouchable(true);
        mentionPopup.setFocusable(false);

        etInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                checkAndShowMentionPopup(s.toString());
            }
        });

        loadMentionItems();
    }

    /** 从通讯录接口加载教师和同学，与 AI 一起组成 @ 候选列表 */
    @SuppressWarnings("unchecked")
    private void loadMentionItems() {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getStudentContacts(token).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                if (!resp.isSuccessful() || resp.body() == null) return;
                List<MentionItem> loaded = new ArrayList<>();
                // AI 始终置顶
                loaded.add(new MentionItem("AI", "AI 助教", "智能问答"));

                List<Map<String, Object>> teachers = (List<Map<String, Object>>) resp.body().get("teachers");
                if (teachers != null) {
                    for (Map<String, Object> item : teachers) {
                        String id = item.get("teacherId") != null ? item.get("teacherId").toString() : "";
                        String name = item.get("realName") != null ? item.get("realName").toString() : "";
                        String title = item.get("title") != null ? item.get("title").toString() : "";
                        String courses = item.get("courseNames") != null ? item.get("courseNames").toString() : "";
                        if (name.isEmpty()) continue;
                        String tag = (title.isEmpty() ? "" : title + " · ") + courses;
                        loaded.add(new MentionItem(id, name, name, tag, "teacher",
                                name.substring(0, 1)));
                    }
                }

                List<Map<String, Object>> classmates = (List<Map<String, Object>>) resp.body().get("classmates");
                if (classmates != null) {
                    for (Map<String, Object> item : classmates) {
                        String id = item.get("userId") != null ? item.get("userId").toString() : "";
                        String name = item.get("realName") != null ? item.get("realName").toString() : "";
                        String no = item.get("studentNo") != null ? item.get("studentNo").toString() : "";
                        if (name.isEmpty()) continue;
                        loaded.add(new MentionItem(id, name, name, "学号: " + (no.isEmpty() ? "暂无" : no),
                                "student", name.substring(0, 1)));
                    }
                }

                mentionItems.clear();
                mentionItems.addAll(loaded);
                if (mentionAdapter != null) {
                    mentionAdapter.setItems(loaded);
                }
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Log.w(TAG, "加载通讯录失败，@提示仅展示 AI", t);
            }
        });
    }

    private void checkAndShowMentionPopup(String text) {
        int cursor = etInput.getSelectionStart();
        if (cursor < 0) cursor = 0;
        int atPos = text.lastIndexOf('@', cursor);
        if (atPos < 0) {
            hideMentionPopup();
            return;
        }
        // 只有光标紧跟在 @ 后面，且 @ 后面还没有空格时才提示
        String afterAt = text.substring(atPos + 1, cursor);
        if (afterAt.contains(" ") || afterAt.contains("\n")) {
            hideMentionPopup();
            return;
        }
        // @ 前面必须是开头或空白
        if (atPos > 0) {
            char before = text.charAt(atPos - 1);
            if (!Character.isWhitespace(before)) {
                hideMentionPopup();
                return;
            }
        }
        // 根据 @ 后的关键字过滤列表，无匹配时不显示
        if (mentionAdapter != null) {
            mentionAdapter.filter(afterAt);
            if (mentionAdapter.getItemCount() == 0) {
                hideMentionPopup();
                return;
            }
        }
        showMentionPopup();
    }

    private void showMentionPopup() {
        if (mentionPopup == null || mentionPopup.isShowing()) return;
        etInput.post(() -> {
            View content = mentionPopup.getContentView();
            int width = etInput.getWidth();
            int maxPopupHeight = (int) (220 * getResources().getDisplayMetrics().density + 0.5f);
            content.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxPopupHeight, View.MeasureSpec.AT_MOST)
            );
            int popupHeight = content.getMeasuredHeight();
            mentionPopup.setWidth(width);
            mentionPopup.setHeight(popupHeight);
            int offsetY = -(etInput.getHeight() + popupHeight);
            mentionPopup.showAsDropDown(etInput, 0, offsetY);
        });
    }

    private void hideMentionPopup() {
        if (mentionPopup != null && mentionPopup.isShowing()) {
            mentionPopup.dismiss();
        }
    }

    private void insertMention(MentionItem item) {
        android.text.Editable editable = etInput.getText();
        int cursor = etInput.getSelectionStart();
        if (cursor < 0) cursor = 0;
        int atPos = editable.toString().lastIndexOf('@', cursor);
        if (atPos < 0) return;
        String replacement = "@" + item.name + " ";
        editable.replace(atPos, cursor, replacement);
        etInput.setSelection(atPos + replacement.length());
    }

    // ===== 发送消息入口：普通消息直接发送，@AI 才调用 AI 回答 =====
    private void handleSend() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;

        if (text.contains("@AI") || text.contains("@ai")) {
            sendToAI(text);
        } else {
            sendTextMessage(text);
        }
    }

    // ===== 发送普通文本消息到课程群聊 =====
    private void sendTextMessage(String text) {
        int localPos = items.size();
        items.add(makeLocalMsg(text, "student"));
        adapter.notifyItemInserted(localPos);
        rvChat.scrollToPosition(localPos);
        etInput.setText("");

        Map<String, String> body = courseBody();
        body.put("content", text);
        body.put("senderRole", "student");

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.sendChatMessage(token, body).enqueue(new Callback<ChatMsgDto>() {
            @Override
            public void onResponse(Call<ChatMsgDto> call, Response<ChatMsgDto> resp) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    runOnUiThread(() -> {
                        if (localPos < items.size()) {
                            ChatMsgDto m = (ChatMsgDto) items.get(localPos);
                            m.setContent(text + "\n（发送失败）");
                            adapter.notifyItemChanged(localPos);
                        }
                    });
                }
                // 发送成功由 WebSocket chat_update 触发 loadMessages 刷新列表
            }

            @Override
            public void onFailure(Call<ChatMsgDto> call, Throwable t) {
                Log.e(TAG, "发送消息失败", t);
                runOnUiThread(() -> {
                    if (localPos < items.size()) {
                        ChatMsgDto m = (ChatMsgDto) items.get(localPos);
                        m.setContent(text + "\n（网络错误）");
                        adapter.notifyItemChanged(localPos);
                    }
                });
            }
        });
    }

    // ===== 发送给 AI =====
    private void sendToAI(String text) {
        addStudentAndAiPlaceholder(text);

        Map<String, String> body = courseBody();
        body.put("content", text);

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.ragChat(token, body).enqueue(aiCallback(items.size() - 1));
    }

    private void addStudentAndAiPlaceholder(String text) {
        int studentPos = items.size();
        items.add(makeLocalMsg(text, "student"));
        items.add(makeLocalMsg("思考中...", "ai"));
        adapter.notifyItemRangeInserted(studentPos, 2);
        rvChat.scrollToPosition(studentPos + 1);
        etInput.setText("");
    }

    private Callback<ChatMsgDto> aiCallback(int aiPos) {
        final int finalPos = aiPos;
        return new Callback<ChatMsgDto>() {
            @Override
            public void onResponse(Call<ChatMsgDto> call, retrofit2.Response<ChatMsgDto> resp) {
                if (finalPos >= items.size()) return;
                final String fullText;
                final String createdAt;
                if (resp.isSuccessful() && resp.body() != null) {
                    fullText = resp.body().getContent();
                    createdAt = resp.body().getCreatedAt();
                } else {
                    fullText = "（AI 回复失败）";
                    createdAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                }
                final String finalCreatedAt = createdAt;
                // 流式逐字输出
                streamCharIdx = 0;
                streamFullText = fullText;
                streamMsgPos = finalPos;
                isStreaming = true;
                streamHandler.removeCallbacksAndMessages(null);
                streamHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isStreaming || streamMsgPos != finalPos) return;
                        if (finalPos >= items.size()) return;
                        ChatMsgDto m = (ChatMsgDto) items.get(finalPos);
                        if (streamCharIdx < streamFullText.length()) {
                            streamCharIdx = Math.min(streamCharIdx + STREAM_CHARS_PER_TICK, streamFullText.length());
                            m.setContent(streamFullText.substring(0, streamCharIdx) + "▌");
                            if (streamCharIdx == streamFullText.length()) {
                                m.setCreatedAt(finalCreatedAt);
                            }
                            adapter.notifyItemChanged(finalPos);
                            rvChat.scrollToPosition(finalPos);
                            streamHandler.postDelayed(this, STREAM_INTERVAL_MS);
                        } else {
                            // 完成
                            m.setContent(streamFullText);
                            m.setCreatedAt(finalCreatedAt);
                            adapter.notifyItemChanged(finalPos);
                            isStreaming = false;
                        }
                    }
                });
            }
            @Override
            public void onFailure(Call<ChatMsgDto> call, Throwable t) {
                Log.e(TAG, "AI请求失败", t);
                runOnUiThread(() -> {
                    if (finalPos < items.size()) {
                        ChatMsgDto m = (ChatMsgDto) items.get(finalPos);
                        m.setContent("（网络超时，请重试）");
                        adapter.notifyItemChanged(finalPos);
                    }
                });
                isStreaming = false;
            }
        };
    }

    private ChatMsgDto makeLocalMsg(String content, String role) {
        ChatMsgDto d = new ChatMsgDto();
        d.setContent(content);
        d.setSenderRole(role);
        d.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        return d;
    }

    // ===== 底部弹出：拍照 / 资料上传 =====
    private void showAddSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.layout_add_sheet, null);
        sheet.setContentView(view);

        view.findViewById(R.id.btn_camera).setOnClickListener(v -> {
            sheet.dismiss();
            openCameraOrGallery();
        });

        view.findViewById(R.id.btn_file).setOnClickListener(v -> {
            sheet.dismiss();
            openFilePicker();
        });

        sheet.show();
    }

    // 拍照或从相册选择
    private void openCameraOrGallery() {
        BottomSheetDialog d = new BottomSheetDialog(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 24, 40, 24);

        View camBtn = makeOptionBtn("拍照", v1 -> { d.dismiss(); dispatchTakePicture(); });
        View galBtn = makeOptionBtn("从相册选择", v2 -> { d.dismiss(); openGallery(); });
        ll.addView(camBtn);
        ll.addView(galBtn);
        d.setContentView(ll);
        d.show();
    }

    private View makeOptionBtn(String text, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(0xFF1D1D1F);
        tv.setPadding(24, 20, 24, 20);
        tv.setOnClickListener(listener);
        return tv;
    }

    // 拍照
    private void dispatchTakePicture() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            cameraFile = File.createTempFile("camera_", ".jpg", dir);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", cameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (IOException e) {
            Toast.makeText(this, "无法创建照片文件", Toast.LENGTH_SHORT).show();
        }
    }

    // 从相册选择
    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQ_CAMERA);
    }

    // 文件选择器
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimes = {"application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        startActivityForResult(intent, REQ_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        if (requestCode == REQ_CAMERA) {
            Uri uri = (data != null && data.getData() != null)
                    ? data.getData()
                    : (cameraFile != null ? FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", cameraFile) : null);
            if (uri != null) uploadFile(uri);
        } else if (requestCode == REQ_FILE) {
            if (data != null && data.getData() != null) uploadFile(data.getData());
        }
    }

    private void uploadFile(Uri uri) {
        int localPos = items.size();
        items.add(makeLocalMsg("正在上传文件...", "student"));
        adapter.notifyItemInserted(localPos);
        rvChat.scrollToPosition(localPos);

        new Thread(() -> {
            try {
                // 读取文件信息
                String fileName = "file";
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";

                // 从 Uri 获取文件名
                android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx);
                    cursor.close();
                }

                // 读取文件内容
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                byte[] bytes = new byte[is.available()];
                is.read(bytes);
                is.close();

                // 构建 Multipart
                RequestBody fileBody = RequestBody.create(bytes, MediaType.parse(mime));
                MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", fileName, fileBody);
                RequestBody coursePart = RequestBody.create(courseName, MediaType.parse("text/plain"));
                RequestBody courseIdPart = courseId > 0
                        ? RequestBody.create(String.valueOf(courseId), MediaType.parse("text/plain")) : null;

                // 1. 上传文件获取 URL（目录按课程ID隔离，同名课程不互相覆盖）
                ApiService api = RetrofitClient.getInstance().create(ApiService.class);
                retrofit2.Response<Map<String, String>> uploadResp =
                        api.uploadChatFile(token, filePart, coursePart, courseIdPart).execute();

                if (!uploadResp.isSuccessful() || uploadResp.body() == null) {
                    throw new RuntimeException("文件上传失败: " + uploadResp.code());
                }

                String fileUrl = uploadResp.body().get("url");
                String finalFileName = uploadResp.body().getOrDefault("fileName", fileName);
                // 图片与文件使用不同的消息协议前缀
                boolean isImage = mime != null && mime.startsWith("image/");
                String content = isImage ? "[image]" + fileUrl : "[file]" + finalFileName + "|" + fileUrl;

                // 2. 作为普通图片/文件消息发送到群聊
                Map<String, String> body = courseBody();
                body.put("content", content);
                body.put("senderRole", "student");
                retrofit2.Response<ChatMsgDto> sendResp = api.sendChatMessage(token, body).execute();

                runOnUiThread(() -> {
                    if (!sendResp.isSuccessful() || sendResp.body() == null) {
                        if (localPos < items.size()) {
                            ChatMsgDto m = (ChatMsgDto) items.get(localPos);
                            m.setContent("（文件发送失败）");
                            adapter.notifyItemChanged(localPos);
                        }
                    }
                    // 发送成功由 WebSocket chat_update 触发 loadMessages 刷新列表
                });
            } catch (Exception e) {
                Log.e(TAG, "文件上传失败", e);
                runOnUiThread(() -> {
                    if (localPos < items.size()) {
                        ChatMsgDto m = (ChatMsgDto) items.get(localPos);
                        m.setContent("（上传失败：" + e.getMessage() + "）");
                        adapter.notifyItemChanged(localPos);
                    }
                });
            }
        }).start();
    }

    // ===== WebSocket 实时通知 → 流式输出到对话 =====
    private void appendScheduleNotice(String content) {
        // 如果正在流式输出中，先结束上一次
        if (isStreaming) {
            streamHandler.removeCallbacksAndMessages(null);
            // 立即显示剩余文本
            if (streamMsgPos >= 0 && streamMsgPos < items.size()) {
                ChatMsgDto m = (ChatMsgDto) items.get(streamMsgPos);
                m.setContent(streamFullText);
                adapter.notifyItemChanged(streamMsgPos);
            }
            isStreaming = false;
        }

        // 插入新的通知消息（AI 角色，左对齐灰色气泡）
        int pos = items.size();
        ChatMsgDto msg = new ChatMsgDto();
        msg.setContent("");  // 先空，流式填充
        msg.setSenderRole("ai");
        msg.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        items.add(msg);
        adapter.notifyItemInserted(pos);
        rvChat.scrollToPosition(pos);

        // 启动流式输出
        streamMsgPos = pos;
        streamFullText = content;
        streamCharIdx = 0;
        isStreaming = true;
        streamNextChar();
    }

    private void streamNextChar() {
        if (!isStreaming || streamMsgPos < 0 || streamMsgPos >= items.size()) {
            isStreaming = false;
            return;
        }
        if (streamCharIdx < streamFullText.length()) {
            streamCharIdx = Math.min(streamCharIdx + STREAM_CHARS_PER_TICK, streamFullText.length());
            String partial = streamFullText.substring(0, streamCharIdx);
            ChatMsgDto m = (ChatMsgDto) items.get(streamMsgPos);
            m.setContent(partial);
            adapter.notifyItemChanged(streamMsgPos);
            // 最后一项时自动滚动
            if (streamMsgPos == items.size() - 1) {
                rvChat.scrollToPosition(streamMsgPos);
            }
            streamHandler.postDelayed(this::streamNextChar, STREAM_INTERVAL_MS);
        } else {
            isStreaming = false;
            // 流式输出完成 → 标记已读，消除红点
            markReadForCurrentCourse();
        }
    }

    /** 标记当前课程消息为已读（消除红点） */
    private void markReadForCurrentCourse() {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.markAsRead(token, courseBody()).enqueue(new Callback<Map<String, String>>() {
            @Override public void onResponse(Call<Map<String, String>> c, retrofit2.Response<Map<String, String>> r) {}
            @Override public void onFailure(Call<Map<String, String>> c, Throwable t) {}
        });
    }

    // ===== Adapter：微信群聊风格（头像 + 左右气泡） =====
    private static class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_TIME = 0, TYPE_TEXT = 1, TYPE_IMAGE = 2, TYPE_FILE = 3, TYPE_EXAM_CARD = 4;
        private final List<Object> data;
        // 用于通知点击跳转答题页
        private final android.content.Context activityContext;
        // 考试/作业状态缓存（examId -> status）
        private final Map<Long, Map<String, Object>> examStatusMap;
        ChatAdapter(List<Object> d) { data = d; activityContext = null; examStatusMap = null; }
        ChatAdapter(List<Object> d, android.content.Context ctx, Map<Long, Map<String, Object>> statusMap) {
            data = d; activityContext = ctx; examStatusMap = statusMap;
        }

        @Override public int getItemViewType(int p) {
            Object o = data.get(p);
            if (o instanceof ChatMsgDto) {
                ChatMsgDto m = (ChatMsgDto) o;
                if ("image".equals(m.msgType)) return TYPE_IMAGE;
                if ("file".equals(m.msgType)) return TYPE_FILE;
                if ("exam_card".equals(m.msgType)) return TYPE_EXAM_CARD;
                return TYPE_TEXT;
            }
            return TYPE_TIME;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            if (vt == TYPE_TIME) {
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(12); tv.setTextColor(0xFF86868B);
                tv.setGravity(Gravity.CENTER); tv.setPadding(0, 12, 0, 4);
                tv.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new TimeVH(tv);
            }
            if (vt == TYPE_IMAGE) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_image, parent, false);
                return new ImgVH(v,
                        v.findViewById(R.id.tv_avatar_left),
                        v.findViewById(R.id.tv_avatar_right),
                        v.findViewById(R.id.tv_sender),
                        v.findViewById(R.id.iv_message),
                        v.findViewById(R.id.ll_message));
            }
            if (vt == TYPE_FILE) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_file, parent, false);
                return new FileVH(v,
                        v.findViewById(R.id.tv_avatar_left),
                        v.findViewById(R.id.tv_avatar_right),
                        v.findViewById(R.id.tv_sender),
                        v.findViewById(R.id.tv_file_name),
                        v.findViewById(R.id.tv_file_icon),
                        v.findViewById(R.id.ll_file_card),
                        v.findViewById(R.id.ll_message));
            }
            if (vt == TYPE_EXAM_CARD) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_exam_card, parent, false);
                return new ExamCardVH(v,
                        v.findViewById(R.id.tv_avatar_left),
                        v.findViewById(R.id.tv_avatar_right),
                        v.findViewById(R.id.tv_sender),
                        v.findViewById(R.id.ll_message),
                        v.findViewById(R.id.tv_exam_title),
                        v.findViewById(R.id.tv_exam_status),
                        v.findViewById(R.id.tv_exam_action),
                        v.findViewById(R.id.tv_exam_icon),
                        v.findViewById(R.id.card_container));
            }
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_text, parent, false);
            // 消息气泡最大宽度 = 屏宽 80%（含公式图片的长消息也能尽量利用宽度）
            TextView msgTv = v.findViewById(R.id.tv_message);
            msgTv.setMaxWidth((int) (parent.getContext().getResources().getDisplayMetrics().widthPixels * 0.8f));
            return new TextVH(v,
                    v.findViewById(R.id.tv_avatar_left),
                    v.findViewById(R.id.tv_avatar_right),
                    v.findViewById(R.id.tv_sender),
                    v.findViewById(R.id.tv_message),
                    v.findViewById(R.id.ll_message),
                    v.findViewById(R.id.ll_link_card),
                    v.findViewById(R.id.tv_link_title),
                    v.findViewById(R.id.tv_link_url));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            if (h instanceof TimeVH) { ((TimeVH)h).tv.setText((String)data.get(p)); return; }
            ChatMsgDto m = (ChatMsgDto) data.get(p);
            boolean me = "student".equals(m.getSenderRole());
            String avatar = avatarText(m);
            String sender = senderLabel(m);

            if (h instanceof ImgVH) {
                ImgVH vh = (ImgVH) h;
                bindAvatar(vh.leftAvatar, vh.rightAvatar, avatar, me);
                vh.tvSender.setText(me ? "" : sender);
                vh.tvSender.setVisibility(me ? View.GONE : View.VISIBLE);
                alignMessageContainer(vh.llMsg, me);

                String url = RetrofitClient.toFullUrl(m.imageUrl);
                final String finalUrl = url;
                vh.iv.setTag(finalUrl);
                if (finalUrl != null && !finalUrl.isEmpty()) {
                    com.bumptech.glide.Glide.with(vh.iv.getContext())
                            .load(finalUrl)
                            .placeholder(R.drawable.bg_placeholder_image)
                            .error(R.drawable.bg_error_image)
                            .fitCenter()
                            .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL, 600)
                            .into(vh.iv);
                }
                vh.iv.setOnClickListener(v -> {
                    if (finalUrl == null || finalUrl.isEmpty()) return;
                    Intent intent = new Intent(v.getContext(), ImagePreviewActivity.class);
                    intent.putExtra("image_url", finalUrl);
                    v.getContext().startActivity(intent);
                });
                return;
            }

            if (h instanceof FileVH) {
                FileVH vh = (FileVH) h;
                bindAvatar(vh.leftAvatar, vh.rightAvatar, avatar, me);
                vh.tvSender.setText(me ? "" : sender);
                vh.tvSender.setVisibility(me ? View.GONE : View.VISIBLE);
                alignMessageContainer(vh.llMsg, me);

                String fileName = m.fileName != null ? m.fileName : "文件";
                vh.tvFileName.setText(fileName);
                vh.tvFileName.setTextColor(me ? Color.WHITE : 0xFF1D1D1F);
                vh.tvFileIcon.setText(fileTypeLabel(fileName));
                vh.tvFileIcon.setTextColor(me ? 0xFF0A84FF : 0xFF0A84FF);

                vh.card.setOnClickListener(v -> {
                    String furl = RetrofitClient.toFullUrl(m.fileUrl);
                    if (furl == null || furl.isEmpty() || "#".equals(furl)) {
                        Toast.makeText(v.getContext(), "文件链接无效", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(furl));
                        v.getContext().startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(v.getContext(), "无法打开文件：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
                // 给文件卡片应用发送者对应的气泡背景
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setCornerRadius(dp(vh.card.getContext(), 16));
                cardBg.setColor(me ? 0xFF0A84FF : 0xFFF5F5F7);
                vh.card.setBackground(cardBg);
                return;
            }

            if (h instanceof ExamCardVH) {
                ExamCardVH vh = (ExamCardVH) h;
                bindAvatar(vh.leftAvatar, vh.rightAvatar, avatar, me);
                vh.tvSender.setText(me ? "" : sender);
                vh.tvSender.setVisibility(me ? View.GONE : View.VISIBLE);
                alignMessageContainer(vh.llMsg, me);

                vh.tvTitle.setText(m.examTitle != null ? m.examTitle : "考试/作业");
                String actionText = "exam".equals(m.examType) ? "去考试 →" : "去写作业 →";
                vh.tvAction.setText(actionText);
                vh.tvIcon.setText("exam".equals(m.examType) ? "试" : "作");

                // 优先用 course-todos 接口返回的真实状态（是否答过、分数）覆盖卡片状态
                // course-todos 只返回 status=1 且未结束的已发布项；若记录缺失说明已删除/已下架/已结束/草稿
                Map<String, Object> status = m.examId != null && examStatusMap != null ? examStatusMap.get(m.examId) : null;
                int submitStatus = status != null && status.get("submitStatus") instanceof Number
                        ? ((Number) status.get("submitStatus")).intValue() : 0;
                Object scoreObj = status != null ? status.get("score") : null;
                int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : -1;
                boolean isActive = status != null;

                if (submitStatus == 2) {
                    vh.tvStatus.setText(score >= 0 ? "已完成 · " + score + "分" : "已完成");
                    vh.tvAction.setText("查看详情 →");
                } else if (submitStatus == 1) {
                    vh.tvStatus.setText("继续作答");
                } else if (!isActive) {
                    // 未提交且不在可作答列表中：教师已删除、已下架、已结束或未发布
                    vh.tvStatus.setText("暂不可作答");
                    vh.tvAction.setText("查看状态 →");
                } else {
                    vh.tvStatus.setText(m.examStatus != null ? m.examStatus : "点击开始作答");
                }

                vh.cardContainer.setOnClickListener(v -> openExamFromNotice(m));
                return;
            }

            TextVH vh = (TextVH) h;
            bindAvatar(vh.leftAvatar, vh.rightAvatar, avatar, me);
            vh.tvSender.setText(me ? "" : sender);
            vh.tvSender.setVisibility(me ? View.GONE : View.VISIBLE);
            alignMessageContainer(vh.llMsg, me);
            RichTextRenderer.set(vh.tv, m.getContent());
            vh.tv.setLineSpacing(4f, 1f);
            applyBubbleStyle(vh.tv, me);

            // 文本消息自动识别 URL 链接
            String firstUrl = autoLinkify(vh.tv);
            bindLinkCard(vh.llLinkCard, vh.tvLinkTitle, vh.tvLinkUrl, firstUrl, me);
        }

        /** 自动识别文本中的 http/https/www 链接并设置为可点击，返回第一个链接 */
        private String autoLinkify(TextView tv) {
            String text = tv.getText().toString();
            // 同时匹配 http(s):// 和 www.xxx.com 形式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?:https?://|www\\.)[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                tv.setMovementMethod(null);
                return null;
            }

            String firstUrl = matcher.group();
            android.text.SpannableString sp = new android.text.SpannableString(text);
            matcher.reset();
            while (matcher.find()) {
                final String rawUrl = matcher.group();
                final String url = rawUrl.startsWith("http") ? rawUrl : "https://" + rawUrl;
                android.text.style.ClickableSpan span = new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(@NonNull android.view.View widget) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        widget.getContext().startActivity(intent);
                    }
                };
                sp.setSpan(span, matcher.start(), matcher.end(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF0A84FF),
                        matcher.start(), matcher.end(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(sp);
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            return firstUrl;
        }

        /** 绑定链接预览卡片 */
        private void bindLinkCard(LinearLayout card, TextView tvTitle, TextView tvUrl, String url, boolean me) {
            if (url == null || url.isEmpty()) {
                card.setVisibility(View.GONE);
                return;
            }
            card.setVisibility(View.VISIBLE);
            String displayUrl = url.startsWith("http") ? url : "https://" + url;
            String domain = extractDomain(displayUrl);
            tvTitle.setText("打开链接");
            tvUrl.setText(domain);
            card.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(displayUrl));
                v.getContext().startActivity(intent);
            });
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(card.getContext(), 12));
            bg.setColor(0xFFE8F1FE);
            card.setBackground(bg);
        }

        private String extractDomain(String url) {
            try {
                java.net.URI uri = new java.net.URI(url);
                String host = uri.getHost();
                return host != null ? host : url;
            } catch (Exception e) {
                return url;
            }
        }

        /** 从考试/作业发布通知跳转到答题页面 */
        private void openExamFromNotice(ChatMsgDto m) {
            String type = "exam";
            String title = "";
            String content = m.getContent() == null ? "" : m.getContent();
            if (content.contains("【作业通知】")) type = "homework";
            // 从《标题》中提取考试/作业标题
            int start = content.indexOf('《');
            int end = content.indexOf('》');
            if (start >= 0 && end > start) {
                title = content.substring(start + 1, end);
            }
            Intent intent = new Intent(activityContext, ExamHomeworkActivity.class);
            intent.putExtra("exam_id", m.getBizId());
            intent.putExtra("exam_title", title);
            intent.putExtra("exam_type", type);
            intent.putExtra("course_name", m.getCourseName());
            activityContext.startActivity(intent);
        }

        private String avatarText(ChatMsgDto m) {
            if ("student".equals(m.getSenderRole())) return "我";
            if ("ai".equals(m.getSenderRole())) return "AI";
            String name = m.getSenderName();
            if (name != null && !name.isEmpty()) return name.substring(0, 1);
            return "?";
        }

        /** 根据文件名后缀返回文件类型简称（用于图标） */
        private String fileTypeLabel(String fileName) {
            if (fileName == null) return "文";
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".pdf")) return "PDF";
            if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "DOC";
            if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "XLS";
            if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "PPT";
            if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) return "ZIP";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) return "IMG";
            if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi")) return "VID";
            if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a")) return "AUD";
            if (lower.endsWith(".py")) return "PY";
            if (lower.endsWith(".java")) return "JAVA";
            if (lower.endsWith(".js")) return "JS";
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML";
            if (lower.endsWith(".txt")) return "TXT";
            int dot = fileName.lastIndexOf('.');
            return dot > 0 && dot < fileName.length() - 1
                    ? fileName.substring(dot + 1).toUpperCase()
                    : "文";
        }

        private String senderLabel(ChatMsgDto m) {
            if ("ai".equals(m.getSenderRole())) return "AI 助教";
            String name = m.getSenderName();
            if (name != null && !name.isEmpty()) return name;
            return "用户";
        }

        private void bindAvatar(TextView left, TextView right, String text, boolean me) {
            left.setVisibility(me ? View.GONE : View.VISIBLE);
            right.setVisibility(me ? View.VISIBLE : View.GONE);
            TextView active = me ? right : left;
            active.setText(text);
        }

        private void alignMessageContainer(LinearLayout ll, boolean me) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) ll.getLayoutParams();
            lp.gravity = me ? Gravity.END : Gravity.START;
            ll.setLayoutParams(lp);
            ll.setGravity(me ? Gravity.END : Gravity.START);
        }

        private void applyBubbleStyle(TextView tv, boolean me) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(tv.getContext(), 16));
            if (me) { bg.setColor(0xFF0A84FF); tv.setTextColor(Color.WHITE); }
            else { bg.setColor(0xFFF5F5F7); tv.setTextColor(0xFF1D1D1F); }
            tv.setBackground(bg);
        }

        @Override public int getItemCount() { return data.size(); }

        static class TextVH extends RecyclerView.ViewHolder {
            TextView leftAvatar, rightAvatar, tvSender, tv, tvLinkTitle, tvLinkUrl;
            LinearLayout llMsg, llLinkCard;
            TextVH(View v, TextView la, TextView ra, TextView s, TextView t, LinearLayout l,
                   LinearLayout lc, TextView lt, TextView lu) {
                super(v); leftAvatar=la; rightAvatar=ra; tvSender=s; tv=t; llMsg=l;
                llLinkCard=lc; tvLinkTitle=lt; tvLinkUrl=lu;
            }
        }
        static class ImgVH extends RecyclerView.ViewHolder {
            TextView leftAvatar, rightAvatar, tvSender;
            android.widget.ImageView iv;
            LinearLayout llMsg;
            ImgVH(View v, TextView la, TextView ra, TextView s, android.widget.ImageView i, LinearLayout l) {
                super(v); leftAvatar=la; rightAvatar=ra; tvSender=s; iv=i; llMsg=l;
            }
        }
        static class FileVH extends RecyclerView.ViewHolder {
            TextView leftAvatar, rightAvatar, tvSender, tvFileName, tvFileIcon;
            LinearLayout llMsg, card;
            FileVH(View v, TextView la, TextView ra, TextView s, TextView fn, TextView fi, LinearLayout c, LinearLayout l) {
                super(v); leftAvatar=la; rightAvatar=ra; tvSender=s; tvFileName=fn; tvFileIcon=fi; card=c; llMsg=l;
            }
        }
        static class ExamCardVH extends RecyclerView.ViewHolder {
            TextView leftAvatar, rightAvatar, tvSender;
            LinearLayout llMsg;
            TextView tvTitle, tvStatus, tvAction, tvIcon;
            android.view.View cardContainer;
            ExamCardVH(View v, TextView la, TextView ra, TextView s, LinearLayout l,
                       TextView title, TextView status, TextView action, TextView icon, android.view.View card) {
                super(v);
                leftAvatar=la; rightAvatar=ra; tvSender=s; llMsg=l;
                tvTitle=title; tvStatus=status; tvAction=action; tvIcon=icon; cardContainer=card;
            }
        }
        static class TimeVH extends RecyclerView.ViewHolder { TextView tv; TimeVH(TextView t) { super(t); tv=t; } }
        static int dp(android.content.Context c, int v) { return (int)(v*c.getResources().getDisplayMetrics().density+0.5f); }
    }
}
