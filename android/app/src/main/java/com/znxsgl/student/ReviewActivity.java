package com.znxsgl.student;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 复习加强页
 * 展示最近一次测评的薄弱点、AI 学习计划与推荐章节，
 * 并提供“去学习章节”的快捷入口。
 */
public class ReviewActivity extends AppCompatActivity {

    private String token;
    private Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvSubject, tvAccuracy, tvSuggestion, tvEmpty;
    private LinearLayout llWeaknesses, llStudyPlan, llRecommended, llScores;
    private View cardScores, cardPlan, cardRecommended;

    private long reviewCourseId = 0;
    private String reviewSubject = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review);

        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        token = "Bearer " + prefs.getString("token", "");

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tv_title)).setText("复习加强");

        tvSubject = findViewById(R.id.tv_review_subject);
        tvAccuracy = findViewById(R.id.tv_review_accuracy);
        tvSuggestion = findViewById(R.id.tv_review_suggestion);
        tvEmpty = findViewById(R.id.tv_review_empty);
        llWeaknesses = findViewById(R.id.ll_review_weaknesses);
        llStudyPlan = findViewById(R.id.ll_review_plan);
        llRecommended = findViewById(R.id.ll_recommended_chapters);
        llScores = findViewById(R.id.ll_review_scores);
        cardScores = findViewById(R.id.card_review_scores);
        cardPlan = findViewById(R.id.card_review_plan);
        cardRecommended = findViewById(R.id.card_recommended);

        findViewById(R.id.btn_review_learn).setOnClickListener(v -> startChapterLearn());
        findViewById(R.id.btn_review_done).setOnClickListener(v -> finish());

        loadReviewPlan();
    }

    private void loadReviewPlan() {
        RetrofitClient.getInstance().create(ApiService.class)
                .getReviewPlan(token).enqueue(new Callback<Map<String, Object>>() {
                    @Override public void onResponse(Call<Map<String, Object>> c, Response<Map<String, Object>> r) {
                        if (!isFinishing() && r.isSuccessful() && r.body() != null) {
                            render(r.body());
                        } else {
                            showEmpty("加载失败，请稍后重试");
                        }
                    }
                    @Override public void onFailure(Call<Map<String, Object>> c, Throwable t) {
                        if (!isFinishing()) {
                            showEmpty("网络错误：" + t.getMessage());
                        }
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private void render(Map<String, Object> data) {
        reviewSubject = data.get("subject") != null ? data.get("subject").toString() : "";
        Object cid = data.get("courseId");
        if (cid instanceof Number) reviewCourseId = ((Number) cid).longValue();

        String accuracy = data.get("accuracy") != null ? data.get("accuracy").toString() : "0%";
        String suggestion = data.get("suggestion") != null ? data.get("suggestion").toString() : "";

        if (reviewCourseId <= 0) {
            showEmpty(suggestion.isEmpty() ? "暂无测评记录，先去专注刷题完成一次测评吧。" : suggestion);
            return;
        }

        tvEmpty.setVisibility(View.GONE);
        tvSubject.setText("针对课程：" + reviewSubject);
        tvAccuracy.setText("上次正确率 " + accuracy);
        tvSuggestion.setText(suggestion.isEmpty() ? "继续保持学习节奏，针对薄弱环节多复习。" : suggestion);

        // 薄弱点
        List<String> weaknesses = toStringList(data.get("weaknesses"));
        renderTags(llWeaknesses, weaknesses, "暂无薄弱环节");

        // 学习计划
        List<String> plan = toStringList(data.get("studyPlan"));
        renderStudyPlan(plan);

        // 能力维度
        Map<String, Object> scores = data.get("scores") instanceof Map
                ? (Map<String, Object>) data.get("scores") : null;
        renderScores(scores);

        // 推荐章节
        List<Map<String, Object>> chapters = data.get("recommendedChapters") instanceof List
                ? (List<Map<String, Object>>) data.get("recommendedChapters") : null;
        renderRecommended(chapters);
    }

    private void showEmpty(String msg) {
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(msg);
        cardScores.setVisibility(View.GONE);
        cardPlan.setVisibility(View.GONE);
        cardRecommended.setVisibility(View.GONE);
        findViewById(R.id.card_review_summary).setVisibility(View.GONE);
        findViewById(R.id.card_review_weaknesses).setVisibility(View.GONE);
    }

    private void renderTags(LinearLayout container, List<String> tags, String emptyText) {
        container.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(emptyText);
            tv.setTextSize(13);
            tv.setTextColor(0xFF8E8E93);
            container.addView(tv);
            return;
        }
        for (String tag : tags) {
            TextView tv = new TextView(this);
            tv.setText(tag);
            tv.setTextSize(13);
            tv.setTextColor(0xFF0A84FF);
            tv.setBackgroundResource(R.drawable.bg_tag_blue);
            tv.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), dp(8));
            tv.setLayoutParams(lp);
            container.addView(tv);
        }
    }

    private void renderStudyPlan(List<String> plan) {
        llStudyPlan.removeAllViews();
        if (plan == null || plan.isEmpty()) {
            cardPlan.setVisibility(View.GONE);
            return;
        }
        cardPlan.setVisibility(View.VISIBLE);
        for (int i = 0; i < plan.size(); i++) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_study_plan, llStudyPlan, false);
            ((TextView) row.findViewById(R.id.tv_plan_index)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.tv_plan_text)).setText(plan.get(i));
            llStudyPlan.addView(row);
        }
    }

    @SuppressWarnings("unchecked")
    private void renderScores(Map<String, Object> scores) {
        llScores.removeAllViews();
        if (scores == null || scores.isEmpty()) {
            cardScores.setVisibility(View.GONE);
            return;
        }
        cardScores.setVisibility(View.VISIBLE);
        for (Map.Entry<String, Object> e : scores.entrySet()) {
            int score = 0;
            Object v = e.getValue();
            if (v instanceof Number) score = ((Number) v).intValue();
            View row = LayoutInflater.from(this).inflate(R.layout.item_score_bar, llScores, false);
            ((TextView) row.findViewById(R.id.tv_score_name)).setText(e.getKey());
            TextView tvBar = row.findViewById(R.id.tv_score_bar);
            TextView tvValue = row.findViewById(R.id.tv_score_value);
            tvValue.setText(String.valueOf(score));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tvBar.getLayoutParams();
            lp.weight = Math.max(1, score);
            tvBar.setLayoutParams(lp);
            llScores.addView(row);
        }
    }

    private void renderRecommended(List<Map<String, Object>> chapters) {
        llRecommended.removeAllViews();
        if (chapters == null || chapters.isEmpty()) {
            cardRecommended.setVisibility(View.GONE);
            return;
        }
        cardRecommended.setVisibility(View.VISIBLE);
        for (Map<String, Object> ch : chapters) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_recommended_chapter, llRecommended, false);
            Object no = ch.get("chapterNo");
            String name = ch.get("chapterName") != null ? ch.get("chapterName").toString() : "";
            String desc = ch.get("description") != null ? ch.get("description").toString() : "";
            ((TextView) row.findViewById(R.id.tv_rec_no)).setText(no != null ? "第 " + no + " 章" : "章节");
            ((TextView) row.findViewById(R.id.tv_rec_name)).setText(name);
            TextView tvDesc = row.findViewById(R.id.tv_rec_desc);
            if (desc != null && !desc.isEmpty()) {
                tvDesc.setText(desc);
                tvDesc.setVisibility(View.VISIBLE);
            } else {
                tvDesc.setVisibility(View.GONE);
            }
            row.setOnClickListener(v -> startChapterLearn());
            llRecommended.addView(row);
        }
    }

    private void startChapterLearn() {
        if (reviewCourseId <= 0) {
            Toast.makeText(this, "暂无推荐课程", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, ChapterLearnActivity.class)
                .putExtra("courseId", reviewCourseId)
                .putExtra("courseName", reviewSubject));
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
