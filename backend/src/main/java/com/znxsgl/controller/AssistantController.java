package com.znxsgl.controller;

import com.znxsgl.service.AssistantService;
import com.znxsgl.service.SchoolInfoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校园 AI 助手：学生提问入口。
 * 知识层级：校园信息（底层）> 课程资料 > 个人数据 > 通用常识。
 */
@RestController
@RequestMapping("/api/ai")
public class AssistantController {

    private final AssistantService assistantService;
    private final SchoolInfoService schoolInfoService;

    public AssistantController(AssistantService assistantService, SchoolInfoService schoolInfoService) {
        this.assistantService = assistantService;
        this.schoolInfoService = schoolInfoService;
    }

    /** 提问 */
    @PostMapping("/assistant")
    public ResponseEntity<?> ask(@RequestBody Map<String, String> body, Authentication auth) {
        if (!(auth != null && auth.getPrincipal() instanceof Long userId)) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }
        String message = body.get("message");
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入问题"));
        }
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        Map<String, Object> data = assistantService.ask(userId, message.trim());
        return ResponseEntity.ok(data);
    }

    /** 首页快捷能力与推荐问题（可由管理端配置，暂用内置） */
    @GetMapping("/assistant/quick")
    public ResponseEntity<Map<String, Object>> quick() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("greeting", "你好，我是智学助手");
        data.put("subtitle", "校内规定、课程资料、我的成绩课表，都可以问我");
        data.put("capabilities", List.of(
                Map.of("key", "school", "title", "校园规定", "desc", "作息/宿舍/请假/奖助"),
                Map.of("key", "course", "title", "课程问答", "desc", "章节重点、习题解析"),
                Map.of("key", "score", "title", "我的成绩", "desc", "考试成绩与答题记录"),
                Map.of("key", "wrong", "title", "错题复盘", "desc", "薄弱知识点分析"),
                Map.of("key", "schedule", "title", "我的课表", "desc", "今天/本周上什么课"),
                Map.of("key", "career", "title", "就业升学", "desc", "专业方向与升学政策")
        ));
        data.put("suggestions", List.of(
                "宿舍几点熄灯？",
                "请假需要什么流程？",
                "我上次考试考了多少分？",
                "我哪门课错题最多？",
                "今天我有几节课？"
        ));
        return ResponseEntity.ok(data);
    }

    /** 学生端只读查看校园信息（用于“校园规定”列表，可选） */
    @GetMapping("/school-info")
    public ResponseEntity<List<Map<String, Object>>> schoolInfo() {
        return ResponseEntity.ok(schoolInfoService.list(null, true));
    }
}
