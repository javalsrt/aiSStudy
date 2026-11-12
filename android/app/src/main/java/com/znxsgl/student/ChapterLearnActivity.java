package com.znxsgl.student;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.znxsgl.student.model.Chapter;
import com.znxsgl.student.model.Lesson;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;
import com.znxsgl.student.widget.G2Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;
import io.noties.markwon.syntax.Prism4jThemeDefault;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChapterLearnActivity extends AppCompatActivity {

    private long courseId;
    private String token;
    private TextView tvProgress;
    private RecyclerView rvChapters;
    private ChapterAdapter adapter;

    private final List<Chapter> chapters = new ArrayList<>();
    private final Set<Long> completedIds = new HashSet<>();
    private final Set<Integer> expandedPositions = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chapter_learn);

        courseId = getIntent().getLongExtra("courseId", 0);
        String courseName = getIntent().getStringExtra("courseName");
        if (courseName == null) courseName = "章节学习";

        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        token = "Bearer " + prefs.getString("token", "");

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tv_title)).setText(courseName);
        tvProgress = findViewById(R.id.tv_progress);
        rvChapters = findViewById(R.id.rv_chapters);
        // 章节目录与总进度统一使用 G2 连续曲率圆角
        rvChapters.setBackground(G2Drawable.solid(this, 0xFFFFFFFF, 0xFFE5E5EA, 1f, 16f));
        tvProgress.setBackground(G2Drawable.solid(this, 0xFFE5F1FF, Color.TRANSPARENT, 0f, 100f));
        rvChapters.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChapterAdapter();
        rvChapters.setAdapter(adapter);

        loadCompletedThenChapters();
    }

    private void loadCompletedThenChapters() {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getCompletedLessons(token, courseId).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> c, Response<ResponseBody> r) {
                try {
                    if (r.isSuccessful() && r.body() != null) {
                        JSONObject obj = new JSONObject(r.body().string());
                        JSONArray arr = obj.optJSONArray("lessonIds");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) completedIds.add(arr.getLong(i));
                        }
                    }
                } catch (Exception ignored) {}
                loadChapters();
            }
            @Override public void onFailure(Call<ResponseBody> c, Throwable t) { loadChapters(); }
        });
    }

    private void loadChapters() {
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getCourseChapters(token, courseId).enqueue(new Callback<List<Chapter>>() {
            @Override public void onResponse(Call<List<Chapter>> c, Response<List<Chapter>> r) {
                chapters.clear();
                if (r.isSuccessful() && r.body() != null) {
                    chapters.addAll(r.body());
                }
                expandedPositions.clear();
                if (!chapters.isEmpty()) expandedPositions.add(0);
                adapter.notifyDataSetChanged();
                updateProgress();
            }
            @Override public void onFailure(Call<List<Chapter>> c, Throwable t) {
                Toast.makeText(ChapterLearnActivity.this, UiMsg.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateProgress() {
        int total = 0;
        for (Chapter ch : chapters) total += ch.getLessons().size();
        int done = 0;
        for (Chapter ch : chapters) for (Lesson l : ch.getLessons()) if (completedIds.contains(l.getId())) done++;
        tvProgress.setText("已完成 " + done + "/" + total + " 课时");
    }

    private void markComplete(Lesson lesson, TextView btnComplete) {
        if (completedIds.contains(lesson.getId())) return;
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("lessonId", lesson.getId());
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.markLessonComplete(token, body).enqueue(new Callback<ResponseBody>() {
            @Override public void onResponse(Call<ResponseBody> c, Response<ResponseBody> r) {
                if (r.isSuccessful()) {
                    completedIds.add(lesson.getId());
                    btnComplete.setText("✓已完成");
                    btnComplete.setBackground(G2Drawable.pressed(
                            ChapterLearnActivity.this, 0xFF34C759,
                            Color.TRANSPARENT, 0f, 8f, 0x22000000));
                    updateProgress();
                    adapter.notifyDataSetChanged();
                } else {
                    Toast.makeText(ChapterLearnActivity.this, "标记失败", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onFailure(Call<ResponseBody> c, Throwable t) {
                Toast.makeText(ChapterLearnActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private class ChapterAdapter extends RecyclerView.Adapter<ChapterVH> {
        private final Handler delayHandler = new Handler(Looper.getMainLooper());
        private final Markwon markwon = Markwon.builder(ChapterLearnActivity.this)
                .usePlugin(HtmlPlugin.create())
                // 数学公式（$$...$$，配合 MarkdownBlockRenderer 的反引号转换）
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(
                        getResources().getDisplayMetrics().scaledDensity * 14f,
                        builder -> {
                            builder.inlinesEnabled(true).blocksEnabled(true);
                            builder.errorHandler((latex, error) -> {
                                // 单条公式失败时显示红字原文，不影响其余内容
                                android.graphics.Paint paint = new android.graphics.Paint();
                                paint.setAntiAlias(true);
                                paint.setColor(0xFFFF3B30);
                                float size = getResources().getDisplayMetrics().scaledDensity * 14f;
                                paint.setTextSize(size);
                                float w = Math.max(paint.measureText(latex) + size, 1f);
                                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                                        (int) w, (int) (size * 1.6f), android.graphics.Bitmap.Config.ARGB_8888);
                                android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                                c.drawText(latex, size / 2f, size, paint);
                                return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
                            });
                        }))
                // Markdown 表格（GFM 管道表）
                .usePlugin(TablePlugin.create(theme -> theme
                        .tableBorderColor(0xFFE5E5EA)
                        .tableBorderWidth(1)
                        .tableCellPadding((int) (getResources().getDisplayMetrics().density * 10))
                        .tableHeaderRowBackgroundColor(0xFFF0F0F4)
                        .tableEvenRowBackgroundColor(0xFFFFFFFF)
                        .tableOddRowBackgroundColor(0xFFFFFFFF)))
                .usePlugin(SyntaxHighlightPlugin.create(
                        new Prism4j(new PrismGrammarLocator()),
                        Prism4jThemeDefault.create()
                ))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        builder
                                // 行内强调（音标/术语）：去掉蓝色底块，改为无底色的主蓝加粗文字，更清爽
                                .codeBackgroundColor(android.graphics.Color.TRANSPARENT)
                                .codeTextColor(0xFF0A84FF)
                                .codeTypeface(Typeface.DEFAULT_BOLD)
                                // 围栏代码块：浅灰底 + 墨色文字
                                .codeBlockBackgroundColor(0xFFF0F0F4)
                                .codeBlockTextColor(0xFF1D1D1F)
                                .codeBlockTypeface(Typeface.MONOSPACE)
                                .codeBlockMargin(0)
                                .blockMargin(0)
                                // 标题：HarmonyOS Medium 字重，去掉下划线分隔
                                .headingTypeface(androidx.core.content.res.ResourcesCompat
                                        .getFont(ChapterLearnActivity.this, R.font.harmonyos_sans_sc_medium))
                                .headingBreakHeight(0)
                                // 提示卡（> ⚠️ 注意）：主蓝竖线
                                .blockQuoteColor(0xFF0A84FF)
                                .blockQuoteWidth(4);
                    }
                })
                .build();

        @NonNull @Override
        public ChapterVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chapter, parent, false);
            return new ChapterVH(v);
        }

        @Override public void onBindViewHolder(@NonNull ChapterVH h, int pos) {
            Chapter ch = chapters.get(pos);
            h.tvTitle.setText("第" + ch.getChapterNo() + "章 · " + ch.getChapterName());

            int total = ch.getLessons().size();
            int doneCount = 0;
            for (Lesson l : ch.getLessons()) if (completedIds.contains(l.getId())) doneCount++;
            // 与 BoardUI tree-view 一致：右侧只保留紧凑的 4/6 进度
            h.tvProgress.setText(doneCount + "/" + total);
            boolean chapterDone = total > 0 && doneCount == total;
            h.tvProgress.setTextColor(chapterDone ? 0xFF34C759 : 0xFF8E8E93);

            boolean expanded = expandedPositions.contains(pos);
            // 无课时的章不显示展开箭头（保留占位以对齐图标）
            h.ivChevron.setVisibility(total > 0 ? View.VISIBLE : View.INVISIBLE);
            h.ivChevron.setRotation(expanded ? 90f : 0f);
            h.ivIcon.setImageResource(expanded
                    ? R.drawable.ic_tree_folder_open
                    : R.drawable.ic_tree_folder);
            h.llHeader.setBackground(G2Drawable.pressed(
                    ChapterLearnActivity.this,
                    expanded ? 0xFFF2F2F7 : Color.TRANSPARENT,
                    Color.TRANSPARENT, 0f, 12f, 0x12000000));
            h.llLessons.setVisibility(expanded ? View.VISIBLE : View.GONE);
            h.llHeader.setOnClickListener(v -> {
                if (expandedPositions.contains(pos)) {
                    expandedPositions.remove(pos);
                } else {
                    expandedPositions.add(pos);
                }
                notifyItemChanged(pos);
            });

            h.llLessons.removeAllViews();
            // 先过滤视频课时，得到实际展示的课时列表
            java.util.List<Lesson> shownLessons = new ArrayList<>();
            for (Lesson l : ch.getLessons()) {
                if (l.getResourceType() != null && l.getResourceType().toLowerCase().contains("video")) continue;
                shownLessons.add(l);
            }
            for (int li = 0; li < shownLessons.size(); li++) {
                Lesson l = shownLessons.get(li);
                boolean isLastLesson = li == shownLessons.size() - 1;
                View lv = LayoutInflater.from(h.itemView.getContext()).inflate(R.layout.item_lesson, h.llLessons, false);
                LinearLayout llHeader = lv.findViewById(R.id.ll_lesson_header);
                ImageView ivIcon = lv.findViewById(R.id.iv_lesson_icon);
                ImageView tvArrow = lv.findViewById(R.id.tv_lesson_arrow);
                ((TextView) lv.findViewById(R.id.tv_lesson_name)).setText(l.getLessonName());
                // 树形引导线：所有叶子都从上方接入，仅最后一个叶子不向下延伸
                lv.findViewById(R.id.line_lesson_top).setVisibility(View.VISIBLE);
                lv.findViewById(R.id.line_lesson_bottom).setVisibility(
                        isLastLesson ? View.INVISIBLE : View.VISIBLE);
                LinearLayout llContentArea = lv.findViewById(R.id.ll_lesson_content_area);
                // 章节内容卡片：12dp G2 圆角（外层树面板 16dp - 4dp 内边距 = 同心）
                llContentArea.setBackground(G2Drawable.solid(
                        ChapterLearnActivity.this, 0xFFFFFFFF, 0xFFE5E5EA, 1f, 12f));
                TextView btnComplete = lv.findViewById(R.id.btn_complete);
                LinearLayout llMarkdownContent = lv.findViewById(R.id.ll_markdown_content);
                if (l.getContent() != null && !l.getContent().isEmpty()) {
                    String formatted = MarkdownUtils.autoFormatIfNeeded(l.getContent());
                    MarkdownBlockRenderer.render(ChapterLearnActivity.this, markwon, formatted, llMarkdownContent);
                }
                boolean done = completedIds.contains(l.getId());
                Runnable showBtnTask = () -> {
                    if (!completedIds.contains(l.getId()) && llContentArea.getVisibility() == View.VISIBLE) {
                        btnComplete.setVisibility(View.VISIBLE);
                    }
                };
                btnComplete.setTag(showBtnTask);
                if (done) {
                    btnComplete.setVisibility(View.VISIBLE);
                    btnComplete.setText("✓ 已完成");
                    btnComplete.setBackground(G2Drawable.pressed(
                            ChapterLearnActivity.this, 0xFF34C759,
                            Color.TRANSPARENT, 0f, 8f, 0x22000000));
                    btnComplete.setTextColor(0xFFFFFFFF);
                } else {
                    btnComplete.setVisibility(View.GONE);
                    btnComplete.setText("完成学习");
                    btnComplete.setBackground(G2Drawable.pressed(
                            ChapterLearnActivity.this, 0xFF0A84FF,
                            Color.TRANSPARENT, 0f, 8f, 0x22000000));
                    btnComplete.setTextColor(0xFFFFFFFF);
                }
                // 节点视觉：展开时浅灰底 + 箭头翻转，已完成时文件图标变绿
                Runnable refreshVisual = () -> updateLessonCardVisual(
                        llHeader, ivIcon, tvArrow, done, llContentArea.getVisibility() == View.VISIBLE);
                refreshVisual.run();
                // 点击课时行展开/折叠 content（仅标题行触发，阅读内容时不会误收起）
                llHeader.setOnClickListener(v -> {
                    if (llContentArea.getVisibility() == View.VISIBLE) {
                        llContentArea.setVisibility(View.GONE);
                        Object tag = btnComplete.getTag();
                        if (tag instanceof Runnable) delayHandler.removeCallbacks((Runnable) tag);
                        if (!completedIds.contains(l.getId())) btnComplete.setVisibility(View.GONE);
                    } else if (l.getContent() != null && !l.getContent().isEmpty()) {
                        llContentArea.setVisibility(View.VISIBLE);
                        if (!completedIds.contains(l.getId())) {
                            btnComplete.setVisibility(View.GONE);
                            delayHandler.removeCallbacks((Runnable) btnComplete.getTag());
                            delayHandler.postDelayed((Runnable) btnComplete.getTag(), 5000);
                        }
                    }
                    refreshVisual.run();
                });
                btnComplete.setOnClickListener(v -> markComplete(l, btnComplete));
                h.llLessons.addView(lv);
            }
        }

        @Override public int getItemCount() { return chapters.size(); }
    }

    static class ChapterVH extends RecyclerView.ViewHolder {
        ImageView ivChevron;
        ImageView ivIcon;
        TextView tvTitle;
        TextView tvProgress;
        LinearLayout llHeader;
        LinearLayout llLessons;
        ChapterVH(View v) {
            super(v);
            ivChevron = v.findViewById(R.id.iv_chapter_chevron);
            ivIcon = v.findViewById(R.id.iv_chapter_icon);
            tvTitle = v.findViewById(R.id.tv_chapter_title);
            tvProgress = v.findViewById(R.id.tv_chapter_progress);
            llHeader = v.findViewById(R.id.ll_chapter_header);
            llLessons = v.findViewById(R.id.ll_lessons);
        }
    }

    /** 叶子节点视觉：展开时浅灰底 + 箭头翻转，已完成时文件图标变绿 */
    private void updateLessonCardVisual(View header, ImageView icon, ImageView arrow, boolean done, boolean expanded) {
        header.setBackground(G2Drawable.pressed(
                this,
                expanded ? 0xFFF2F2F7 : Color.TRANSPARENT,
                Color.TRANSPARENT, 0f, 12f, 0x12000000));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                done ? 0xFF34C759 : 0xFFC7C7CC));
        arrow.setRotation(expanded ? 180f : 0f);
    }

    private String typeLabel(String type) {
        if (type == null) return "资料";
        String t = type.toLowerCase();
        if (t.contains("video")) return "视频";
        if (t.contains("doc")) return "文档";
        if (t.contains("quiz") || t.contains("test")) return "测验";
        if (t.contains("discuss")) return "讨论";
        return type;
    }

    private String typeIcon(String type) {
        if (type == null) return "资";
        String t = type.toLowerCase();
        if (t.contains("video")) return "▶";
        if (t.contains("doc")) return "文";
        if (t.contains("quiz") || t.contains("test")) return "测";
        if (t.contains("discuss")) return "讨";
        return "资";
    }
}
