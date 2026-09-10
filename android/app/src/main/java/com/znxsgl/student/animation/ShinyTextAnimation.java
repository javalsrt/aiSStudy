package com.znxsgl.student.animation;

import android.animation.ValueAnimator;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.widget.TextView;

/**
 * 文字扫光动画（Shiny Text）
 * 参考 reactbits.dev/text-animations/shiny-text 实现思路：
 * 使用 LinearGradient 的高光区域在文字上水平扫过，配合 ValueAnimator 循环播放。
 */
public class ShinyTextAnimation {

    private static final long DEFAULT_DURATION = 2200L;

    /**
     * 为 TextView 应用扫光动画。
     *
     * @param textView 目标文字控件
     */
    public static void applyShimmer(TextView textView) {
        applyShimmer(textView, DEFAULT_DURATION);
    }

    /**
     * 为 TextView 应用扫光动画，可自定义单次扫描时长。
     */
    public static void applyShimmer(TextView textView, long durationMillis) {
        if (textView == null) {
            return;
        }

        textView.post(() -> {
            int width = textView.getWidth();
            if (width <= 0) {
                return;
            }

            // 高光渐变：主色 -> 高光白 -> 主色，营造金属扫光感
            int[] colors = new int[]{
                    0xFF5E6AD2,
                    0xFFFFFFFF,
                    0xFF5E6AD2
            };
            float[] positions = new float[]{0f, 0.5f, 1f};

            LinearGradient gradient = new LinearGradient(
                    -width, 0, width, 0,
                    colors, positions,
                    Shader.TileMode.CLAMP
            );

            ValueAnimator animator = ValueAnimator.ofFloat(-1f, 2f);
            animator.setDuration(durationMillis);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);

            animator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                Matrix matrix = new Matrix();
                // 将渐变整体水平移动，形成扫光效果
                matrix.setTranslate(value * width, 0);
                gradient.setLocalMatrix(matrix);
                textView.getPaint().setShader(gradient);
                textView.invalidate();
            });

            animator.start();
            // 将 animator 绑定到 view tag，便于外部需要时停止
            textView.setTag(animator);
        });
    }

    /**
     * 停止指定 TextView 上的扫光动画并恢复普通绘制。
     */
    public static void stopShimmer(TextView textView) {
        if (textView == null) {
            return;
        }
        Object tag = textView.getTag();
        if (tag instanceof ValueAnimator) {
            ((ValueAnimator) tag).cancel();
        }
        textView.getPaint().setShader(null);
        textView.invalidate();
    }
}
