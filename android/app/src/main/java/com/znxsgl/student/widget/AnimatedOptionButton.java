package com.znxsgl.student.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 选择题选项按钮（与判断题按钮统一风格）。
 * 未选中为浅灰圆角块，选中后底色与文字颜色平滑淡入为「浅蓝底 + 主蓝字」。
 *
 * 圆角改为自绘的 G2 连续曲率（SquirclePath），固定 16dp，与卡片、输入框同一套曲率；
 * 旧实现用 GradientDrawable 胶囊（半径 = 高度/2），被全局 SquircleInstaller 替换后
 * 未选中态是胶囊、选中态又像普通圆角，两态形状不一致。
 */
public class AnimatedOptionButton extends AppCompatTextView {

    private static final int COLOR_BG_IDLE = 0xFFFFFFFF;      // 白底（参考 Duolingo 词块）
    private static final int COLOR_BG_SELECTED = 0xFFE5F1FF;  // primary_soft 浅蓝
    private static final int COLOR_BORDER_IDLE = 0xFFE1E3E8;  // 浅灰描边
    private static final int COLOR_BORDER_SELECTED = 0xFF0A84FF;
    private static final int COLOR_TEXT_IDLE = 0xFF1D1D1F;    // 墨色文字
    private static final int COLOR_TEXT_SELECTED = 0xFF0A84FF;// 主蓝文字

    /** 与图形面板、输入框统一的 G2 圆角 */
    private static final float CORNER_RADIUS_DP = 16f;
    private static final long FADE_DURATION_MS = 180L;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private Path bgPath = new Path();
    private float progress = 0f;
    private boolean optionSelected = false;
    private ValueAnimator animator;

    public AnimatedOptionButton(Context context) {
        super(context);
        init();
    }

    public AnimatedOptionButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnimatedOptionButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setGravity(Gravity.CENTER_VERTICAL);
        setClickable(true);
        setFocusable(true);
        // 背景完全自绘，避免 SquircleInstaller 再次改写圆角
        setBackground(null);
        bgPaint.setColor(COLOR_BG_IDLE);
        setTextColor(COLOR_TEXT_IDLE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 内缩半个描边，避免边框被 View 边界裁掉
        float inset = dp(0.75f);
        bounds.set(inset, inset, w - inset, h - inset);
        bgPath = SquirclePath.create(bounds, dp(CORNER_RADIUS_DP));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        bgPaint.setColor(blendColor(COLOR_BG_IDLE, COLOR_BG_SELECTED, progress));
        canvas.drawPath(bgPath, bgPaint);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setColor(blendColor(COLOR_BORDER_IDLE, COLOR_BORDER_SELECTED, progress));
        canvas.drawPath(bgPath, borderPaint);
        super.onDraw(canvas);
    }

    public void setOptionSelected(boolean selected) {
        boolean changed = optionSelected != selected;
        optionSelected = selected;
        cancelAnimator();
        if (changed) {
            animateTo(selected ? 1f : 0f);
        } else {
            // 列表复用回绑时直接落到目标态，避免每次滑动都重新播放动画
            progress = selected ? 1f : 0f;
            applyProgress();
        }
    }

    public boolean isOptionSelected() {
        return optionSelected;
    }

    private void animateTo(float target) {
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(FADE_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            applyProgress();
        });
        animator.start();
    }

    private void applyProgress() {
        setTextColor(blendColor(COLOR_TEXT_IDLE, COLOR_TEXT_SELECTED, progress));
        invalidate();
    }

    private int blendColor(int from, int to, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        int a = (int) (android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * clamped);
        int r = (int) (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * clamped);
        int g = (int) (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * clamped);
        int b = (int) (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * clamped);
        return android.graphics.Color.argb(a, r, g, b);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        super.onDetachedFromWindow();
    }
}
