package com.znxsgl.student.fragment;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private Handler handler = new Handler(Looper.getMainLooper());
    private int seconds;
    private boolean running, quizActive;
    private String startTime, token;
    private SharedPreferences prefs;

    private List<QuizQuestion> quizQuestions = new ArrayList<>();
    private QuizPagerAdapter quizAdapter;
    private long lastPageEntryTime;
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

            // 长按提交按钮
            View btnDone = view.findViewById(R.id.btn_quiz_done);
            if (btnDone != null) btnDone.setOnLongClickListener(v -> {
                // 长按动画
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction(() ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
                collectAndSubmit();
                return true;
            });

            loadStudentCourses(view);

            try {
                quizAdapter = new QuizPagerAdapter(quizQuestions);
                quizAdapter.setOnAnswerListener(new QuizPagerAdapter.OnAnswerListener() {
                    @Override public void onAnswered(int pos, String a) {
                        recordTime(pos);
                        if (viewPagerQuiz != null && pos + 1 < quizQuestions.size())
                            viewPagerQuiz.setCurrentItem(pos + 1, true);
                    }
                    @Override public void onAutoSkip(int pos) {}
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
                            lastPageIndex = pos; lastPageEntryTime = System.currentTimeMillis();
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

    private void loadStudentCourses(View view) {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getStudentCourses(token).enqueue(new Callback<List<StudentCourse>>() {
            @Override public void onResponse(Call<List<StudentCourse>> call, Response<List<StudentCourse>> resp) {
                if (!isAdded()) return;
                panelData.clear();
                if (resp.isSuccessful() && resp.body() != null) {
                    for (StudentCourse c : resp.body()) {
                        if (c.getCourseName() == null) continue;
                        // 活动类条目（自习/班会/大扫除）只在课表展示，不作为可刷题课程卡片
                        if (isActivityName(c.getCourseName())) continue;
                        panelData.add(c);
                    }
                }
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
        rvQuizPanels.setAdapter(new RecyclerView.Adapter<PanelVH>() {
            @NonNull @Override
            public PanelVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_quiz_panel, parent, false);
                // 设置为正方形
                GridLayoutManager.LayoutParams lp = new GridLayoutManager.LayoutParams(panelSize, panelSize);
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
                if (isCourse) {
                    final long cid = courseId;
                    boolean hasChapters = ((StudentCourse) item).isHasChapters();
                    // 课程还没有章节内容时，「章节」「开始」入口一并隐藏（不可刷题也不可学习）
                    h.btnChapter.setVisibility(hasChapters ? View.VISIBLE : View.GONE);
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
        TextView tvName, tvSub, tvBig, btnChapter, btnAction;
        PanelVH(View v) { super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvSub = v.findViewById(R.id.tv_sub);
            tvBig = v.findViewById(R.id.tv_big);
            btnChapter = v.findViewById(R.id.btn_chapter);
            btnAction = v.findViewById(R.id.btn_action);
        }
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
                quizQuestions.add(q);
            }
            quizAdapter.notifyDataSetChanged();
            // 重置到第1题：ViewPager2会保留上次测评停留的位置，不重置会导致新测试直接显示旧页（如第15题）
            if (viewPagerQuiz != null) viewPagerQuiz.setCurrentItem(0, false);
            if (rvQuizPanels != null) rvQuizPanels.setVisibility(View.GONE);
            llQuizArea.setVisibility(View.VISIBLE);
            lastPageIndex=0; lastPageEntryTime=System.currentTimeMillis();
            quizActive = true;
        });
    }

    // ===== 采集 + 提交 =====

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
                    quizActive = false;
                    Toast.makeText(RetrofitClient.safeContext(getContext()),"提交失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
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

    /** 供 MainActivity 调用：取消答题 */
    public void cancelQuiz() {
        quizActive = false;
        quizQuestions.clear();
        if (quizAdapter != null) quizAdapter.notifyDataSetChanged();
        if (llQuizArea != null) llQuizArea.setVisibility(View.GONE);
        if (rvQuizPanels != null) rvQuizPanels.setVisibility(View.VISIBLE);
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
            btn.setBackgroundColor(Color.parseColor(i == 0 ? "#5E6AD2" : "#F9F9FB"));
            final int si = i;
            btn.setOnClickListener(v -> {
                for (int j = 0; j < llSubjects.getChildCount(); j++) {
                    TextView child = (TextView) llSubjects.getChildAt(j);
                    child.setTextColor(Color.parseColor(j == si ? "#FFFFFF" : "#6E6E73"));
                    child.setBackgroundColor(Color.parseColor(j == si ? "#5E6AD2" : "#F9F9FB"));
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
                ((TextView) h.itemView.findViewById(R.id.tv_question)).setText(it.question);
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
                    child.setBackgroundColor(Color.parseColor(j == si ? "#5E6AD2" : "#F9F9FB"));
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
            btnB.setTextColor(Color.parseColor("#5E6AD2"));
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
            // 从其他 Tab 返回时刷新课程列表，避免后台删除课程后仍显示旧数据
            loadStudentCourses(getView());
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
            q.setDurationSec(q.getDurationSec()+(int)((System.currentTimeMillis()-lastPageEntryTime)/1000));
        }
    }

    @Override public void onResume() {
        super.onResume();
        // 从后台切回前台时刷新课程列表
        loadStudentCourses(getView());
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
