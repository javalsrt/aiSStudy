package com.znxsgl.student.fragment;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.znxsgl.student.ChapterLearnActivity;
import com.znxsgl.student.MainActivity;
import com.znxsgl.student.RichTextRenderer;
import com.znxsgl.student.UiMsg;
import com.znxsgl.student.QuizPagerAdapter;
import com.znxsgl.student.R;
import com.znxsgl.student.model.QuizQuestion;
import com.znxsgl.student.model.StudentCourse;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.ResponseBody;
import org.json.JSONObject;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class FocusFragment extends Fragment {

    private TextView tvTimerCorner, tvToday, tvLast;
    /** 课程纸卡舞台：默认从左上角往下依次铺开，可自由拖动 */
    private View paperBoard;
    /** 课程 id → 纸卡，用于恢复拖动位置与层级 */
    private final Map<Long, com.znxsgl.student.widget.CoursePaperView> paperCards = new LinkedHashMap<>();
    /** 纸卡层级：列表末尾显示在最上方（最后拖动的那张） */
    private final List<Long> paperZOrder = new ArrayList<>();
    /** 课程 id → 「第X章 名称」；没有学习记录的课程不放，卡片上不显示 */
    private final Map<Long, String> recentChapterText = new HashMap<>();
    private long lastRecentChapterAt;
    /** 本轮卡片还没铺好时置 true，等布局完成后再摆位置 */
    private boolean paperLayoutPending;
    private View llQuizArea;
    private ViewPager2 viewPagerQuiz;
    private android.widget.ProgressBar quizProgress;
    private com.znxsgl.student.widget.SegmentedProgressView quizSegments;
    private TextView tvQuizCounter;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int seconds;
    private boolean running, quizActive;
    private String startTime, token;
    private SharedPreferences prefs;

    private List<QuizQuestion> quizQuestions = new ArrayList<>();
    private QuizPagerAdapter quizAdapter;
    private long lastPageEntryTime;
    private static final int MAX_QUESTION_DURATION_SEC = 600;
    private int lastPageIndex = -1;
    private Long latestSessionId;
    private long currentQuizCourseId; // 本次答题所属课程，报告页据此拉取 BKT 掌握度
    private int quizPollAttempt; // 异步出题轮询次数（上限 90 次 ≈ 3 分钟）

    private final List<Object> panelData = new ArrayList<>();

    private final Runnable tick = () -> { seconds++; if (tvTimerCorner != null) tvTimerCorner.setText(formatDuration(seconds)); handler.postDelayed(this.tick, 1000); };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_focus, container, false);
        try {
            prefs = requireActivity().getSharedPreferences("znxsgl", 0);
            token = "Bearer " + prefs.getString("token", "");

            View backBtn = view.findViewById(R.id.btn_back);
            if (backBtn != null) backBtn.setVisibility(View.GONE);

            tvTimerCorner = view.findViewById(R.id.tv_timer_corner);
            paperBoard = view.findViewById(R.id.paper_board);
            View resetBtn = view.findViewById(R.id.btn_paper_reset);
            if (resetBtn != null) resetBtn.setOnClickListener(v -> resetPaperLayoutWithAnimation());
            if (paperBoard != null) {
                // 缓存数据在首次布局前就渲染时，等测量完成再摆卡片位置
                paperBoard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    if (paperLayoutPending) applyPaperLayout();
                });
            }
            llQuizArea = view.findViewById(R.id.ll_quiz_area);
            viewPagerQuiz = view.findViewById(R.id.viewpager_quiz);
            quizProgress = view.findViewById(R.id.quiz_progress);
            quizSegments = view.findViewById(R.id.quiz_segments);
            tvQuizCounter = view.findViewById(R.id.tv_quiz_counter);
            applySystemBarInsets(view);
            applyQuizHeaderInset(view);
            View quizClose = view.findViewById(R.id.btn_quiz_close);
            if (quizClose != null) quizClose.setOnClickListener(v -> {
                if (llQuizArea != null) llQuizArea.setVisibility(View.GONE);
                if (paperBoard != null) paperBoard.setVisibility(View.VISIBLE);
                setNavVisible(true);
                setQuizStatusBar(false);
            });

            // ViewPager2 内部的 RecyclerView 默认可获得焦点：软键盘弹起或窗口尺寸变化时，
            // 它会把焦点从解析题/填空题的输入框抢走。焦点不在 EditText 上时，退格键事件
            // 会发给 RecyclerView 并被丢弃，表现为「能输入文字但删不掉」。
            // 这里禁止翻页容器自己获焦，焦点只交给页面里的 EditText。
            if (viewPagerQuiz != null) {
                viewPagerQuiz.setFocusable(false);
                viewPagerQuiz.setFocusableInTouchMode(false);
                View pagerInner = viewPagerQuiz.getChildAt(0);
                if (pagerInner != null) {
                    pagerInner.setFocusable(false);
                    pagerInner.setFocusableInTouchMode(false);
                }
            }

            // 长按提交按钮：按满触发提交，中途松手会中断动画
            com.znxsgl.student.widget.HoldSubmitButton btnDone = view.findViewById(R.id.btn_quiz_done);
            if (btnDone != null) btnDone.setOnHoldSubmitListener(this::collectAndSubmit);

            loadStudentCourses(view);

            try {
                quizAdapter = new QuizPagerAdapter(quizQuestions);
                quizAdapter.setOnAnswerListener(new QuizPagerAdapter.OnAnswerListener() {
                    @Override public void onAnswered(int pos, String a) {
                        recordTime(pos);
                        // 「题目自动跳转」开启时，选择/判断题作答后自动滑到下一题；关闭则由用户手动滑动
                        boolean autoJump = prefs == null || prefs.getBoolean("quiz_auto_jump", true);
                        if (autoJump && viewPagerQuiz != null && pos + 1 < quizQuestions.size())
                            viewPagerQuiz.setCurrentItem(pos + 1, true);
                    }
                    @Override public void onAutoSkip(int pos) {}
                    @Override public void onInputFocused(EditText et) { keepAnswerFocus(et); }
                });
                if (viewPagerQuiz != null) {
                    viewPagerQuiz.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
                    viewPagerQuiz.setAdapter(quizAdapter);
                    viewPagerQuiz.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                        @Override public void onPageSelected(int pos) {
                            // 离开上一页时保存文本答案
                            if (lastPageIndex >= 0) {
                                saveTextAnswer(lastPageIndex);
                                recordTime(lastPageIndex);
                            }
                            lastPageIndex = pos; lastPageEntryTime = SystemClock.elapsedRealtime();
                            updateQuizProgress(pos);
                        }
                    });
                }
            } catch (Exception ignored) {}

            loadTodayTotal();
            showLastSession();
        } catch (Exception e) {
            Toast.makeText(RetrofitClient.safeContext(getContext()), "初始化异常", Toast.LENGTH_LONG).show();
        }
        return view;
    }

    // ===== 课程加载 =====

    /** 活动类课表条目：无章节不可刷题，学习页卡片中过滤（课表页仍正常展示） */
    private static boolean isActivityName(String name) {
        return "自习".equals(name) || "班会".equals(name) || "大扫除".equals(name);
    }

    // 切 Tab 节流：30 秒内复用已加载的课程，避免每次切换都等网络
    private long lastCoursesRefreshAt = 0;
    private static final long COURSES_REFRESH_MS = 30_000;

    /** 课程列表缓存键：按登录 token 区分账号，重新登录后自动使用新缓存 */
    private String coursesCacheKey() {
        return "focus_courses_cache_" + Integer.toHexString(token == null ? 0 : token.hashCode());
    }

    /** 冷启动时立即渲染上次成功拉取的课程卡片，把几秒的网络等待变成后台静默刷新 */
    private void renderCachedCourses() {
        if (prefs == null || !panelData.isEmpty()) return;
        try {
            String raw = prefs.getString(coursesCacheKey(), null);
            if (raw == null || raw.isEmpty()) return;
            List<StudentCourse> cached = new com.google.gson.Gson().fromJson(raw,
                    new com.google.gson.reflect.TypeToken<List<StudentCourse>>() {}.getType());
            if (cached == null) return;
            List<StudentCourse> valid = new ArrayList<>();
            for (StudentCourse c : cached) {
                if (c.getCourseName() != null && !isActivityName(c.getCourseName())) valid.add(c);
            }
            if (valid.isEmpty()) return;
            panelData.clear();
            panelData.addAll(valid);
            buildCoursePapers();
        } catch (Exception ignored) {
        }
    }

    private void loadCoursesThrottled() {
        long now = System.currentTimeMillis();
        if (!panelData.isEmpty() && now - lastCoursesRefreshAt < COURSES_REFRESH_MS) return;
        lastCoursesRefreshAt = now;
        loadStudentCourses(getView());
    }

    private void loadStudentCourses(View view) {
        // 冷启动先用上次缓存的课程卡片秒开，网络结果回来后再静默更新
        renderCachedCourses();
        loadRecentChapters(false);
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getStudentCourses(token).enqueue(new Callback<List<StudentCourse>>() {
            @Override public void onResponse(Call<List<StudentCourse>> call, Response<List<StudentCourse>> resp) {
                if (!isAdded()) return;
                // 请求失败时保留现有卡片，避免切页面时列表突然变空
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (panelData.isEmpty()) handler.post(() -> buildCoursePapers());
                    return;
                }
                List<StudentCourse> next = new ArrayList<>();
                for (StudentCourse c : resp.body()) {
                    if (c.getCourseName() == null) continue;
                    // 活动类条目（自习/班会/大扫除）只在课表展示，不作为可刷题课程卡片
                    if (isActivityName(c.getCourseName())) continue;
                    next.add(c);
                }
                // 成功拉取后写入本地缓存，下次冷启动直接渲染
                try {
                    prefs.edit().putString(coursesCacheKey(),
                            new com.google.gson.Gson().toJson(next)).apply();
                } catch (Exception ignored) {
                }
                // 数据未变化时不重建卡片列表（切 Tab 秒开，不再整页重排）
                boolean changed = next.size() != panelData.size();
                if (!changed) {
                    for (int i = 0; i < next.size(); i++) {
                        Object o = panelData.get(i);
                        if (!(o instanceof StudentCourse)) { changed = true; break; }
                        StudentCourse a = next.get(i), b = (StudentCourse) o;
                        if (a.getCourseId() != b.getCourseId()
                                || a.getUnreadCount() != b.getUnreadCount()
                                || !String.valueOf(a.getLastMessage()).equals(String.valueOf(b.getLastMessage()))) {
                            changed = true;
                            break;
                        }
                    }
                }
                if (!changed) return;
                panelData.clear();
                panelData.addAll(next);
                // 暂时下线「错题解析」「复习加强」入口卡片
                handler.post(() -> buildCoursePapers());
            }
            @Override public void onFailure(Call<List<StudentCourse>> call, Throwable t) {
                if (!isAdded()) return;
                // 已有缓存或已展示课程时，失败不再填充演示数据，避免覆盖真实缓存
                if (!panelData.isEmpty()) return;
                String[] def = {"Python程序设计","数据结构与算法基础","微信小程序设计",
                        "多媒体课件设计与开发","微课制作","高级网站技术",
                        "中国近现代史纲要","教师口语","教师书写技能",
                        "班主任工作技能训练","计算机综合实训","安全教育（六）",
                        "形势与政策"};
                for (String d : def) panelData.add(d);
                handler.post(() -> buildCoursePapers());
            }
        });
    }

    /** 学习页最多展示的课程纸卡数量 */
    private static final int MAX_PAPERS = 16;

    /** 学习页：把所有课程渲染成可自由拖动的纸质卡片，默认从左上角往下依次铺开 */
    private void buildCoursePapers() {
        if (!(paperBoard instanceof ViewGroup)) return;
        ViewGroup board = (ViewGroup) paperBoard;
        board.removeAllViews();
        paperCards.clear();
        int n = Math.min(panelData.size(), MAX_PAPERS);
        if (n == 0) return;
        int cardW = defaultCardW();
        int cardH = defaultCardH(cardW);
        for (int i = 0; i < n; i++) {
            Object item = panelData.get(i);
            com.znxsgl.student.widget.CoursePaperView card =
                    new com.znxsgl.student.widget.CoursePaperView(requireContext());
            String name = item instanceof StudentCourse
                    ? ((StudentCourse) item).getCourseName() : String.valueOf(item);
            String teacher = item instanceof StudentCourse
                    ? ((StudentCourse) item).getTeacherName() : "";
            long paperId = paperIdOf(item, i);
            card.bind(item, name, teacher);
            card.setRecentChapter(recentChapterText.get(paperId));
            card.setOnOpenListener(this::openCourseCard);
            card.setOnMovedListener(this::onPaperMoved);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER);
            board.addView(card, lp);
            paperCards.put(paperId, card);
            card.setRotation(0f);
            card.setRestElevation(dp(6));
        }
        paperLayoutPending = true;
        applyPaperLayout();
    }

    private static long paperIdOf(Object item, int index) {
        if (item instanceof StudentCourse) return ((StudentCourse) item).getCourseId();
        return -(index + 1L);
    }

    /** 纸卡的默认尺寸（用户长按角改过大小后，以保存的尺寸为准） */
    private int defaultCardW() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(228), screenW - dp(100));
    }

    private int defaultCardH(int cardW) {
        return Math.round(cardW * 0.74f);
    }

    /** 拉取每门课程最近学习的章节；短时间内不重复请求 */
    private void loadRecentChapters(boolean force) {
        if (!isAdded()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastRecentChapterAt < 10_000) return;
        lastRecentChapterAt = now;
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getRecentChapters(token).enqueue(new Callback<List<Map<String, Object>>>() {
            @Override public void onResponse(Call<List<Map<String, Object>>> call,
                                             Response<List<Map<String, Object>>> resp) {
                if (!isAdded() || !resp.isSuccessful() || resp.body() == null) return;
                recentChapterText.clear();
                for (Map<String, Object> row : resp.body()) {
                    try {
                        Object cid = row.get("courseId");
                        Object nameObj = row.get("chapterName");
                        if (!(cid instanceof Number) || nameObj == null) continue;
                        String name = String.valueOf(nameObj);
                        if (name.isEmpty()) continue;
                        Object no = row.get("chapterNo");
                        int chapterNo = no instanceof Number ? ((Number) no).intValue() : 0;
                        recentChapterText.put(((Number) cid).longValue(),
                                chapterNo > 0 ? "第" + chapterNo + "章 " + name : name);
                    } catch (Exception ignored) {
                    }
                }
                applyRecentChaptersToCards();
            }
            @Override public void onFailure(Call<List<Map<String, Object>>> call, Throwable t) {
            }
        });
    }

    /** 把最近章节文字刷到已有卡片上（不重建卡片，保留拖动位置） */
    private void applyRecentChaptersToCards() {
        for (Map.Entry<Long, com.znxsgl.student.widget.CoursePaperView> e : paperCards.entrySet()) {
            e.getValue().setRecentChapter(recentChapterText.get(e.getKey()));
        }
    }

    /** 每个账号单独保存卡片位置与层级，换账号互不影响 */
    private String paperPosKey() {
        return "paper_pos_" + Integer.toHexString(token == null ? 0 : token.hashCode());
    }

    private String paperZKey() {
        return "paper_z_" + Integer.toHexString(token == null ? 0 : token.hashCode());
    }

    /** 按保存的位置铺卡片；没保存过的卡片从左上角往下依次铺开，保证每个课程名都露出来 */
    private void applyPaperLayout() {
        if (!paperLayoutPending || !(paperBoard instanceof ViewGroup)) return;
        ViewGroup board = (ViewGroup) paperBoard;
        if (board.getWidth() <= 0 || paperCards.isEmpty()) return; // 等布局完成后再摆
        int contentW = board.getWidth() - board.getPaddingLeft() - board.getPaddingRight();
        int contentH = board.getHeight() - board.getPaddingTop() - board.getPaddingBottom();
        if (contentW <= 0 || contentH <= 0) return;
        paperLayoutPending = false;

        int defW = defaultCardW(), defH = defaultCardH(defW);
        int n = paperCards.size();
        int step = defaultStep(n, contentH, defH);
        float baseX = -(contentW - defW) / 2f;
        float baseY = -(contentH - defH) / 2f;

        Map<Long, float[]> saved = loadSavedPaperPositions();

        // 层级：保存的顺序在前（末尾在最上层），新课程按默认顺序补在后面。
        // 没有有效的位置记录时（首次使用或旧格式已被丢弃），层级也回到默认顺序
        paperZOrder.clear();
        if (!saved.isEmpty()) {
            for (Long id : loadSavedPaperOrder()) {
                if (paperCards.containsKey(id) && !paperZOrder.contains(id)) paperZOrder.add(id);
            }
        }
        for (Long id : paperCards.keySet()) {
            if (!paperZOrder.contains(id)) paperZOrder.add(id);
        }

        int i = 0;
        for (Long id : paperCards.keySet()) {
            com.znxsgl.student.widget.CoursePaperView card = paperCards.get(id);
            float[] pos = saved.get(id);
            // 长按角改过大小的卡片：恢复保存的宽高
            int w = defW, h = defH;
            if (pos != null && pos.length >= 4) {
                w = Math.max(dp(112), Math.round(pos[2]));
                h = Math.max(dp(84), Math.round(pos[3]));
            }
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp != null && (lp.width != w || lp.height != h)) {
                lp.width = w;
                lp.height = h;
                card.setLayoutParams(lp);
            }
            if (pos != null) {
                card.setTranslationX(pos[0]);
                card.setTranslationY(pos[1]);
            } else {
                card.setTranslationX(baseX);
                card.setTranslationY(baseY + i * step);
            }
            card.setRotation(0f);
            i++;
        }
        applyZOrder();
    }

    /** 默认铺开的纵向步长：既让每个课程名露出来，又保证所有卡片都落在可视区域内 */
    private int defaultStep(int n, int contentH, int cardH) {
        if (n <= 1) return 0;
        return Math.max(dp(30), Math.min(dp(46), (contentH - cardH - dp(8)) / (n - 1)));
    }

    /** 按 paperZOrder 重排层级：越靠后越在上层 */
    private void applyZOrder() {
        float density = getResources().getDisplayMetrics().density;
        for (int idx = 0; idx < paperZOrder.size(); idx++) {
            com.znxsgl.student.widget.CoursePaperView card = paperCards.get(paperZOrder.get(idx));
            if (card == null) continue;
            card.bringToFront();
            card.setRestElevation(dp(6) + idx * 0.7f * density);
        }
    }

    /** 拖动结束：这张卡置顶，并记住所有卡片的当前位置与层级 */
    private void onPaperMoved(com.znxsgl.student.widget.CoursePaperView card) {
        Long id = null;
        for (Map.Entry<Long, com.znxsgl.student.widget.CoursePaperView> e : paperCards.entrySet()) {
            if (e.getValue() == card) { id = e.getKey(); break; }
        }
        if (id == null) return;
        paperZOrder.remove(id);
        paperZOrder.add(id);
        applyZOrder();
        savePaperState();
    }

    private void savePaperState() {
        if (prefs == null || paperCards.isEmpty()) return;
        try {
            JSONObject pos = new JSONObject();
            for (Map.Entry<Long, com.znxsgl.student.widget.CoursePaperView> e : paperCards.entrySet()) {
                com.znxsgl.student.widget.CoursePaperView c = e.getValue();
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                org.json.JSONArray a = new org.json.JSONArray();
                a.put(c.getTranslationX());
                a.put(c.getTranslationY());
                a.put(lp != null ? lp.width : c.getWidth());
                a.put(lp != null ? lp.height : c.getHeight());
                pos.put(String.valueOf(e.getKey()), a);
            }
            org.json.JSONArray z = new org.json.JSONArray();
            for (Long id : paperZOrder) z.put((long) id);
            prefs.edit()
                    .putString(paperPosKey(), pos.toString())
                    .putString(paperZKey(), z.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private Map<Long, float[]> loadSavedPaperPositions() {
        Map<Long, float[]> map = new HashMap<>();
        if (prefs == null) return map;
        try {
            JSONObject obj = new JSONObject(prefs.getString(paperPosKey(), "{}"));
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                org.json.JSONArray a = obj.optJSONArray(k);
                // 旧版本只保存了坐标、没有宽高，是旧布局规则下的数据，直接丢弃
                if (a == null || a.length() < 4) continue;
                float[] v = new float[a.length()];
                for (int idx = 0; idx < a.length(); idx++) v[idx] = (float) a.optDouble(idx);
                map.put(Long.parseLong(k), v);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private List<Long> loadSavedPaperOrder() {
        List<Long> list = new ArrayList<>();
        if (prefs == null) return list;
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString(paperZKey(), "[]"));
            for (int i = 0; i < a.length(); i++) list.add(a.optLong(i));
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 右上角「+」：清掉用户摆放，所有卡片错峰飞回左上角依次铺开 */
    private void resetPaperLayoutWithAnimation() {
        if (!(paperBoard instanceof ViewGroup) || paperCards.isEmpty()) return;
        ViewGroup board = (ViewGroup) paperBoard;
        if (prefs != null) prefs.edit().remove(paperPosKey()).remove(paperZKey()).apply();
        paperZOrder.clear();
        paperZOrder.addAll(paperCards.keySet());

        int contentW = board.getWidth() - board.getPaddingLeft() - board.getPaddingRight();
        int contentH = board.getHeight() - board.getPaddingTop() - board.getPaddingBottom();
        if (contentW <= 0 || contentH <= 0) return;
        int defW = defaultCardW(), defH = defaultCardH(defW);
        int n = paperZOrder.size();
        int step = defaultStep(n, contentH, defH);
        float baseX = -(contentW - defW) / 2f;
        float baseY = -(contentH - defH) / 2f;

        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            com.znxsgl.student.widget.CoursePaperView card = paperCards.get(paperZOrder.get(i));
            if (card == null) continue;
            // 顺手把长按角改过的尺寸恢复默认
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp != null && (lp.width != defW || lp.height != defH)) {
                lp.width = defW;
                lp.height = defH;
                card.setLayoutParams(lp);
            }
            card.bringToFront();
            card.setRestElevation(dp(6) + i * 0.7f * density);
            card.animate()
                    .translationX(baseX)
                    .translationY(baseY + i * step)
                    .rotation(0f)
                    .setDuration(420)
                    .setStartDelay(i * 16L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    /** 点课程纸卡：直接进入该课程的章节 */
    private void openCourseCard(Object item) {
        if (!(item instanceof StudentCourse) || getContext() == null) return;
        StudentCourse c = (StudentCourse) item;
        startActivity(new Intent(getContext(), ChapterLearnActivity.class)
                .putExtra("courseId", c.getCourseId())
                .putExtra("courseName", c.getCourseName()));
    }

    private int dp(int val) {
        return (int) (val * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ===== 确认弹窗 → 加载 → 答题 =====

    private void showStartConfirm(String subject, long courseId) {
        if (!isAdded()) return;
        RetrofitClient.getInstance().create(ApiService.class)
                .getCompletedLessons(token, courseId).enqueue(new Callback<ResponseBody>() {
                    @Override public void onResponse(Call<ResponseBody> c, Response<ResponseBody> r) {
                        if (!isAdded()) return;
                        boolean canQuiz = true;
                        try {
                            if (r.isSuccessful() && r.body() != null) {
                                JSONObject obj = new JSONObject(r.body().string());
                                canQuiz = obj.optBoolean("canQuiz", true);
                            }
                        } catch (Exception ignored) {}
                        final boolean ok = canQuiz;
                        handler.post(() -> {
                            if (!ok) {
                                Toast.makeText(RetrofitClient.safeContext(getContext()), "请先学习章节内容再答题", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            new com.znxsgl.student.dialog.ConfirmCardDialog(getContext(),
                                    "开始练习", "是否开始学习「" + subject + "」？",
                                    "AI 将为「" + subject + "」生成一套练习题。",
                                    "否", "是", () -> startQuiz(subject, courseId)).show();
                        });
                    }
                    @Override public void onFailure(Call<ResponseBody> c, Throwable t) {
                        if (!isAdded()) return;
                        handler.post(() -> new com.znxsgl.student.dialog.ConfirmCardDialog(getContext(),
                                "开始练习", "是否开始学习「" + subject + "」？",
                                "AI 将为「" + subject + "」生成一套练习题。",
                                "否", "是", () -> startQuiz(subject, courseId)).show());
                    }
                });
    }

    private void startQuiz(String subject, long courseId) {
        if (!isAdded()) return;
        if ("错题解析".equals(subject)) {
            showWrongAnalysis();
            return;
        }
        currentQuizCourseId = courseId;
        com.znxsgl.student.dialog.FullscreenLoaderDialog loading =
                new com.znxsgl.student.dialog.FullscreenLoaderDialog(requireContext(),
                        com.znxsgl.student.dialog.FullscreenLoaderDialog.LoaderType.RARE_COW);
        loading.show();

        Map<String, Object> b = new HashMap<>();
        b.put("subject", subject);
        b.put("subjectType", subject.contains("思政")||subject.contains("人文")?"公共":"专业");
        b.put("courseId", courseId);
        // 异步出题：请求立即返回 taskId，不阻塞、不报"繁忙"，由 pollQuizResult 轮询取题
        RetrofitClient.getInstance().create(ApiService.class).generateQuiz(token, b).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                if (!isAdded()) return;
                if (r.isSuccessful() && r.body() != null && r.body().get("taskId") != null) {
                    quizPollAttempt = 0;
                    pollQuizResult(String.valueOf(r.body().get("taskId")), loading);
                } else {
                    loading.dismiss();
                    handler.post(() -> Toast.makeText(RetrofitClient.safeContext(getContext()), "出题启动失败", Toast.LENGTH_SHORT).show());
                }
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                loading.dismiss();
                handler.post(() -> Toast.makeText(RetrofitClient.safeContext(getContext()), "网络错误", Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 轮询异步出题结果：每 2 秒查一次，最多 90 次（3 分钟），完成后直接展示题目 */
    private void pollQuizResult(final String taskId, final com.znxsgl.student.dialog.FullscreenLoaderDialog loading) {
        RetrofitClient.getInstance().create(ApiService.class).getQuizResult(token, taskId).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                if (!isAdded()) return;
                if (r.isSuccessful() && r.body() != null) {
                    Map<String, Object> body = r.body();
                    String status = body.get("status") != null ? body.get("status").toString() : "";
                    if ("done".equals(status)) {
                        quizPollAttempt = 0;
                        loading.dismiss();
                        showQuizQuestions(body);
                    } else if ("error".equals(status)) {
                        quizPollAttempt = 0;
                        loading.dismiss();
                        String err = body.get("error") != null ? body.get("error").toString() : "出题失败，请稍后重试";
                        handler.post(() -> Toast.makeText(RetrofitClient.safeContext(getContext()), err, Toast.LENGTH_SHORT).show());
                    } else {
                        // pending：继续轮询
                        if (++quizPollAttempt > 90) {
                            quizPollAttempt = 0;
                            loading.dismiss();
                            handler.post(() -> Toast.makeText(RetrofitClient.safeContext(getContext()), "出题超时，请重试", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        handler.postDelayed(() -> pollQuizResult(taskId, loading), 2000);
                    }
                } else {
                    quizPollAttempt = 0;
                    loading.dismiss();
                    handler.post(() -> Toast.makeText(RetrofitClient.safeContext(getContext()), "查询出题状态失败", Toast.LENGTH_SHORT).show());
                }
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                if (!isAdded()) return;
                if (++quizPollAttempt > 90) {
                    quizPollAttempt = 0;
                    loading.dismiss();
                    handler.post(() -> Toast.makeText(RetrofitClient.safeContext(getContext()), "网络错误，请重试", Toast.LENGTH_SHORT).show());
                    return;
                }
                handler.postDelayed(() -> pollQuizResult(taskId, loading), 2000);
            }
        });
    }

    /** 展示异步返回的题目 */
    private void showQuizQuestions(Map<String, Object> body) {
        if (!isAdded() || body.get("questions") == null) return;
        Object sid = body.get("sessionId");
        if (sid instanceof Number) latestSessionId = ((Number) sid).longValue();
        handler.post(() -> {
            quizQuestions.clear();
            for (Map<String, Object> qm : (List<Map<String, Object>>) body.get("questions")) {
                QuizQuestion q = new QuizQuestion();
                q.setQuestionType((String)qm.get("questionType"));
                q.setSubject((String)qm.get("subject"));
                q.setQuestion((String)qm.get("question"));
                q.setOptions((List<String>)qm.get("options"));
                q.setCorrectAnswer((String)qm.get("correctAnswer"));
                q.setGraph((String)qm.get("graph"));
                quizQuestions.add(q);
            }
            quizAdapter.notifyDataSetChanged();
            // 重置到第1题：ViewPager2会保留上次测评停留的位置，不重置会导致新测试直接显示旧页（如第15题）
            if (viewPagerQuiz != null) viewPagerQuiz.setCurrentItem(0, false);
            updateQuizProgress(0);
            if (paperBoard != null) paperBoard.setVisibility(View.GONE);
            llQuizArea.setVisibility(View.VISIBLE);
            setQuizStatusBar(true);
            setNavVisible(false);   // 全屏答题：隐藏悬浮导航
            lastPageIndex=0; lastPageEntryTime=SystemClock.elapsedRealtime();
            quizActive = true;
        });
    }

    // ===== 采集 + 提交 =====

    /** 答题页是白底，状态栏跟随白色；退出答题恢复透明（露出整页虹彩背景） */
    private void setQuizStatusBar(boolean quiz) {
        if (getActivity() == null) return;
        android.view.Window window = getActivity().getWindow();
        window.setStatusBarColor(quiz ? 0xFFFFFFFF : android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightStatusBars(quiz);
        androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(quiz);
    }

    /** 背景是全屏的，内容区补回状态栏与系统导航栏的高度 */
    private void applySystemBarInsets(View root) {
        int navBar = systemBarHeight("navigation_bar_height");
        if (paperBoard != null) {
            paperBoard.setPadding(paperBoard.getPaddingLeft(),
                    paperBoard.getPaddingTop() + systemBarHeight("status_bar_height"),
                    paperBoard.getPaddingRight(),
                    paperBoard.getPaddingBottom() + navBar);
        }
        View submitBar = root.findViewById(R.id.quiz_submit_bar);
        if (submitBar != null) {
            submitBar.setPadding(submitBar.getPaddingLeft(), submitBar.getPaddingTop(),
                    submitBar.getPaddingRight(), submitBar.getPaddingBottom() + navBar);
        }
        // 「+」按钮贴在状态栏下方右侧
        View resetBtn = root.findViewById(R.id.btn_paper_reset);
        if (resetBtn != null && resetBtn.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) resetBtn.getLayoutParams();
            lp.topMargin = systemBarHeight("status_bar_height") + dp(8);
            resetBtn.setLayoutParams(lp);
        }
    }

    private int systemBarHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /** 更新顶部答题进度：分段进度条 + 题号（旧细线进度条保留同步） */
    private void updateQuizProgress(int pos) {
        int total = quizQuestions.size();
        if (quizSegments != null) quizSegments.setProgress(pos, total);
        if (tvQuizCounter != null) {
            tvQuizCounter.setText((pos + 1) + "/" + Math.max(total, 1));
        }
        if (quizProgress != null) {
            quizProgress.setProgress(total <= 0 ? 0 : (int) (((pos + 1) * 100f) / total));
        }
    }

    /** 顶部栏补状态栏高度，避免分段进度条被状态栏遮住 */
    private void applyQuizHeaderInset(View root) {
        View header = root.findViewById(R.id.quiz_header);
        if (header == null) return;
        int statusBar = 0;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) statusBar = getResources().getDimensionPixelSize(id);
        float density = getResources().getDisplayMetrics().density;
        int top = statusBar + (int) (6 * density);
        header.setPadding(header.getPaddingLeft(), top, header.getPaddingRight(), (int) (6 * density));
    }

    private void collectAndSubmit() {
        recordTime(lastPageIndex);
        // 解析/填空题的答案在 onPageSelected 已自动保存

        // 统计
        int emptyCount = 0, wrongCount = 0;
        for (QuizQuestion q : quizQuestions) {
            if (q.getUserAnswer() == null || q.getUserAnswer().isEmpty()) emptyCount++;
            else if (q.getCorrectAnswer() != null) {
                String ca = q.getCorrectAnswer(), ua = q.getUserAnswer();
                if (ua.contains("不会")) emptyCount++;
                else {
                    String c = ca.replace("*","").replaceAll("[A-D]\\.\\s*","").trim();
                    String u = ua.replace("*","").replaceAll("[A-D]\\.\\s*","").trim();
                    if (!c.equalsIgnoreCase(u)) wrongCount++;
                }
            }
        }
        String msg = "已答 " + (quizQuestions.size() - emptyCount) + "/" + quizQuestions.size();
        if (wrongCount > 0) msg += " | 错题 " + wrongCount;
        if (emptyCount > 0) msg += " | 空题 " + emptyCount;

        new com.znxsgl.student.dialog.ConfirmCardDialog(getContext(),
                "确认提交", msg,
                "确实要提交吗？未答题将标记为\"不会\"",
                "继续答题", "确认提交", () -> submitQuiz()).show();
    }

    private void submitQuiz() {
        for (QuizQuestion q : quizQuestions)
            if (q.getUserAnswer()==null||q.getUserAnswer().isEmpty()) q.setUserAnswer("不会");

        com.znxsgl.student.dialog.FullscreenLoaderDialog pd =
                new com.znxsgl.student.dialog.FullscreenLoaderDialog(requireContext(),
                        com.znxsgl.student.dialog.FullscreenLoaderDialog.LoaderType.RARE_COW);
        pd.show();

        List<Map<String, Object>> answers = new ArrayList<>();
        for (QuizQuestion q : quizQuestions) {
            Map<String, Object> a = new HashMap<>();
            a.put("questionType",q.getQuestionType()); a.put("subject",q.getSubject());
            a.put("question",q.getQuestion());
            a.put("userAnswer",q.getUserAnswer()); a.put("correctAnswer",q.getCorrectAnswer());
            a.put("durationSec",q.getDurationSec()); a.put("modifiedCount",q.getModifiedCount());
            answers.add(a);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId",latestSessionId); body.put("answers",answers);
        RetrofitClient.getInstance().create(ApiService.class).evaluateQuiz(token, body).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                if (!isAdded()) return;
                handler.post(() -> {
                    pd.dismiss();
                    int total = quizQuestions.size();
                    quizQuestions.clear(); quizAdapter.notifyDataSetChanged();
                    llQuizArea.setVisibility(View.GONE);
                    if (paperBoard != null) paperBoard.setVisibility(View.VISIBLE);
                    setNavVisible(true);
                    quizActive = false;
                    setQuizStatusBar(false);
                    if (r.isSuccessful() && r.body() != null) {
                        startQuizResult(r.body(), total);
                    } else {
                        Toast.makeText(RetrofitClient.safeContext(getContext()),"测评完成",Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                if (!isAdded()) return;
                handler.post(() -> {
                    pd.dismiss();
                    quizQuestions.clear(); quizAdapter.notifyDataSetChanged();
                    llQuizArea.setVisibility(View.GONE);
                    if (paperBoard != null) paperBoard.setVisibility(View.VISIBLE);
                    setNavVisible(true);
                    quizActive = false;
                    setQuizStatusBar(false);
                    Toast.makeText(RetrofitClient.safeContext(getContext()),"提交失败：" + UiMsg.network(t), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startQuizResult(Map<String, Object> data, int totalQuestions) {
        if (!isAdded()) return;
        Intent it = new Intent(getContext(), com.znxsgl.student.QuizResultActivity.class);

        int correct = num(data.get("correctCount"));
        int skip = num(data.get("skipCount"));
        int duration = num(data.get("totalDurationSec"));
        it.putExtra("correctCount", correct);
        it.putExtra("skipCount", skip);
        it.putExtra("totalQuestions", totalQuestions);
        it.putExtra("totalDurationSec", duration);
        it.putExtra("courseId", currentQuizCourseId);
        it.putExtra("sessionId", latestSessionId != null ? latestSessionId : 0L);

        Object s = data.get("suggestion");
        it.putExtra("suggestion", s != null ? s.toString() : "");

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = data.get("scores") instanceof Map
                ? (Map<String, Object>) data.get("scores") : null;
        it.putExtra("scores", scores != null ? new java.util.HashMap<>(scores) : null);

        it.putStringArrayListExtra("strengths", toStringList(data.get("strengths")));
        it.putStringArrayListExtra("weaknesses", toStringList(data.get("weaknesses")));
        it.putStringArrayListExtra("studyPlan", toStringList(data.get("study_plan")));

        // 本次错题详情，JSON 字符串传递
        Object wrongObj = data.get("wrongAnswers");
        String wrongJson = "";
        if (wrongObj instanceof List && !((List<?>) wrongObj).isEmpty()) {
            wrongJson = new com.google.gson.Gson().toJson(wrongObj);
        }
        it.putExtra("wrongAnswersJson", wrongJson);

        startActivityForResult(it, 1001);
    }

    private ArrayList<String> toStringList(Object obj) {
        ArrayList<String> list = new ArrayList<>();
        if (obj instanceof List) {
            for (Object o : (List<?>) obj) {
                if (o != null) list.add(o.toString());
            }
        }
        return list;
    }

    private int num(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        return 0;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == android.app.Activity.RESULT_OK && data != null) {
            String action = data.getStringExtra("action");
            if ("wrong_analysis".equals(action)) {
                showWrongAnalysis();
            } else if ("review".equals(action)) {
                startActivity(new Intent(getContext(), com.znxsgl.student.ReviewActivity.class));
            }
        }
    }

    // ===== 错题解析（AI聊天模式） =====

    /** 供 MainActivity 调用：是否正在答题 */
    public boolean isQuizActive() { return quizActive; }

    /**
     * 软键盘弹起时窗口会重新布局（adjustResize），ViewPager2 的内容页会被重新挂载，
     * 输入框的焦点随之被清空。焦点不在输入框时，退格键事件无人接收，表现为
     * 「能打字但删不掉」。这里在键盘可见、且输入框仍然挂载的情况下把焦点还给它。
     */
    private void keepAnswerFocus(EditText et) {
        View root = getView();
        if (et == null || root == null) return;
        root.postDelayed(() -> {
            if (!et.isAttachedToWindow() || et.isFocused()) return;
            androidx.core.view.WindowInsetsCompat insets =
                    androidx.core.view.ViewCompat.getRootWindowInsets(root);
            if (insets == null
                    || !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) return;
            et.requestFocus();
        }, 450);
    }

    /** 供 MainActivity 调用：取消答题 */
    public void cancelQuiz() {
        quizActive = false;
        quizQuestions.clear();
        if (quizAdapter != null) quizAdapter.notifyDataSetChanged();
        if (llQuizArea != null) llQuizArea.setVisibility(View.GONE);
        if (paperBoard != null) paperBoard.setVisibility(View.VISIBLE);
        setNavVisible(true);
    }

    /** 答题全屏控制：隐藏/恢复悬浮导航 */
    private void setNavVisible(boolean visible) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(visible);
        }
    }

    /**
     * 错题解析：加载数据 → Dialog(ViewPager2竖滑 + RecyclerView科目栏)
     */
    private void showWrongAnalysis() {
        if (!isAdded()) return;

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getWrongAnalysis(token, new HashMap<>()).enqueue(new Callback<Map<String, Object>>() {
            @Override public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                if (!isAdded()) return;

                if (resp.isSuccessful() && resp.body() != null) {
                    Map<String, Object> data = resp.body();
                    if (data.containsKey("error")) {
                        Toast.makeText(RetrofitClient.safeContext(getContext()), data.get("error").toString(), Toast.LENGTH_LONG).show();
                        return;
                    }

                    // 缓存标志
                    boolean fromCache = data.get("cached") instanceof Boolean && (Boolean) data.get("cached");

                    // 按科目分组
                    Map<String, List<WrongItem>> bySubj = new LinkedHashMap<>();
                    Object raw = data.get("bySubject");
                    if (raw instanceof List) {
                        for (Object o : (List<?>) raw) {
                            Map<String, Object> s = (Map<String, Object>) o;
                            String subj = str(s, "subject");
                            List<WrongItem> items = new ArrayList<>();
                            Object list = s.get("items");
                            if (list instanceof List) {
                                for (Object obj : (List<?>) list) {
                                    Map<String, Object> m = (Map<String, Object>) obj;
                                    WrongItem wi = new WrongItem();
                                    wi.subject = subj;
                                    wi.question = str(m, "question");
                                    wi.knowledge = str(m, "knowledge");
                                    wi.errorReason = str(m, "errorReason");
                                    wi.improve = str(m, "improve");
                                    Object aid = m.get("answerId");
                                    if (aid instanceof Number) wi.answerId = ((Number) aid).longValue();
                                    items.add(wi);
                                }
                            }
                            if (!items.isEmpty()) bySubj.put(subj, items);
                        }
                    }

                    if (bySubj.isEmpty()) {
                        Toast.makeText(RetrofitClient.safeContext(getContext()), fromCache ? "" : "暂无错题数据", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 首次分析才提示，缓存直接展示
                    if (!fromCache) {
                        Toast.makeText(RetrofitClient.safeContext(getContext()), "错题分析完成！", Toast.LENGTH_SHORT).show();
                    }
                    showAnalysisDialog(bySubj);
                } else {
                    Toast.makeText(RetrofitClient.safeContext(getContext()), "暂无错题数据", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                Toast.makeText(RetrofitClient.safeContext(getContext()), "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** ViewPager2 分析对话框 */
    private void showAnalysisDialog(Map<String, List<WrongItem>> bySubj) {
        if (!isAdded()) return;
        List<String> subjNames = new ArrayList<>(bySubj.keySet());
        Set<Integer> understoodIdx = new HashSet<>();
        Set<Integer> bookmarkIdx = new HashSet<>();

        View root = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_wrong_analysis, null);
        LinearLayout llSubjects = root.findViewById(R.id.ll_subjects);
        TextView tvPage = root.findViewById(R.id.tv_page);
        ViewPager2 vpQuestions = root.findViewById(R.id.vp_questions);
        TextView btnUnderstand = root.findViewById(R.id.btn_understand);
        TextView btnBookmark = root.findViewById(R.id.btn_bookmark);
        FrameLayout rootOverlay = root.findViewById(R.id.root_overlay);
        TextView btnClose = root.findViewById(R.id.btn_close);

        // 展平题目
        List<WrongItem> allItems = new ArrayList<>();
        int[] subjStarts = new int[subjNames.size()];
        int[] subjCounts = new int[subjNames.size()];
        int total = 0;
        for (int i = 0; i < subjNames.size(); i++) {
            subjStarts[i] = total;
            List<WrongItem> items = bySubj.get(subjNames.get(i));
            subjCounts[i] = items.size();
            allItems.addAll(items);
            total += items.size();
        }

        // 科目按钮
        for (int i = 0; i < subjNames.size(); i++) {
            String name = subjNames.get(i);
            TextView btn = new TextView(getContext());
            btn.setText(name + " (" + subjCounts[i] + ")");
            btn.setTextSize(13);
            btn.setPadding(16, 8, 16, 8);
            btn.setTextColor(Color.parseColor(i == 0 ? "#FFFFFF" : "#6E6E73"));
            btn.setBackgroundColor(Color.parseColor(i == 0 ? "#0A84FF" : "#F9F9FB"));
            final int si = i;
            btn.setOnClickListener(v -> {
                for (int j = 0; j < llSubjects.getChildCount(); j++) {
                    TextView child = (TextView) llSubjects.getChildAt(j);
                    child.setTextColor(Color.parseColor(j == si ? "#FFFFFF" : "#6E6E73"));
                    child.setBackgroundColor(Color.parseColor(j == si ? "#0A84FF" : "#F9F9FB"));
                }
                vpQuestions.setCurrentItem(subjStarts[si], false);
            });
            llSubjects.addView(btn);
        }

        // ViewPager
        vpQuestions.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        vpQuestions.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int vt) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext())
                        .inflate(R.layout.item_wrong_question, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                WrongItem it = allItems.get(pos);
                RichTextRenderer.set(h.itemView.findViewById(R.id.tv_question), it.question);
                ((TextView) h.itemView.findViewById(R.id.tv_error)).setText(it.errorReason);
                ((TextView) h.itemView.findViewById(R.id.tv_improve)).setText(it.improve);
            }
            @Override public int getItemCount() { return allItems.size(); }
        });

        // 页码
        final int[] lastSubj = {0};
        vpQuestions.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int pos) {
                int si = 0;
                for (int i = 0; i < subjNames.size(); i++) {
                    if (pos >= subjStarts[i] && (i == subjNames.size() - 1 || pos < subjStarts[i + 1])) {
                        si = i; break;
                    }
                }
                int inSubj = pos - subjStarts[si] + 1;
                tvPage.setText(subjNames.get(si) + "  " + inSubj + "/" + subjCounts[si]
                        + "  (共" + subjNames.size() + "科)");
                if (si != lastSubj[0]) {
                    lastSubj[0] = si;
                    for (int j = 0; j < llSubjects.getChildCount(); j++) {
                        TextView child = (TextView) llSubjects.getChildAt(j);
                    child.setTextColor(Color.parseColor(j == si ? "#FFFFFF" : "#6E6E73"));
                    child.setBackgroundColor(Color.parseColor(j == si ? "#0A84FF" : "#F9F9FB"));
                    }
                }
                // 刷新按钮状态
                refreshBtns(btnUnderstand, btnBookmark, pos, understoodIdx, bookmarkIdx);
            }
        });

        // 关闭逻辑（提前定义，后续 Dialog 会用）
        final android.app.Dialog[] dialogRef = {null};
        Runnable dismissDlg = () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
        };

        // 明白了（仅标记，关闭时统一提交）
        btnUnderstand.setOnClickListener(v -> {
            int pos = vpQuestions.getCurrentItem();
            if (understoodIdx.contains(pos)) understoodIdx.remove(pos);
            else understoodIdx.add(pos);
            refreshBtns(btnUnderstand, btnBookmark, pos, understoodIdx, bookmarkIdx);
        });

        // 收藏（仅标记，关闭时统一提交）
        btnBookmark.setOnClickListener(v -> {
            int pos = vpQuestions.getCurrentItem();
            if (bookmarkIdx.contains(pos)) bookmarkIdx.remove(pos);
            else bookmarkIdx.add(pos);
            refreshBtns(btnUnderstand, btnBookmark, pos, understoodIdx, bookmarkIdx);
        });

        // 左右滑动切科
        rootOverlay.setOnTouchListener(new android.view.View.OnTouchListener() {
            float swipeStartX;
            @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) swipeStartX = e.getX();
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    float dx = e.getX() - swipeStartX;
                    if (Math.abs(dx) > 80) {
                        int cur = vpQuestions.getCurrentItem();
                        int curSi = 0;
                        for (int i = 0; i < subjNames.size(); i++) {
                            if (cur >= subjStarts[i] && (i == subjNames.size() - 1 || cur < subjStarts[i + 1]))
                            { curSi = i; break; }
                        }
                        if (dx > 0 && curSi > 0) vpQuestions.setCurrentItem(subjStarts[curSi - 1], false);
                        else if (dx < 0 && curSi < subjNames.size() - 1) vpQuestions.setCurrentItem(subjStarts[curSi + 1], false);
                    }
                }
                return false;
            }
        });

        // 创建 Dialog
        android.app.Dialog dlg = new android.app.Dialog(getContext(),
                android.R.style.Theme_Translucent_NoTitleBar);
        dialogRef[0] = dlg;
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);
        dlg.setOnDismissListener(d -> {
            // 离开时提交：明白了
            for (int idx : understoodIdx) {
                if (idx < allItems.size()) {
                    WrongItem it = allItems.get(idx);
                    markUnderstood(it.question, it.answerId);
                }
            }
            // 离开时提交：收藏
            for (int idx : bookmarkIdx) {
                if (idx < allItems.size()) submitBookmark(allItems.get(idx));
            }
        });
        dlg.setOnShowListener(d -> {
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                // 彻底清除 DecorView 装饰（阴影/边框/圆角残留）
                w.getDecorView().setPadding(0, 0, 0, 0);
                w.getDecorView().setBackgroundColor(Color.TRANSPARENT);
                w.getDecorView().setOutlineProvider(null);
                w.getDecorView().setElevation(0);
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams();
                lp.copyFrom(w.getAttributes());
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.CENTER;
                lp.dimAmount = 0f;
                w.setAttributes(lp);
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });

        // 关闭按钮
        btnClose.setOnClickListener(v2 -> dismissDlg.run());

        // 点击黑色背景
        rootOverlay.setOnClickListener(v2 -> dismissDlg.run());

        // 阻止卡片内点击穿透
        root.findViewById(R.id.card_container).setOnClickListener(v2 -> {});

        dlg.show();
    }

    private void refreshBtns(TextView btnU, TextView btnB, int pos,
                             Set<Integer> understoodIdx, Set<Integer> bookmarkIdx) {
        if (understoodIdx.contains(pos)) {
            btnU.setText("✓ 已明白");
            btnU.setTextColor(Color.WHITE);
            btnU.setBackgroundColor(Color.parseColor("#34C759"));
        } else {
            btnU.setText("✓ 明白了");
            btnU.setTextColor(Color.parseColor("#8E8E93"));
            btnU.setBackgroundColor(Color.TRANSPARENT);
        }
        if (bookmarkIdx.contains(pos)) {
            btnB.setText("★ 已收藏");
            btnB.setTextColor(Color.parseColor("#0A84FF"));
        } else {
            btnB.setText("☆ 收藏");
            btnB.setTextColor(Color.parseColor("#1D1D1F"));
        }
    }

    private void submitBookmark(WrongItem it) {
        Map<String, String> b = new HashMap<>();
        b.put("question", it.question);
        b.put("subject", it.subject);
        b.put("questionType", "错题");
        b.put("knowledge", it.knowledge);
        b.put("errorReason", it.errorReason);
        b.put("improve", it.improve);
        if (it.answerId != null) b.put("answerId", String.valueOf(it.answerId));
        RetrofitClient.getInstance().create(ApiService.class)
                .toggleBookmark(token, b).enqueue(new Callback<Map<String, Object>>() {
                    @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                        if (r.isSuccessful() && r.body() != null && r.body().get("bookmarked") != null) {
                            boolean bm = (Boolean) r.body().get("bookmarked");
                            System.out.println("=== 收藏" + (bm ? "成功" : "取消"));
                        }
                    }
                    @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                        System.out.println("=== 收藏失败: " + (t != null ? t.getMessage() : ""));
                    }
                });
    }

    /** 标记明白 */
    private void markUnderstood(String question, Object answerId) {
        Map<String, String> b = new HashMap<>();
        b.put("question", question);
        if (answerId instanceof Number) b.put("answerId", String.valueOf(((Number) answerId).longValue()));
        RetrofitClient.getInstance().create(ApiService.class)
                .markUnderstood(token, b).enqueue(new Callback<Map<String, Object>>() {
                    @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {}
                    @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {}
                });
    }

    /** 切换收藏 */
    private void toggleBookmark(WrongItem it, final TextView btn) {
        Map<String, String> b = new HashMap<>();
        b.put("question", it.question);
        b.put("subject", it.subject);
        b.put("questionType", "错题");
        b.put("knowledge", it.knowledge);
        b.put("errorReason", it.errorReason);
        b.put("improve", it.improve);
        RetrofitClient.getInstance().create(ApiService.class)
                .toggleBookmark(token, b).enqueue(new Callback<Map<String, Object>>() {
                    @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                        if (r.isSuccessful() && r.body() != null) {
                            Boolean bm = (Boolean) r.body().get("bookmarked");
                            if (bm != null && !bm) btn.setText("☆ 收藏");
                        }
                    }
                    @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {}
                });
    }

    private android.graphics.drawable.GradientDrawable getTagBg() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(8);
        bg.setColor(Color.parseColor("#F2F2F7"));
        return bg;
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : "";
    }

    private static class WrongItem {
        String subject, question, knowledge, errorReason, improve;
        Long answerId;
    }

    // ===== 切 Tab 提醒 =====

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) stopAndSave();
        else {
            // 从其他 Tab 返回：课程列表 30 秒内复用缓存，避免每次切换都等网络
            loadCoursesThrottled();
            loadTodayTotal(); showLastSession();
            if (!quizActive && (llQuizArea==null||llQuizArea.getVisibility()!=View.VISIBLE))
                handler.postDelayed(this::autoStart, 3000);
        }
    }

    // ===== 计时 =====

    /** 翻页时自动保存文本输入框的答案 */
    private void saveTextAnswer(int pos) {
        if (pos < 0 || pos >= quizQuestions.size()) return;
        QuizQuestion q = quizQuestions.get(pos);
        String type = q.getQuestionType();
        if (!"解析".equals(type) && !"填空".equals(type)) return;
        // 通过 ViewPager2 内部 RecyclerView 获取 ViewHolder
        RecyclerView rv = (RecyclerView) viewPagerQuiz.getChildAt(0);
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        EditText et = null;
        if (vh instanceof QuizPagerAdapter.AnalysisVH) et = ((QuizPagerAdapter.AnalysisVH)vh).etAnswer;
        else if (vh instanceof QuizPagerAdapter.FillVH) et = ((QuizPagerAdapter.FillVH)vh).etAnswer;
        if (et != null) {
            String txt = et.getText().toString().trim();
            if (!txt.isEmpty()) q.setUserAnswer(txt);
        }
    }

    private void recordTime(int pos) {
        if (pos>=0 && pos<quizQuestions.size() && lastPageEntryTime>0) {
            QuizQuestion q = quizQuestions.get(pos);
            long elapsedMs = SystemClock.elapsedRealtime() - lastPageEntryTime;
            int addSec = (int) Math.max(0, Math.min(elapsedMs / 1000, MAX_QUESTION_DURATION_SEC));
            int totalSec = Math.min(q.getDurationSec() + addSec, MAX_QUESTION_DURATION_SEC);
            q.setDurationSec(totalSec);
            lastPageEntryTime = SystemClock.elapsedRealtime();
        }
    }

    @Override public void onResume() {
        super.onResume();
        // 从后台切回前台时刷新课程列表（30 秒内复用缓存）
        loadCoursesThrottled();
        // 从章节页返回后刷新「最近学习」文字
        loadRecentChapters(false);
        if (!quizActive && (llQuizArea==null||llQuizArea.getVisibility()!=View.VISIBLE))
            handler.postDelayed(this::autoStart, 3000);
    }
    @Override public void onPause() { super.onPause(); if(!quizActive) stopAndSave(); }

    @Override
    public void onDestroyView() {
        paperBoard = null;
        paperCards.clear();
        paperZOrder.clear();
        paperLayoutPending = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    private void autoStart() {
        if (running) return;
        running=true;
        if (seconds==0) startTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.getDefault()).format(new Date());
        handler.post(tick); updateStatus("focusing");
    }
    private void stopAndSave() {
        handler.removeCallbacks(this::autoStart);
        if(running){running=false;handler.removeCallbacks(tick);updateStatus("idle");}
        if(seconds>=1){saveToServer(seconds);}
    }
    private String formatDuration(int s) {
        if(s<3600)return String.format(Locale.getDefault(),"%02d:%02d",s/60,s%60);
        return String.format(Locale.getDefault(),"%d:%02d:%02d",s/3600,(s%3600)/60,s%60);
    }

    private void saveToServer(int sec) {
        Map<String,Object> b = new HashMap<>();
        b.put("durationSeconds",sec);
        String now = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.getDefault()).format(new Date());
        b.put("startedAt",startTime!=null?startTime:now); b.put("finishedAt",now);
        RetrofitClient.getInstance().create(ApiService.class).saveFocus(token,b).enqueue(new Callback<Map<String,Object>>(){
            @Override public void onResponse(Call<Map<String,Object>> c, Response<Map<String,Object>> r) {
                if(r.isSuccessful()&&r.body()!=null){ Object t=r.body().get("todayTotal");
                    if(t instanceof Number && tvToday!=null) handler.post(()->tvToday.setText("今日专注 "+formatDuration(((Number)t).intValue()))); }
            }
            @Override public void onFailure(Call<Map<String,Object>> c, Throwable t) {}
        });
        seconds=0;
    }
    private void loadTodayTotal() {
        RetrofitClient.getInstance().create(ApiService.class).getFocusToday(token).enqueue(new Callback<Map<String,Object>>(){
            @Override public void onResponse(Call<Map<String,Object>> c, Response<Map<String,Object>> r) {
                if(r.isSuccessful()&&r.body()!=null){Object t=r.body().get("totalSeconds");
                    if(t instanceof Number && tvToday!=null)handler.post(()->tvToday.setText("今日专注 "+formatDuration(((Number)t).intValue())));}
            }
            @Override public void onFailure(Call<Map<String,Object>> c, Throwable t) {}
        });
    }
    private void showLastSession() {
        RetrofitClient.getInstance().create(ApiService.class).getLastFocus(token).enqueue(new Callback<Map<String,Object>>(){
            @Override public void onResponse(Call<Map<String,Object>> c, Response<Map<String,Object>> r) {
                if(r.isSuccessful()&&r.body()!=null && tvLast!=null){Object s=r.body().get("seconds");
                    if(s instanceof Number){int sec=((Number)s).intValue();
                        if(sec>0)handler.post(()->tvLast.setText("上次 "+formatDuration(sec)));}}
            }
            @Override public void onFailure(Call<Map<String,Object>> c, Throwable t) {}
        });
    }
    private void updateStatus(String s) {
        Map<String,String> b = new HashMap<>(); b.put("status",s);
        RetrofitClient.getInstance().create(ApiService.class).updateFocusStatus(token,b).enqueue(emptyCb());
    }
    private Callback<Map<String,Object>> emptyCb(){return new Callback<Map<String,Object>>(){
        @Override public void onResponse(Call<Map<String,Object>> c, Response<Map<String,Object>> r){}
        @Override public void onFailure(Call<Map<String,Object>> c, Throwable t){}
    };}
}
