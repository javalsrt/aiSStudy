package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.znxsgl.student.R;

import java.util.Locale;

/**
 * 函数图像绘制控件：解析形如 "y=2*x+3" 的表达式，在坐标系中绘制曲线。
 *
 * 纯自研轻量实现：递归下降表达式解析（支持 + - * / ^、括号、
 * sin/cos/tan/sqrt/abs/ln/log/exp 函数与 pi、e 常量、隐式乘号），
 * 取点采样后绘制网格、坐标轴与曲线。表达式解析失败时不绘制，由调用方隐藏视图。
 */
public class FunctionPlotView extends View {

    /** 表达式节点：给定 x 求值 */
    private interface Node {
        double eval(double x);
    }

    private static final double X_MIN = -10d;
    private static final double X_MAX = 10d;
    private static final int SAMPLE_COUNT = 480;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();

    private Node node;
    private double yMin = -10d;
    private double yMax = 10d;

    public FunctionPlotView(Context context) {
        super(context);
        init();
    }

    public FunctionPlotView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FunctionPlotView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.hairline));
        gridPaint.setStrokeWidth(d);
        axisPaint.setColor(ContextCompat.getColor(getContext(), R.color.ink_tertiary));
        axisPaint.setStrokeWidth(1.5f * d);
        curvePaint.setColor(ContextCompat.getColor(getContext(), R.color.primary));
        curvePaint.setStrokeWidth(2.5f * d);
        curvePaint.setStyle(Paint.Style.STROKE);
        labelPaint.setColor(ContextCompat.getColor(getContext(), R.color.ink_subtle));
        labelPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 10f);
    }

    /**
     * 设置函数表达式（可带 "y=" 前缀）。
     * @return 解析是否成功；失败时控件不绘制任何曲线，调用方应隐藏该视图
     */
    public boolean setExpression(String expr) {
        node = null;
        if (expr != null && !expr.trim().isEmpty()) {
            try {
                node = new Parser(normalize(expr)).parseAll();
            } catch (Exception e) {
                node = null;
            }
        }
        if (node != null) autoFitY();
        invalidate();
        return node != null;
    }

    // ==================== 表达式规范化 ====================

    /** 统一全角/排版字符，去掉 y= / f(x)= 前缀 */
    private static String normalize(String raw) {
        String s = raw.trim()
                .replace("×", "*").replace("·", "*").replace("÷", "/")
                .replace("−", "-").replace("－", "-").replace("＋", "+")
                .replace("²", "^2").replace("³", "^3")
                .replace("{", "").replace("}", "")
                .toLowerCase(Locale.US);
        return s.replaceAll("^(y|f\\(x\\)|g\\(x\\))\\s*=\\s*", "");
    }

    // ==================== y 轴自适应 ====================

    private void autoFitY() {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int k = 0; k <= SAMPLE_COUNT; k++) {
            double x = X_MIN + (X_MAX - X_MIN) * k / SAMPLE_COUNT;
            double y = node.eval(x);
            if (Double.isFinite(y) && Math.abs(y) < 1e6) {
                if (y < min) min = y;
                if (y > max) max = y;
            }
        }
        if (min == Double.POSITIVE_INFINITY || max == Double.NEGATIVE_INFINITY) {
            yMin = -10d;
            yMax = 10d;
            return;
        }
        if (max - min < 4d) {
            double c = (min + max) / 2d;
            min = c - 2d;
            max = c + 2d;
        }
        // 陡峭函数（如 tan）收敛到以原点附近的合理窗口
        if (max - min > 40d) {
            min = Math.max(min, -20d);
            max = Math.min(max, 20d);
        }
        double pad = (max - min) * 0.12d;
        yMin = min - pad;
        yMax = max + pad;
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (node == null) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float d = getResources().getDisplayMetrics().density;
        float padL = getPaddingLeft() + 4 * d;
        float padR = getPaddingRight() + 4 * d;
        float padT = getPaddingTop() + 4 * d;
        float padB = getPaddingBottom() + 4 * d;
        float labelH = labelPaint.getTextSize() + 6 * d;
        float plotW = w - padL - padR;
        float plotH = h - padT - padB - labelH;
        if (plotW <= 0 || plotH <= 0) return;

        float pxPerX = plotW / (float) (X_MAX - X_MIN);

        // 网格与刻度步长：保证相邻网格在屏幕上足够稀疏
        double gridStep = pickStep(pxPerX, 20 * d);
        double labelStep = pickStep(pxPerX, 46 * d);

        canvas.save();
        canvas.clipRect(padL, padT, padL + plotW, padT + plotH);

        // 网格
        for (double gx = Math.ceil(X_MIN / gridStep) * gridStep; gx <= X_MAX; gx += gridStep) {
            float px = toPx(gx, padL, plotW);
            canvas.drawLine(px, padT, px, padT + plotH, gridPaint);
        }
        for (double gy = Math.ceil(yMin / gridStep) * gridStep; gy <= yMax; gy += gridStep) {
            float py = toPy(gy, padT, plotH);
            canvas.drawLine(padL, py, padL + plotW, py, gridPaint);
        }

        // 坐标轴（0 落在可视范围内才画）
        if (0d >= yMin && 0d <= yMax) {
            float py = toPy(0d, padT, plotH);
            canvas.drawLine(padL, py, padL + plotW, py, axisPaint);
        }
        if (0d >= X_MIN && 0d <= X_MAX) {
            float px = toPx(0d, padL, plotW);
            canvas.drawLine(px, padT, px, padT + plotH, axisPaint);
        }

        // 曲线：按像素列采样，断点/跳跃处断开路径（避免 tan 等函数的垂直连线）
        curvePath.reset();
        boolean started = false;
        double prevY = 0d;
        double jumpLimit = (yMax - yMin) * 2d;
        for (int px = 0; px <= plotW; px++) {
            double x = X_MIN + (X_MAX - X_MIN) * px / plotW;
            double y = node.eval(x);
            if (!Double.isFinite(y) || Math.abs(y) > 1e6) {
                started = false;
                continue;
            }
            if (started && Math.abs(y - prevY) > jumpLimit) {
                started = false; // 相邻采样值跳变过大，视为不连续
            }
            float py = toPy(y, padT, plotH);
            // 远离可视区时断开，避免垂直线条
            if (py < padT - plotH * 2 || py > padT + plotH * 3) {
                started = false;
                prevY = y;
                continue;
            }
            if (!started) {
                curvePath.moveTo(padL + px, py);
                started = true;
            } else {
                curvePath.lineTo(padL + px, py);
            }
            prevY = y;
        }
        canvas.drawPath(curvePath, curvePaint);
        canvas.restore();

        // x 轴刻度数值（画在绘图区下方的留白条里）
        float labelBaseline = padT + plotH + labelH - 2 * d;
        labelPaint.setTextAlign(Paint.Align.CENTER);
        for (double v = Math.ceil(X_MIN / labelStep) * labelStep; v <= X_MAX; v += labelStep) {
            float px = toPx(v, padL, plotW);
            if (px < padL + 12 * d) {
                labelPaint.setTextAlign(Paint.Align.LEFT);
            } else if (px > padL + plotW - 12 * d) {
                labelPaint.setTextAlign(Paint.Align.RIGHT);
            } else {
                labelPaint.setTextAlign(Paint.Align.CENTER);
            }
            canvas.drawText(fmt(v), px, labelBaseline, labelPaint);
        }
    }

    /** 从 1/2/5/10/20 中选出使单格像素宽度不小于 minPx 的最小步长 */
    private static double pickStep(float pxPerUnit, float minPx) {
        double[] steps = {1d, 2d, 5d, 10d, 20d};
        for (double s : steps) {
            if (pxPerUnit * s >= minPx) return s;
        }
        return 20d;
    }

    private float toPx(double x, float padL, float plotW) {
        return padL + (float) ((x - X_MIN) / (X_MAX - X_MIN) * plotW);
    }

    private float toPy(double y, float padT, float plotH) {
        return padT + (float) ((yMax - y) / (yMax - yMin) * plotH);
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.round(v)) < 1e-9) {
            return String.valueOf((long) Math.round(v));
        }
        return String.format(Locale.US, "%.1f", v);
    }

    // ==================== 递归下降解析器 ====================

    /** 文法：expr := term (('+'|'-') term)*；term := unary (('*'|'/'|隐式) unary)*；
     *  unary := ('-'|'+') unary | power；power := primary ('^' unary)? */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Node parseAll() throws Exception {
            Node n = expr();
            ws();
            if (i < s.length()) throw new Exception("多余字符: " + s.substring(i));
            return n;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            ws();
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private Node expr() throws Exception {
            Node left = term();
            while (true) {
                char c = peek();
                if (c == '+') {
                    i++;
                    Node r = term();
                    Node l = left;
                    left = x -> l.eval(x) + r.eval(x);
                } else if (c == '-') {
                    i++;
                    Node r = term();
                    Node l = left;
                    left = x -> l.eval(x) - r.eval(x);
                } else {
                    return left;
                }
            }
        }

        private Node term() throws Exception {
            Node left = unary();
            while (true) {
                char c = peek();
                if (c == '*') {
                    i++;
                    Node r = unary();
                    Node l = left;
                    left = x -> l.eval(x) * r.eval(x);
                } else if (c == '/') {
                    i++;
                    Node r = unary();
                    Node l = left;
                    left = x -> l.eval(x) / r.eval(x);
                } else if (c == '\0' || c == '+' || c == '-' || c == ')') {
                    return left;
                } else if (Character.isLetterOrDigit(c) || c == '(') {
                    // 隐式乘号，如 2x、3(x+1)、x sin(x)
                    Node r = unary();
                    Node l = left;
                    left = x -> l.eval(x) * r.eval(x);
                } else {
                    throw new Exception("意外字符: " + c);
                }
            }
        }

        private Node unary() throws Exception {
            char c = peek();
            if (c == '-') {
                i++;
                Node n = unary();
                return x -> -n.eval(x);
            }
            if (c == '+') {
                i++;
                return unary();
            }
            return power();
        }

        private Node power() throws Exception {
            Node base = primary();
            if (peek() == '^') {
                i++;
                Node expNode = unary();
                return x -> Math.pow(base.eval(x), expNode.eval(x));
            }
            return base;
        }

        private Node primary() throws Exception {
            char c = peek();
            if (c == '(') {
                i++;
                Node n = expr();
                if (peek() != ')') throw new Exception("缺少右括号");
                i++;
                return n;
            }
            if (Character.isDigit(c) || c == '.') return number();
            if (Character.isLetter(c)) return ident();
            throw new Exception("意外字符: " + c);
        }

        private Node number() throws Exception {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            try {
                final double v = Double.parseDouble(s.substring(start, i));
                return x -> v;
            } catch (NumberFormatException e) {
                throw new Exception("非法数字");
            }
        }

        private Node ident() throws Exception {
            int start = i;
            while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
            final String name = s.substring(start, i);
            if (peek() == '(') {
                i++;
                final Node arg = expr();
                if (peek() != ')') throw new Exception("缺少右括号");
                i++;
                switch (name) {
                    case "sin": return x -> Math.sin(arg.eval(x));
                    case "cos": return x -> Math.cos(arg.eval(x));
                    case "tan": return x -> Math.tan(arg.eval(x));
                    case "asin": return x -> Math.asin(arg.eval(x));
                    case "acos": return x -> Math.acos(arg.eval(x));
                    case "atan": return x -> Math.atan(arg.eval(x));
                    case "sqrt": return x -> Math.sqrt(arg.eval(x));
                    case "abs": return x -> Math.abs(arg.eval(x));
                    case "ln": return x -> Math.log(arg.eval(x));
                    case "log": return x -> Math.log10(arg.eval(x));
                    case "exp": return x -> Math.exp(arg.eval(x));
                    default: throw new Exception("未知函数: " + name);
                }
            }
            switch (name) {
                case "x": return x -> x;
                case "pi": return x -> Math.PI;
                case "e": return x -> Math.E;
                default: throw new Exception("未知标识符: " + name);
            }
        }
    }
}
