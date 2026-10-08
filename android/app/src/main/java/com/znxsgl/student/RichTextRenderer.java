package com.znxsgl.student;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.annotation.NonNull;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin;
import io.noties.markwon.syntax.Prism4jThemeDarkula;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;

/**
 * 统一富文本渲染器：Markdown + LaTeX 数学公式（$...$ 行内 / $$...$$ 独立块）
 * + 代码高亮 + HTML。用于答题题目、AI 回复、解析等场景。
 *
 * 用法：RichTextRenderer.set(textView, text);
 * 纯文本直接 setText，含 Markdown/LaTeX 时走 Markwon 渲染，渲染异常时降级为纯文本。
 */
public final class RichTextRenderer {

    private RichTextRenderer() {}

    private static volatile Markwon instance;

    public static Markwon get(Context context) {
        if (instance == null) {
            synchronized (RichTextRenderer.class) {
                if (instance == null) {
                    final Context app = context.getApplicationContext();
                    // 公式基准字号（14sp），随系统字体缩放
                    final float texTextSize = app.getResources().getDisplayMetrics().scaledDensity * 14f;
                    instance = Markwon.builder(app)
                            .usePlugin(HtmlPlugin.create())
                            // 关键：JLatexMathPlugin 依赖行内解析器，缺失会导致构建 Markwon 时抛异常
                            .usePlugin(MarkwonInlineParserPlugin.create())
                            .usePlugin(JLatexMathPlugin.create(texTextSize, builder -> {
                                // 显式开启行内 $...$ 与独立块 $$...$$ 公式解析
                                builder.inlinesEnabled(true).blocksEnabled(true);
                                // 单条公式解析失败时：只把该条公式显示为红字原文，不影响其余内容
                                builder.errorHandler((latex, error) -> {
                                    android.graphics.Paint paint = new android.graphics.Paint();
                                    paint.setAntiAlias(true);
                                    paint.setColor(0xFFFF3B30);
                                    paint.setTextSize(texTextSize);
                                    float w = Math.max(paint.measureText(latex) + texTextSize, 1f);
                                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                                            (int) w, (int) (texTextSize * 1.6f),
                                            android.graphics.Bitmap.Config.ARGB_8888);
                                    android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                                    c.drawText(latex, texTextSize / 2f, texTextSize, paint);
                                    return new android.graphics.drawable.BitmapDrawable(app.getResources(), bmp);
                                });
                            }))
                            .usePlugin(SyntaxHighlightPlugin.create(
                                    new Prism4j(new PrismGrammarLocator()),
                                    Prism4jThemeDarkula.create()))
                            .usePlugin(new AbstractMarkwonPlugin() {
                                @Override
                                public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                                    builder.codeBlockTextColor(0xFFE2E8F0)
                                            .codeBlockTypeface(Typeface.MONOSPACE)
                                            .codeBlockMargin(0)
                                            .blockMargin(0)
                                            .headingBreakColor(0xFFE0E6F0);
                                }
                            })
                            .build();
                }
            }
        }
        return instance;
    }

    /** 向 TextView 渲染富文本；空文本直接清空；异常时降级为纯文本 */
    public static void set(TextView tv, String text) {
        if (tv == null) return;
        if (text == null || text.trim().isEmpty()) {
            tv.setText("");
            return;
        }
        try {
            get(tv.getContext()).setMarkdown(tv, prepare(text));
        } catch (Exception e) {
            android.util.Log.e("RichTextRenderer", "渲染失败，降级为纯文本", e);
            tv.setText(text);
        }
    }

    /**
     * 预处理入口：代码块（``` 围栏）原样保留，其余部分依次做
     * 块公式独立成段 → 单 $ 转 $$ → 裸 LaTeX 命令自动补 $$ 定界符。
     */
    public static String prepare(String markdown) {
        if (markdown == null || (!markdown.contains("\\") && !markdown.contains("$"))) return markdown;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("```[\\s\\S]*?```").matcher(markdown);
        StringBuilder sb = new StringBuilder(markdown.length() + 32);
        int last = 0;
        while (m.find()) {
            sb.append(transformSegment(markdown.substring(last, m.start())));
            sb.append(m.group());
            last = m.end();
        }
        sb.append(transformSegment(markdown.substring(last)));
        return sb.toString();
    }

    private static String transformSegment(String s) {
        String out = normalizeLatex(s);
        out = wrapBareLatex(out);
        return out;
    }

    /**
     * 裸 LaTeX 自动包裹：AI 常直接把 \lim_{n \to \infty}、a_n \to a 等命令混在正文里
     * 而忘记加 $$ 定界符。扫描含 LaTeX 命令（\字母）的连续片段，向两侧扩展
     * 数学字符（数字/字母/上下标/括号/运算符等），自动包上 $$...$$。
     * 跳过：行内代码（`...`）、已被 $ 定界的部分、纯中文边界。
     */
    public static String wrapBareLatex(String text) {
        if (!text.contains("\\")) return text;
        StringBuilder sb = new StringBuilder(text.length() + 32);
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            // 行内代码原样跳过
            if (c == '`') {
                int close = text.indexOf('`', i + 1);
                if (close < 0) { sb.append(text, i, n); break; }
                sb.append(text, i, close + 1);
                i = close + 1;
                continue;
            }
            if (c == '\\' && i + 1 < n && Character.isLetter(text.charAt(i + 1))) {
                int ls = expandLeft(text, i);
                int re = expandRight(text, i);
                boolean delimited = (ls > 0 && text.charAt(ls - 1) == '$')
                        || (re < n && text.charAt(re) == '$');
                String run = text.substring(ls, re).trim();
                if (!delimited && run.contains("\\") && run.length() > 2) {
                    sb.append("$$").append(run).append("$$");
                } else {
                    sb.append(text, ls, re);
                }
                i = re;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 是否为公式片段允许的字符（中文、换行、$、反引号都会终止扩展） */
    private static boolean isLatexChar(char c) {
        if (c == '\n' || c == '$' || c == '`') return false;
        if (c > 0x2E7F) return false; // 中文及全角字符
        return Character.isLetterOrDigit(c)
                || " \\{}^_()[]|.,+-=<>/!'%*~;:?&\"".indexOf(c) >= 0;
    }

    private static int expandLeft(String text, int from) {
        int i = from;
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (!isLatexChar(c)) break;
            // 英文单词（连续 2+ 字母）不属于公式，停止包含
            if (Character.isLetter(c) && i - 2 >= 0 && Character.isLetter(text.charAt(i - 2))) break;
            i--;
        }
        return i;
    }

    private static int expandRight(String text, int from) {
        int n = text.length();
        int i = from + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (!isLatexChar(c)) break;
            if (Character.isLetter(c)) {
                // 取出完整单词：非 LaTeX 来源的英文单词（2+ 字母）不属于公式
                int j = i;
                while (j < n && Character.isLetter(text.charAt(j))) j++;
                char prev = i > 0 ? text.charAt(i - 1) : ' ';
                boolean latexWord = prev == '\\' || prev == '{' || prev == '_' || prev == '^' || prev == '/';
                if (!latexWord && j - i >= 2) break;
                i = j;
                continue;
            }
            i++;
        }
        return i;
    }

    /**
     * 规范化 LaTeX 定界符：Markwon 4.6.2 的行内处理器只认 $$...$$，
     * 把单个 $...$ 行内公式统一转成 $$...$$。句中的 $$...$$ 保持原位（内联渲染），
     * 独立成段的 $$...$$ 由块解析器渲染，无需强行拆段。
     */
    private static String normalizeLatex(String text) {
        if (!text.contains("$")) return text;
        java.util.regex.Pattern single = java.util.regex.Pattern.compile(
                "(?<!\\$)\\$(?!\\$)([^$\\n]+?)\\$(?!\\$)");
        java.util.regex.Matcher m = single.matcher(text);
        StringBuffer buf = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(buf, java.util.regex.Matcher.quoteReplacement("$$" + m.group(1) + "$$"));
        }
        m.appendTail(buf);
        return buf.toString();
    }
}
