package com.znxsgl.student;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class QuizResultActivity extends AppCompatActivity {

    private String token;
    private final Gson gson = new Gson();
    private boolean revealFinished = false;
    private Runnable revealFinishRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz_result);

        // 状态栏与页面底色统一（canvas），避免顶部出现割裂白条
        getWindow().setStatusBarColor(0xFFF2F2F7);
        getWindow().setNavigationBarColor(0xFFF2F2F7);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);

        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        token = "Bearer " + prefs.getString("token", "");

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tv_title)).setText("测评报告");

        Intent it = getIntent();
        int correct = it.getIntExtra("correctCount", 0);
        int skip = it.getIntExtra("skipCount", 0);
        int total = it.getIntExtra("totalQuestions", 0);
        int totalSec = it.getIntExtra("totalDurationSec", 0);
        String suggestion = it.getStringExtra("suggestion");

        int answered = total - skip;
        int accuracy = total > 0 ? correct * 100 / total : 0;

        ((TextView) findViewById(R.id.tv_accuracy)).setText(accuracy + "%");
        ((TextView) findViewById(R.id.tv_correct_count)).setText(correct + "/" + total);
        ((TextView) findViewById(R.id.tv_answered_count)).setText(String.valueOf(answered));
        ((TextView) findViewById(R.id.tv_skip_count)).setText(String.valueOf(skip));
        ((TextView) findViewById(R.id.tv_duration)).setText(formatDuration(totalSec));

        // 六维评分
        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) it.getSerializableExtra("scores");
        renderScores(scores);

        // 优劣势
        renderTags(R.id.ll_strengths, it.getStringArrayListExtra("strengths"), "暂无突出优势");
        renderTags(R.id.ll_weaknesses, it.getStringArrayListExtra("weaknesses"), "暂无薄弱环节");

        // 综合建议
        TextView tvSuggestion = findViewById(R.id.tv_suggestion);
        if (suggestion != null && !suggestion.isEmpty()) {
            tvSuggestion.setText(suggestion);
        } else {
            tvSuggestion.setText("继续保持学习节奏，多复习薄弱章节。");
        }

        // 学习计划
        renderStudyPlan(it.getStringArrayListExtra("studyPlan"));

        // 本次错题
        renderWrongAnswers(it.getStringExtra("wrongAnswersJson"));

        // BKT 知识点掌握度（异步加载，不阻塞报告展示）
        loadMastery(it.getLongExtra("courseId", 0), it.getLongExtra("sessionId", 0));

        findViewById(R.id.btn_done).setOnClickListener(v -> finish());

        // 提交后先播放揭晓动画，再衔接显示测评报告
        playRevealAnimation(accuracy, correct, total, totalSec);
    }

    /**
     * 揭晓动画：模仿 uiverse ugly-horse-87 金色奖杯卡片
     * 卡片自顶部滑入（slide-in-top）→ 奖杯弹出 → 分割线展开 → 正确率数字滚动 → 停留后淡出衔接报告
     */
    private void playRevealAnimation(int accuracy, int correct, int total, int totalSec) {
        View overlay = findViewById(R.id.reveal_overlay);
        View card = findViewById(R.id.reveal_card);
        ImageView trophy = findViewById(R.id.iv_reveal_trophy);
        View line = findViewById(R.id.reveal_line);
        View statsRow = findViewById(R.id.reveal_stats_row);
        TextView tvScore = findViewById(R.id.tv_reveal_score);
        TextView tvCorrect = findViewById(R.id.tv_reveal_correct);
        TextView tvTime = findViewById(R.id.tv_reveal_time);

        tvCorrect.setText(correct + "/" + total);
        tvTime.setText(formatDuration(totalSec));

        // 报告内容先隐藏并下移，等待动画结束后淡入
        View content = findViewById(R.id.report_content);
        content.setAlpha(0f);
        content.setTranslationY(dp(32));

        // 初始状态（对应组件的 slide-in-top 起始帧）
        card.setAlpha(0f);
        card.setTranslationY(-dp(160));
        trophy.setAlpha(0f);
        trophy.setScaleX(0.3f);
        trophy.setScaleY(0.3f);
        line.setScaleX(0f);
        statsRow.setAlpha(0f);
        statsRow.setTranslationY(dp(16));

        // 卡片自顶部滑入
        card.animate().translationY(0f).alpha(1f)
                .setDuration(850)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

        // 奖杯回弹式弹出
        trophy.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(320)
                .setDuration(520)
                .setInterpolator(new OvershootInterpolator(1.5f))
                .start();

        // 金色分割线从中心向两侧展开
        line.animate().scaleX(1f)
                .setStartDelay(550)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // 正确率数字从 0 滚动到实际值
        ValueAnimator counter = ValueAnimator.ofInt(0, Math.max(accuracy, 0));
        counter.setStartDelay(450);
        counter.setDuration(1100);
        counter.setInterpolator(new DecelerateInterpolator());
        counter.addUpdateListener(a -> tvScore.setText(a.getAnimatedValue() + "%"));
        counter.start();

        // SCORE / TIME 数据行淡入
        statsRow.animate().alpha(1f).translationY(0f)
                .setStartDelay(900)
                .setDuration(450)
                .start();

        // 点击可跳过
        overlay.setOnClickListener(v -> finishRevealOverlay());

        // 停留约 1.4 秒后淡出，衔接显示报告
        revealFinishRunnable = this::finishRevealOverlay;
        overlay.postDelayed(revealFinishRunnable, 2500);
    }

    /** 淡出动画层，显示完整报告 */
    private void finishRevealOverlay() {
        if (revealFinished) return;
        revealFinished = true;

        View overlay = findViewById(R.id.reveal_overlay);
        View content = findViewById(R.id.report_content);

        overlay.animate().alpha(0f)
                .setDuration(450)
                .withEndAction(() -> overlay.setVisibility(View.GONE))
                .start();
        content.animate().alpha(1f).translationY(0f)
                .setDuration(550)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    @Override
    protected void onDestroy() {
        if (revealFinishRunnable != null) {
            View overlay = findViewById(R.id.reveal_overlay);
            if (overlay != null) overlay.removeCallbacks(revealFinishRunnable);
        }
        super.onDestroy();
    }

    private String formatDuration(int sec) {
        int m = sec / 60;
        int s = sec % 60;
        if (m > 0) return String.format(Locale.getDefault(), "%d分%d秒", m, s);
        return s + "秒";
    }

    private void renderScores(Map<String, Object> scores) {
        LinearLayout container = findViewById(R.id.ll_scores);
        container.removeAllViews();
        if (scores == null || scores.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("本次作答数据不足，未生成能力维度");
            tv.setTextSize(13);
            tv.setTextColor(0xFF8E8E93);
            container.addView(tv);
            return;
        }
        for (Map.Entry<String, Object> e : scores.entrySet()) {
            int score = 0;
            Object v = e.getValue();
            if (v instanceof Number) score = ((Number) v).intValue();
            if (score > 10) score = score / 10; // 兼容百分制返回，统一按 10 分制展示
            int pct = Math.max(0, Math.min(10, score));
            View row = LayoutInflater.from(this).inflate(R.layout.item_score_bar, container, false);
            ((TextView) row.findViewById(R.id.tv_score_name)).setText(e.getKey());
            TextView tvBar = row.findViewById(R.id.tv_score_bar);
            TextView tvValue = row.findViewById(R.id.tv_score_value);
            tvValue.setText(String.valueOf(pct));
            // 按 10 分制计算进度条占比：bar weight = pct，轨道空白 weight = 10 - pct
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tvBar.getLayoutParams();
            lp.weight = Math.max(0.2f, pct);
            tvBar.setLayoutParams(lp);
            LinearLayout track = (LinearLayout) tvBar.getParent();
            if (track.getChildCount() > 1) {
                View gap = track.getChildAt(1);
                LinearLayout.LayoutParams gp = (LinearLayout.LayoutParams) gap.getLayoutParams();
                gp.weight = 10 - pct;
                gap.setLayoutParams(gp);
            }
            container.addView(row);
        }
    }

    private void renderTags(int containerId, ArrayList<String> tags, String emptyText) {
        LinearLayout container = findViewById(containerId);
        container.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(emptyText);
            tv.setTextSize(13);
            tv.setTextColor(0xFF8E8E93);
            container.addView(tv);
            return;
        }
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            TextView tv = new TextView(this);
            tv.setText(tag);
            tv.setTextSize(13);
            tv.setTextColor(0xFF0A84FF);
            tv.setBackgroundResource(R.drawable.bg_tag_blue);
            tv.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            // 垂直堆叠：仅条目之间留 8dp 间距，最后一条不留，避免卡片底部多出空白
            if (i < tags.size() - 1) {
                lp.setMargins(0, 0, 0, dp(8));
            }
            tv.setLayoutParams(lp);
            container.addView(tv);
        }
    }

    private void renderStudyPlan(ArrayList<String> plan) {
        LinearLayout container = findViewById(R.id.ll_study_plan);
        container.removeAllViews();
        if (plan == null || plan.isEmpty()) {
            findViewById(R.id.card_plan).setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < plan.size(); i++) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_study_plan, container, false);
            ((TextView) row.findViewById(R.id.tv_plan_index)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.tv_plan_text)).setText(plan.get(i));
            container.addView(row);
        }
    }

    private void renderWrongAnswers(String wrongJson) {
        RecyclerView rv = findViewById(R.id.rv_wrong_answers);
        TextView tvEmpty = findViewById(R.id.tv_wrong_empty);

        List<Map<String, String>> wrongAnswers = new ArrayList<>();
        if (wrongJson != null && !wrongJson.isEmpty()) {
            try {
                wrongAnswers = gson.fromJson(wrongJson, new TypeToken<List<Map<String, String>>>(){}.getType());
            } catch (Exception ignored) {}
        }

        if (wrongAnswers == null || wrongAnswers.isEmpty()) {
            rv.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        rv.setVisibility(View.VISIBLE);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new WrongAnswerAdapter(wrongAnswers));
    }

    private static class WrongAnswerAdapter extends RecyclerView.Adapter<WrongAnswerAdapter.VH> {
        private final List<Map<String, String>> data;

        WrongAnswerAdapter(List<Map<String, String>> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_quiz_wrong, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Map<String, String> item = data.get(pos);
            String type = item.getOrDefault("questionType", "单选");
            if ("单选".equals(type)) type = "单选题";
            else if ("判断".equals(type)) type = "判断题";
            else if ("解析".equals(type)) type = "解析题";
            else if ("填空".equals(type)) type = "填空题";

            h.tvType.setText(type);
            RichTextRenderer.set(h.tvQuestion, String.valueOf(item.getOrDefault("question", "")));

            String ua = item.get("userAnswer");
            if (ua == null || ua.isEmpty() || "不会".equals(ua)) {
                h.tvUserAnswer.setText("未作答 / 不会");
            } else {
                // 学生作答可能含 LaTeX / Markdown，统一走富文本渲染
                RichTextRenderer.set(h.tvUserAnswer, ua);
            }
            // 正确答案常带 $...$ 公式，必须富文本渲染，否则会直接显示原始 LaTeX 源码
            RichTextRenderer.set(h.tvCorrectAnswer, item.getOrDefault("correctAnswer", ""));
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvType, tvQuestion, tvUserAnswer, tvCorrectAnswer;
            VH(View v) { super(v);
                tvType = v.findViewById(R.id.tv_type);
                tvQuestion = v.findViewById(R.id.tv_question);
                tvUserAnswer = v.findViewById(R.id.tv_user_answer);
                tvCorrectAnswer = v.findViewById(R.id.tv_correct_answer);
            }
        }
    }

    // ===== BKT 知识点掌握度 =====

    private void loadMastery(long courseId, long sessionId) {
        if (courseId <= 0) return;
        RetrofitClient.getInstance().create(ApiService.class)
                .getMastery(token, courseId, sessionId)
                .enqueue(new Callback<Map<String, Object>>() {
                    @Override
                    public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                        if (!resp.isSuccessful() || resp.body() == null) return;
                        runOnUiThread(() -> renderMastery(resp.body()));
                    }

                    @Override
                    public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                        // 掌握度属于附加信息，加载失败不影响报告主体
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private void renderMastery(Map<String, Object> body) {
        List<Map<String, Object>> list;
        try {
            list = (List<Map<String, Object>>) body.get("knowledge");
        } catch (Exception e) {
            return;
        }
        if (list == null || list.isEmpty()) return;

        // 优先只展示本次有作答的知识点；本次没有命中知识点时展示全部
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> item : list) {
            if (toInt(item.get("attempts")) > 0) shown.add(item);
        }
        if (shown.isEmpty()) shown = list;

        Object overall = body.get("mastery");
        double overallP = overall instanceof Number ? ((Number) overall).doubleValue() : 0;
        ((TextView) findViewById(R.id.tv_mastery_overall)).setText(Math.round(overallP * 100) + "%");

        LinearLayout container = findViewById(R.id.ll_mastery);
        container.removeAllViews();
        for (Map<String, Object> item : shown) {
            container.addView(buildMasteryRow(item));
        }
        findViewById(R.id.card_mastery).setVisibility(View.VISIBLE);
    }

    private View buildMasteryRow(Map<String, Object> item) {
        double mastery = Math.max(0, Math.min(1, toDouble(item.get("mastery"))));
        int attempts = toInt(item.get("attempts"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(14);
        row.setLayoutParams(rowLp);

        // 第一行：知识点名称 + 本次变化 + 百分比
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(String.valueOf(item.getOrDefault("name", "知识点")));
        name.setTextSize(15);
        name.setTextColor(0xFF1C1C1E);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);
        head.addView(name);

        Object deltaObj = item.get("delta");
        if (deltaObj instanceof Number) {
            double delta = ((Number) deltaObj).doubleValue();
            TextView tvDelta = new TextView(this);
            tvDelta.setText(String.format(Locale.getDefault(), "%+.1f%%", delta * 100));
            tvDelta.setTextSize(12);
            tvDelta.setTextColor(delta >= 0 ? 0xFF34C759 : 0xFFFF3B30);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.rightMargin = dp(8);
            tvDelta.setLayoutParams(dlp);
            head.addView(tvDelta);
        }

        TextView pct = new TextView(this);
        pct.setText(Math.round(mastery * 100) + "%");
        pct.setTextSize(16);
        pct.setTypeface(null, android.graphics.Typeface.BOLD);
        pct.setTextColor(attempts > 0 ? 0xFF0A84FF : 0xFF8E8E93);
        head.addView(pct);
        row.addView(head);

        // 第二行：掌握度进度条
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0xFFE5E5EA);
        trackBg.setCornerRadius(dp(4));
        track.setBackground(trackBg);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        trackLp.topMargin = dp(8);
        track.setLayoutParams(trackLp);

        View fill = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(masteryColor(mastery));
        fillBg.setCornerRadius(dp(4));
        fill.setBackground(fillBg);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max((float) mastery, 0.001f)));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max((float) (1 - mastery), 0.001f)));
        row.addView(track);

        // 未练习的知识点给出提示，避免把先验初值误解为真实水平
        if (attempts <= 0) {
            TextView hint = new TextView(this);
            hint.setText("尚未练习，显示 BKT 初始值 30%");
            hint.setTextSize(11);
            hint.setTextColor(0xFF8E8E93);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = dp(4);
            hint.setLayoutParams(hlp);
            row.addView(hint);
        }
        return row;
    }

    private int masteryColor(double p) {
        if (p >= 0.8) return 0xFF34C759;
        if (p >= 0.6) return 0xFF0A84FF;
        if (p >= 0.4) return 0xFFFF9500;
        return 0xFFFF3B30;
    }

    private double toDouble(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private int toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}