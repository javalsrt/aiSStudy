package com.znxsgl.student.fragment;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.znxsgl.student.NewsAdapter;
import com.znxsgl.student.R;
import com.znxsgl.student.model.NewsItem;
import com.znxsgl.student.model.NewsPage;
import com.znxsgl.student.network.ApiService;
import com.znxsgl.student.network.RetrofitClient;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 「咨询」页：顶部轮播 + 职教政策/资讯列表。
 * <p>政策内容全部来自官方站点（标题 + 原文链接），点击直接外链跳转官方原文，
 * 详情页由官方页面承载，App 内不转载正文。
 */
public class NewsFragment extends Fragment {

    private static final int PAGE_SIZE = 20;

    private RecyclerView rvNews;
    private NewsAdapter adapter;
    private View loadingView, emptyView;
    private TextView tvEmptyTitle;
    private ImageView btnRefresh;

    private ViewPager2 bannerPager;
    private LinearLayout bannerDots;
    private final Handler bannerHandler = new Handler(Looper.getMainLooper());
    private boolean bannerAutoEnabled;
    private int bannerCount;
    private final Runnable bannerAutoScroll = new Runnable() {
        @Override
        public void run() {
            if (!bannerAutoEnabled || bannerPager == null || bannerCount <= 1) return;
            int next = (bannerPager.getCurrentItem() + 1) % bannerCount;
            bannerPager.setCurrentItem(next, true);
            bannerHandler.postDelayed(this, 3500L);
        }
    };

    private final List<NewsItem> items = new ArrayList<>();
    private LinearLayoutManager layoutManager;
    private String token;

    private int page = 1;
    private boolean hasMore = true;
    private boolean requesting = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_news, container, false);

        token = "Bearer " + requireActivity().getSharedPreferences("znxsgl", 0)
                .getString("token", "");

        rvNews = view.findViewById(R.id.rv_news);
        loadingView = view.findViewById(R.id.news_loading);
        emptyView = view.findViewById(R.id.news_empty);
        tvEmptyTitle = view.findViewById(R.id.tv_empty_title);
        btnRefresh = view.findViewById(R.id.btn_refresh);
        bannerPager = view.findViewById(R.id.banner_pager);
        bannerDots = view.findViewById(R.id.banner_dots);
        setupBanner();

        layoutManager = new LinearLayoutManager(getContext());
        rvNews.setLayoutManager(layoutManager);
        adapter = new NewsAdapter(items, this::openOriginal);
        rvNews.setAdapter(adapter);
        rvNews.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || requesting || !hasMore) return;
                if (layoutManager.findLastVisibleItemPosition() >= items.size() - 3) {
                    loadPage(page + 1, false);
                }
            }
        });

        btnRefresh.setOnClickListener(v -> {
            spinRefresh();
            loadPage(1, true);
        });

        loadPage(1, true);
        return view;
    }

    private void setupBanner() {
        int[] images = {R.drawable.consult_banner_1, R.drawable.consult_banner_2};
        bannerCount = images.length;
        bannerPager.setAdapter(new BannerAdapter(images));
        bannerPager.setOffscreenPageLimit(1);
        bannerPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateBannerDots(position % Math.max(1, bannerCount));
            }
        });
        buildBannerDots();
        updateBannerDots(0);
    }

    private void buildBannerDots() {
        if (bannerDots == null) return;
        bannerDots.removeAllViews();
        int size = dp(6);
        for (int i = 0; i < bannerCount; i++) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dp(3), 0, dp(3), 0);
            dot.setLayoutParams(lp);
            bannerDots.addView(dot);
        }
    }

    private void updateBannerDots(int selected) {
        if (bannerDots == null) return;
        int activeWidth = dp(16);
        int inactiveWidth = dp(6);
        int height = dp(6);
        for (int i = 0; i < bannerDots.getChildCount(); i++) {
            View dot = bannerDots.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            lp.width = i == selected ? activeWidth : inactiveWidth;
            lp.height = height;
            lp.setMargins(dp(3), 0, dp(3), 0);
            dot.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(height / 2f);
            bg.setColor(i == selected ? 0xFFFFFFFF : 0x66FFFFFF);
            dot.setBackground(bg);
        }
    }

    /** 2 张图自动轮播；切到其他页签时通过 onHiddenChanged 暂停，避免后台空转 */
    private void startBannerAutoScroll() {
        bannerHandler.removeCallbacks(bannerAutoScroll);
        bannerAutoEnabled = true;
        if (bannerPager != null && bannerCount > 1 && !isHidden()) {
            bannerHandler.postDelayed(bannerAutoScroll, 3500L);
        }
    }

    private void stopBannerAutoScroll() {
        bannerAutoEnabled = false;
        bannerHandler.removeCallbacks(bannerAutoScroll);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) stopBannerAutoScroll();
        else startBannerAutoScroll();
    }

    @Override
    public void onResume() {
        super.onResume();
        startBannerAutoScroll();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopBannerAutoScroll();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void spinRefresh() {
        if (btnRefresh == null) return;
        btnRefresh.animate().rotationBy(360f).setDuration(500).start();
    }

    /** 加载第 p 页；reset=true 时清空重载（刷新/首次） */
    private void loadPage(int p, boolean reset) {
        if (requesting) return;
        requesting = true;
        if (reset && items.isEmpty()) showLoading(true);

        RetrofitClient.getInstance().create(ApiService.class)
                .getNews(token, p, PAGE_SIZE)
                .enqueue(new Callback<NewsPage>() {
                    @Override
                    public void onResponse(@NonNull Call<NewsPage> call,
                                           @NonNull Response<NewsPage> resp) {
                        requesting = false;
                        showLoading(false);
                        if (!isAdded()) return;
                        if (!resp.isSuccessful() || resp.body() == null) {
                            onLoadFailed(reset);
                            return;
                        }
                        NewsPage body = resp.body();
                        List<NewsItem> list = body.getList() != null ? body.getList() : new ArrayList<>();
                        if (reset) items.clear();
                        items.addAll(list);
                        page = p;
                        hasMore = body.hasMore();
                        adapter.notifyDataSetChanged();
                        updateEmptyState();
                    }

                    @Override
                    public void onFailure(@NonNull Call<NewsPage> call, @NonNull Throwable t) {
                        requesting = false;
                        showLoading(false);
                        if (!isAdded()) return;
                        onLoadFailed(reset);
                    }
                });
    }

    private void onLoadFailed(boolean reset) {
        if (reset && items.isEmpty()) {
            updateEmptyState();
        } else {
            Toast.makeText(RetrofitClient.safeContext(getContext()),
                    "网络异常，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void showLoading(boolean show) {
        if (loadingView != null) loadingView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateEmptyState() {
        boolean empty = items.isEmpty();
        if (emptyView != null) emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (rvNews != null) rvNews.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty && tvEmptyTitle != null) tvEmptyTitle.setText("暂无最新政策");
    }

    /** 外链跳转官方原文（不做站内转载） */
    private void openOriginal(NewsItem item) {
        String url = item.getSourceUrl();
        if (TextUtils.isEmpty(url)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(RetrofitClient.safeContext(getContext()),
                    "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopBannerAutoScroll();
        if (bannerPager != null) bannerPager.setAdapter(null);
        rvNews = null;
        adapter = null;
        loadingView = null;
        emptyView = null;
        btnRefresh = null;
        bannerPager = null;
        bannerDots = null;
    }

    private static class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.VH> {
        private final int[] images;

        BannerAdapter(int[] images) {
            this.images = images;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView image = new ImageView(parent.getContext());
            image.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return new VH(image);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.image.setImageResource(images[position]);
            holder.image.setContentDescription("咨询轮播图 " + (position + 1));
        }

        @Override
        public int getItemCount() {
            return images.length;
        }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView image;

            VH(@NonNull ImageView itemView) {
                super(itemView);
                image = itemView;
            }
        }
    }
}
