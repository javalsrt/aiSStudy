package com.znxsgl.student.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

/**
 * G2 连续曲率圆角（squircle）背景。
 *
 * 普通 shape 的圆角是正圆弧，直线段与圆角衔接处曲率突变；
 * 这里用超椭圆（|x|^n + |y|^n = 1，n = 5）采样绘制，曲率从直线段连续过渡，
 * 与 Web 端 corner-shape: squircle 的观感一致。
 */
public class G2Drawable extends Drawable {

    /** 超椭圆指数：2 为正圆，越大越接近方角；5 接近 iOS squircle */
    private static final int EXPONENT = 5;
    /** 每个圆角的采样段数，足够让 16dp 圆角看不出折线 */
    private static final int STEPS = 8;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float radiusPx;
    private final float strokeWidthPx;
    private final boolean hasStroke;

    private G2Drawable(int fillColor, int strokeColor, float strokeWidthPx, float radiusPx) {
        this.radiusPx = radiusPx;
        this.strokeWidthPx = strokeWidthPx;
        this.hasStroke = strokeWidthPx > 0 && Color.alpha(strokeColor) > 0;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);
        if (hasStroke) {
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidthPx);
            strokePaint.setColor(strokeColor);
        }
    }

    /** 无按压态的纯色/描边 G2 背景 */
    public static Drawable solid(Context context, int fillColor, int strokeColor,
                                 float strokeWidthDp, float radiusDp) {
        float density = context.getResources().getDisplayMetrics().density;
        return new G2Drawable(fillColor, strokeColor, strokeWidthDp * density, radiusDp * density);
    }

    /** 带水波纹的 G2 背景（用于可点击的树行 / 按钮） */
    public static Drawable pressed(Context context, int fillColor, int strokeColor,
                                   float strokeWidthDp, float radiusDp, int rippleColor) {
        Drawable content = solid(context, fillColor, strokeColor, strokeWidthDp, radiusDp);
        Drawable mask = solid(context, Color.WHITE, Color.TRANSPARENT, 0f, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rebuildPath(bounds);
    }

    private void rebuildPath(Rect b) {
        if (b.width() <= 0 || b.height() <= 0) {
            path.reset();
            return;
        }
        buildPath(new RectF(b), radiusPx, path);
    }

    /** 供 Canvas 直接绘制（如骨架屏）复用同一套 G2 路径 */
    public static void buildPath(RectF b, float radiusPx, Path out) {
        out.reset();
        if (b.width() <= 0 || b.height() <= 0) return;

        float r = Math.min(radiusPx, Math.min(b.width(), b.height()) / 2f);
        if (r <= 0f) {
            out.addRect(b, Path.Direction.CW);
            return;
        }

        float l = b.left, t = b.top, rt = b.right, bt = b.bottom;
        out.moveTo(l + r, t);
        out.lineTo(rt - r, t);
        // 右上角：从顶边到右边
        appendCorner(out, rt - r, t + r, r, 1, -1, true);
        out.lineTo(rt, bt - r);
        // 右下角：从右边到底边
        appendCorner(out, rt - r, bt - r, r, 1, 1, false);
        out.lineTo(l + r, bt);
        // 左下角：从底边到左边
        appendCorner(out, l + r, bt - r, r, -1, 1, true);
        out.lineTo(l, t + r);
        // 左上角：从左边到顶边
        appendCorner(out, l + r, t + r, r, -1, -1, false);
        out.close();
    }

    /**
     * 追加一个超椭圆圆角。
     *
     * @param dirX 圆心指向圆角外侧的 X 方向（1 或 -1）
     * @param dirY 圆心指向圆角外侧的 Y 方向（1 或 -1）
     * @param fromVertical 起点是否在竖直边（决定参数从 π/2 往 0 还是从 0 往 π/2）
     */
    private static void appendCorner(Path path, float cx, float cy, float r,
                                     int dirX, int dirY, boolean fromVertical) {
        for (int i = 1; i <= STEPS; i++) {
            double progress = (double) i / STEPS;
            double angle = Math.PI / 2 * (fromVertical ? 1 - progress : progress);
            double u = Math.pow(Math.max(0, Math.cos(angle)), 2.0 / EXPONENT);
            double v = Math.pow(Math.max(0, Math.sin(angle)), 2.0 / EXPONENT);
            path.lineTo(cx + dirX * r * (float) u, cy + dirY * r * (float) v);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (path.isEmpty()) return;
        canvas.drawPath(path, fillPaint);
        if (hasStroke) canvas.drawPath(path, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        strokePaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
