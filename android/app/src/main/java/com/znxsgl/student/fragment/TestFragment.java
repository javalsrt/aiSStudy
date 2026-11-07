package com.znxsgl.student.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.znxsgl.student.R;

/**
 * 「测试」页：页面样式样板（复刻基线）。
 *
 * <p>本页需要顶部弥散渐变一直铺到屏幕最顶端，因此由 {@code MainActivity}
 * 在切页时统一开启沉浸式布局（见 applyStatusBarFor），本页只负责按状态栏高度
 * 给滚动容器补内边距，保证标题不被状态栏压住。
 *
 * <p>注意：不要在这里用 onResume/onPause 开关沉浸式。底部页签是 show/hide 切换，
 * 被隐藏的 Fragment 不会走 onPause，一旦开启就再也关不掉，其他页面顶部会被状态栏挡住。
 */
public class TestFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_test, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ScrollView scroll = view.findViewById(R.id.test_scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }
}
