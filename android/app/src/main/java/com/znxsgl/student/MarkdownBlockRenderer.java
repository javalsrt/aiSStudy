package com.znxsgl.student;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;

import io.noties.markwon.Markwon;

/**
 * 将 Markdown 按块渲染到 LinearLayout：普通块用 TextView，代码块用独立横向滚动容器。
 * 解决 RecyclerView 嵌套 RecyclerView 的滚动冲突，同时保证代码块完整不被切断。
 */
public class MarkdownBlockRenderer {

    public static void render(@NonNull Context context,
                              @NonNull Markwon markwon,
                              @NonNull String markdown,
                              @NonNull LinearLayout container) {
        container.removeAllViews();
        // 预处理：块公式独立成段、单 $ 转 $$、裸 LaTeX 命令自动补定界符（代码块原样保留）
        String prepared = convertInlineMathBackticks(RichTextRenderer.prepare(markdown));
        // 剩余反引号片段转为加粗文字：章节里多为音标/术语，不需要行内代码底色
        prepared = convertInlineCodeToBold(prepared);
        android.util.Log.d("MarkdownRender", "prepare后输入: " + prepared.substring(0, Math.min(prepared.length(), 600)));
        Node root = markwon.parse(prepared);
        Node child = root.getFirstChild();
        while (child != null) {
            if (child instanceof FencedCodeBlock) {
                FencedCodeBlock code = (FencedCodeBlock) child;
                String lang = code.getInfo() != null ? code.getInfo().trim().toLowerCase() : "";
                if ("mermaid".equals(lang)) {
                    addMermaidBlock(context, container, code.getLiteral());
                } else {
                    addCodeBlock(context, markwon, container, code);
                }
            } else if (child instanceof BlockQuote) {
                // 引用块（> ⚠️ 注意）改为浅蓝提示卡，避免默认左侧蓝线拉长显示异常
                addQuoteBlock(context, markwon, container, (BlockQuote) child);
            } else {
                addTextBlock(context, markwon, container, child);
            }
            child = child.getNext();
        }
    }

    /**
     * Mermaid 图渲染：用 WebView 加载 mermaid.js（CDN）把流程图/结构图绘制成 SVG。
     * mermaid 出图是异步的（晚于 onPageFinished），因此轮询等待 SVG 真正生成后
     * 通过 JavascriptInterface 回调原生层设置 WebView 高度，避免图被截断。
     */
    private static void addMermaidBlock(@NonNull Context context,
                                        @NonNull LinearLayout container,
                                        @NonNull String mermaidCode) {
        float density = context.getResources().getDisplayMetrics().density;
        WebView webView = new WebView(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (180 * density));
        params.setMargins(0, 0, 0, dp2px(context, 8));
        webView.setLayoutParams(params);
        webView.getSettings().setJavaScriptEnabled(true);
        android.graphics.drawable.GradientDrawable wbg = new android.graphics.drawable.GradientDrawable();
        wbg.setColor(0xFFFFFFFF);
        wbg.setCornerRadius(dp2px(context, 10));
        webView.setBackground(wbg);
        // 圆角裁剪，避免白色矩形直角与内容卡片风格不一致
        webView.setClipToOutline(true);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        final float fdensity = density;
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onHeight(final float cssHeight) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    int px = (int) (cssHeight * fdensity) + dp2px(context, 6);
                    webView.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, px));
                });
            }
        }, "NativeHeight");

        // 转义 HTML 特殊字符后注入
        String escaped = mermaidCode
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">"
                + "<style>body{margin:0;padding:8px;background:#FFFFFF;}"
                + ".mermaid{display:flex;justify-content:center;font-size:14px;}</style>"
                + "</head><body><pre class=\"mermaid\">" + escaped + "</pre>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js\"></script>"
                + "<script>"
                + "var pollCount = 0;"
                + "function pollHeight() {"
                + "  var svg = document.querySelector('.mermaid svg');"
                + "  if (svg && window.NativeHeight) {"
                + "    window.NativeHeight.onHeight(Math.ceil(Math.max(document.body.scrollHeight, 150)));"
                + "  } else if (pollCount++ < 80) { setTimeout(pollHeight, 150); }"
                + "}"
                + "mermaid.initialize({startOnLoad:true,theme:'base',"
                + "themeVariables:{primaryColor:'#EAF2FF',primaryBorderColor:'#7EA6E0',primaryTextColor:'#1D1D1F',lineColor:'#94A3B8'},"
                + "flowchart:{useMaxWidth:true}});"
                + "pollHeight();"
                + "</script>"
                + "</body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        container.addView(webView);
    }

    /**
     * AI 常把数学式包在反引号里（如 `limsup a_n <= limsup a_n`），导致公式渲染成代码块。
     * 启发式转换：反引号片段含 ^ / _ / \ 等数学标记时改写为 $$...$$，交给 LaTeX 渲染。
     */
    static String convertInlineMathBackticks(@NonNull String s) {
        if (!s.contains("`")) return s;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("`([^`\\n]+)`").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1);
            boolean math = inner.contains("^") || inner.contains("_") || inner.contains("\\");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    math ? "$$" + inner + "$$" : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 行内反引号 → 加粗：章节内容里的反引号多为音标/单词（如 /æ/、ship/），
     * 渲染成行内代码会出现蓝色底块，影响阅读；统一改为加粗文字，保留强调效果。
     * 围栏代码块（```）内部保持原样，避免破坏示例代码。
     */
    static String convertInlineCodeToBold(@NonNull String s) {
        if (!s.contains("`")) return s;
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("```[\\s\\S]*?```").matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while (fence.find()) {
            out.append(boldSingleBackticks(s.substring(last, fence.start())));
            out.append(fence.group());
            last = fence.end();
        }
        out.append(boldSingleBackticks(s.substring(last)));
        return out.toString();
    }

    private static String boldSingleBackticks(String segment) {
        if (!segment.contains("`")) return segment;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("`([^`\\n]+)`").matcher(segment);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("**" + m.group(1) + "**"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 清理深色/饱和底色的文字背景（AI 内容偶尔自带高亮或行内代码底色），
     * 保留浅灰表头等浅色背景；去掉底色后把原本的白字改为主蓝加粗，避免白字不可见。
     */
    private static Spanned stripDarkTextBackground(Spanned spanned) {
        BackgroundColorSpan[] bgSpans = spanned.getSpans(0, spanned.length(), BackgroundColorSpan.class);
        if (bgSpans.length == 0) return spanned;
        SpannableStringBuilder sb = new SpannableStringBuilder(spanned);
        for (BackgroundColorSpan span : bgSpans) {
            int start = sb.getSpanStart(span);
            int end = sb.getSpanEnd(span);
            sb.removeSpan(span);
            if (start < 0 || end <= start || end > sb.length()) continue;
            int bgColor = span.getBackgroundColor();
            if (androidx.core.graphics.ColorUtils.calculateLuminance(bgColor) > 0.75f) continue;
            ForegroundColorSpan[] fgSpans = sb.getSpans(start, end, ForegroundColorSpan.class);
            for (ForegroundColorSpan fg : fgSpans) {
                // 白/浅色文字在去掉底色后无法阅读，移除原前景色
                if (androidx.core.graphics.ColorUtils.calculateLuminance(fg.getForegroundColor()) > 0.7f) {
                    sb.removeSpan(fg);
                }
            }
            sb.setSpan(new ForegroundColorSpan(0xFF0A84FF), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** 引用块（> ⚠️ 注意）渲染为浅蓝圆角提示卡，替代默认左侧竖线样式 */
    private static void addQuoteBlock(@NonNull Context context, @NonNull Markwon markwon,
                                      @NonNull LinearLayout container, @NonNull BlockQuote quote) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(context, 12);
        card.setPadding(pad, dp2px(context, 10), pad, dp2px(context, 10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFF2F7FF);
        bg.setCornerRadius(dp2px(context, 10));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp2px(context, 8));
        card.setLayoutParams(lp);

        Node c = quote.getFirstChild();
        while (c != null) {
            if (c instanceof Paragraph) {
                TextView tv = new TextView(context);
                tv.setTextSize(14);
                tv.setTextColor(context.getResources().getColor(R.color.ink));
                tv.setLineSpacing(dp2px(context, 5), 1f);
                markwon.setParsedMarkdown(tv, markwon.render(c));
                card.addView(tv);
            }
            c = c.getNext();
        }
        container.addView(card);
    }

    private static void addTextBlock(@NonNull Context context,
                                     @NonNull Markwon markwon,
                                     @NonNull LinearLayout container,
                                     @NonNull Node node) {
        TextView textView = new TextView(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp2px(context, 8));
        textView.setLayoutParams(params);
        textView.setTextSize(15);
        textView.setTextColor(context.getResources().getColor(R.color.ink));
        textView.setLineSpacing(dp2px(context, 8), 1f);
        Spanned spanned = markwon.render(node);
        // 去掉深色文字底块（音标/术语的蓝底），改为无底色的主蓝加粗
        spanned = stripDarkTextBackground(spanned);
        // 必须走 setParsedMarkdown（内部 preRender），否则 LaTeX 异步公式图永远不会加载
        markwon.setParsedMarkdown(textView, spanned);
        container.addView(textView);
    }

    private static void addCodeBlock(@NonNull Context context,
                                     @NonNull Markwon markwon,
                                     @NonNull LinearLayout container,
                                     @NonNull FencedCodeBlock node) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_markdown_code_block, container, false);
        TextView tvCode = view.findViewById(R.id.tv_code);
        ImageView ivCopy = view.findViewById(R.id.iv_copy);

        // 用 Markwon 渲染该代码块节点，触发语法高亮
        Spanned spanned = markwon.render(node);
        tvCode.setText(spanned);

        final String code = node.getLiteral().trim();
        ivCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("code", code));
                Toast.makeText(v.getContext(), "代码已复制", Toast.LENGTH_SHORT).show();
            }
        });

        container.addView(view);
    }

    private static int dp2px(@NonNull Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
