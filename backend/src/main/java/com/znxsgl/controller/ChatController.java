package com.znxsgl.controller;

import com.znxsgl.dto.ChatMessageDTO;
import com.znxsgl.dto.StudentAskStatsDTO;
import com.znxsgl.dto.TeacherCourseDTO;
import com.znxsgl.service.ChatService;
import com.znxsgl.service.LlmService;
import com.znxsgl.service.RagService;
import com.znxsgl.service.ScheduleService;
import com.znxsgl.websocket.ScheduleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final LlmService llmService;
    private final RagService ragService;
    private final ScheduleWebSocketHandler wsHandler;
    private final JdbcTemplate jdbc;
    private final ScheduleService scheduleService;

    public ChatController(ChatService chatService, LlmService llmService,
                          RagService ragService, ScheduleWebSocketHandler wsHandler,
                          JdbcTemplate jdbc, ScheduleService scheduleService) {
        this.chatService = chatService;
        this.llmService = llmService;
        this.ragService = ragService;
        this.wsHandler = wsHandler;
        this.jdbc = jdbc;
        this.scheduleService = scheduleService;
    }

    // 获取课程聊天记录（个人：学生看自己的AI对话，按userId过滤）
    @GetMapping("/{courseName}")
    public ResponseEntity<List<ChatMessageDTO>> getMessages(
            @PathVariable String courseName, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(chatService.getMessages(courseName, userId));
    }

    // 获取课程公开聊天（群聊：教师/学生都能看到所有人的消息）
    @GetMapping("/{courseName}/public")
    public ResponseEntity<List<ChatMessageDTO>> getPublicMessages(@PathVariable String courseName) {
        return ResponseEntity.ok(chatService.getPublicMessages(courseName));
    }

    // 发送消息（支持 @mention 和 @AI）
    @PostMapping("/send")
    public ResponseEntity<ChatMessageDTO> sendMessage(
            @RequestBody Map<String, String> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String courseName = body.get("courseName");
        String content = body.get("content");
        String senderRole = body.getOrDefault("senderRole", "student");

        // 检测 @mention
        Long mentionUserId = null;
        if (content != null && content.contains("@")) {
            mentionUserId = chatService.parseMention(courseName, content);
        }

        ChatMessageDTO msg = chatService.sendMessage(courseName, userId, content, senderRole, mentionUserId);

        // 如果 @了AI，触发AI回复
        if (content != null && (content.contains("@AI") || content.contains("@ai"))) {
            try {
                String aiReply = llmService.chat(buildSystemPrompt(courseName, ragContext(content, courseName)),
                        content.replace("@AI", "").replace("@ai", "").trim());
                if (aiReply == null || aiReply.trim().isEmpty()) {
                    aiReply = "AI 服务暂时不可用，请稍后重试。";
                }
                chatService.sendMessage(courseName, userId, aiReply, "ai", mentionUserId);
            } catch (Exception e) {
                log.warn("AI 回复失败：course={}, userId={}", courseName, userId, e);
            }
        }

        // WebSocket推送：@消息只推送给被@的人
        try {
            String preview;
            if (content != null && content.startsWith("[image]")) preview = "📷 [图片]";
            else if (content != null && content.startsWith("[file]")) {
                String fn = content.substring(6); int ps = fn.indexOf('|');
                preview = "📄 [文件] " + (ps > 0 ? fn.substring(0, Math.min(ps, 20)) : fn.substring(0, 20));
            } else preview = content != null && content.length() > 60 ? content.substring(0, 60) + "..." : (content != null ? content : "");

            if (mentionUserId != null) {
                // @消息只推送给目标学生
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("courseName", courseName);
                data.put("senderName", msg.getSenderName());
                data.put("content", preview);
                data.put("senderRole", senderRole);
                wsHandler.sendToUser(mentionUserId, "chat_update", data);
            } else {
                // 公开消息推送给所有学生
                List<Long> studentIds = jdbc.queryForList(
                    "SELECT DISTINCT u.id FROM user u " +
                    "JOIN course_class cc ON cc.class_id = u.class_id " +
                    "JOIN course c ON c.id = cc.course_id " +
                    "WHERE c.course_name = ? AND u.role = 1 AND u.id != ?",
                    Long.class, courseName, userId);
                for (Long sid : studentIds) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("courseName", courseName);
                    data.put("senderName", msg.getSenderName());
                    data.put("content", preview);
                    data.put("senderRole", senderRole);
                    wsHandler.sendToUser(sid, "chat_update", data);
                }
            }
        } catch (Exception e) {
            log.warn("WebSocket 推送失败：course={}, userId={}", courseName, userId, e);
        }

        return ResponseEntity.ok(msg);
    }

    // RAG 对话（自动检索课程知识库）
    @PostMapping("/rag")
    public ResponseEntity<ChatMessageDTO> ragChat(@RequestBody Map<String, String> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        String courseName = body.get("courseName");
        String content = body.get("content");

        // 1. 保存学生消息
        chatService.sendMessage(courseName, userId, content, "student");

        // 2. RAG 检索相关知识（表不存在时优雅降级）
        String ragContext = "";
        try {
            ragContext = ragService.retrieveContext(courseName, content);
        } catch (Exception e) {
            log.warn("RAG 检索失败（可能表未创建）：course={}", courseName, e);
        }

        // 3. 调用 AI
        String aiReply = llmService.chat(buildSystemPrompt(courseName, ragContext), content);
        if (aiReply == null || aiReply.trim().isEmpty()) {
            aiReply = "AI 服务暂时不可用，请稍后重试。";
        }
        return ResponseEntity.ok(chatService.sendMessage(courseName, userId, aiReply, "ai"));
    }

    // 上传文件并 AI 分析
    @PostMapping("/upload")
    public ResponseEntity<ChatMessageDTO> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("courseName") String courseName,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();

        // 1. 保存用户消息（文件上传提示）
        String hint = "📎 正在分析《" + file.getOriginalFilename() + "》...";
        chatService.sendMessage(courseName, userId, hint, "student");

        // 2. 分析文件
        String analysisResult = ragService.uploadAndAnalyze(courseName, file);

        // 3. 保存分析结果
        return ResponseEntity.ok(chatService.sendMessage(courseName, userId, analysisResult, "ai"));
    }

    // 教师查看学生提问统计
    @GetMapping("/stats/{courseName}")
    public ResponseEntity<?> getAskStats(
            @PathVariable String courseName,
            @RequestParam(required = false) Long classId,
            Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        try {
            List<StudentAskStatsDTO> stats = chatService.getAskStats(courseName, teacherUserId, classId);
            return ResponseEntity.ok(stats);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /** 学生进入课程详情时，标记该课程所有教师消息为已读 */
    @PostMapping("/read")
    public ResponseEntity<Map<String, String>> markAsRead(@RequestBody Map<String, String> body,
                                                           Authentication auth) {
        String courseName = body.get("courseName");
        Long userId = (Long) auth.getPrincipal();
        chatService.markAsRead(courseName, userId);
        return ResponseEntity.ok(Map.of("msg", "已读"));
    }

    /** 教师进入课程聊天时，标记该课程下所有非教师消息为已读 */
    @PostMapping("/teacher/read")
    public ResponseEntity<Map<String, String>> markTeacherRead(@RequestBody Map<String, String> body) {
        String courseName = body.get("courseName");
        if (courseName == null || courseName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("msg", "courseName 不能为空"));
        }
        int rows = jdbc.update(
                "UPDATE chat_message SET is_read = 1 " +
                        "WHERE course_name = ? AND sender_role != 'teacher' AND is_read = 0",
                courseName);
        log.info("教师已读标记: courseName={}, rows={}", courseName, rows);
        return ResponseEntity.ok(Map.of("msg", "已读", "rows", String.valueOf(rows)));
    }

    /** 简单文件上传（图片/文档），返回可访问 URL */
    @PostMapping("/upload-file")
    public ResponseEntity<Map<String, String>> uploadChatFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("courseName") String courseName) {
        try {
            String uploadDir = System.getProperty("user.dir") + "/uploads/chat/" + courseName;
            File dir = new File(uploadDir);
            if (!dir.exists()) dir.mkdirs();

            String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(uploadDir, filename);
            Files.write(filePath, file.getBytes());

            String relPath = "/uploads/chat/" + courseName + "/" + filename;
            // 返回相对路径，由前端根据自身 base URL 拼接，避免硬编码 localhost 导致真机/生产环境失效
            String downloadUrl = "/api/chat/download-file?url=" +
                    java.net.URLEncoder.encode(relPath, java.nio.charset.StandardCharsets.UTF_8);
            log.info("聊天文件上传成功: courseName={}, fileName={}, downloadUrl={}", courseName, filename, downloadUrl);
            return ResponseEntity.ok(Map.of("url", downloadUrl, "fileName", filename));
        } catch (Exception e) {
            log.error("聊天文件上传失败", e);
            return ResponseEntity.badRequest().body(Map.of("error", "上传失败: " + e.getMessage()));
        }
    }

    /** 文件下载接口：根据 url 参数读取本地文件并返回，支持图片在线预览 */
    @GetMapping("/download-file")
    public ResponseEntity<Resource> downloadFile(@RequestParam String url) {
        try {
            String relPath = url.startsWith("/uploads/") ? url.substring(9) : url;
            Path filePath = Paths.get(System.getProperty("user.dir"), "uploads", relPath).toAbsolutePath().normalize();
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                log.warn("文件不存在或不可读: {}", filePath);
                return ResponseEntity.notFound().build();
            }

            String filename = filePath.getFileName().toString();
            String contentType = determineContentType(filename);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (Exception e) {
            log.error("文件下载失败: url={}", url, e);
            return ResponseEntity.badRequest().build();
        }
    }

    private String determineContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    /** 获取课程所有学生（用于@mention） */
    @GetMapping("/{courseName}/students")
    public ResponseEntity<?> getCourseStudents(
            @PathVariable String courseName, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        // 校验课程归属当前教师
        Long teacherId = resolveTeacherId(userId);
        if (teacherId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM course WHERE course_name = ? AND teacher_id = ?",
            Integer.class, courseName, teacherId);
        if (count == null || count == 0) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        List<Map<String, Object>> list = jdbc.queryForList(
            "SELECT DISTINCT u.id, u.real_name AS realName FROM user u " +
            "JOIN course_class cc ON cc.class_id = u.class_id " +
            "JOIN course c ON c.id = cc.course_id " +
            "WHERE c.course_name = ? AND u.role = 1 ORDER BY u.real_name",
            courseName);
        return ResponseEntity.ok(list);
    }

    /** 通过 userId 反查 teacher 表的 id（依据 real_name 关联） */
    private Long resolveTeacherId(Long userId) {
        try {
            List<Long> ids = jdbc.queryForList(
                "SELECT t.id FROM teacher t " +
                "JOIN user u ON u.real_name = t.real_name " +
                "WHERE u.id = ? LIMIT 1", Long.class, userId);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取学生各课程未读消息数量 */
    @GetMapping("/unread")
    public ResponseEntity<List<Map<String, Object>>> getUnreadCount(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        // 统计“发给当前学生”的未读消息：
        // 1) user_id = 当前学生 且 发送者不是学生自己（教师通知/回复、AI 回复等）
        // 2) @提及当前学生的私密消息
        List<Map<String, Object>> result = jdbc.queryForList(
            "SELECT cm.course_name AS courseName, COUNT(*) AS count " +
            "FROM chat_message cm " +
            "WHERE cm.is_read = 0 " +
            "  AND (" +
            "    (cm.user_id = ? AND cm.sender_role != 'student') " +
            "    OR cm.mention_user_id = ?" +
            "  ) " +
            "  AND cm.course_name IN (SELECT c.course_name FROM course c " +
            "    JOIN course_class cc ON cc.course_id = c.id " +
            "    JOIN user u ON u.class_id = cc.class_id WHERE u.id = ?) " +
            "GROUP BY cm.course_name",
            userId, userId, userId);
        return ResponseEntity.ok(result);
    }

    /** 获取教师未读聊天通知（学生和 AI 发送的消息） */
    @GetMapping("/teacher/unread")
    public ResponseEntity<List<Map<String, Object>>> getTeacherUnreadNotifications(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<TeacherCourseDTO> courses = scheduleService.getTeacherCourses(userId);
        if (courses.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<String> courseNames = courses.stream()
                .map(TeacherCourseDTO::getCourseName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // 构建 IN 占位符
        String placeholders = String.join(",", Collections.nCopies(courseNames.size(), "?"));
        String sql = "SELECT cm.id, cm.course_name AS courseName, " +
                "cm.sender_name AS senderName, cm.sender_role AS senderRole, " +
                "cm.content, cm.created_at AS createdAt " +
                "FROM chat_message cm " +
                "WHERE cm.is_read = 0 " +
                "  AND cm.sender_role != 'teacher' " +
                "  AND cm.course_name IN (" + placeholders + ") " +
                "ORDER BY cm.created_at DESC " +
                "LIMIT 50";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, courseNames.toArray());
        for (Map<String, Object> row : rows) {
            String content = String.valueOf(row.getOrDefault("content", ""));
            row.put("preview", buildChatPreview(content));
        }
        return ResponseEntity.ok(rows);
    }

    private String buildChatPreview(String content) {
        if (content == null) return "";
        if (content.startsWith("[image]")) return "[图片]";
        if (content.startsWith("[file]")) {
            String rest = content.substring(6);
            int ps = rest.indexOf('|');
            String name = ps > 0 ? rest.substring(0, ps) : rest;
            return "[文件] " + (name.length() > 20 ? name.substring(0, 20) + "..." : name);
        }
        return content.length() > 60 ? content.substring(0, 60) + "..." : content;
    }

    // ========== 私有辅助方法 ==========

    /** 构建统一 system prompt */
    private String buildSystemPrompt(String courseName, String ragContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是《").append(courseName).append("》课程的智能助教。严格遵守以下规则：\n");
        sb.append("1. 只回答与《").append(courseName).append("》课程直接相关的内容，拒绝无关问题。\n");
        sb.append("2. 回答简洁直接，不要寒暄、不要反问、不要说「还有什么需要帮助吗」。\n");
        sb.append("3. 不要使用Markdown格式（如**加粗**、#标题），用纯文本输出。\n");
        sb.append("3.1 数学公式必须使用 LaTeX 语法，且无论行内还是独立公式一律用 $$...$$ 包裹（行内如 $$x^2+1$$，独立公式单独成行如 $$f(x) \\approx \\sum_{n=0}^{\\infty} \\frac{f^{(n)}(a)}{n!}(x-a)^n$$）；分数用 \\frac{}{}、上下标用 ^ _、希腊字母用 \\alpha 等命令，禁止用 ^2/2! 这类纯文本形式表达公式，禁止只用单个 $ 包裹公式。\n");
        sb.append("4. 如果需要列表，用数字或-开头，不使用*加粗。\n");
        sb.append("5. 不要添加免责声明、备注或额外建议，只说该说的内容。\n");
        sb.append("6. 当学生问「你能做什么/你能干什么/你会什么」等关于你自身能力的问题时，必须清晰列出具体能力，例如：\n");
        sb.append("   - 回答本课程的知识点、概念和习题\n");
        sb.append("   - 解释课程中的重点、难点和易错点\n");
        sb.append("   - 分析你上传的课程图片、文件或作业\n");
        sb.append("   - 根据你的学习情况推荐复习重点和学习路径\n");
        sb.append("   - 总结课程章节内容并生成练习\n");
        sb.append("   列出能力后，用一句话引导学生提出具体问题。");
        if (!ragContext.isEmpty()) {
            sb.append("\n\n参考材料：\n").append(ragContext);
        }
        return sb.toString();
    }

    /** 获取 RAG 上下文 */
    private String ragContext(String content, String courseName) {
        try {
            return ragService.retrieveContext(courseName, content);
        } catch (Exception e) {
            return "";
        }
    }
}
