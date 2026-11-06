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
            return ResponseEntity.ok(schoolInfoService.save(id, body, operatorName(auth)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 批量导入：按「## 标题」分段粘贴整份校规 */
    @PostMapping("/bulk")
    public ResponseEntity<?> bulk(@RequestBody Map<String, Object> body, Authentication auth) {
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        String category = body.get("category") == null ? null : String.valueOf(body.get("category"));
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "导入内容不能为空"));
        }
        int imported = schoolInfoService.bulkImport(category, text, operatorName(auth));
        return ResponseEntity.ok(Map.of("msg", "成功导入 " + imported + " 条", "imported", imported));
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
        int imported = schoolInfoService.bulkImport(category, text, operatorName(auth));
        return ResponseEntity.ok(Map.of("msg", imported > 0 ? "成功导入 " + imported + " 条新规" : "未发现新条目（标题重复或格式不符）",
                "imported", imported));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        schoolInfoService.delete(id);
        return ResponseEntity.ok(Map.of("msg", "已删除"));
    }

    /** 启用/停用 */
    @PostMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        int status = "1".equals(String.valueOf(body.get("status"))) ? 1 : 0;
        jdbc.update("UPDATE school_info SET status = ? WHERE id = ?", status, id);
        return ResponseEntity.ok(Map.of("msg", status == 1 ? "已启用" : "已停用"));
    }

    private String operatorName(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Long uid)) return null;
        List<String> names = jdbc.queryForList("SELECT real_name FROM user WHERE id = ?", String.class, uid);
        return names.isEmpty() ? null : names.get(0);
    }
}
