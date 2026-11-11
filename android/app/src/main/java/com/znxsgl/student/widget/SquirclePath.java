package com.znxsgl.student.widget;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * G2 连续曲率圆角（超椭圆 n=4）路径生成工具。
 *
 * 普通 drawRoundRect 生成的是 G1 圆弧，直边与圆角连接处曲率突变；
 * 这里用超椭圆采样点近似 iOS Squircle，过渡更顺滑。
 */
public final class SquirclePath {

    private static final int CORNER_SEGMENTS = 16;
    /** 超椭圆指数 2/n，n=4 时为 0.5 */
    private static final double EXPONENT = 0.5;

    private SquirclePath() {
    }

    /** 生成整块圆角矩形路径；radius 会自动收敛到不超过短边的一半 */
    public static Path create(RectF rect, float radius) {
        Path path = new Path();
        if (rect.width() <= 0 || rect.height() <= 0) {
            return path;
        }
        if (radius <= 0) {
            path.addRect(rect, Path.Direction.CW);
            return path;
        }
        float max = Math.min(rect.width(), rect.height()) / 2f;
        float r = Math.min(radius, max);
        float left = rect.left;
        float top = rect.top;
        float right = rect.right;
        float bottom = rect.bottom;

        path.moveTo(left + r, top);
        path.lineTo(right - r, top);
        appendCorner(path, right - r, top + r, r, -90, 0);
        path.lineTo(right, bottom - r);
        appendCorner(path, right - r, bottom - r, r, 0, 90);
        path.lineTo(left + r, bottom);
        appendCorner(path, left + r, bottom - r, r, 90, 180);
        path.lineTo(left, top + r);
        appendCorner(path, left + r, top + r, r, 180, 270);
        path.close();
        return path;
    }

    private static void appendCorner(Path path, float cx, float cy, float r,
                                     float startDeg, float endDeg) {
        for (int i = 1; i <= CORNER_SEGMENTS; i++) {
            float deg = startDeg + (endDeg - startDeg) * i / CORNER_SEGMENTS;
            double t = Math.toRadians(deg);
            double cos = Math.cos(t);
            double sin = Math.sin(t);
            float x = (float) (cx + r * Math.signum(cos) * Math.pow(Math.abs(cos), EXPONENT));
            float y = (float) (cy + r * Math.signum(sin) * Math.pow(Math.abs(sin), EXPONENT));
            path.lineTo(x, y);
        }
    }
}
