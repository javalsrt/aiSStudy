package com.znxsgl.student;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.znxsgl.student.model.QuizQuestion;

import java.util.List;

public class QuizPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_CHOICE = 0;
    private static final int TYPE_JUDGE = 1;
    private static final int TYPE_ANALYSIS = 2;
    private static final int TYPE_FILL = 3;

    // 统一配色
    private static final int COLOR_PRIMARY = 0xFF5E6AD2;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_HAIRLINE = 0xFFE5E5EA;
    private static final int COLOR_INK = 0xFF1D1D1F;
    private static final int COLOR_SUCCESS = 0xFF34C759;
    private static final int COLOR_DANGER = 0xFFFF3B30;

    private final List<QuizQuestion> questions;
    private OnAnswerListener listener;

    public interface OnAnswerListener {
        void onAnswered(int position, String answer);
        void onAutoSkip(int position);
    }

    public QuizPagerAdapter(List<QuizQuestion> questions) { this.questions = questions; }
    public void setOnAnswerListener(OnAnswerListener l) { this.listener = l; }

    @Override public int getItemViewType(int pos) {
        String t = questions.get(pos).getQuestionType();
        if ("判断".equals(t)) return TYPE_JUDGE;
        if ("解析".equals(t)) return TYPE_ANALYSIS;
        if ("填空".equals(t)) return TYPE_FILL;
        return TYPE_CHOICE;
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (type == TYPE_JUDGE) return new JudgeVH(inf.inflate(R.layout.item_quiz_judge, parent, false));
        if (type == TYPE_ANALYSIS) return new AnalysisVH(inf.inflate(R.layout.item_quiz_analysis, parent, false));
        if (type == TYPE_FILL) return new FillVH(inf.inflate(R.layout.item_quiz_fill, parent, false));
        return new ChoiceVH(inf.inflate(R.layout.item_quiz_choice, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
        QuizQuestion q = questions.get(pos);
        if (h instanceof ChoiceVH) bindChoice((ChoiceVH) h, q, pos);
        else if (h instanceof JudgeVH) bindJudge((JudgeVH) h, q, pos);
        else if (h instanceof AnalysisVH) bindAnalysis((AnalysisVH) h, q, pos);
        else if (h instanceof FillVH) bindFill((FillVH) h, q, pos);
    }

    @Override public int getItemCount() { return questions.size(); }

    // ===== 选择题 =====
    private void bindChoice(ChoiceVH vh, QuizQuestion q, int pos) {
        vh.tvTag.setText("单选题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        vh.tvQuestion.setText(q.getQuestion());
        vh.llOptions.removeAllViews();

        List<String> options = q.getOptions();
        if (options != null) {
            for (String optText : options) {
                boolean selected = optText.equals(q.getUserAnswer());
                TextView tv = createOptionText(vh.itemView.getContext(), optText, selected);

                final String answer = optText;
                tv.setOnClickListener(v -> {
                    if (answer.equals(q.getUserAnswer())) return;
                    q.setUserAnswer(answer);
                    q.setModifiedCount(q.getModifiedCount() + 1);
                    notifyItemChanged(pos);
                    if (listener != null) listener.onAnswered(pos, answer);
                });
                vh.llOptions.addView(tv);
            }
        }
    }

    // ===== 判断题 =====
    private void bindJudge(JudgeVH vh, QuizQuestion q, int pos) {
        vh.tvTag.setText("判断题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        vh.tvQuestion.setText(q.getQuestion());

        boolean selectedTrue = "正确".equals(q.getUserAnswer());
        boolean selectedFalse = "错误".equals(q.getUserAnswer());

        applyJudgeStyle(vh.btnTrue, "正确", selectedTrue, true);
        applyJudgeStyle(vh.btnFalse, "错误", selectedFalse, false);
        applyPillFeedback(vh.btnTrue);
        applyPillFeedback(vh.btnFalse);

        vh.btnTrue.setOnClickListener(v -> {
            if (selectedTrue) return;
            q.setUserAnswer("正确");
            q.setModifiedCount(q.getModifiedCount() + 1);
            notifyItemChanged(pos);
            v.postDelayed(() -> {
                if (listener != null) listener.onAnswered(pos, "正确");
            }, 200);
        });
        vh.btnFalse.setOnClickListener(v -> {
            if (selectedFalse) return;
            q.setUserAnswer("错误");
            q.setModifiedCount(q.getModifiedCount() + 1);
            notifyItemChanged(pos);
            v.postDelayed(() -> {
                if (listener != null) listener.onAnswered(pos, "错误");
            }, 200);
        });
    }

    // ===== 解析题 =====
    private void bindAnalysis(AnalysisVH vh, QuizQuestion q, int pos) {
        vh.tvTag.setText("解析题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        vh.tvQuestion.setText(q.getQuestion());
        if (q.getUserAnswer() == null || q.getUserAnswer().isEmpty()) {
            vh.etAnswer.setText("");
        } else if (!vh.etAnswer.getText().toString().equals(q.getUserAnswer())) {
            vh.etAnswer.setText(q.getUserAnswer());
        }
    }

    // ===== 填空题 =====
    private void bindFill(FillVH vh, QuizQuestion q, int pos) {
        vh.tvTag.setText("填空题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        vh.tvQuestion.setText(q.getQuestion());
        if (q.getUserAnswer() == null || q.getUserAnswer().isEmpty()) {
            vh.etAnswer.setText("");
        } else if (!vh.etAnswer.getText().toString().equals(q.getUserAnswer())) {
            vh.etAnswer.setText(q.getUserAnswer());
        }
    }

    /** 统一选项样式：白色卡片 + 选中主色 */
    private TextView createOptionText(android.content.Context ctx, String text, boolean selected) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18));
        tv.setBackground(makeOptionBg(selected));
        tv.setTextColor(selected ? COLOR_WHITE : COLOR_INK);
        tv.setClickable(true);
        tv.setFocusable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(ctx, 12));
        tv.setLayoutParams(lp);

        // 点击反馈
        tv.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
            }
            return false;
        });
        return tv;
    }

    private void applyJudgeStyle(TextView tv, String label, boolean selected, boolean isTrue) {
        tv.setText(label);
        tv.setBackgroundResource(R.drawable.bg_btn_pill);
        tv.setSelected(selected);
        tv.setTextColor(selected ? COLOR_WHITE : (isTrue ? COLOR_SUCCESS : COLOR_DANGER));
        tv.setElevation(dp(tv.getContext(), selected ? 0 : 4));
    }

    private void applyPillFeedback(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.96f).scaleY(0.96f)
                        .translationZ(dp(view.getContext(), 1))
                        .setDuration(80).start();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f)
                        .translationZ(dp(view.getContext(), 4))
                        .setDuration(120).start();
            }
            return false;
        });
    }

    private GradientDrawable makeOptionBg(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14);
        bg.setColor(selected ? COLOR_PRIMARY : COLOR_WHITE);
        bg.setStroke(1, selected ? COLOR_PRIMARY : COLOR_HAIRLINE);
        return bg;
    }

    private int dp(android.content.Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 提交前收集解析/填空题的文本答案 */
    public void collectTextAnswers() {
        // 暂存当前输入，供 submitQuiz 使用
    }

    // ===== ViewHolders =====
    static class ChoiceVH extends RecyclerView.ViewHolder {
        TextView tvTag, tvNum, tvQuestion; LinearLayout llOptions;
        ChoiceVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question); llOptions = v.findViewById(R.id.ll_options); }
    }
    static class JudgeVH extends RecyclerView.ViewHolder {
        TextView tvTag, tvNum, tvQuestion, btnTrue, btnFalse;
        JudgeVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question);
            btnTrue = v.findViewById(R.id.btn_true); btnFalse = v.findViewById(R.id.btn_false); }
    }
    public static class AnalysisVH extends RecyclerView.ViewHolder {
        public TextView tvTag, tvNum, tvQuestion; public EditText etAnswer;
        AnalysisVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question); etAnswer = v.findViewById(R.id.et_answer); }
    }
    public static class FillVH extends RecyclerView.ViewHolder {
        public TextView tvTag, tvNum, tvQuestion; public EditText etAnswer;
        FillVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question); etAnswer = v.findViewById(R.id.et_answer); }
    }
}