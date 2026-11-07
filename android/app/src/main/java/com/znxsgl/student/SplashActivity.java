package com.znxsgl.student;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.appcompat.app.AppCompatActivity;

import com.znxsgl.student.network.RetrofitClient;

/**
 * 启动页：品牌渐变背景 + 图标弹性进场 + 光晕脉冲 + 标语淡入。
 * 停留约 1.5 秒后按登录态进入主页或登录页，并做淡入淡出切换。
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ValueAnimator glowAnim;
    private boolean navigated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        // 与登录页一致，初始化全局 Context（401 拦截跳转依赖）
        RetrofitClient.init(this);

        playEntrance();
        handler.postDelayed(this::navigateNext, SPLASH_DURATION_MS);
    }

    private void playEntrance() {
        View logoBox = findViewById(R.id.fl_logo);
        View glow = findViewById(R.id.v_glow);
        View appName = findViewById(R.id.tv_app_name);
        View slogan = findViewById(R.id.tv_slogan);

        float dp = getResources().getDisplayMetrics().density;
        float rise = dp * 10;

        // 图标弹性放大 + 淡入
        logoBox.setScaleX(0.78f);
        logoBox.setScaleY(0.78f);
        logoBox.setAlpha(0f);
        logoBox.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(560)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .start();

        // 名称与标语随后上浮淡入
        appName.setAlpha(0f);
        appName.setTranslationY(rise);
        slogan.setAlpha(0f);
        slogan.setTranslationY(rise);
        appName.animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(420).start();
        slogan.animate().alpha(1f).translationY(0f).setStartDelay(350).setDuration(420).start();

        // 图标背后的光晕：向外扩散并逐渐消隐，循环播放
        glow.setScaleX(0.95f);
        glow.setScaleY(0.95f);
        glow.setAlpha(0f);
        glowAnim = ValueAnimator.ofFloat(0f, 1f);
        glowAnim.setDuration(1700);
        glowAnim.setStartDelay(320);
        glowAnim.setRepeatCount(ValueAnimator.INFINITE);
        glowAnim.setInterpolator(new LinearInterpolator());
        glowAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float scale = 0.95f + 0.5f * f;
            glow.setScaleX(scale);
            glow.setScaleY(scale);
            glow.setAlpha(0.32f * (1f - f));
        });
        glowAnim.start();
    }

    private void navigateNext() {
        if (navigated || isFinishing()) return;
        navigated = true;
        SharedPreferences prefs = getSharedPreferences("znxsgl", MODE_PRIVATE);
        String token = prefs.getString("token", null);
        Intent next = token != null
                ? new Intent(this, MainActivity.class)
                : new Intent(this, LoginActivity.class);
        startActivity(next);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (glowAnim != null) glowAnim.cancel();
        super.onDestroy();
    }
}
