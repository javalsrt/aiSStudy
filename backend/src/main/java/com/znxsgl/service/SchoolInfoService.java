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

    /**
     * 批量导入：按「## 标题」、Word 标题样式、或独立短标题行切段。
     * 不传分类时按内容自动归类（老师无需判断属于哪个类目）；
     * 同名条目已存在则更新正文，不重复插入。
     */
    public Map<String, Object> bulkImport(String category, String text, String operator) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            result.put("inserted", 0);
            result.put("updated", 0);
            return result;
        }
        List<Map<String, String>> items = splitSections(text);
        int inserted = 0;
        int updated = 0;
        for (Map<String, String> it : items) {
            String title = it.get("title").trim();
            String content = it.get("content").trim();
            if (title.isEmpty() || content.isEmpty()) continue;
            String cat = (category == null || category.isBlank()) ? classify(title, content) : category;
            List<Long> exists = jdbc.queryForList("SELECT id FROM school_info WHERE title = ?", Long.class, title);
            if (!exists.isEmpty()) {
                jdbc.update("UPDATE school_info SET category = ?, content = ?, status = 1, updated_by = ? WHERE id = ?",
                        cat, truncate(content, 20000), operator, exists.get(0));
                updated++;
            } else {
                jdbc.update("INSERT INTO school_info (category, title, content, keywords, sort, status, updated_by) " +
                                "VALUES (?, ?, ?, '', 100, 1, ?)",
                        cat, title, truncate(content, 20000), operator);
                inserted++;
            }
        }
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("total", inserted + updated);
        return result;
    }

    /** 按标题与正文关键词自动归类，避免老师纠结类目 */
    public String classify(String title, String content) {
        String t = (title == null ? "" : title) + (content == null ? "" : content);
        if (containsAny(t, "奖助", "奖学金", "助学金", "资助", "减免", "勤工俭学", "助学贷款")) return "奖助政策";
        if (containsAny(t, "就业", "升学", "专升本", "招聘", "顶岗实习", "对口升学", "职业生涯")) return "就业升学";
        if (containsAny(t, "教学", "课程", "考试", "学籍", "成绩", "实训", "课堂", "作业", "教材", "学分")) return "教学管理";
        if (containsAny(t, "宿舍", "熄灯", "食堂", "住宿", "水电", "门禁", "作息", "校园卡", "医务", "快递")) return "生活服务";
        if (containsAny(t, "请假", "外出", "违纪", "处分", "考勤", "安全", "门卫", "吸烟", "手机", "仪容", "校规", "管理制度"))
            return "规章制度";
        return "校园概况";
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
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

    /**
     * 切分条目，兼容三种来源：
     * 1) Markdown「## 标题」；2) Word 标题样式（导入时已被加上「##」）；
     * 3) 普通文档里独立成行的短标题（如「学生请假管理办法」）。
     */
    private List<Map<String, String>> splitSections(String text) {
        List<Map<String, String>> items = new ArrayList<>();
        String currentTitle = null;
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            String title = detectTitle(l);
            if (title != null && !title.isEmpty()) {
                if (currentTitle != null && current.length() > 0) {
                    items.add(Map.of("title", currentTitle, "content", current.toString().trim()));
                }
                currentTitle = title;
                current.setLength(0);
            } else if (currentTitle != null) {
                current.append(l).append('\n');
            } else if (!l.isEmpty()) {
                // 首个标题前的内容：用一个兜底条目标题承载，避免整段丢失
                currentTitle = "校园管理规定";
                current.append(l).append('\n');
            }
        }
        if (currentTitle != null && current.length() > 0) {
            items.add(Map.of("title", currentTitle, "content", current.toString().trim()));
        }
        return items;
    }

    /** 判断一行是否为条目标题 */
    private String detectTitle(String line) {
        if (line == null) return null;
        String l = line.trim();
        if (l.isEmpty()) return null;
        if (l.startsWith("#")) {
            return l.replaceFirst("^#+\\s*", "").trim();
        }
        if (l.matches("^[^\\s：:]{2,40}[：:]$")) {
            return l.substring(0, l.length() - 1).trim();
        }
        // 正文句子/条款编号排除
        if (l.length() < 2 || l.length() > 30) return null;
        if (l.matches(".*[。；，、；：:].*$")) return null;
        if (l.matches("^[一二三四五六七八九十]+、.*")) return null;
        if (l.matches("^（[一二三四五六七八九十]+）.*")) return null;
        if (l.matches("^第[一二三四五六七八九十百零〇\\d]+[条章节款].*")) return null;
        if (l.matches("^\\d+[.、].*")) return null;
        if (l.matches("^(特|为|根据|按照|本|凡|经|如|对|各|其中|同时|此外|因此|并|其).*")) return null;
        // 明确的文件类标题后缀
        if (l.matches(".*(办法|规定|制度|通知|须知|流程|要求|说明|方案|细则|简介|公约|职责|标准|计划|条例|守则|规范)$")) {
            return l;
        }
        // 短标题：不超过 12 字且无标点，视为标题
        return l.length() <= 12 ? l : null;
    }
}
