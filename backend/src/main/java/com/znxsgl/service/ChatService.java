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
                        .and(w -> w.eq(ChatMessage::getUserId, userId)
                                .or().isNull(ChatMessage::getMentionUserId)
                                .or().eq(ChatMessage::getMentionUserId, userId)
                                .or().eq(ChatMessage::getSenderRole, "ai"))
                        .orderByAsc(ChatMessage::getCreatedAt));
        return chatMessageConverter.toDtoList(msgs);
    }

    /** 课程公开聊天（教师端看全部，AI消息显示"AI对{学生}说"） */
    public List<ChatMessageDTO> getPublicMessages(Long courseId) {
        List<ChatMessage> msgs = chatMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getCourseId, courseId)
                        .orderByAsc(ChatMessage::getCreatedAt));
        return msgs.stream().map(m -> {
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
        if (courseId == null) return;
        chatMapper.markAsRead(courseId, userId);
    }

    /** 教师进入课程聊天：标记该课程下学生/AI 消息为已读 */
    public int markTeacherRead(Long courseId) {
        if (courseId == null) return 0;
        return chatMapper.markTeacherRead(courseId);
    }

    /** 学生各课程未读数（courseId + courseName + count） */
    public List<Map<String, Object>> unreadByCourse(Long userId) {
        return chatMapper.countUnreadByCourse(userId);
    }

    /** 教师未读聊天通知（学生/AI 发送的消息，按 course_id 隔离） */
    public List<Map<String, Object>> teacherUnread(Long userId) {
        return jdbc.queryForList(
                "SELECT cm.id, cm.course_id AS courseId, cm.course_name AS courseName, " +
                        "  cm.sender_name AS senderName, cm.sender_role AS senderRole, " +
                        "  cm.content, cm.created_at AS createdAt " +
                        "FROM chat_message cm " +
                        "JOIN course c ON c.id = cm.course_id " +
                        "JOIN teacher t ON t.id = c.teacher_id " +
                        "JOIN user u ON u.real_name = t.real_name " +
                        "WHERE u.id = ? AND cm.is_read = 0 AND cm.sender_role != 'teacher' " +
                        "ORDER BY cm.created_at DESC LIMIT 50",
                userId);
    }

    private Long firstLong(String sql, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
