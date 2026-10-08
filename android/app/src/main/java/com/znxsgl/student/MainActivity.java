package com.znxsgl.student;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.znxsgl.student.fragment.FocusFragment;
import com.znxsgl.student.fragment.ScheduleFragment;
import com.znxsgl.student.fragment.ProfileFragment;
import com.znxsgl.student.network.RetrofitClient;
import com.znxsgl.student.network.WebSocketManager;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private FragmentManager fragmentManager;
    private Fragment currentFragment;
    private FocusFragment focusFragment;
    private BottomNavigationView bottomNav;
    /** 各 tab 的 Fragment 缓存：切换只 show/hide，避免每次重建 + 重新请求 */
    private final Map<Integer, Fragment> fragmentCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 在 MainActivity 生命周期管理 WebSocket，确保整个 App 期间保持连接
        connectWebSocket();

        fragmentManager = getSupportFragmentManager();

        bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            // 进入「我的」即视为已读，清除红点
            if (id == R.id.nav_profile) {
                profileUnread = 0;
                updateProfileBadge();
            }
            // 答题中切换：先弹窗确认
            if (id != R.id.nav_focus && focusFragment != null && focusFragment.isQuizActive()) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("取消答题？")
                        .setMessage("正在答题中，切换界面将取消本次测试，不记录成绩。")
                        .setPositiveButton("确定取消", (d, w) -> {
                            focusFragment.cancelQuiz();
                            switchFragment(getFragmentById(id));
                        })
                        .setNegativeButton("继续答题", (d, w) -> {
                            // 恢复导航栏选中状态为专注
                            bottomNav.setSelectedItemId(R.id.nav_focus);
                        })
                        .setOnDismissListener(d -> {
                            // 点击外部关闭时也要恢复
                            if (focusFragment != null && focusFragment.isQuizActive()) {
                                bottomNav.setSelectedItemId(R.id.nav_focus);
                            }
                        })
                        .show();
                return true;
            }
            switchFragment(getFragmentById(id));
            return true;
        });

        bottomNav.setSelectedItemId(R.id.nav_schedule);
    }

    /** 答题时隐藏悬浮导航（全屏答题），结束后恢复 */
    public void setBottomNavVisible(boolean visible) {
        if (bottomNav == null) return;
        if (visible) {
            bottomNav.setVisibility(android.view.View.VISIBLE);
            bottomNav.animate().alpha(1f).setDuration(180L).start();
        } else {
            bottomNav.animate().alpha(0f).setDuration(180L)
                    .withEndAction(() -> bottomNav.setVisibility(android.view.View.INVISIBLE))
                    .start();
        }
    }

    // ========== 「我的」未读红点 ==========
    private int profileUnread = 0;

    /** 收到新消息时累加红点（供 WebSocket 回调调用） */
    public void incrementProfileUnread() {
        profileUnread++;
        updateProfileBadge();
    }

    private void updateProfileBadge() {
        if (bottomNav == null) return;
        if (profileUnread > 0) {
            com.google.android.material.badge.BadgeDrawable badge =
                    bottomNav.getOrCreateBadge(R.id.nav_profile);
            badge.setVisible(true);
            badge.setMaxCharacterCount(3);
            badge.setNumber(Math.min(profileUnread, 99));
        } else {
            bottomNav.removeBadge(R.id.nav_profile);
        }
    }

    private void connectWebSocket() {
        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        long userId = prefs.getLong("userId", 0);
        if (userId == 0) return;

        WebSocketManager ws = WebSocketManager.getInstance();
        ws.setListener((courseName, content, scheduleInfo) -> {
            runOnUiThread(() -> {
                showScheduleToast(content);
                // 「我的」tab 累加未读红点
                incrementProfileUnread();
                // 通知 ProfileFragment 刷新课程列表（红点状态）
                if (currentFragment instanceof ProfileFragment) {
                    ((ProfileFragment) currentFragment).loadCoursesIfAdded();
                }
            });
        });
        ws.connect(RetrofitClient.getBaseUrl(), userId);
    }

    /** 显示排课通知 —— 自定义多行 Toast，一目了然 */
    private void showScheduleToast(String content) {
        // 用自定义 View 替代默认 Toast，支持多行显示
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(content);
        tv.setTextSize(15);
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setPadding(36, 24, 36, 24);
        tv.setLineSpacing(6f, 1f);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE61D1D1F);
        bg.setCornerRadius(24f);
        tv.setBackground(bg);

        android.widget.Toast toast = new android.widget.Toast(this);
        toast.setDuration(android.widget.Toast.LENGTH_LONG);
        toast.setView(tv);
        toast.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, 160);
        toast.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 恢复状态栏颜色（防止系统重置）
        applyStatusBarForCurrentFragment();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WebSocketManager.getInstance().disconnect();
    }

    private void applyStatusBarForCurrentFragment() {
        if (currentFragment instanceof ScheduleFragment) {
            getWindow().setStatusBarColor(Color.WHITE);
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightStatusBars(true);
        } else {
            getWindow().setStatusBarColor(0xFFF2F2F7);
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightStatusBars(true);
        }
    }

    private Fragment getFragmentById(int id) {
        // 复用已创建的实例：切换 tab 不重建视图、不重新请求后端
        Fragment cached = fragmentCache.get(id);
        if (cached != null) return cached;

        Fragment fragment;
        if (id == R.id.nav_schedule) fragment = new ScheduleFragment();
        else if (id == R.id.nav_profile) fragment = new ProfileFragment();
        else {
            if (focusFragment == null) focusFragment = new FocusFragment();
            fragment = focusFragment;
        }
        fragmentCache.put(id, fragment);
        return fragment;
    }

    private void switchFragment(Fragment fragment) {
        if (fragment == currentFragment) return;

        // 根据 Fragment 类型设置状态栏颜色
        if (fragment instanceof ScheduleFragment) {
            // 课表：白色状态栏
            getWindow().setStatusBarColor(Color.WHITE);
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightStatusBars(true);
        } else {
            // 专注/我的：与 canvas 背景色统一 (#F2F2F7)
            getWindow().setStatusBarColor(0xFFF2F2F7);
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightStatusBars(true);
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        if (fragment.isAdded()) {
            transaction.show(fragment);
        } else {
            transaction.add(R.id.fragment_container, fragment, fragment.getClass().getSimpleName());
        }
        if (currentFragment != null) {
            transaction.hide(currentFragment);
        }
        transaction.commit();
        currentFragment = fragment;
        // 跟踪 FocusFragment
        if (fragment instanceof FocusFragment) focusFragment = (FocusFragment) fragment;
    }
}
