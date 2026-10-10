package com.znxsgl.student.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
 * 「资讯」页：职教政策列表。
 * <p>内容全部来自官方站点（标题 + 原文链接），点击直接外链跳转官方原文，
 * 详情页由官方页面承载，App 内不转载正文。
 */
public class NewsFragment extends Fragment {

    private static final int PAGE_SIZE = 20;

    private RecyclerView rvNews;
    private NewsAdapter adapter;
    private View loadingView, emptyView;
    private TextView tvEmptyTitle;
    private ImageView btnRefresh;

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
        rvNews = null;
        adapter = null;
        loadingView = null;
        emptyView = null;
        btnRefresh = null;
    }
}
