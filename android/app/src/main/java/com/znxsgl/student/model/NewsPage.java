package com.znxsgl.student.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** 资讯分页响应：/api/news */
public class NewsPage {

    @SerializedName("list")
    private List<NewsItem> list;

    @SerializedName("page")
    private int page;

    @SerializedName("hasMore")
    private boolean hasMore;

    public List<NewsItem> getList() { return list; }

    public int getPage() { return page; }

    public boolean hasMore() { return hasMore; }
}
