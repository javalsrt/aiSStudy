package com.znxsgl.student;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;

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
        webView.setBackgroundColor(0xFFFFFFFF);
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
                + "mermaid.initialize({startOnLoad:true,theme:'neutral',flowchart:{useMaxWidth:true}});"
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
