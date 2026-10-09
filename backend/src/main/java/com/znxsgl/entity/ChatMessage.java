package com.znxsgl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 聊天消息表，存储课程内的聊天消息
 */
@Data
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String courseName;

    /** 聊天室唯一键：课程ID（同名课程互相隔离，courseName 仅用于展示） */
    private Long courseId;

    /** 发送者用户ID */
    private Long userId;
    private String senderName;
    private String senderRole;
    private String content;

    /** 是否已读：0未读，1已读 */
    private Integer isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** @私密消息的目标学生ID，null表示公开消息 */
    private Long mentionUserId;

    /** 业务类型：exam_publish（考试/作业发布通知）等，普通聊天为NULL */
    private String bizType;

    /** 业务ID：对应 exam_homework.id 等 */
    private Long bizId;
}
