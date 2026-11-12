package com.znxsgl.agent.tools;

import com.znxsgl.agent.AgentException;
import com.znxsgl.agent.BaseAgentTool;
import com.znxsgl.agent.dto.AgentContext;
import com.znxsgl.agent.dto.ToolResult;
import com.znxsgl.service.SchoolInfoService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校园信息查询与维护。
 */
@Component
public class SchoolInfoAgentTool extends BaseAgentTool {

    private final SchoolInfoService schoolInfoService;

    public SchoolInfoAgentTool(SchoolInfoService schoolInfoService) {
        this.schoolInfoService = schoolInfoService;
    }

    @Override
    public String name() {
        return "schoolInfo.search";
    }

    @Override
    public String label() {
        return "查询校园信息";
    }

    @Override
    public String description() {
        return "按关键词或分类查询校规、通知、校园概况等信息。";
    }

    @Override
    public Set<String> roles() {
        return Set.of("ADMIN", "TEACHER");
    }

    @Override
    public Map<String, Object> argsSpec() {
        return argSpec(
                "keyword", "可选，标题/内容关键词",
                "category", "可选，分类",
                "limit", "可选，默认 10，最多 30");
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
        String keyword = str(args, "keyword");
        String category = str(args, "category");
        List<Map<String, Object>> rows = schoolInfoService.list(
                category.isEmpty() ? null : category, true);
        List<Map<String, Object>> items = new ArrayList<>();
        int max = limit(args, 10, 30);
        for (Map<String, Object> row : rows) {
            String title = str(row, "title");
            String content = str(row, "content");
            if (!keyword.isEmpty() && !title.contains(keyword) && !content.contains(keyword)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("标题", title);
            item.put("分类", row.get("category"));
            item.put("内容摘要", content.length() > 120 ? content.substring(0, 120) + "…" : content);
            item.put("id", row.get("id"));
            items.add(item);
            if (items.size() >= max) break;
        }
        return ToolResult.ok(items.isEmpty() ? "没有找到相关校园信息。" : "找到 " + items.size() + " 条校园信息。",
                items, "/admin/school-info");
    }

    @Component
    public static class Create extends BaseAgentTool {
        private final SchoolInfoAgentTool parent;

        public Create(SchoolInfoAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "schoolInfo.create";
        }

        @Override
        public String label() {
            return "新增校园信息";
        }

        @Override
        public String description() {
            return "新增一条校园信息（校规、通知等），需要标题和内容。仅管理员。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "title", "必填，标题",
                    "content", "必填，正文",
                    "category", "可选，分类",
                    "keywords", "可选，检索关键词");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.create(ctx, args);
        }
    }

    @Component
    public static class Update extends BaseAgentTool {
        private final SchoolInfoAgentTool parent;

        public Update(SchoolInfoAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "schoolInfo.update";
        }

        @Override
        public String label() {
            return "修改校园信息";
        }

        @Override
        public String description() {
            return "修改校园信息的标题、正文、分类或关键词。仅管理员。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec(
                    "infoId", "必填，校园信息 ID",
                    "title", "可选，新标题",
                    "content", "可选，新正文",
                    "category", "可选，新分类",
                    "keywords", "可选，新关键词");
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.update(ctx, args);
        }
    }

    @Component
    public static class Delete extends BaseAgentTool {
        private final SchoolInfoAgentTool parent;

        public Delete(SchoolInfoAgentTool parent) {
            this.parent = parent;
        }

        @Override
        public String name() {
            return "schoolInfo.delete";
        }

        @Override
        public String label() {
            return "删除校园信息";
        }

        @Override
        public String description() {
            return "删除一条校园信息，需要二次确认。仅管理员。";
        }

        @Override
        public Set<String> roles() {
            return Set.of("ADMIN");
        }

        @Override
        public boolean requiresConfirm() {
            return true;
        }

        @Override
        public Map<String, Object> argsSpec() {
            return argSpec("infoId", "必填，校园信息 ID");
        }

        @Override
        public ToolResult preview(AgentContext ctx, Map<String, Object> args) {
            return parent.previewDelete(ctx, args);
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args) {
            return parent.delete(ctx, args);
        }
    }

    private ToolResult create(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        String title = reqStr(args, "title", "标题");
        String content = reqStr(args, "content", "正文");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        if (!str(args, "category").isEmpty()) body.put("category", str(args, "category"));
        if (!str(args, "keywords").isEmpty()) body.put("keywords", str(args, "keywords"));
        Map<String, Object> result = schoolInfoService.save(null, body, ctx.getRealName());
        return ToolResult.ok("已新增校园信息《" + title + "》。",
                data("标题", title, "分类", str(args, "category"), "id", result.get("id")),
                "/admin/school-info");
    }

    private ToolResult update(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        Long infoId = reqLng(args, "infoId", "校园信息 ID");
        Map<String, Object> row = row("SELECT * FROM school_info WHERE id = ?", infoId);
        if (row == null) {
            throw new AgentException("INFO_NOT_FOUND", "校园信息不存在");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", str(args, "title").isEmpty() ? row.get("title") : str(args, "title"));
        body.put("content", str(args, "content").isEmpty() ? row.get("content") : str(args, "content"));
        body.put("category", str(args, "category").isEmpty() ? row.get("category") : str(args, "category"));
        body.put("keywords", str(args, "keywords").isEmpty() ? row.get("keywords") : str(args, "keywords"));
        schoolInfoService.save(infoId, body, ctx.getRealName());
        return ToolResult.ok("已更新校园信息《" + body.get("title") + "》。",
                data("标题", body.get("title"), "id", infoId), "/admin/school-info");
    }

    private ToolResult previewDelete(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        Long infoId = reqLng(args, "infoId", "校园信息 ID");
        Map<String, Object> row = row("SELECT * FROM school_info WHERE id = ?", infoId);
        if (row == null) {
            throw new AgentException("INFO_NOT_FOUND", "校园信息不存在");
        }
        return ToolResult.ok("即将删除校园信息《" + row.get("title") + "》，删除后不可恢复。",
                data("标题", row.get("title"), "分类", row.get("category")), "/admin/school-info");
    }

    private ToolResult delete(AgentContext ctx, Map<String, Object> args) {
        requireAdmin(ctx);
        Long infoId = reqLng(args, "infoId", "校园信息 ID");
        Map<String, Object> row = row("SELECT * FROM school_info WHERE id = ?", infoId);
        if (row == null) {
            throw new AgentException("INFO_NOT_FOUND", "校园信息不存在");
        }
        schoolInfoService.delete(infoId);
        return ToolResult.ok("已删除校园信息《" + row.get("title") + "》。",
                data("标题", row.get("title")), "/admin/school-info");
    }
}
