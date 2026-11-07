package com.znxsgl.student;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.znxsgl.student.fragment.AiAssistantFragment;
import com.znxsgl.student.fragment.FocusFragment;
import com.znxsgl.student.fragment.ScheduleFragment;
import com.znxsgl.student.fragment.ProfileFragment;
import com.znxsgl.student.fragment.TestFragment;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;
import com.znxsgl.student.network.WebSocketManager;
import com.znxsgl.student.widget.AiTabBurstView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private FragmentManager fragmentManager;
    private Fragment currentFragment;
    private FocusFragment focusFragment;
    private BottomNavigationView bottomNav;
    /** 中间 AI 星星的点击爆发动效层 */
    private AiTabBurstView aiBurst;
    private ValueAnimator aiIconAnimator;
    /** 星星正在播放弹性缩放时为 true，此时 styleAiTab 不要再改写缩放值 */
    private boolean aiIconPopping;
    /** 已挂上布局监听的 AI 图标，避免重复注册 */
    private ImageView aiIconWatched;
    private View aiIconWatchedItem;
    /** 图标每次重新布局后都重新校正垂直位置 */
    private final View.OnLayoutChangeListener aiIconLayoutWatcher =
            (v, l, t, r, b, ol, ot, or, ob) -> centerIconVertically(aiIconWatchedItem);
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
        aiBurst = findViewById(R.id.ai_tab_burst);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            // 注意：不在切到「我的」时清红点——红点表示真实未读，进入对应课程聊天后才会消除
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
            // 选中态会重排页签内部视图，重新应用一次样式，避免文字或位置回弹
            bottomNav.post(this::styleAiTab);
            // 中间 AI 星星：切换过来时播放一次点击爆发动效
            if (id == R.id.nav_ai) playAiTabBurst();
            return true;
        });

        // 重复点击同一个页签：AI 页补一次动效反馈，其余页签保证内容已就位。
        // 注意必须在这里也切一次：setSelectedItemId(nav_schedule) 时该项本来就是选中态，
        // Material 只走「重选」分支、不回调 OnItemSelectedListener，少这一步首屏会空白。
        bottomNav.setOnItemReselectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_ai) playAiTabBurst();
            switchFragment(getFragmentById(id));
        });

        // 红点：聊天推送 + 未读接口统一驱动
        registerChatBadgeListener();
        refreshUnread();

        // 中间 AI 页签只保留图标文字隐藏，图标放大
        styleAiTab();

        bottomNav.setSelectedItemId(R.id.nav_schedule);
    }

    /** AI 页签：隐藏「AI 助手」文字，仅放大星星图标 */
    private void styleAiTab() {
        if (bottomNav == null) return;
        // 标题置空：Material 在选中时会重新显示 largeLabel，只有清空标题才能彻底不出现文字
        android.view.MenuItem aiMenu = bottomNav.getMenu().findItem(R.id.nav_ai);
        if (aiMenu != null) aiMenu.setTitle("");
        bottomNav.post(() -> {
            try {
                ViewGroup menuView = (ViewGroup) bottomNav.getChildAt(0);
                if (menuView == null) return;
                View aiItem = findAiTabView();
                if (aiItem == null) return;
                aiItem.setContentDescription("AI 助手");
                menuView.setClipChildren(false);
                if (aiItem instanceof ViewGroup) ((ViewGroup) aiItem).setClipChildren(false);
                // 缩放动画进行中时不再覆盖，否则会打断弹簧手感
                if (!aiIconPopping) hideLabelAndEnlargeIcon(aiItem);
                watchAiIconLayout(aiItem);
                centerIconVertically(aiItem);
            } catch (Exception ignored) {
            }
        });
    }

    // ========== 中间 AI 星星的点击爆发动效 ==========

    /**
     * 播放 AI 页签点击动效：星星做一次「压下 → 弹起 → 回落」的弹性缩放，
     * 同时以星星为圆心播放声波涟漪 + 星芒 + 粒子（见 {@link AiTabBurstView}）。
     *
     * 延后一帧启动：避开点击当帧的选中重排，圆心交由 {@link AiTabBurstView}
     * 逐帧跟随锚点，后续布局变化不会再造成错位。
     */
    private void playAiTabBurst() {
        if (aiBurst == null) return;
        aiBurst.postDelayed(() -> {
            try {
                View aiItem = findAiTabView();
                if (aiItem == null || aiBurst == null) return;
                // 锚点用页签容器而不是图标 ImageView：图标被放大 1.95 倍后，
                // 它的 layout 中心与绘制出来的视觉重心不一致，直接用会整体偏移
                aiBurst.play(aiItem);
                ImageView icon = findIcon(aiItem);
                if (icon != null) animateIconPop(icon);
            } catch (Exception ignored) {
            }
        }, 16L);
    }

    /** 取 AI 页签的 View：按菜单 id 定位，不依赖它在导航栏里的固定位置 */
    private View findAiTabView() {
        if (bottomNav == null) return null;
        ViewGroup menuView = (ViewGroup) bottomNav.getChildAt(0);
        if (menuView == null) return null;
        android.view.Menu menu = bottomNav.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).getItemId() == R.id.nav_ai) {
                return i < menuView.getChildCount() ? menuView.getChildAt(i) : null;
            }
        }
        return null;
    }

    /**
     * 星星图标的弹性缩放。
     * 基准值是 {@link #styleAiTab()} 里设定的 1.95 倍，动画结束必须回到基准，
     * 否则图标会被留在缩小状态。
     */
    private void animateIconPop(View icon) {
        if (aiIconAnimator != null && aiIconAnimator.isRunning()) aiIconAnimator.cancel();
        final float base = 1.95f;
        final OvershootInterpolator pop = new OvershootInterpolator(2.4f);
        final DecelerateInterpolator settle = new DecelerateInterpolator(1.8f);

        aiIconAnimator = ValueAnimator.ofFloat(0f, 1f);
        aiIconAnimator.setDuration(600L);
        aiIconAnimator.setInterpolator(new LinearInterpolator());
        aiIconAnimator.addUpdateListener(a -> {
            float p = Math.max(0f, Math.min(1f, (float) a.getAnimatedValue()));
            float scale;
            if (p < 0.16f) {
                // 1. 按下：轻微收缩
                scale = 1f - 0.25f * (p / 0.16f);
            } else if (p < 0.58f) {
                // 2. 弹起：超调越过原尺寸
                scale = 0.75f + 0.38f * pop.getInterpolation(clamp01((p - 0.16f) / 0.42f));
            } else {
                // 3. 回落：稳稳收在原尺寸
                // 注意：k 必须夹在 0..1，DecelerateInterpolator 内部是 Math.pow(1-k, n)，
                // 浮点误差让 k 略大于 1 时会得到负底数，Math.pow 直接返回 NaN
                scale = 1.13f - 0.13f * settle.getInterpolation(clamp01((p - 0.58f) / 0.42f));
            }
            icon.setScaleX(base * scale);
            icon.setScaleY(base * scale);
        });
        aiIconAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                aiIconPopping = false;
                icon.setScaleX(base);
                icon.setScaleY(base);
            }
        });
        aiIconPopping = true;
        aiIconAnimator.start();
    }

    /** 递归：隐藏文字（小号/大号标题），并把图标放大 */
    private void hideLabelAndEnlargeIcon(View v) {
        if (v == null) return;
        if (v instanceof android.widget.TextView) {
            v.setVisibility(View.GONE);
            return;
        }
        if (v instanceof android.widget.ImageView) {
            // BottomNavigationMenuView 会在测量时重置图标尺寸，这里用缩放实现真正的放大
            v.setScaleX(1.95f);
            v.setScaleY(1.95f);
            if (v.getParent() instanceof ViewGroup parent) parent.setClipChildren(false);
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                hideLabelAndEnlargeIcon(g.getChildAt(i));
            }
        }
    }

    /**
     * 隐藏文字后图标会偏上，把它移到页签的垂直中心。
     *
     * 注意：必须在布局稳定后测量。启动时 Material 的页签还没排好标号，
     * 图标此时贴着容器顶部（实测 iconCenter≈38，正常应为≈144），
     * 用这个假位置算出来的是「向下 40px」，会把星星整体压低。
     * 因此这里由图标自身的布局回调驱动（见 watchAiIconLayout）。
     */
    private void centerIconVertically(View item) {
        if (item == null) return;
        try {
            android.widget.ImageView icon = findIcon(item);
            if (icon == null || item.getHeight() == 0 || icon.getHeight() == 0) return;
            // 归零后再测量：getLocationInWindow 含 translation，且选中/未选中布局不同
            icon.setTranslationY(0f);
            int[] itemPos = new int[2];
            int[] iconPos = new int[2];
            item.getLocationInWindow(itemPos);
            icon.getLocationInWindow(iconPos);
            float itemCenter = item.getHeight() / 2f;
            float iconCenter = (iconPos[1] - itemPos[1]) + icon.getHeight() / 2f;
            // 星星图形在 24dp viewBox 里偏下（大星在上、两颗小星在下），
            // 放大后视觉重心比图标 bounds 中心低，需整体上提对齐其他页签图标的视觉中线
            icon.setTranslationY(itemCenter - iconCenter - dp(14));
        } catch (Exception ignored) {
        }
    }

    /**
     * 监听 AI 图标的布局变化：Material 在选中/未选中之间切换时会重新摆放图标，
     * 每次摆放完成后都按最新位置重新校正，避免用过期坐标算出偏移。
     */
    private void watchAiIconLayout(View aiItem) {
        android.widget.ImageView icon = findIcon(aiItem);
        if (icon == null || icon == aiIconWatched) return;
        if (aiIconWatched != null) aiIconWatched.removeOnLayoutChangeListener(aiIconLayoutWatcher);
        aiIconWatched = icon;
        aiIconWatchedItem = aiItem;
        icon.addOnLayoutChangeListener(aiIconLayoutWatcher);
    }

    private android.widget.ImageView findIcon(View v) {
        if (v instanceof android.widget.ImageView) return (android.widget.ImageView) v;
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ImageView found = findIcon(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
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

    // ========== 「我的」未读红点（唯一数据源：服务端 /api/chat/unread） ==========
    private int unreadTotal = 0;
    private WebSocketManager.OnChatUpdateListener chatBadgeListener;

    /**
     * 当前正在查看的聊天课程ID。
     * 非空表示用户已经在该课程的聊天窗口里，此时收到的消息不再计入红点、也不再弹提示。
     */
    private static Long activeChatCourseId = null;

    /** 由课程聊天页在 onResume/onPause 调用 */
    public static void setActiveChatCourse(Long courseId) {
        activeChatCourseId = courseId;
    }

    public static Long getActiveChatCourseId() {
        return activeChatCourseId;
    }

    /** 更新「我的」tab 红点（number = 未读消息总数） */
    public void setUnreadTotal(int total) {
        unreadTotal = Math.max(0, total);
        if (bottomNav == null) return;
        if (unreadTotal > 0) {
            BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.nav_profile);
            badge.setVisible(true);
            badge.setMaxCharacterCount(3);
            badge.setNumber(Math.min(unreadTotal, 99));
        } else {
            bottomNav.removeBadge(R.id.nav_profile);
        }
    }

    /**
     * 拉取未读数：同时更新「我的」红点与课程列表红点。
     * 进入/离开聊天页、收到聊天推送、回到 App 时都会调用，保证本地与服务端一致。
     */
    public void refreshUnread() {
        String token = "Bearer " + getSharedPreferences("znxsgl", 0).getString("token", "");
        ApiService api = RetrofitClient.getInstance().create(ApiService.class);
        api.getUnreadChatCount(token).enqueue(new Callback<List<Map<String, Object>>>() {
            @Override
            public void onResponse(Call<List<Map<String, Object>>> call,
                                   Response<List<Map<String, Object>>> resp) {
                Map<Long, Integer> counts = new HashMap<>();
                int total = 0;
                if (resp.isSuccessful() && resp.body() != null) {
                    for (Map<String, Object> row : resp.body()) {
                        Object idObj = row.get("courseId");
                        if (!(idObj instanceof Number)) continue;
                        Object cnt = row.get("count");
                        int n = cnt instanceof Number ? ((Number) cnt).intValue() : 0;
                        counts.put(((Number) idObj).longValue(), n);
                        total += n;
                    }
                }
                setUnreadTotal(total);
                ProfileFragment profile = findProfileFragment();
                if (profile != null) profile.applyUnreadCounts(counts);
            }

            @Override
            public void onFailure(Call<List<Map<String, Object>>> call, Throwable t) {
            }
        });
    }

    private ProfileFragment findProfileFragment() {
        Fragment f = fragmentCache.get(R.id.nav_profile);
        return f instanceof ProfileFragment ? (ProfileFragment) f : null;
    }

    /** 聊天推送：不在对应窗口时才刷新红点与课程列表 */
    private void registerChatBadgeListener() {
        chatBadgeListener = (courseId, courseName, senderName, content) -> runOnUiThread(() -> {
            Long active = activeChatCourseId;
            if (active != null && active.equals(courseId)) return; // 用户正在该聊天窗口，无需红点/提示
            refreshUnread();
            ProfileFragment profile = findProfileFragment();
            if (profile != null) profile.loadCoursesIfAdded();
        });
        WebSocketManager.getInstance().addChatListener(chatBadgeListener);
    }

    private void connectWebSocket() {
        SharedPreferences prefs = getSharedPreferences("znxsgl", 0);
        long userId = prefs.getLong("userId", 0);
        if (userId == 0) return;

        WebSocketManager ws = WebSocketManager.getInstance();
        ws.setListener((courseName, content, scheduleInfo) -> {
            runOnUiThread(() -> {
                showScheduleToast(content);
                // 通知 ProfileFragment 刷新课程列表（排课信息变化）
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
        // 从聊天页返回时同步未读（对应课程红点应已消除）
        refreshUnread();
    }

    @Override
    protected void onDestroy() {
        // 先停动效，避免动画回调打到已销毁的视图
        if (aiIconAnimator != null) aiIconAnimator.cancel();
        if (aiBurst != null) aiBurst.stop();
        if (aiIconWatched != null) aiIconWatched.removeOnLayoutChangeListener(aiIconLayoutWatcher);
        super.onDestroy();
        if (chatBadgeListener != null) {
            WebSocketManager.getInstance().removeChatListener(chatBadgeListener);
        }
        WebSocketManager.getInstance().disconnect();
    }

    /** 全站统一：状态栏/导航栏与页面底色 canvas 保持一致，避免个别页面出现“白条” */
    private void applyStatusBarForCurrentFragment() {
        applyStatusBarFor(currentFragment);
    }

    /**
     * 按目标页面套用窗口外观：状态栏配色 + 内容是否延伸到状态栏下方。
     *
     * <p>沉浸式（内容画到状态栏下）只给「测试」页开：它的顶部渐变要从屏幕最顶端铺开。
     * 这里必须逐页显式开关——本项目切页用的是 show/hide，被隐藏的 Fragment 不会走
     * onPause，靠 Fragment 自己恢复的话，只要进过一次「测试」页，
     * setDecorFitsSystemWindows(false) 就会一直生效，其他页面顶部全被状态栏压住。
     *
     * <p>AI 助手页不走沉浸式：状态栏取渐变顶边同色（aurora_top）与其无缝衔接。
     */
    private void applyStatusBarFor(Fragment fragment) {
        boolean edgeToEdge = fragment instanceof TestFragment;
        boolean auroraTop = fragment instanceof AiAssistantFragment;

        WindowCompat.setDecorFitsSystemWindows(getWindow(), !edgeToEdge);

        int statusBarColor;
        if (edgeToEdge) {
            statusBarColor = Color.TRANSPARENT;
        } else if (auroraTop) {
            statusBarColor = getResources().getColor(R.color.aurora_top, getTheme());
        } else if (fragment instanceof ScheduleFragment) {
            // 课表页顶部是固定的纯白区块（周次 + 日期条），状态栏跟随白色，
            // 否则状态栏的 canvas 灰会在白色上沿压出一条灰带
            statusBarColor = Color.WHITE;
        } else {
            statusBarColor = 0xFFF2F2F7;
        }

        getWindow().setStatusBarColor(statusBarColor);
        getWindow().setNavigationBarColor(0xFFF2F2F7);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(true);
    }

    private Fragment getFragmentById(int id) {
        // 复用已创建的实例：切换 tab 不重建视图、不重新请求后端
        Fragment cached = fragmentCache.get(id);
        if (cached != null) return cached;

        Fragment fragment;
        if (id == R.id.nav_schedule) fragment = new ScheduleFragment();
        else if (id == R.id.nav_profile) fragment = new ProfileFragment();
        else if (id == R.id.nav_test) fragment = new TestFragment();
        else if (id == R.id.nav_ai) fragment = new AiAssistantFragment();
        else {
            if (focusFragment == null) focusFragment = new FocusFragment();
            fragment = focusFragment;
        }
        fragmentCache.put(id, fragment);
        return fragment;
    }

    private void switchFragment(Fragment fragment) {
        if (fragment == currentFragment) return;

        // 状态栏/导航栏按目标页面配色（AI 页需与顶部渐变衔接）
        applyStatusBarFor(fragment);

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
