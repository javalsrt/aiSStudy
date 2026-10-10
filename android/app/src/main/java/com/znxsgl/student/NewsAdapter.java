package com.znxsgl.student;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.znxsgl.student.model.NewsItem;

import java.util.List;

/** 资讯列表适配器：标题 + 分类/置顶标签 + 来源·日期 */
public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(NewsItem item);
    }

    private final List<NewsItem> items;
    private final OnItemClickListener listener;

    public NewsAdapter(List<NewsItem> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_news, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        NewsItem item = items.get(position);
        holder.tvTitle.setText(item.getTitle());

        if (TextUtils.isEmpty(item.getCategory())) {
            holder.tvCategory.setVisibility(View.GONE);
        } else {
            holder.tvCategory.setVisibility(View.VISIBLE);
            holder.tvCategory.setText(item.getCategory());
        }
        holder.tvTop.setVisibility(item.isTop() ? View.VISIBLE : View.GONE);

        // 来源 · 日期
        StringBuilder meta = new StringBuilder();
        if (!TextUtils.isEmpty(item.getSource())) meta.append(item.getSource());
        if (!TextUtils.isEmpty(item.getPublishedAt())) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(item.getPublishedAt());
        }
        holder.tvMeta.setText(meta);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvTitle, tvCategory, tvTop, tvMeta;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_news_title);
            tvCategory = itemView.findViewById(R.id.tv_news_category);
            tvTop = itemView.findViewById(R.id.tv_news_top);
            tvMeta = itemView.findViewById(R.id.tv_news_meta);
        }
    }
}
