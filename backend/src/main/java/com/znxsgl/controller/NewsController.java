package com.znxsgl.controller;

import com.znxsgl.service.NewsFetchService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资讯（职教政策）接口。
 * <p>
 * 只返回「标题 + 来源 + 官方原文链接 + 发布日期」，App 侧点击直接外链跳原文，
 * 服务端不存储、不分发正文，规避新闻信息服务资质与版权风险。
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private static final int MAX_PAGE_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final NewsFetchService newsFetchService;

    public NewsController(JdbcTemplate jdbc, NewsFetchService newsFetchService) {
        this.jdbc = jdbc;
        this.newsFetchService = newsFetchService;
    }

    /** 资讯列表（分页，按置顶→发布日期倒序） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category) {

        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        StringBuilder sql = new StringBuilder(
                "SELECT id, title, source, category, source_url AS sourceUrl, " +
                "DATE_FORMAT(published_at, '%Y-%m-%d') AS publishedAt, is_top AS isTop " +
                "FROM news_article WHERE status = 1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category.trim());
        }
        sql.append(" ORDER BY is_top DESC, published_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(s + 1);              // 多取一条用于判断是否还有下一页
        args.add((p - 1) * s);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean hasMore = rows.size() > s;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, s));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("page", p);
        result.put("size", s);
        result.put("hasMore", hasMore);
        return ResponseEntity.ok(result);
    }

    /** 手动触发抓取（教师/管理员） */
    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN') or hasRole('TEACHER')")
    public ResponseEntity<Map<String, Object>> refresh() {
        return ResponseEntity.ok(Map.of("inserted", newsFetchService.refresh()));
    }
}
