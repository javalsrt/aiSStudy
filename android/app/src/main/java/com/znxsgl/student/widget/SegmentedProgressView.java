package com.znxsgl.student.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.znxsgl.student.R;

/**
 * 分段进度条：每道题对应一段，已答段填充主色，当前段浅色，未答段细线色。
 * 用于答题页顶部，替代细线进度条，让"还剩几题"一眼可见。
 */
public class SegmentedProgressView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int total = 0;
    private int current = -1;
    private final int colorDone;
    private final int colorCurrent;
    private final int colorTodo;

    public SegmentedProgressView(Context context) {
        super(context);
        colorDone = ContextCompat.getColor(context, R.color.primary);
        colorCurrent = ContextCompat.getColor(context, R.color.primary_disabled);
        colorTodo = ContextCompat.getColor(context, R.color.hairline_strong);
    }

    public SegmentedProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        colorDone = ContextCompat.getColor(context, R.color.primary);
        colorCurrent = ContextCompat.getColor(context, R.color.primary_disabled);
        colorTodo = ContextCompat.getColor(context, R.color.hairline_strong);
    }

    public SegmentedProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        colorDone = ContextCompat.getColor(context, R.color.primary);
        colorCurrent = ContextCompat.getColor(context, R.color.primary_disabled);
        colorTodo = ContextCompat.getColor(context, R.color.hairline_strong);
    }

    /** @param current 当前题下标，从 0 开始；小于 0 表示还没开始 */
    public void setProgress(int current, int total) {
        this.current = current;
        this.total = Math.max(total, 0);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (total <= 0) return;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = h / 2f;

        // 底槽：整条浅灰
        rect.set(0, 0, w, h);
        paint.setColor(colorTodo);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // 已完成：连续主色条（参考 Duolingo 的粗进度条），当前题计入进度
        int done = Math.max(1, Math.min(current + 1, total));
        float doneW = w * done / total;
        rect.set(0, 0, Math.max(doneW, h), h);
        paint.setColor(colorDone);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
