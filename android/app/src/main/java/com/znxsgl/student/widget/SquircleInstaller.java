package com.znxsgl.student.widget;

import android.app.Activity;
import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.button.MaterialButton;

/**
 * 全局 G2 圆角安装器。
 *
 * Activity 创建/恢复时遍历视图树，把 XML shape、代码创建的 GradientDrawable
 * 圆角背景替换成 {@link SquircleDrawable}；同时监听 ViewGroup 子 View 变化，
 * 覆盖 RecyclerView item 等动态视图。
 */
public final class SquircleInstaller {

    private SquircleInstaller() {
    }

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                View decor = activity.getWindow().getDecorView();
                decor.post(() -> applyTree(decor));
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                // 页面在 onResume 里动态设置的背景也一并覆盖
                View decor = activity.getWindow().getDecorView();
                decor.post(() -> applyTree(decor));
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }

    public static void applyTree(View root) {
        apply(root);
    }

    private static void apply(View view) {
        if (view == null) {
            return;
        }
        wrapBackground(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                apply(group.getChildAt(i));
            }
            // 动态添加的 item（列表、Fragment 容器）也自动套用 G2
            group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    apply(child);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
        }
    }

    private static void wrapBackground(View view) {
        // MaterialButton 的背景是 MaterialShapeDrawable（圆弧），单独重建为 G2 + 前景波纹
        if (view instanceof MaterialButton) {
            wrapMaterialButton((MaterialButton) view);
            return;
        }
        Drawable bg = view.getBackground();
        if (bg == null || bg instanceof SquircleDrawable) {
            return;
        }
        if (!(bg instanceof GradientDrawable)) {
            // Ripple / StateList / MaterialShape 背景保留原样，避免丢失交互状态
            return;
        }
        GradientDrawable gradient = (GradientDrawable) bg;
        float radius = resolveRadius(gradient);
        if (radius <= 0) {
            return;
        }
        Drawable copy;
        try {
            copy = gradient.getConstantState() != null
                    ? gradient.getConstantState().newDrawable().mutate()
                    : gradient;
        } catch (Exception e) {
            copy = gradient;
        }
        if (copy instanceof GradientDrawable) {
            // 内容不再画圆角，圆角由 SquircleDrawable 统一裁剪
            ((GradientDrawable) copy).setCornerRadius(0f);
        }
        view.setBackground(new SquircleDrawable(copy, radius));
    }

    /** MaterialButton：用纯色 G2 背景替换圆弧 shape，并补一个前景波纹保留点击反馈 */
    private static void wrapMaterialButton(MaterialButton button) {
        if (button.getBackground() instanceof SquircleDrawable) {
            return;
        }
        float radius = button.getCornerRadius();
        if (radius <= 0) {
            return;
        }
        ColorStateList tint = button.getBackgroundTintList();
        int color = tint != null ? tint.getDefaultColor() : Color.TRANSPARENT;
        if (color == Color.TRANSPARENT || Color.alpha(color) == 0) {
            return;
        }
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(0f);
        button.setBackground(new SquircleDrawable(fill, radius));
        if (button.getForeground() == null) {
            button.setForeground(new RippleDrawable(
                    ColorStateList.valueOf(0x1A000000), null, null));
        }
    }

    /** 只处理四角半径一致的背景；四角不同（如仅顶部圆角）保留原设计 */
    private static float resolveRadius(GradientDrawable gradient) {
        float[] radii = gradient.getCornerRadii();
        if (radii != null) {
            float first = radii[0];
            for (float r : radii) {
                if (Math.abs(r - first) > 0.5f) {
                    return -1f;
                }
            }
            return first;
        }
        return gradient.getCornerRadius();
    }
}
