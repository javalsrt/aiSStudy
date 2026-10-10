package com.znxsgl.student.model;

import com.google.gson.annotations.SerializedName;

/** 资讯条目（服务端只提供标题与官方原文链接，正文由外链跳转） */
public class NewsItem {

    @SerializedName("id")
    private long id;

    @SerializedName("title")
    private String title;

    @SerializedName("source")
    private String source;

    @SerializedName("category")
    private String category;

    @SerializedName("sourceUrl")
    private String sourceUrl;

    @SerializedName("publishedAt")
    private String publishedAt;

    @SerializedName("isTop")
    private int isTop;

    public long getId() { return id; }

    public String getTitle() { return title; }

    public String getSource() { return source; }

    public String getCategory() { return category; }

    public String getSourceUrl() { return sourceUrl; }

    public String getPublishedAt() { return publishedAt; }

    public boolean isTop() { return isTop == 1; }
}
