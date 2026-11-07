package com.znxsgl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.znxsgl.converter.ChatMessageConverter;
import com.znxsgl.dto.ChatMessageDTO;
import com.znxsgl.dto.StudentAskStatsDTO;
import com.znxsgl.entity.ChatMessage;
import com.znxsgl.entity.User;
import com.znxsgl.mapper.ChatMessageMapper;
import com.znxsgl.mapper.UserMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 课程聊天服务。
 * <p>
 * 聊天室唯一键是 <b>course_id</b>：课程名在不同教师之间可以重复（库中存在 4 门「英语」等
 * 18 组同名课程），若按课程名做房间键，会导致 @ 名单、群聊记录、未读数跨教师串号。
 * 因此所有房间维度的读写都走 courseId，courseName 只用于展示。
 */
@Service
public class ChatService {

    private static final Pattern MENTION = Pattern.compile("@(\\S+)");

    private final ChatMessageMapper chatMapper;
    private final UserMapper userMapper;
    private final ChatMessageConverter chatMessageConverter;
    private final JdbcTemplate jdbc;

    public ChatService(ChatMessageMapper chatMapper, UserMapper userMapper,
                       ChatMessageConverter chatMessageConverter, JdbcTemplate jdbc) {
        this.chatMapper = chatMapper;
        this.userMapper = userMapper;
        this.chatMessageConverter = chatMessageConverter;
        this.jdbc = jdbc;
    }

    // ==================== 房间键解析 ====================

    /**
     * 把课程名收敛为「调用者自己的」唯一课程ID。
     * <p>旧客户端（含已发布的 App）仍按课程名调用，这里按身份解析，从根上消除同名串号：
     * 教师 → 本人任教的同名课程；学生 → 本人班级所修的同名课程；都匹配不到时兜底取最早一门。
     *
     * @return 课程ID；课程名不存在时返回 null
     */
    public Long resolveCourseId(Long userId, String courseName) {
        if (courseName == null || courseName.trim().isEmpty()) return null;
        String name = courseName.trim();
        // 教师：本人任教的课程
        Long id = firstLong("SELECT c.id FROM course c " +
                "JOIN teacher t ON t.id = c.teacher_id " +
                "JOIN user u ON u.real_name = t.real_name " +
                "WHERE u.id = ? AND c.course_name = ? LIMIT 1", userId, name);
        if (id != null) return id;
        // 学生：本人班级所修的课程
        id = firstLong("SELECT c.id FROM course c " +
                "JOIN course_class cc ON cc.course_id = c.id " +
                "JOIN user u ON u.class_id = cc.class_id " +
                "WHERE u.id = ? AND c.course_name = ? LIMIT 1", userId, name);
        if (id != null) return id;
        // 兜底（管理员等无归属关系）：取同名课程中最早的一门，保证旧接口仍可用
        return firstLong("SELECT id FROM course WHERE course_name = ? ORDER BY id LIMIT 1", name);
    }

    /** 课程ID → 课程名（展示用） */
    public String courseNameOf(Long courseId) {
        if (courseId == null) return null;
        List<String> names = jdbc.queryForList(
                "SELECT course_name FROM course WHERE id = ?", String.class, courseId);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 调用者是否有权访问该课程房间（教师任教 / 学生所在班级开课） */
    public boolean canAccess(Long userId, Long courseId) {
        if (userId == null || courseId == null) return false;
        Long hit = firstLong("SELECT c.id FROM course c " +
                "JOIN teacher t ON t.id = c.teacher_id " +
                "JOIN user u ON u.real_name = t.real_name " +
                "WHERE u.id = ? AND c.id = ? LIMIT 1", userId, courseId);
        if (hit != null) return true;
        hit = firstLong("SELECT c.id FROM course c " +
                "JOIN course_class cc ON cc.course_id = c.id " +
                "JOIN user u ON u.class_id = cc.class_id " +
                "WHERE u.id = ? AND c.id = ? LIMIT 1", userId, courseId);
        return hit != null;
    }

    // ==================== 房间读写 ====================

    /** 课程聊天记录（学生视角：自己的消息 + 公开消息 + @自己的消息 + AI回复） */
    public List<ChatMessageDTO> getMessages(Long courseId, Long userId) {
        List<ChatMessage> msgs = chatMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getCourseId, courseId)
                        .orderByAsc(ChatMessage::getCreatedAt));
        List<ChatMessage> visible = new ArrayList<>();
        for (ChatMessage m : msgs) {
            if (isVisibleTo(m, userId)) visible.add(m);
        }
        // 兜底去重：同发送者 + 同内容 + 10 分钟内只留一条（防历史脏数据继续刷屏）
        List<ChatMessage> deduped = new ArrayList<>();
        Map<String, LocalDateTime> lastNoticeAt = new HashMap<>();
        for (ChatMessage m : visible) {
            String c = m.getContent();
            boolean isNotice = "teacher".equals(m.getSenderRole()) && c != null
                    && (c.contains("【课程通知】") || c.contains("排课已更新"));
            if (isNotice) {
                String key = String.valueOf(m.getSenderName()) + "|" + c;
                LocalDateTime prev = lastNoticeAt.get(key);
                if (prev != null && m.getCreatedAt() != null
                        && Math.abs(Duration.between(prev, m.getCreatedAt()).toMinutes()) < 10) {
                    continue;
                }
                lastNoticeAt.put(key, m.getCreatedAt());
            }
            deduped.add(m);
        }
        return chatMessageConverter.toDtoList(deduped);
    }

    /**
     * 学生视角的消息可见性判断。
     * <p>
     * 关键修复：排课/调课通知是「按学生逐条落库」的（每人一条，用于各自红点），
     * 旧实现用 {@code OR mention_user_id IS NULL} 匹配，导致每条通知（均为非@消息）
     * 对所有学生可见 —— 一次调课就会在每个学生聊天里刷出全班 50 条重复通知。
     */
    private boolean isVisibleTo(ChatMessage m, Long userId) {
        if (m == null) return false;
        boolean mine = userId != null && userId.equals(m.getUserId());
        Long mention = m.getMentionUserId();
        String role = m.getSenderRole();
        if ("teacher".equals(role)) {
            String c = m.getContent();
            boolean isNotice = c != null && (c.contains("【课程通知】") || c.contains("排课已更新"));
            if (isNotice) return mine;                       // 排课通知：只给对应学生
            if (mention != null) return userId != null && mention.equals(userId); // @某人：只给被@者
            return true;                                     // 普通教师群聊：全班可见
        }
        if ("student".equals(role)) {
            if (mine) return true;                           // 自己发的
            if (mention != null) return userId != null && mention.equals(userId); // @别人：不可见
            return true;                                     // 学生公聊
        }
        return true;                                         // AI / system
    }

    /** 课程公开聊天（教师端看全部，AI消息显示"AI对{学生}说"） */
    public List<ChatMessageDTO> getPublicMessages(Long courseId) {
        List<ChatMessage> msgs = chatMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getCourseId, courseId)
                        .orderByAsc(ChatMessage::getCreatedAt));
        // 群聊展示去重：排课/调课通知按「每个学生一条」落库（用于各自的未读红点），
        // 群聊直接展示会重复 N 次。这里折叠「同发送者 + 同内容 + 10 分钟内」的重复通知，保留最早一条。
        List<ChatMessage> deduped = new ArrayList<>();
        Map<String, LocalDateTime> lastNoticeAt = new HashMap<>();
        for (ChatMessage m : msgs) {
            String content = m.getContent();
            boolean isNotice = "teacher".equals(m.getSenderRole()) && content != null
                    && (content.contains("【课程通知】") || content.contains("排课已更新") || content.contains("调课"));
            if (isNotice) {
                String key = String.valueOf(m.getSenderName()) + "|" + content;
                LocalDateTime prev = lastNoticeAt.get(key);
                if (prev != null && m.getCreatedAt() != null
                        && Math.abs(Duration.between(prev, m.getCreatedAt()).toMinutes()) < 10) {
                    continue;
                }
                lastNoticeAt.put(key, m.getCreatedAt());
            }
            deduped.add(m);
        }
        return deduped.stream().map(m -> {
            ChatMessageDTO d = chatMessageConverter.toDto(m);
            if ("ai".equals(m.getSenderRole())) {
                User student = userMapper.selectById(m.getUserId());
                String studentName = student != null ? student.getRealName() : "学生";
                d.setSenderName("AI对" + studentName + "说");
            }
            return d;
        }).toList();
    }

    /** 发送消息（支持 @mention）；createdAt 由 MyBatis-Plus 自动填充 */
    public ChatMessageDTO sendMessage(Long courseId, Long userId, String content,
                                      String senderRole, Long mentionUserId) {
        User user = userMapper.selectById(userId);

        ChatMessage msg = new ChatMessage();
        msg.setCourseId(courseId);
        msg.setCourseName(courseNameOf(courseId));
        msg.setUserId(userId);
        msg.setSenderName(user != null ? user.getRealName() : "系统");
        msg.setSenderRole(senderRole);
        msg.setContent(content);
        msg.setMentionUserId(mentionUserId);
        // 新消息一律按「未读」落库：教师的铃铛通知依赖 is_read=0 判定，
        // 学生端的未读另有 chat_read_state 水位线，不依赖这个字段。
        msg.setIsRead(0);
        chatMapper.insert(msg);

        return chatMessageConverter.toDto(msg);
    }

    /**
     * 解析 content 中的 @姓名，返回匹配的学生ID。
     * <p>匹配范围严格限定在「该课程的班级」内，避免给非本课程/非本班学生发私密消息。
     */
    public Long parseMention(Long courseId, String content) {
        if (content == null || courseId == null) return null;
        Matcher m = MENTION.matcher(content);
        if (!m.find()) return null;
        String name = m.group(1);
        return firstLong("SELECT u.id FROM user u " +
                "JOIN course_class cc ON cc.class_id = u.class_id " +
                "WHERE cc.course_id = ? AND u.role = 1 AND u.real_name = ? LIMIT 1", courseId, name);
    }

    /** 课程学生列表（@ 点名用） */
    public List<Map<String, Object>> getCourseStudents(Long courseId) {
        return jdbc.queryForList(
                "SELECT DISTINCT u.id, u.real_name AS realName FROM user u " +
                        "JOIN course_class cc ON cc.class_id = u.class_id " +
                        "WHERE cc.course_id = ? AND u.role = 1 ORDER BY u.real_name",
                courseId);
    }

    /** 课程班级内的学生ID列表（WebSocket 群发用） */
    public List<Long> studentIdsOf(Long courseId, Long excludeUserId) {
        return jdbc.queryForList(
                "SELECT DISTINCT u.id FROM user u " +
                        "JOIN course_class cc ON cc.class_id = u.class_id " +
                        "WHERE cc.course_id = ? AND u.role = 1 AND u.id != ?",
                Long.class, courseId, excludeUserId == null ? -1L : excludeUserId);
    }

    /** 教师查看学生提问统计（校验该课程属于当前教师） */
    public List<StudentAskStatsDTO> getAskStats(Long courseId, Long teacherUserId, Long classId) {
        Long owns = firstLong("SELECT c.id FROM course c " +
                "JOIN teacher t ON t.id = c.teacher_id " +
                "JOIN user u ON u.real_name = t.real_name " +
                "WHERE c.id = ? AND u.id = ? LIMIT 1", courseId, teacherUserId);
        if (owns == null) {
            throw new IllegalArgumentException("无权查看该课程的统计数据");
        }
        return chatMapper.countStudentAsks(courseId, classId);
    }

    /** 标记该学生在指定课程的所有应读消息为已读 */
    public void markAsRead(Long courseId, Long userId) {
        if (courseId == null || userId == null) return;
        // 记录「该学生读到哪条消息」：群聊消息只有一行（user_id=教师），
        // 无法用 is_read 表达每个学生的已读状态，按学生+课程存消息ID水位线。
        Long maxId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM chat_message WHERE course_id = ?", Long.class, courseId);
        jdbc.update("INSERT INTO chat_read_state (user_id, course_id, last_read_id, last_read_at) " +
                        "VALUES (?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE last_read_id = ?, last_read_at = NOW()",
                userId, courseId, maxId, maxId);
        // 兼容旧字段（通知类消息按学生各一行，仍沿用 is_read）
        chatMapper.markAsRead(courseId, userId);
    }

    /** 教师进入课程聊天：标记该课程下学生/AI 消息为已读 */
    public int markTeacherRead(Long courseId) {
        if (courseId == null) return 0;
        return chatMapper.markTeacherRead(courseId);
    }

    /**
     * 学生各课程未读数（courseId + courseName + count）。
     * 基于 chat_read_state.last_read_at 计算：该时间之后的「非本人发送」消息即未读，
     * 这样教师群发消息（user_id=教师）也能给学生产生红点。
     */
    public List<Map<String, Object>> unreadByCourse(Long userId) {
        return jdbc.queryForList(
                "SELECT cm.course_id AS courseId, c.course_name AS courseName, COUNT(*) AS count " +
                        "FROM chat_message cm " +
                        "JOIN course c ON c.id = cm.course_id " +
                        "JOIN chat_read_state rs ON rs.course_id = cm.course_id AND rs.user_id = ? " +
                        "WHERE cm.id > rs.last_read_id " +
                        "  AND NOT (cm.sender_role = 'student' AND cm.user_id = ?) " +
                        "  AND (cm.mention_user_id IS NULL OR cm.mention_user_id = ?) " +
                        "GROUP BY cm.course_id, c.course_name",
                userId, userId, userId);
    }

    /**
     * 教师未读聊天通知：按「课程」聚合，每个课程只返回最新一条 + 未读条数。
     * 原实现按消息逐条返回，同一课程几十条未读会在通知里堆成一屏旧消息，
     * 且点开一门课后剩余条目仍反复提示。
     */
    public List<Map<String, Object>> teacherUnread(Long userId) {
        return jdbc.queryForList(
                "SELECT t.id, t.courseId, t.courseName, t.senderName, t.senderRole, " +
                        "       t.content, t.createdAt, t.unreadCount " +
                        "FROM (" +
                        "  SELECT cm.id, cm.course_id AS courseId, cm.course_name AS courseName, " +
                        "         cm.sender_name AS senderName, cm.sender_role AS senderRole, " +
                        "         cm.content, cm.created_at AS createdAt, " +
                        "         COUNT(*) OVER (PARTITION BY cm.course_id) AS unreadCount, " +
                        "         ROW_NUMBER() OVER (PARTITION BY cm.course_id ORDER BY cm.id DESC) AS rn " +
                        "  FROM chat_message cm " +
                        "  JOIN course c ON c.id = cm.course_id " +
                        "  JOIN teacher t ON t.id = c.teacher_id " +
                        "  JOIN user u ON u.real_name = t.real_name " +
                        "  WHERE u.id = ? AND cm.is_read = 0 AND cm.sender_role != 'teacher' " +
                        ") t WHERE t.rn = 1 ORDER BY t.createdAt DESC LIMIT 20",
                userId);
    }

    private Long firstLong(String sql, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
