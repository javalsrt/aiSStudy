package com.znxsgl.student;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.znxsgl.student.model.QuizQuestion;
import com.znxsgl.student.widget.AnimatedChoiceButton;
import com.znxsgl.student.widget.AnimatedOptionButton;
import com.znxsgl.student.widget.FunctionPlotView;

import java.util.List;

public class QuizPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_CHOICE = 0;
    private static final int TYPE_JUDGE = 1;
    private static final int TYPE_ANALYSIS = 2;
    private static final int TYPE_FILL = 3;

    private final List<QuizQuestion> questions;
    private OnAnswerListener listener;

    public interface OnAnswerListener {
        void onAnswered(int position, String answer);
        void onAutoSkip(int position);
        /** 解析题/填空题输入框获得焦点（软键盘随之弹起） */
        default void onInputFocused(EditText et) { }
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
        // 题干去重：AI 生成偶发整句重复
        String question = Texts.removeDuplicateSentences(q.getQuestion());
        if (h instanceof ChoiceVH) bindChoice((ChoiceVH) h, q, question, pos);
        else if (h instanceof JudgeVH) bindJudge((JudgeVH) h, q, question, pos);
        else if (h instanceof AnalysisVH) bindAnalysis((AnalysisVH) h, q, question, pos);
        else if (h instanceof FillVH) bindFill((FillVH) h, q, question, pos);
    }

    @Override public int getItemCount() { return questions.size(); }

    // ===== 选择题 =====
    private void bindChoice(ChoiceVH vh, QuizQuestion q, String question, int pos) {
        vh.tvTag.setText("单选题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        RichTextRenderer.set(vh.tvQuestion, question);
        bindGraph(vh.graphView, q);
        vh.llOptions.removeAllViews();

        List<String> options = q.getOptions();
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                final String answer = options.get(i);
                boolean selected = answer.equals(q.getUserAnswer());
                View row = createOptionRow(vh.itemView.getContext(), i, answer, selected, () -> {
                    if (answer.equals(q.getUserAnswer())) return;
                    q.setUserAnswer(answer);
                    q.setModifiedCount(q.getModifiedCount() + 1);
                    notifyItemChanged(pos);
                    if (listener != null) listener.onAnswered(pos, answer);
                });
                vh.llOptions.addView(row);
            }
        }
    }

    // ===== 判断题 =====
    private void bindJudge(JudgeVH vh, QuizQuestion q, String question, int pos) {
        vh.tvTag.setText("判断题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        RichTextRenderer.set(vh.tvQuestion, question);
        bindGraph(vh.graphView, q);

        boolean selectedTrue = "正确".equals(q.getUserAnswer());
        boolean selectedFalse = "错误".equals(q.getUserAnswer());

        vh.btnTrue.setText("正确");
        vh.btnFalse.setText("错误");
        vh.btnTrue.setSelected(selectedTrue);
        vh.btnFalse.setSelected(selectedFalse);

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
    private void bindAnalysis(AnalysisVH vh, QuizQuestion q, String question, int pos) {
        vh.tvTag.setText("解析题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        RichTextRenderer.set(vh.tvQuestion, question);
        bindGraph(vh.graphView, q);
        bindTextAnswer(vh.etAnswer, q);
    }

    // ===== 填空题 =====
    private void bindFill(FillVH vh, QuizQuestion q, String question, int pos) {
        vh.tvTag.setText("填空题");
        vh.tvNum.setText((pos + 1) + "/" + questions.size());
        RichTextRenderer.set(vh.tvQuestion, question);
        bindGraph(vh.graphView, q);
        bindTextAnswer(vh.etAnswer, q);
    }

    /**
     * 函数图像绑定：题目带 graph 表达式且解析成功时显示坐标曲线，否则隐藏。
     */
    private void bindGraph(FunctionPlotView view, QuizQuestion q) {
        if (view == null) return;
        String g = q.getGraph();
        if (g == null || g.trim().isEmpty()) {
            view.setVisibility(View.GONE);
            return;
        }
        boolean ok = view.setExpression(g);
        view.setVisibility(ok ? View.VISIBLE : View.GONE);
    }

    /**
     * 解析题/填空题输入框绑定：用户输入实时写回 QuizQuestion.userAnswer。
     * 修复：此前没有监听器，提交时空值被统一改写成“不会”，导致报告显示「未作答」。
     * ViewHolder 复用需先移除旧监听器，避免答案串题。
     */
    private void bindTextAnswer(EditText et, QuizQuestion q) {
        Object tag = et.getTag();
        if (tag instanceof TextWatcher) {
            et.removeTextChangedListener((TextWatcher) tag);
        }
        // 回显已保存答案（先清监听再回显，避免回显触发写回导致光标跳动）
        String saved = q.getUserAnswer();
        if (saved == null) saved = "";
        // 文本没变就不要重设：bind 阶段调用 setText 会让输入法与 EditText 的编辑状态失步，
        // 出现「能继续输入但退格删不掉」的问题
        if (!saved.contentEquals(et.getText())) {
            et.setText(saved);
            // 光标收到末尾：setText 会把光标重置到开头，导致继续输入时插在最前面
            Editable cur = et.getText();
            if (cur != null) et.setSelection(cur.length());
        }

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                q.setUserAnswer(s.toString().trim());
            }
        };
        et.addTextChangedListener(watcher);
        et.setTag(watcher);

        // 输入框内的触摸不再交给 ViewPager2：否则横向拖动会被当成翻页手势，
        // 表现为长按选择、拖选、退格等操作失灵（「能输入但删不掉」）
        et.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        // 获得焦点后把输入框滚到可见区域，避免被软键盘挡住
        et.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) return;
            if (listener != null) listener.onInputFocused(et);
            et.post(() -> {
                android.graphics.Rect rect = new android.graphics.Rect();
                et.getDrawingRect(rect);
                et.requestRectangleOnScreen(rect, false);
            });
        });
    }

    /** 选项行：圆形字母徽标 + 选项胶囊按钮（点徽标或胶囊均可选中） */
    private View createOptionRow(android.content.Context ctx, int index, String rawText,
                                 boolean selected, Runnable onSelect) {
        char letter = (char) ('A' + index);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(ctx, 12));
        row.setLayoutParams(rowLp);

        TextView chip = new TextView(ctx);
        chip.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)));
        chip.setGravity(Gravity.CENTER);
        chip.setText(String.valueOf(letter));
        chip.setTextSize(15);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(ContextCompat.getColor(ctx, selected ? R.color.primary : R.color.ink_muted));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setShape(GradientDrawable.OVAL);
        chipBg.setColor(ContextCompat.getColor(ctx, selected ? R.color.primary_soft : R.color.surface_3));
        chip.setBackground(chipBg);

        AnimatedOptionButton tv = new AnimatedOptionButton(ctx);
        // AI 选项文本常自带 "A. " 前缀，剥掉后由圆徽标呈现，避免重复
        RichTextRenderer.set(tv, stripOptionLetter(rawText));
        tv.setTextSize(17);
        tv.setPadding(dp(ctx, 16), dp(ctx, 18), dp(ctx, 16), dp(ctx, 18));
        tv.setOptionSelected(selected);
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.setMarginStart(dp(ctx, 12));
        tv.setLayoutParams(tvLp);

        View.OnClickListener click = v -> onSelect.run();
        tv.setOnClickListener(click);
        chip.setOnClickListener(click);

        row.addView(chip);
        row.addView(tv);
        return row;
    }

    /** 去掉 AI 选项自带的 "A." / "B、" 等字母前缀 */
    private static String stripOptionLetter(String text) {
        if (text == null) return "";
        return text.replaceFirst("^\\s*[A-Da-dＡ-Ｄ][\\.、,\\)）:：]\\s*", "").trim();
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

    private int dp(android.content.Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 提交前收集解析/填空题的文本答案 */
    public void collectTextAnswers() {
        // 暂存当前输入，供 submitQuiz 使用
    }

    // ===== ViewHolders =====
    static class ChoiceVH extends RecyclerView.ViewHolder {
        TextView tvTag, tvNum, tvQuestion; LinearLayout llOptions; FunctionPlotView graphView;
        ChoiceVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question); llOptions = v.findViewById(R.id.ll_options);
            graphView = v.findViewById(R.id.graph_view); }
    }
    static class JudgeVH extends RecyclerView.ViewHolder {
        TextView tvTag, tvNum, tvQuestion;
        AnimatedChoiceButton btnTrue, btnFalse;
        FunctionPlotView graphView;
        JudgeVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question);
            btnTrue = v.findViewById(R.id.btn_true); btnFalse = v.findViewById(R.id.btn_false);
            graphView = v.findViewById(R.id.graph_view); }
    }
    public static class AnalysisVH extends RecyclerView.ViewHolder {
        public TextView tvTag, tvNum, tvQuestion; public EditText etAnswer; FunctionPlotView graphView;
        AnalysisVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question); etAnswer = v.findViewById(R.id.et_answer);
            graphView = v.findViewById(R.id.graph_view); }
    }
    public static class FillVH extends RecyclerView.ViewHolder {
        public TextView tvTag, tvNum, tvQuestion; public EditText etAnswer; FunctionPlotView graphView;
        FillVH(View v) { super(v);
            tvTag = v.findViewById(R.id.tv_quiz_tag); tvNum = v.findViewById(R.id.tv_quiz_num);
            tvQuestion = v.findViewById(R.id.tv_quiz_question); etAnswer = v.findViewById(R.id.et_answer);
            graphView = v.findViewById(R.id.graph_view); }
    }
}