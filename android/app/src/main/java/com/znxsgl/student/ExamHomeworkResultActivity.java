package com.znxsgl.student;

import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
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
 * 学生考试/作业结果页
 */
public class ExamHomeworkResultActivity extends AppCompatActivity {

    private long examId;
    private String token;

    private TextView tvTitle, tvScore, tvPassStatus, tvScoreSummary;
    private LinearLayout llDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exam_homework_result);

        examId = getIntent().getLongExtra("exam_id", 0);
        String examTitle = getIntent().getStringExtra("exam_title");

        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        token = "Bearer " + prefs.getString("token", "");

        tvTitle = findViewById(R.id.tv_title);
        tvScore = findViewById(R.id.tv_score);
        tvPassStatus = findViewById(R.id.tv_pass_status);
        tvScoreSummary = findViewById(R.id.tv_score_summary);
        llDetail = findViewById(R.id.ll_detail);

        tvTitle.setText(examTitle != null ? examTitle + " 结果" : "考试结果");
        findViewById(R.id.tv_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        loadResult();
    }

    @SuppressWarnings("unchecked")
    private void loadResult() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("加载结果中...");
        pd.show();

        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getExamHomeworkResult(token, examId).enqueue(new Callback<Map<String, Object>>() {
            @Override
            public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> resp) {
                pd.dismiss();
                if (!resp.isSuccessful() || resp.body() == null) {
                    Toast.makeText(ExamHomeworkResultActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                Map<String, Object> body = resp.body();
                renderResult(body);
            }

            @Override
            public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                pd.dismiss();
                Toast.makeText(ExamHomeworkResultActivity.this, "网络错误：" + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void renderResult(Map<String, Object> body) {
        int totalScore = body.get("totalScore") instanceof Number ? ((Number) body.get("totalScore")).intValue() : 100;
        int passScore = body.get("passScore") instanceof Number ? ((Number) body.get("passScore")).intValue() : 60;
        int score = body.get("score") instanceof Number ? ((Number) body.get("score")).intValue() : 0;
        boolean passed = Boolean.TRUE.equals(body.get("passed"));

        tvScore.setText(String.valueOf(score));
        tvPassStatus.setText(passed ? "已通过" : "未通过");
        tvPassStatus.setTextColor(passed ? 0xFF34C759 : 0xFFFF3B30);
        tvPassStatus.setBackgroundColor(passed ? 0xFFE8F8EE : 0xFFFFECEB);
        tvScoreSummary.setText("满分 " + totalScore + " 分，及格 " + passScore + " 分");

        Object detailObj = body.get("detail");
        if (!(detailObj instanceof List)) return;
        List<Map<String, Object>> detail = (List<Map<String, Object>>) detailObj;

        for (Map<String, Object> item : detail) {
            View card = LayoutInflater.from(this).inflate(R.layout.item_exam_result_detail, llDetail, false);

            TextView tvIndex = card.findViewById(R.id.tv_index);
            TextView tvType = card.findViewById(R.id.tv_type);
            TextView tvScoreTag = card.findViewById(R.id.tv_score_tag);
            TextView tvQuestion = card.findViewById(R.id.tv_question);
            TextView tvUserAnswer = card.findViewById(R.id.tv_user_answer);
            TextView tvCorrectAnswer = card.findViewById(R.id.tv_correct_answer);

            int index = item.get("index") instanceof Number ? ((Number) item.get("index")).intValue() : 0;
            String type = item.get("type") != null ? item.get("type").toString() : "";
            int qScore = item.get("score") instanceof Number ? ((Number) item.get("score")).intValue() : 0;
            int isCorrect = item.get("isCorrect") instanceof Number ? ((Number) item.get("isCorrect")).intValue() : 0;

            tvIndex.setText("第 " + index + " 题");
            tvType.setText(typeLabel(type));
            tvScoreTag.setText(qScore + " 分");
            tvScoreTag.setTextColor(isCorrect == 1 ? 0xFF34C759 : 0xFFFF3B30);
            tvScoreTag.setBackgroundColor(isCorrect == 1 ? 0xFFE8F8EE : 0xFFFFECEB);

            RichTextRenderer.set(tvQuestion, String.valueOf(item.get("question")));
            tvUserAnswer.setText("你的答案：" + (item.get("userAnswer") != null ? item.get("userAnswer") : "未作答"));
            tvCorrectAnswer.setText("正确答案：" + (item.get("correctAnswer") != null ? item.get("correctAnswer") : ""));

            llDetail.addView(card);
        }
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "single_choice" -> "单选题";
            case "multiple_choice" -> "多选题";
            case "true_false" -> "判断题";
            case "fill_blank" -> "填空题";
            case "short_answer" -> "简答题";
            default -> type;
        };
    }
}
