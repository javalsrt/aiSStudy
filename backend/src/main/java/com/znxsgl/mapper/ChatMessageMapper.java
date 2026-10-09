package com.znxsgl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.znxsgl.dto.StudentAskStatsDTO;
import com.znxsgl.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 聊天消息 Mapper。
 * <p>
 * 注意：聊天室唯一键已从 course_name（不同教师可重名）迁移为 course_id，
 * 所有按房间维度的读写都必须用 course_id，course_name 仅用于展示。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 按课程统计学生提问次数（可选班级过滤） */
    @Select("<script>" +
            "SELECT u.id AS userId, u.student_no AS studentNo, u.real_name AS realName, " +
            "  ci.class_name AS className, COUNT(cm.id) AS askCount " +
            "FROM chat_message cm " +
            "JOIN user u ON u.id = cm.user_id " +
            "LEFT JOIN class_info ci ON ci.id = u.class_id " +
            "WHERE cm.course_id = #{courseId} " +
            "  AND cm.sender_role = 'student' " +
            "<if test='classId != null'> AND u.class_id = #{classId} </if>" +
            "GROUP BY u.id, u.student_no, u.real_name, ci.class_name " +
            "ORDER BY askCount DESC" +
            "</script>")
    List<StudentAskStatsDTO> countStudentAsks(@Param("courseId") Long courseId,
                                              @Param("classId") Long classId);

    /** 标记课程中所有当前学生应读的消息为已读：
     *  1) 通知/教师回复/AI 回复（user_id 为接收学生）
     *  2) 其他学生发送的公聊消息（user_id 为发送者）
     *  3) @提及当前学生的消息
     */
    @Update("UPDATE chat_message SET is_read = 1 " +
            "WHERE course_id = #{courseId} AND is_read = 0 " +
            "  AND (" +
            "    (user_id = #{userId} AND sender_role != 'student') " +
            "    OR (user_id != #{userId} AND sender_role = 'student') " +
            "    OR mention_user_id = #{userId}" +
            "  )")
    int markAsRead(@Param("courseId") Long courseId, @Param("userId") Long userId);

    /** 教师进入课程聊天：把该课程下学生/AI 消息全部标记已读 */
    @Update("UPDATE chat_message SET is_read = 1 " +
            "WHERE course_id = #{courseId} AND sender_role != 'teacher' AND is_read = 0")
    int markTeacherRead(@Param("courseId") Long courseId);

    /** 学生各课程的未读数（按课程ID聚合，同名课程不再合并） */
    @Select("SELECT cm.course_id AS courseId, c.course_name AS courseName, COUNT(*) AS count " +
            "FROM chat_message cm " +
            "JOIN course c ON c.id = cm.course_id " +
            "WHERE cm.is_read = 0 " +
            "  AND ((cm.user_id = #{userId} AND cm.sender_role != 'student') OR cm.mention_user_id = #{userId}) " +
            "  AND cm.course_id IN ( " +
            "    SELECT cc.course_id FROM course_class cc " +
            "    JOIN user u ON u.class_id = cc.class_id WHERE u.id = #{userId} ) " +
            "GROUP BY cm.course_id, c.course_name")
    List<Map<String, Object>> countUnreadByCourse(@Param("userId") Long userId);
}
