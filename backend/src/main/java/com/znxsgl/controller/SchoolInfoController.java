package com.znxsgl.controller;

import com.znxsgl.service.SchoolInfoService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校园信息（校规/校内制度）管理：AI 助手的底层知识来源。
 * 管理端维护，学生端只读（通过 AI 问答间接使用）。
 */
@RestController
@RequestMapping("/api/admin/school-info")
@PreAuthorize("hasRole('ADMIN')")
public class SchoolInfoController {

    private final SchoolInfoService schoolInfoService;
    private final JdbcTemplate jdbc;

    public SchoolInfoController(SchoolInfoService schoolInfoService, JdbcTemplate jdbc) {
        this.schoolInfoService = schoolInfoService;
        this.jdbc = jdbc;
    }

    /** 列表（可按分类过滤） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "category", required = false) String category) {
        List<Map<String, Object>> rows = schoolInfoService.list(category, false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", rows.size());
        data.put("items", rows);
        data.put("categories", List.of("校园概况", "规章制度", "教学管理", "生活服务", "奖助政策", "就业升学"));
        return ResponseEntity.ok(data);
    }

    /** 新增/修改（id 为空=新增） */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
            String title = body.get("title") == null ? "" : String.valueOf(body.get("title"));
            String category = body.get("category") == null ? "校园概况" : String.valueOf(body.get("category"));
            Map<String, Object> saved = schoolInfoService.save(id, body, operatorName(auth));
            writeLog(auth, id == null ? "CREATE" : "UPDATE", (Long) saved.get("id"), title, category,
                    id == null ? "新增规定" : "修改正文与设置");
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 规定概览：总览统计 + 分类分布 + 最近更新 */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM school_info", Integer.class);
        Integer enabled = jdbc.queryForObject("SELECT COUNT(*) FROM school_info WHERE status = 1", Integer.class);
        data.put("total", total == null ? 0 : total);
        data.put("enabled", enabled == null ? 0 : enabled);
        data.put("disabled", (total == null ? 0 : total) - (enabled == null ? 0 : enabled));
        data.put("categories", jdbc.queryForList(
                "SELECT category, COUNT(*) AS count, SUM(status = 1) AS enabled, " +
                        "DATE_FORMAT(MAX(updated_at), '%m-%d %H:%i') AS updatedAt " +
                        "FROM school_info GROUP BY category ORDER BY count DESC"));
        data.put("recent", jdbc.queryForList(
                "SELECT id, title, category, status, DATE_FORMAT(updated_at, '%m-%d %H:%i') AS updatedAt, " +
                        "COALESCE(updated_by, '') AS updatedBy FROM school_info " +
                        "ORDER BY updated_at DESC LIMIT 5"));
        return ResponseEntity.ok(data);
    }

    /** 最近变更记录 */
    @GetMapping("/logs")
    public ResponseEntity<List<Map<String, Object>>> logs(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        return ResponseEntity.ok(jdbc.queryForList(
                "SELECT id, operator_name AS operatorName, action, item_id AS itemId, title, category, detail, " +
                        "DATE_FORMAT(created_at, '%m-%d %H:%i') AS createdAt " +
                        "FROM school_info_log ORDER BY id DESC LIMIT ?", n));
    }

    /** 写变更记录（失败不影响主流程） */
    private void writeLog(Authentication auth, String action, Long itemId, String title,
                          String category, String detail) {
        try {
            Long uid = (auth != null && auth.getPrincipal() instanceof Long id) ? id : null;
            String name = null;
            if (uid != null) {
                List<String> names = jdbc.queryForList("SELECT real_name FROM user WHERE id = ?", String.class, uid);
                if (!names.isEmpty()) name = names.get(0);
            }
            jdbc.update("INSERT INTO school_info_log (operator_id, operator_name, action, item_id, title, category, detail) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    uid, name, action, itemId, truncate(title, 200), truncate(category, 50), truncate(detail, 300));
        } catch (Exception ignored) {
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 批量导入：按「## 标题」分段粘贴整份校规 */
    @PostMapping("/bulk")
    public ResponseEntity<?> bulk(@RequestBody Map<String, Object> body, Authentication auth) {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        String category = body.get("category") == null ? null : String.valueOf(body.get("category"));
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "导入内容不能为空"));
        }
        Map<String, Object> result = schoolInfoService.bulkImport(category, text, operatorName(auth));
        writeLog(auth, "IMPORT", null, "批量导入", category,
                "新增 " + result.get("inserted") + " 条，更新 " + result.get("updated") + " 条");
        return ResponseEntity.ok(buildImportResponse(result));
    }

    /** 统一导入结果文案（新增/更新分开统计，避免「0 条」让人误以为格式错误） */
    private Map<String, Object> buildImportResponse(Map<String, Object> r) {
        int inserted = (int) r.getOrDefault("inserted", 0);
        int updated = (int) r.getOrDefault("updated", 0);
        int total = inserted + updated;
        String msg;
        if (total == 0) {
            msg = "未识别到有效条目：请确认文档包含标题行（如「学生请假管理办法」单独成行）";
        } else if (updated > 0) {
            msg = "导入完成：新增 " + inserted + " 条，更新同标题 " + updated + " 条";
        } else {
            msg = "导入完成：新增 " + inserted + " 条";
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("msg", msg);
        resp.put("inserted", inserted);
        resp.put("updated", updated);
        resp.put("imported", total);
        return resp;
    }

    /**
     * 一键导入新规：支持 .docx（WPS/Word 均可另存为 docx）、.txt、.md。
     * 按「## 标题」或独立「标题：」行分段；旧版 .doc/.wps 二进制格式请先另存为 docx。
     */
    @PostMapping("/import-file")
    public ResponseEntity<?> importFile(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                        @RequestParam(value = "category", required = false) String category,
                                        Authentication auth) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择文件"));
        }
        String name = String.valueOf(file.getOriginalFilename()).toLowerCase();
        String text;
        try {
            if (name.endsWith(".docx")) {
                try (java.io.InputStream in = file.getInputStream();
                     org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                             new org.apache.poi.xwpf.usermodel.XWPFDocument(in)) {
                    StringBuilder sb = new StringBuilder();
                    for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : doc.getParagraphs()) {
                        String line = p.getText();
                        boolean heading = p.getStyle() != null && p.getStyle().toLowerCase().contains("heading");
                        if (heading && line != null && !line.isBlank()) {
                            sb.append("## ").append(line.trim()).append('\n');
                        } else if (line != null && !line.isBlank()) {
                            sb.append(line.trim()).append('\n');
                        }
                    }
                    text = sb.toString();
                }
            } else if (name.endsWith(".txt") || name.endsWith(".md")) {
                text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } else if (name.endsWith(".doc") || name.endsWith(".wps")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "暂不支持旧版 .doc/.wps 二进制格式，请在 WPS/Word 中另存为 .docx 后再导入"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "仅支持 .docx / .txt / .md 文件"));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件解析失败：" + e.getMessage()));
        }
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件内容为空"));
        }
        Map<String, Object> result = schoolInfoService.bulkImport(category, text, operatorName(auth));
        writeLog(auth, "IMPORT", null, "一键导入新规", category,
                "文件 " + String.valueOf(file.getOriginalFilename()) + "：新增 " + result.get("inserted")
                        + " 条，更新 " + result.get("updated") + " 条");
        return ResponseEntity.ok(buildImportResponse(result));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, Authentication auth) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title, category FROM school_info WHERE id = ?", id);
        schoolInfoService.delete(id);
        if (!rows.isEmpty()) {
            writeLog(auth, "DELETE", id, String.valueOf(rows.get(0).get("title")),
                    String.valueOf(rows.get(0).get("category")), "删除规定");
        }
        return ResponseEntity.ok(Map.of("msg", "已删除"));
    }

    /** 启用/停用 */
    @PostMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      Authentication auth) {
        int status = "1".equals(String.valueOf(body.get("status"))) ? 1 : 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title, category FROM school_info WHERE id = ?", id);
        jdbc.update("UPDATE school_info SET status = ? WHERE id = ?", status, id);
        if (!rows.isEmpty()) {
            writeLog(auth, "STATUS", id, String.valueOf(rows.get(0).get("title")),
                    String.valueOf(rows.get(0).get("category")), status == 1 ? "启用" : "停用");
        }
        return ResponseEntity.ok(Map.of("msg", status == 1 ? "已启用" : "已停用"));
    }

    private String operatorName(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Long uid)) return null;
        List<String> names = jdbc.queryForList("SELECT real_name FROM user WHERE id = ?", String.class, uid);
        return names.isEmpty() ? null : names.get(0);
    }
}
