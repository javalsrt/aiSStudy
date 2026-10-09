package com.znxsgl.controller;

import com.znxsgl.dto.ChatMessageDTO;
import com.znxsgl.dto.StudentAskStatsDTO;
import com.znxsgl.service.ChatService;
import com.znxsgl.service.LlmService;
import com.znxsgl.service.RagService;
import com.znxsgl.websocket.ScheduleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 课程聊天接口。
 * <p>
 * 聊天室唯一键为 <b>courseId</b>。为兼容已发布的客户端，旧接口（按 courseName）继续可用，
 * 但服务端会按调用者身份把课程名收敛为该调用者自己的唯一课程ID，因此同名课程不再串号。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final LlmService llmService;
    private final RagService ragService;
    private final ScheduleWebSocketHandler wsHandler;
    private final JdbcTemplate jdbc;

    public ChatController(ChatService chatService, LlmService llmService,
                          RagService ragService, ScheduleWebSocketHandler wsHandler,
                          JdbcTemplate jdbc) {
        this.chatService = chatService;
        this.llmService = llmService;
        this.ragService = ragService;
        this.wsHandler = wsHandler;
        this.jdbc = jdbc;
    }

    // ==================== 房间读写（courseId 精确接口） ====================

    /** 课程聊天记录（学生：自己的消息 + 公开消息 + @自己的消息 + AI回复） */
    @GetMapping("/by-course/{courseId}")
    public ResponseEntity<?> getMessagesById(@PathVariable Long courseId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        if (!chatService.canAccess(userId, courseId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权访问该课程聊天"));
        }
        return ResponseEntity.ok(chatService.getMessages(courseId, userId));
    }

    /** 课程群聊记录（教师/学生都能看到课程内全部消息） */
    @GetMapping("/by-course/{courseId}/public")
    public ResponseEntity<?> getPublicMessagesById(@PathVariable Long courseId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        if (!chatService.canAccess(userId, courseId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权访问该课程聊天"));
        }
        return ResponseEntity.ok(chatService.getPublicMessages(courseId));
    }

    /** 课程学生列表（@ 点名，仅教师本人课程） */
    @GetMapping("/by-course/{courseId}/students")
    public ResponseEntity<?> getCourseStudentsById(@PathVariable Long courseId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        if (!ownsCourse(userId, courseId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        return ResponseEntity.ok(chatService.getCourseStudents(courseId));
    }

    /** 发送消息（支持 @mention 和 @AI）；body 传 courseId（推荐）或 courseName */
    @PostMapping("/send")
    public ResponseEntity<?> sendMessage(@RequestBody Map<String, String> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = resolveRoom(body, userId);
        if (courseId == null) return ResponseEntity.badRequest().body(Map.of("error", "课程不存在"));
        String content = body.get("content");
        String senderRole = body.getOrDefault("senderRole", "student");
        String courseName = safeCourseName(courseId);

        // @ 提及：只在课程班级内匹配，避免给非本课程学生发私密消息
        Long mentionUserId = content != null && content.contains("@")
                ? chatService.parseMention(courseId, content)
                : null;

        ChatMessageDTO msg = chatService.sendMessage(courseId, userId, content, senderRole, mentionUserId);

        // 如果 @了AI，触发AI回复
        if (content != null && (content.contains("@AI") || content.contains("@ai"))) {
            try {
                String aiReply = llmService.chat(buildSystemPrompt(courseName, ragContext(content, courseName)),
                        content.replace("@AI", "").replace("@ai", "").trim());
                if (aiReply == null || aiReply.trim().isEmpty()) {
                    aiReply = "AI 服务暂时不可用，请稍后重试。";
                }
                chatService.sendMessage(courseId, userId, aiReply, "ai", mentionUserId);
            } catch (Exception e) {
                log.warn("AI 回复失败：courseId={}, userId={}", courseId, userId, e);
            }
        }

        // WebSocket 推送：@消息只推送给被@的人，否则推给本课程全部学生
        try {
            String preview = buildChatPreview(content);
            if (mentionUserId != null) {
                Map<String, Object> data = chatMessagePushData(courseId, courseName, msg.getSenderName(), preview, senderRole);
                wsHandler.sendToUser(mentionUserId, "chat_update", data);
            } else {
                for (Long sid : chatService.studentIdsOf(courseId, userId)) {
                    Map<String, Object> data = chatMessagePushData(courseId, courseName, msg.getSenderName(), preview, senderRole);
                    wsHandler.sendToUser(sid, "chat_update", data);
                }
            }
        } catch (Exception e) {
            log.warn("WebSocket 推送失败：courseId={}, userId={}", courseId, userId, e);
        }

        return ResponseEntity.ok(msg);
    }

    /** 标记该学生在指定课程的消息为已读 */
    @PostMapping("/read")
    public ResponseEntity<Map<String, String>> markAsRead(@RequestBody Map<String, String> body,
                                                          Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        chatService.markAsRead(resolveRoom(body, userId), userId);
        return ResponseEntity.ok(Map.of("msg", "已读"));
    }

    /** 教师进入课程聊天时，标记该课程下所有非教师消息为已读 */
    @PostMapping("/teacher/read")
    public ResponseEntity<Map<String, String>> markTeacherRead(@RequestBody Map<String, String> body,
                                                               Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = resolveRoom(body, userId);
        if (courseId == null) {
            return ResponseEntity.badRequest().body(Map.of("msg", "课程不存在"));
        }
        int rows = chatService.markTeacherRead(courseId);
        log.info("教师已读标记: courseId={}, rows={}", courseId, rows);
        return ResponseEntity.ok(Map.of("msg", "已读", "rows", String.valueOf(rows)));
    }

    // ==================== 旧接口（按 courseName，服务端收敛到调用者自己的课程） ====================

    @GetMapping("/{courseName}")
    public ResponseEntity<List<ChatMessageDTO>> getMessages(@PathVariable String courseName, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = chatService.resolveCourseId(userId, courseName);
        if (courseId == null) return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(chatService.getMessages(courseId, userId));
    }

    @GetMapping("/{courseName}/public")
    public ResponseEntity<List<ChatMessageDTO>> getPublicMessages(@PathVariable String courseName, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = chatService.resolveCourseId(userId, courseName);
        if (courseId == null) return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(chatService.getPublicMessages(courseId));
    }

    /** 获取课程学生列表（用于@mention，仅课程归属教师可见） */
    @GetMapping("/{courseName}/students")
    public ResponseEntity<?> getCourseStudents(@PathVariable String courseName, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = chatService.resolveCourseId(userId, courseName);
        if (courseId == null || !ownsCourse(userId, courseId)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限"));
        }
        return ResponseEntity.ok(chatService.getCourseStudents(courseId));
    }

    /** 教师查看学生提问统计 */
    @GetMapping("/stats/{courseName}")
    public ResponseEntity<?> getAskStats(@PathVariable String courseName,
                                         @RequestParam(required = false) Long classId,
                                         Authentication auth) {
        Long teacherUserId = (Long) auth.getPrincipal();
        try {
            Long courseId = chatService.resolveCourseId(teacherUserId, courseName);
            if (courseId == null) {
                return ResponseEntity.status(403).body(Map.of("error", "无权查看该课程的统计数据"));
            }
            List<StudentAskStatsDTO> stats = chatService.getAskStats(courseId, teacherUserId, classId);
            return ResponseEntity.ok(stats);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== RAG 对话 / 文件上传 ====================

    /** RAG 对话（自动检索课程知识库） */
    @PostMapping("/rag")
    public ResponseEntity<?> ragChat(@RequestBody Map<String, String> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = resolveRoom(body, userId);
        if (courseId == null) return ResponseEntity.badRequest().body(Map.of("error", "课程不存在"));
        String courseName = safeCourseName(courseId);
        String content = body.get("content");

        chatService.sendMessage(courseId, userId, content, "student", null);

        String ragContext = "";
        try {
            ragContext = ragService.retrieveContext(courseName, content);
        } catch (Exception e) {
            log.warn("RAG 检索失败（可能表未创建）：courseId={}", courseId, e);
        }

        String aiReply = llmService.chat(buildSystemPrompt(courseName, ragContext), content);
        if (aiReply == null || aiReply.trim().isEmpty()) {
            aiReply = "AI 服务暂时不可用，请稍后重试。";
        }
        return ResponseEntity.ok(chatService.sendMessage(courseId, userId, aiReply, "ai", null));
    }

    /** 上传文件并 AI 分析 */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "courseName", required = false) String courseName,
                                        @RequestParam(value = "courseId", required = false) Long courseIdParam,
                                        Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = courseIdParam != null ? courseIdParam : chatService.resolveCourseId(userId, courseName);
        if (courseId == null) return ResponseEntity.badRequest().body(Map.of("error", "课程不存在"));
        String resolvedName = safeCourseName(courseId);

        String hint = "📎 正在分析《" + file.getOriginalFilename() + "》...";
        chatService.sendMessage(courseId, userId, hint, "student", null);

        String analysisResult = ragService.uploadAndAnalyze(resolvedName, file);
        return ResponseEntity.ok(chatService.sendMessage(courseId, userId, analysisResult, "ai", null));
    }

    /** 简单文件上传（图片/文档），返回可访问 URL */
    @PostMapping("/upload-file")
    public ResponseEntity<Map<String, String>> uploadChatFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "courseName", required = false) String courseName,
            @RequestParam(value = "courseId", required = false) Long courseIdParam,
            Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long courseId = courseIdParam != null ? courseIdParam : chatService.resolveCourseId(userId, courseName);
        // 目录用课程ID，彻底避免同名课程文件互相覆盖；旧消息里的历史路径不受影响
        String folder = courseId != null ? String.valueOf(courseId)
                : (courseName != null ? courseName : "common");
        try {
            String uploadDir = System.getProperty("user.dir") + "/uploads/chat/" + folder;
            File dir = new File(uploadDir);
            if (!dir.exists()) dir.mkdirs();

            String filename = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(uploadDir, filename);
            Files.write(filePath, file.getBytes());

            String relPath = "/uploads/chat/" + folder + "/" + filename;
            String downloadUrl = "/api/chat/download-file?url=" +
                    java.net.URLEncoder.encode(relPath, java.nio.charset.StandardCharsets.UTF_8);
            log.info("聊天文件上传成功: courseId={}, fileName={}, downloadUrl={}", courseId, filename, downloadUrl);
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

    // ==================== 未读 ====================

    /** 学生各课程未读消息数量（含 courseId，同名课程不再合并） */
    @GetMapping("/unread")
    public ResponseEntity<List<Map<String, Object>>> getUnreadCount(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(chatService.unreadByCourse(userId));
    }

    /** 教师未读聊天通知（学生和 AI 发送的消息，按 course_id 隔离） */
    @GetMapping("/teacher/unread")
    public ResponseEntity<List<Map<String, Object>>> getTeacherUnreadNotifications(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        List<Map<String, Object>> rows = chatService.teacherUnread(userId);
        for (Map<String, Object> row : rows) {
            row.put("preview", buildChatPreview(String.valueOf(row.getOrDefault("content", ""))));
        }
        return ResponseEntity.ok(rows);
    }

    // ==================== 私有辅助方法 ====================

    /** 解析房间ID：优先 body 里的 courseId，其次按调用者身份解析 courseName */
    private Long resolveRoom(Map<String, String> body, Long userId) {
        if (body == null) return null;
        String id = body.get("courseId");
        if (id != null && !id.trim().isEmpty()) {
            try {
                return Long.valueOf(id.trim());
            } catch (NumberFormatException ignored) {
                // 非法 courseId 时回退按课程名解析
            }
        }
        return chatService.resolveCourseId(userId, body.get("courseName"));
    }

    /** 调用者是否为该课程的任课教师 */
    private boolean ownsCourse(Long userId, Long courseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course c " +
                        "JOIN teacher t ON t.id = c.teacher_id " +
                        "JOIN user u ON u.real_name = t.real_name " +
                        "WHERE c.id = ? AND u.id = ?",
                Integer.class, courseId, userId);
        return n != null && n > 0;
    }

    private String safeCourseName(Long courseId) {
        String name = chatService.courseNameOf(courseId);
        return name != null ? name : "";
    }

    private Map<String, Object> chatMessagePushData(Long courseId, String courseName,
                                                    String senderName, String preview, String senderRole) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("courseId", courseId);
        data.put("courseName", courseName);
        data.put("senderName", senderName);
        data.put("content", preview);
        data.put("senderRole", senderRole);
        return data;
    }

    private String buildChatPreview(String content) {
        if (content == null) return "";
        if (content.startsWith("[image]")) return "📷 [图片]";
        if (content.startsWith("[file]")) {
            String fn = content.substring(6);
            int ps = fn.indexOf('|');
            return "📄 [文件] " + (ps > 0 ? fn.substring(0, Math.min(ps, 20)) : fn.substring(0, 20));
        }
        return content.length() > 60 ? content.substring(0, 60) + "..." : content;
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
