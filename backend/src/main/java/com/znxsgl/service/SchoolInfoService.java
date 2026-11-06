package com.znxsgl.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校园信息知识库：管理员维护的校规/校内信息，作为 AI 助手的**底层知识**。
 * 检索不依赖向量模型（校内文本量小、术语集中），用「标题/关键词/正文」关键词加权打分。
 */
@Service
public class SchoolInfoService {

    private final JdbcTemplate jdbc;

    public SchoolInfoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String category, boolean onlyEnabled) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, category, title, content, keywords, sort, status, updated_by AS updatedBy, " +
                        "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i') AS updatedAt FROM school_info WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category.trim());
        }
        if (onlyEnabled) sql.append(" AND status = 1");
        sql.append(" ORDER BY sort, id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> save(Long id, Map<String, Object> body, String operator) {
        String category = str(body.get("category"), "校园概况");
        String title = str(body.get("title"), "").trim();
        String content = str(body.get("content"), "").trim();
        String keywords = str(body.get("keywords"), "").trim();
        int sort = intVal(body.get("sort"), 0);
        int status = intVal(body.get("status"), 1);
        if (title.isEmpty() || content.isEmpty()) {
            throw new IllegalArgumentException("标题和内容不能为空");
        }
        if (id == null) {
            jdbc.update("INSERT INTO school_info (category, title, content, keywords, sort, status, updated_by) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    category, title, truncate(content, 20000), truncate(keywords, 300), sort, status, operator);
            return Map.of("msg", "已新增", "id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        }
        jdbc.update("UPDATE school_info SET category=?, title=?, content=?, keywords=?, sort=?, status=?, updated_by=? WHERE id=?",
                category, title, truncate(content, 20000), truncate(keywords, 300), sort, status, operator, id);
        return Map.of("msg", "已保存", "id", id);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM school_info WHERE id = ?", id);
    }

    /** 批量导入：按「## 标题」或「标题：」切段，整段作为正文 */
    public int bulkImport(String category, String text, String operator) {
        if (text == null || text.isBlank()) return 0;
        List<Map<String, String>> items = splitSections(text);
        int n = 0;
        for (Map<String, String> it : items) {
            String title = it.get("title").trim();
            String content = it.get("content").trim();
            if (title.isEmpty() || content.isEmpty()) continue;
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM school_info WHERE title = ?", Integer.class, title);
            if (exists != null && exists > 0) continue;
            jdbc.update("INSERT INTO school_info (category, title, content, keywords, sort, status, updated_by) " +
                            "VALUES (?, ?, ?, '', 100, 1, ?)",
                    category == null || category.isBlank() ? "校园概况" : category, title, truncate(content, 20000), operator);
            n++;
        }
        return n;
    }

    /**
     * 检索与问题最相关的校园信息（底层知识）。
     * 打分：标题命中 +6 / 关键词命中 +4 / 正文每命中一次 +1（单项上限 3）。
     */
    public List<Map<String, Object>> retrieve(String question, int limit) {
        if (question == null || question.isBlank()) return List.of();
        Set<String> tokens = tokenize(question);
        if (tokens.isEmpty()) return List.of();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, category, title, content, keywords FROM school_info WHERE status = 1");
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String title = str(r.get("title"), "");
            String keywords = str(r.get("keywords"), "");
            String content = str(r.get("content"), "");
            int score = 0;
            for (String t : tokens) {
                if (t.length() < 2) continue;
                if (title.contains(t)) score += 6;
                if (keywords.contains(t)) score += 4;
                int hit = 0;
                int idx = 0;
                while (hit < 3 && (idx = content.indexOf(t, idx)) >= 0) {
                    hit++;
                    idx += t.length();
                }
                score += hit;
            }
            if (score >= 6) {
                Map<String, Object> m = new LinkedHashMap<>(r);
                m.put("score", score);
                scored.add(m);
            }
        }
        scored.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    /** 中文按 2-gram 切分 + 英文/数字按词切分 */
    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String cleaned = text.replaceAll("[\\p{Punct}\\s，。！？、；：（）《》【】“”‘’…—]", " ").trim();
        for (String part : cleaned.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (part.matches(".*[\\u4e00-\\u9fa5].*")) {
                if (part.length() <= 4) {
                    tokens.add(part);
                }
                for (int i = 0; i + 1 < part.length(); i++) {
                    tokens.add(part.substring(i, i + 2));
                }
            } else if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        // 去掉过于常见的无意义二元词
        tokens.removeIf(t -> t.equals("怎么") || t.equals("什么") || t.equals("如何") || t.equals("可以")
                || t.equals("学校") || t.equals("我们"));
        return tokens;
    }

    private String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private int intVal(Object o, int def) {
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 「## 标题\n正文」「标题：正文」两种写法都支持 */
    private List<Map<String, String>> splitSections(String text) {
        List<Map<String, String>> items = new ArrayList<>();
        String currentTitle = null;
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            String title = null;
            if (l.startsWith("##")) {
                title = l.replaceFirst("^#+\\s*", "").trim();
            } else if (l.matches("^[^\\s：:]{2,40}[：:]$")) {
                title = l.substring(0, l.length() - 1).trim();
            }
            if (title != null && !title.isEmpty()) {
                if (currentTitle != null && current.length() > 0) {
                    items.add(Map.of("title", currentTitle, "content", current.toString().trim()));
                }
                currentTitle = title;
                current.setLength(0);
            } else if (currentTitle != null) {
                current.append(l).append('\n');
            }
        }
        if (currentTitle != null && current.length() > 0) {
            items.add(Map.of("title", currentTitle, "content", current.toString().trim()));
        }
        return items;
    }
}
