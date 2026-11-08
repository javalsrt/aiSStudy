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
    private RecyclerView rvQuizPanels;
    private View llQuizArea;
    private ViewPager2 viewPagerQuiz;
    private android.widget.ProgressBar quizProgress;
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
            rvQuizPanels = view.findViewById(R.id.rv_quiz_panels);
            llQuizArea = view.findViewById(R.id.ll_quiz_area);
            viewPagerQuiz = view.findViewById(R.id.viewpager_quiz);
            quizProgress = view.findViewById(R.id.quiz_progress);

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

    private void loadCoursesThrottled() {
        long now = System.currentTimeMillis();
        if (!panelData.isEmpty() && now - lastCoursesRefreshAt < COURSES_REFRESH_MS) return;
        lastCoursesRefreshAt = now;
        loadStudentCourses(getView());
    }

    private void loadStudentCourses(View view) {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getStudentCourses(token).enqueue(new Callback<List<StudentCourse>>() {
            @Override public void onResponse(Call<List<StudentCourse>> call, Response<List<StudentCourse>> resp) {
                if (!isAdded()) return;
                // 请求失败时保留现有卡片，避免切页面时列表突然变空
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (panelData.isEmpty()) handler.post(() -> buildPanels());
                    return;
                }
                List<StudentCourse> next = new ArrayList<>();
                for (StudentCourse c : resp.body()) {
                    if (c.getCourseName() == null) continue;
                    // 活动类条目（自习/班会/大扫除）只在课表展示，不作为可刷题课程卡片
                    if (isActivityName(c.getCourseName())) continue;
                    next.add(c);
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
                handler.post(() -> buildPanels());
            }
            @Override public void onFailure(Call<List<StudentCourse>> call, Throwable t) {
                if (!isAdded()) return;
                String[] def = {"Python程序设计","数据结构与算法基础","微信小程序设计",
                        "多媒体课件设计与开发","微课制作","高级网站技术",
                        "中国近现代史纲要","教师口语","教师书写技能",
                        "班主任工作技能训练","计算机综合实训","安全教育（六）",
                        "形势与政策"};
                for (String d : def) panelData.add(d);
                handler.post(() -> buildPanels());
            }
        });
    }

    private void buildPanels() {
        if (rvQuizPanels == null) return;
        int panelSize = (getResources().getDisplayMetrics().widthPixels - dp(44)) / 2;

        rvQuizPanels.setLayoutManager(new GridLayoutManager(getContext(), 2));
        // 交错排列的卡片会上下错位，需要允许绘制到边界外
        rvQuizPanels.setClipChildren(false);
        rvQuizPanels.setClipToPadding(false);
        rvQuizPanels.setAdapter(new RecyclerView.Adapter<PanelVH>() {
            @NonNull @Override
            public PanelVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_quiz_panel, parent, false);
                // 设置为正方形
                GridLayoutManager.LayoutParams lp = new GridLayoutManager.LayoutParams(panelSize, panelSize);
                // 右下角间距统一 12dp（与列间距一致），卡片网格整齐紧凑
                lp.setMargins(0, 0, viewType == 0 ? dp(12) : 0, dp(12));
                v.setLayoutParams(lp);
                return new PanelVH(v);
            }
            @Override public void onBindViewHolder(@NonNull PanelVH h, int pos) {
                Object item = panelData.get(pos);
                final String name;
                boolean isCourse = item instanceof StudentCourse;
                long courseId = 0;
                if (isCourse) {
                    StudentCourse c = (StudentCourse) item;
                    name = c.getCourseName();
                    courseId = c.getCourseId();
                } else {
                    name = (String) item;
                }
                h.tvName.setText(name);
                String sub;
                if (isCourse) {
                    StudentCourse c = (StudentCourse) item;
                    sub = (c.getTeacherName() != null && !c.getTeacherName().isEmpty())
                            ? c.getTeacherName() : "章节练习 · 巩固提升";
                } else if ("复习加强".equals(name)) {
                    sub = "重点知识巩固提升";
                } else {
                    sub = "错题回顾 · 查漏补缺";
                }
                h.tvSub.setText(sub);
                h.tvBig.setText(name.isEmpty() ? "学" : String.valueOf(name.charAt(0)));

                // ===== 柔和分层卡片：主色 + 属性标签 + 错落排列 =====
                // 每个 2×2 区块用四种不同颜色（左上粉 / 右上绿 / 左下蓝 / 右下橙）
                int rowIdx = pos / 2;
                int colIdx = pos % 2;
                int color = PANEL_COLORS[(rowIdx % 2) * 2 + colIdx];
                stylePanel(h, color);
                if (isCourse) {
                    StudentCourse c = (StudentCourse) item;
                    String t = (c.getTeacherName() != null && !c.getTeacherName().isEmpty())
                            ? c.getTeacherName() : "在线课程";
                    h.tvMeta.setText(t + " | 章节学习 | 随时练");
                } else if ("复习加强".equals(name)) {
                    h.tvMeta.setText("重点巩固 | 错题回顾");
                } else {
                    h.tvMeta.setText("错题解析 | 薄弱点定位");
                }
                // 取消错落浮动：上下浮动会撑大行间距，改为整齐排列
                h.itemView.setTranslationY(0);
                if (isCourse) {
                    final long cid = courseId;
                    boolean hasChapters = ((StudentCourse) item).isHasChapters();
                    // 新版卡片：章节入口 = 点击卡片本体；白胶囊按钮只承担「开始」
                    h.btnChapter.setVisibility(View.GONE);
                    h.btnAction.setVisibility(hasChapters ? View.VISIBLE : View.GONE);
                    h.btnAction.setText("开始");
                    if (hasChapters) {
                        // 点击卡片主体或「章节」按钮 → 章节学习；点击「开始」按钮 → 开始刷题
                        View.OnClickListener toChapter = v -> startActivity(
                                new Intent(getContext(), ChapterLearnActivity.class)
                                        .putExtra("courseId", cid).putExtra("courseName", name));
                        h.itemView.setOnClickListener(toChapter);
                        h.btnChapter.setOnClickListener(toChapter);
                        h.btnAction.setOnClickListener(v -> showStartConfirm(name, cid));
                    } else {
                        h.itemView.setOnClickListener(null);
                    }
                } else {
                    h.btnChapter.setVisibility(View.GONE);
                    h.btnAction.setVisibility(View.VISIBLE);
                    h.btnAction.setText("复习加强".equals(name) ? "进入复习" : "去解析");
                    h.itemView.setOnClickListener(v -> {
                        if ("复习加强".equals(name)) {
                            startActivity(new Intent(getContext(), com.znxsgl.student.ReviewActivity.class));
                        } else if ("错题解析".equals(name)) {
                            showWrongAnalysis();
                        }
                    });
                    h.btnAction.setOnClickListener(v -> h.itemView.performClick());
                }
            }
            @Override public int getItemCount() { return panelData.size(); }
            @Override public int getItemViewType(int pos) { return pos % 2; } // 0=左边(有margin), 1=右边(无margin)
        });
    }

    static class PanelVH extends RecyclerView.ViewHolder {
        TextView tvName, tvSub, tvBig, tvMeta, btnChapter, btnAction;
        View sheetBack, cardMain, metaStrip;
        PanelVH(View v) { super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvSub = v.findViewById(R.id.tv_sub);
            tvBig = v.findViewById(R.id.tv_big);
            tvMeta = v.findViewById(R.id.tv_meta);
            btnChapter = v.findViewById(R.id.btn_chapter);
            btnAction = v.findViewById(R.id.btn_action);
            sheetBack = v.findViewById(R.id.sheet_back);
            cardMain = v.findViewById(R.id.card_main);
            metaStrip = v.findViewById(R.id.ll_meta_strip);
        }
    }

    /** 卡片主色：饱和考试卡配色（红/绿/蓝/橙），按 2×2 区块循环 */
    private static final int[] PANEL_COLORS = {
            0xFFF0696B, // 珊瑚红
            0xFF41B189, // 石绿
            0xFF5B7BD5, // 靛蓝
            0xFFE8834F  // 暖橙
    };

    /** 套用卡片主色：主色卡 + 右侧浅色错层纸片 + 底部深色信息条 + 白色胶囊按钮 */
    private void stylePanel(PanelVH h, int color) {
        // 主卡片：饱和纯色 + 14dp 圆角
        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
        card.setColor(color);
        card.setCornerRadius(dp(14));
        h.cardMain.setBackground(card);

        // 背后纸片：主色混白 55%，右侧露出一条浅色边
        android.graphics.drawable.GradientDrawable back = new android.graphics.drawable.GradientDrawable();
        back.setColor(androidx.core.graphics.ColorUtils.blendARGB(color, android.graphics.Color.WHITE, 0.55f));
        back.setCornerRadius(dp(14));
        h.sheetBack.setBackground(back);

        // 底部信息条：主色压深 14%，底部圆角与卡片一致、顶部平角
        android.graphics.drawable.GradientDrawable strip = new android.graphics.drawable.GradientDrawable();
        strip.setColor(androidx.core.graphics.ColorUtils.blendARGB(color, android.graphics.Color.BLACK, 0.14f));
        float r = dp(14);
        strip.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        h.metaStrip.setBackground(strip);

        // 开始按钮：白色胶囊 + 深一档主色文字
        h.btnAction.setTextColor(darken(color, 0.62f));
    }

    /** 与黑色混合，得到同色系更深的一档 */
    private int darken(int color, float ratio) {
        int r = (int) (((color >> 16) & 0xFF) * ratio);
        int g = (int) (((color >> 8) & 0xFF) * ratio);
        int b = (int) ((color & 0xFF) * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
            if (rvQuizPanels != null) rvQuizPanels.setVisibility(View.GONE);
            llQuizArea.setVisibility(View.VISIBLE);
            setNavVisible(false);   // 全屏答题：隐藏悬浮导航
            lastPageIndex=0; lastPageEntryTime=SystemClock.elapsedRealtime();
            quizActive = true;
        });
    }

    // ===== 采集 + 提交 =====

    /** 更新顶部答题进度条（按当前题号百分比） */
    private void updateQuizProgress(int pos) {
        if (quizProgress == null) return;
        int total = quizQuestions.size();
        quizProgress.setProgress(total <= 0 ? 0 : (int) (((pos + 1) * 100f) / total));
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
                    if (rvQuizPanels != null) rvQuizPanels.setVisibility(View.VISIBLE);
                    setNavVisible(true);
                    quizActive = false;
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
                    if (rvQuizPanels != null) rvQuizPanels.setVisibility(View.VISIBLE);
                    setNavVisible(true);
                    quizActive = false;
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
        if (rvQuizPanels != null) rvQuizPanels.setVisibility(View.VISIBLE);
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
        if (!quizActive && (llQuizArea==null||llQuizArea.getVisibility()!=View.VISIBLE))
            handler.postDelayed(this::autoStart, 3000);
    }
    @Override public void onPause() { super.onPause(); if(!quizActive) stopAndSave(); }

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
