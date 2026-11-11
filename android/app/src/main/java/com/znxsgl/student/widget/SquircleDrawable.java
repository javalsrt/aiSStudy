package com.znxsgl.student.widget;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * G2 连续曲率圆角背景：把任意 Drawable（纯色、渐变、描边）裁剪到超椭圆路径内。
 * 用于运行时替换 XML shape / 代码创建的 GradientDrawable 圆角背景。
 */
public class SquircleDrawable extends Drawable {

    private final Drawable content;
    private final float radius;
    private Path path;

    public SquircleDrawable(@NonNull Drawable content, float radius) {
        this.content = content;
        this.radius = radius;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        content.setBounds(bounds);
        path = SquirclePath.create(new RectF(bounds), radius);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        if (path == null) {
            path = SquirclePath.create(new RectF(bounds), radius);
        }
        int save = canvas.save();
        canvas.clipPath(path);
        content.setBounds(bounds);
        content.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        content.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        content.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return content.getOpacity() == PixelFormat.UNKNOWN ? PixelFormat.TRANSLUCENT : content.getOpacity();
    }

    @Override
    public boolean isStateful() {
        return content.isStateful();
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        return content.getPadding(padding);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        return content.setState(state);
    }
}
