package com.znxsgl.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatMessageDTO {
    private Long id;
    private String courseName;
    private Long courseId;
    private Long userId;
    private String senderName;
    private String senderRole;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /** 业务类型：exam_publish（考试/作业发布通知）等，普通聊天为NULL */
    private String bizType;

    /** 业务ID：对应 exam_homework.id 等 */
    private Long bizId;
}
