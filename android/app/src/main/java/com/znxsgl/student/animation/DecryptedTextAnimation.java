package com.znxsgl.student.animation;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.Random;

/**
 * 解密文本动画（Decrypted Text）
 * 参考 reactbits.dev/text-animations/decrypted-text 实现思路：
 * 每个字符先显示为随机乱码，随后按顺序逐步“解密”为真实字符，循环播放。
 */
public class DecryptedTextAnimation {

    private static final String RANDOM_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
    private static final long DEFAULT_REVEAL_DURATION = 1800L;
    private static final long DEFAULT_HOLD_DURATION = 1200L;

    private final TextView textView;
    private final String targetText;
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ValueAnimator revealAnimator;
    private Runnable loopRunnable;
    private boolean running = false;

    public DecryptedTextAnimation(TextView textView, String targetText) {
        this.textView = textView;
        this.targetText = targetText;
    }

    /**
     * 启动循环解密动画：解密 -> 停留 -> 重置 -> 再次解密。
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        startRevealCycle();
    }

    public void stop() {
        running = false;
        if (revealAnimator != null) {
            revealAnimator.cancel();
        }
        if (loopRunnable != null) {
            handler.removeCallbacks(loopRunnable);
        }
        textView.setText(targetText);
    }

    private void startRevealCycle() {
        if (!running) {
            return;
        }

        revealAnimator = ValueAnimator.ofFloat(0f, 1f);
        revealAnimator.setDuration(DEFAULT_REVEAL_DURATION);
        revealAnimator.addUpdateListener(animation -> {
            if (textView == null) {
                return;
            }
            float progress = (float) animation.getAnimatedValue();
            textView.setText(buildDecryptedText(progress));
        });
        revealAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!running) {
                    return;
                }
                textView.setText(targetText);
                loopRunnable = () -> startRevealCycle();
                handler.postDelayed(loopRunnable, DEFAULT_HOLD_DURATION);
            }
        });
        revealAnimator.start();
    }

    /**
     * 根据进度构建当前显示的解密文本。
     * progress 0 -> 全部乱码；progress 1 -> 全部真实字符。
     */
    private String buildDecryptedText(float progress) {
        int len = targetText.length();
        if (len == 0) {
            return "";
        }
        int revealedCount = (int) (len * progress);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char targetChar = targetText.charAt(i);
            if (targetChar == ' ') {
                sb.append(' ');
            } else if (i < revealedCount) {
                sb.append(targetChar);
            } else {
                sb.append(RANDOM_POOL.charAt(random.nextInt(RANDOM_POOL.length())));
            }
        }
        return sb.toString();
    }
}
